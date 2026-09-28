package systems.grebe.devtools.mcp.testjvm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.modules.java.JavaEnvironment;
import systems.grebe.devtools.mcp.modules.java.JavaSettingsModule;

/** Startet {@link FixtureApp} als echten Kindprozess (optional mit JMX und JDWP). */
public final class Fixture implements AutoCloseable {

    private final Process process;
    public final int jmxPort;
    public final int jdwpPort;

    private Fixture(Process process, int jmxPort, int jdwpPort) {
        this.process = process;
        this.jmxPort = jmxPort;
        this.jdwpPort = jdwpPort;
    }

    public static Fixture start(boolean jmx, boolean jdwp, boolean deadlock) {
        int jmxPort = jmx ? freePort() : -1;
        int jdwpPort = jdwp ? freePort() : -1;
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> cmd = new ArrayList<>(List.of(java, "-Xmx256m", "-XX:+UnlockDiagnosticVMOptions", "-XX:+DebugNonSafepoints",
                "-XX:NativeMemoryTracking=summary", "-Dfixture.marker=devtools-mcp-test"));
        if (jmx) {
            cmd.addAll(List.of("-Dcom.sun.management.jmxremote.port=" + jmxPort,
                    "-Dcom.sun.management.jmxremote.rmi.port=" + jmxPort,
                    "-Dcom.sun.management.jmxremote.authenticate=false",
                    "-Dcom.sun.management.jmxremote.ssl=false",
                    "-Dcom.sun.management.jmxremote.host=127.0.0.1",
                    "-Djava.rmi.server.hostname=127.0.0.1"));
        }
        if (jdwp) {
            cmd.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:" + jdwpPort);
        }
        cmd.addAll(List.of("-cp", System.getProperty("java.class.path"), FixtureApp.class.getName()));
        if (deadlock) {
            cmd.add("deadlock");
        }
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            CompletableFuture<Void> ready = new CompletableFuture<>();
            Thread.ofVirtual().start(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (line.contains("FIXTURE READY")) {
                            ready.complete(null);
                        }
                    }
                } catch (IOException ignored) {
                    // Ende
                }
            });
            ready.get(60, TimeUnit.SECONDS);
            Thread.sleep(1500); // JIT/Last anlaufen lassen
            return new Fixture(p, jmxPort, jdwpPort);
        } catch (Exception e) {
            throw new IllegalStateException("Fixture-JVM startet nicht", e);
        }
    }

    public long pid() {
        return process.pid();
    }

    /** Java-Umgebung, die nur diese Fixture freigibt. */
    public JavaEnvironment environment(Path artifactDir) {
        List<ConfigField> schema = new JavaSettingsModule().configSchema();
        Map<String, String> v = new java.util.HashMap<>(Map.of(
                "artifactDir", artifactDir.toString(),
                "includeProcesses", "testjvm\\.FixtureApp",
                "excludeProcesses", ""));
        if (jmxPort > 0) {
            v.put("jmxTargets", "fixture=127.0.0.1:" + jmxPort);
        }
        return new JavaEnvironment(ModuleConfig.of(schema, v));
    }

    @Override
    public void close() {
        process.destroyForcibly();
        try {
            process.waitFor(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
