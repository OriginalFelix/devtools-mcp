package systems.grebe.devtools.mcp.modules.java;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.management.MBeanOperationInfo;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;

import com.sun.management.HotSpotDiagnosticMXBean;
import com.sun.tools.attach.VirtualMachine;
import jdk.management.jfr.FlightRecorderMXBean;
import jdk.management.jfr.RecordingInfo;
import systems.grebe.devtools.mcp.core.CommandRunner;
import systems.grebe.devtools.mcp.api.Errors;

/**
 * Ziel-JVM für Diagnosebefehle. Drei Varianten mit gleicher Schnittstelle:
 * <ul>
 *   <li>{@link Local}: JVM auf diesem Rechner – jcmd, Dateien direkt im Ablageordner</li>
 *   <li>{@link InContainer}: JVM in einem Docker-/Podman-Container – {@code exec jcmd}, Dateien per {@code cp}</li>
 *   <li>{@link Remote}: JVM über JMX – DiagnosticCommand-MBean, JFR-Daten per FlightRecorderMXBean-Stream</li>
 * </ul>
 */
public sealed interface JvmTarget permits JvmTarget.Local, JvmTarget.InContainer, JvmTarget.Remote {

    Duration DCMD_TIMEOUT = Duration.ofMinutes(5);

    /** Kurzname für Dateinamen. */
    String label();

    /** Beschreibung für Ausgaben. */
    String describe();

    /** Diagnosebefehl (wie jcmd), z.B. {@code dcmd("Thread.print", "-l")}. */
    String dcmd(String command, String... args);

    /** Holt den aktuellen Stand einer laufenden oder beendeten JFR-Aufzeichnung als lokale Datei. */
    Path fetchRecording(String recordingName, ArtifactStore store);

    /** Heap-Dump; liefert die lokale Datei (oder wirft, wenn sie nur auf dem entfernten Host liegt). */
    Path heapDump(boolean liveOnly, ArtifactStore store);

    // ---------------------------------------------------------------------------------------------------------

    static JvmTarget resolve(JavaEnvironment env, String target) {
        if (target == null || target.isBlank()) {
            List<LocalJvms.Jvm> all = env.processes().list();
            if (all.size() == 1) {
                return new Local(env, all.getFirst());
            }
            throw new IllegalArgumentException("Bitte ein Ziel angeben (PID, Name, container:<name>, jmx:<alias>). "
                    + "Lokale JVMs: " + all.stream().map(j -> j.pid() + " " + j.displayName()).toList());
        }
        String t = target.trim();
        if (t.startsWith("container:")) {
            String rest = t.substring("container:".length());
            String name = rest;
            Long pid = null;
            int colon = rest.lastIndexOf(':');
            if (colon > 0 && rest.substring(colon + 1).matches("\\d+")) {
                name = rest.substring(0, colon);
                pid = Long.parseLong(rest.substring(colon + 1));
            }
            return new InContainer(env, name, pid);
        }
        if (t.startsWith("jmx:")) {
            String alias = t.substring(4);
            JavaEnvironment.JmxTarget jt = env.jmxTargets().get(alias);
            if (jt == null) {
                throw new IllegalArgumentException("JMX-Ziel '" + alias + "' ist nicht konfiguriert. Verfügbar: "
                        + env.jmxTargets().keySet());
            }
            return new Remote(env, jt);
        }
        return new Local(env, env.processes().find(t));
    }

    /** "Thread.print" -> "threadPrint", "GC.class_histogram" -> "gcClassHistogram". */
    static String mbeanOperation(String command) {
        String[] parts = command.split("[._]");
        StringBuilder sb = new StringBuilder(parts[0].toLowerCase());
        for (int i = 1; i < parts.length; i++) {
            if (!parts[i].isEmpty()) {
                sb.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1).toLowerCase());
            }
        }
        return sb.toString();
    }

    /** Ruft einen Diagnosebefehl über die DiagnosticCommand-MBean auf. */
    static String dcmdViaMBean(MBeanServerConnection mbs, String command, String... args) throws Exception {
        ObjectName name = new ObjectName("com.sun.management:type=DiagnosticCommand");
        String op = mbeanOperation(command);
        MBeanOperationInfo info = null;
        for (MBeanOperationInfo o : mbs.getMBeanInfo(name).getOperations()) {
            if (o.getName().equals(op)) {
                info = o;
                break;
            }
        }
        if (info == null) {
            throw new IllegalArgumentException("Diagnosebefehl '" + command + "' wird von der Ziel-JVM nicht angeboten.");
        }
        Object result = info.getSignature().length == 0
                ? mbs.invoke(name, op, new Object[0], new String[0])
                : mbs.invoke(name, op, new Object[]{args}, new String[]{String[].class.getName()});
        return result == null ? "" : result.toString();
    }

    /** Streamt eine JFR-Aufzeichnung per FlightRecorderMXBean in eine lokale Datei. */
    static Path streamRecording(MBeanServerConnection mbs, String recordingName, Path dest) throws Exception {
        FlightRecorderMXBean fr = ManagementFactory.newPlatformMXBeanProxy(mbs, FlightRecorderMXBean.MXBEAN_NAME,
                FlightRecorderMXBean.class);
        RecordingInfo info = fr.getRecordings().stream()
                .filter(r -> r.getName().equals(recordingName) || String.valueOf(r.getId()).equals(recordingName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Aufzeichnung '" + recordingName + "' nicht gefunden."));
        long id = info.getId();
        boolean snapshot = "RUNNING".equals(info.getState());
        long source = snapshot ? fr.cloneRecording(id, true) : id; // laufende Aufzeichnung: Kopie stoppen und lesen
        long stream = fr.openStream(source, Map.of("blockSize", "1048576"));
        try (OutputStream out = Files.newOutputStream(dest)) {
            byte[] chunk;
            while ((chunk = fr.readStream(stream)) != null) {
                out.write(chunk);
            }
        } finally {
            fr.closeStream(stream);
            if (snapshot) {
                fr.closeRecording(source);
            }
        }
        return dest;
    }

    // ================================================================================================ Local

    final class Local implements JvmTarget {
        private final JavaEnvironment env;
        private final LocalJvms.Jvm jvm;

        Local(JavaEnvironment env, LocalJvms.Jvm jvm) {
            this.env = env;
            this.jvm = jvm;
        }

        public long pid() {
            return jvm.pid();
        }

        public LocalJvms.Jvm jvm() {
            return jvm;
        }

        @Override
        public String label() {
            String n = jvm.displayName();
            int dot = n.lastIndexOf('.');
            return jvm.pid() + "-" + (dot >= 0 && !n.endsWith(".jar") ? n.substring(dot + 1) : n);
        }

        @Override
        public String describe() {
            return "lokale JVM " + jvm.pid() + " (" + jvm.displayName() + ")";
        }

        @Override
        public String dcmd(String command, String... args) {
            List<String> cmd = new ArrayList<>(List.of(env.jcmd().toString(), String.valueOf(jvm.pid()), command));
            cmd.addAll(List.of(args));
            var r = CommandRunner.run(cmd, DCMD_TIMEOUT).orThrow("jcmd " + command);
            return stripHeader(r.output(), jvm.pid());
        }

        @Override
        public Path fetchRecording(String recordingName, ArtifactStore store) {
            Path dest = store.newFile(label() + "-" + recordingName, "jfr");
            dcmd("JFR.dump", "name=" + recordingName, "filename=" + dest);
            requireFile(dest);
            return store.commit(dest);
        }

        @Override
        public Path heapDump(boolean liveOnly, ArtifactStore store) {
            Path dest = store.newFile(label(), "hprof");
            if (liveOnly) {
                dcmd("GC.heap_dump", dest.toString());
            } else {
                dcmd("GC.heap_dump", "-all", dest.toString());
            }
            requireFile(dest);
            return store.commit(dest);
        }

        /** Öffnet eine JMX-Verbindung über den lokalen Management-Agenten der Ziel-JVM. */
        public JMXConnector connectJmx() {
            try {
                VirtualMachine vm = VirtualMachine.attach(String.valueOf(jvm.pid()));
                try {
                    String url = vm.startLocalManagementAgent();
                    return JMXConnectorFactory.connect(new JMXServiceURL(url));
                } finally {
                    vm.detach();
                }
            } catch (Exception e) {
                throw new IllegalStateException("JMX-Verbindung zu PID " + jvm.pid() + " nicht möglich: " + e.getMessage(), e);
            }
        }
    }

    // ================================================================================================ Container

    final class InContainer implements JvmTarget {
        private static final String WORK = "/tmp/devtools-mcp";
        private final JavaEnvironment env;
        private final String container;
        private final long pid;

        InContainer(JavaEnvironment env, String container, Long pid) {
            this.env = env;
            this.container = container;
            env.containers().checkAllowed(container);
            this.pid = pid != null ? pid : detectPid();
        }

        public String container() {
            return container;
        }

        public long pid() {
            return pid;
        }

        public Containers containers() {
            return env.containers();
        }

        private long detectPid() {
            var r = env.containers().exec(container, Duration.ofSeconds(30), "jcmd", "-l");
            if (!r.ok()) {
                throw new IllegalStateException("jcmd im Container '" + container + "' nicht ausführbar – enthält das Image ein JDK? "
                        + r.output().strip());
            }
            List<Long> pids = new ArrayList<>();
            List<String> lines = new ArrayList<>();
            for (String line : r.output().split("\\R")) {
                Matcher m = Pattern.compile("^(\\d+)\\s+(.*)$").matcher(line.trim());
                if (m.matches() && !m.group(2).contains("sun.tools.jcmd.JCmd")) {
                    pids.add(Long.parseLong(m.group(1)));
                    lines.add(line.trim());
                }
            }
            if (pids.size() == 1) {
                return pids.getFirst();
            }
            throw new IllegalArgumentException(pids.isEmpty()
                    ? "Keine JVM im Container '" + container + "' gefunden."
                    : "Mehrere JVMs im Container, bitte container:" + container + ":<pid> angeben: " + lines);
        }

        @Override
        public String label() {
            return container + "-" + pid;
        }

        @Override
        public String describe() {
            return "JVM " + pid + " im Container " + container + " (" + env.containers().cli() + ")";
        }

        @Override
        public String dcmd(String command, String... args) {
            List<String> cmd = new ArrayList<>(List.of("jcmd", String.valueOf(pid), command));
            cmd.addAll(List.of(args));
            var r = env.containers().exec(container, DCMD_TIMEOUT, cmd.toArray(String[]::new)).orThrow("jcmd " + command);
            return stripHeader(r.output(), pid);
        }

        /** Führt einen Befehl im Container aus. */
        public CommandRunner.Result exec(Duration timeout, String... command) {
            return env.containers().exec(container, timeout, command);
        }

        /** Holt eine Datei aus dem Container in den Ablageordner und löscht sie im Container. */
        public Path fetch(String containerPath, ArtifactStore store, String ext) {
            Path dest = store.newFile(label(), ext);
            env.containers().copyFrom(container, containerPath, dest);
            exec(Duration.ofSeconds(30), "rm", "-f", containerPath);
            requireFile(dest);
            return store.commit(dest);
        }

        public String workFile(String ext) {
            exec(Duration.ofSeconds(30), "mkdir", "-p", WORK);
            return WORK + "/" + System.currentTimeMillis() + "." + ext;
        }

        @Override
        public Path fetchRecording(String recordingName, ArtifactStore store) {
            String remote = workFile("jfr");
            dcmd("JFR.dump", "name=" + recordingName, "filename=" + remote);
            return fetch(remote, store, "jfr");
        }

        @Override
        public Path heapDump(boolean liveOnly, ArtifactStore store) {
            String remote = workFile("hprof");
            if (liveOnly) {
                dcmd("GC.heap_dump", remote);
            } else {
                dcmd("GC.heap_dump", "-all", remote);
            }
            return fetch(remote, store, "hprof");
        }
    }

    // ================================================================================================ JMX

    final class Remote implements JvmTarget {
        private final JavaEnvironment env;
        private final JavaEnvironment.JmxTarget target;

        Remote(JavaEnvironment env, JavaEnvironment.JmxTarget target) {
            this.env = env;
            this.target = target;
        }

        public JavaEnvironment.JmxTarget jmxTarget() {
            return target;
        }

        @Override
        public String label() {
            return "jmx-" + target.alias();
        }

        @Override
        public String describe() {
            return "JVM über JMX " + target.alias() + " (" + target.address() + ")";
        }

        public JMXConnector connect() {
            Map<String, Object> envMap = new HashMap<>();
            if (env.jmxUser() != null) {
                envMap.put(JMXConnector.CREDENTIALS, new String[]{env.jmxUser(), env.jmxPassword() == null ? "" : env.jmxPassword()});
            }
            try {
                return JMXConnectorFactory.connect(new JMXServiceURL(
                        "service:jmx:rmi:///jndi/rmi://" + target.address() + "/jmxrmi"), envMap);
            } catch (IOException e) {
                throw new IllegalStateException("JMX-Verbindung zu " + target.address() + " fehlgeschlagen: " + e.getMessage(), e);
            }
        }

        @Override
        public String dcmd(String command, String... args) {
            try (JMXConnector c = connect()) {
                return dcmdViaMBean(c.getMBeanServerConnection(), command, args);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("Diagnosebefehl über JMX fehlgeschlagen: " + Errors.rootMessage(e), e);
            }
        }

        @Override
        public Path fetchRecording(String recordingName, ArtifactStore store) {
            Path dest = store.newFile(label() + "-" + recordingName, "jfr");
            try (JMXConnector c = connect()) {
                streamRecording(c.getMBeanServerConnection(), recordingName, dest);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("JFR-Übertragung über JMX fehlgeschlagen: " + Errors.rootMessage(e), e);
            }
            return store.commit(dest);
        }

        @Override
        public Path heapDump(boolean liveOnly, ArtifactStore store) {
            String remote = "/tmp/devtools-mcp-" + System.currentTimeMillis() + ".hprof";
            try (JMXConnector c = connect()) {
                HotSpotDiagnosticMXBean hs = ManagementFactory.newPlatformMXBeanProxy(c.getMBeanServerConnection(),
                        "com.sun.management:type=HotSpotDiagnostic", HotSpotDiagnosticMXBean.class);
                hs.dumpHeap(remote, liveOnly);
            } catch (Exception e) {
                throw new IllegalStateException("Heap-Dump über JMX fehlgeschlagen: " + Errors.rootMessage(e), e);
            }
            throw new IllegalStateException("Heap-Dump wurde auf dem entfernten Host geschrieben: " + target.host() + ":" + remote
                    + " – er kann über JMX nicht übertragen werden. Datei dort abholen und mit visualvm_heap_analyze auswerten.");
        }
    }

    // ================================================================================================ Hilfen

    static String stripHeader(String output, long pid) {
        String s = output;
        String header = pid + ":";
        if (s.startsWith(header)) {
            int nl = s.indexOf('\n');
            s = nl < 0 ? "" : s.substring(nl + 1);
        }
        return s.stripTrailing();
    }

    static void requireFile(Path p) {
        if (!Files.isRegularFile(p)) {
            throw new IllegalStateException("Erwartete Datei wurde nicht erzeugt: " + p);
        }
    }

}
