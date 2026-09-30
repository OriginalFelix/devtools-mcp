package systems.grebe.devtools.mcp.modules.java;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import systems.grebe.devtools.mcp.core.CommandRunner;

/** Lokale JVMs des aktuellen Benutzers (über die Attach-API bzw. jcmd -l als Ausweichweg). */
public final class LocalJvms {

    public record Jvm(long pid, String mainClass, String commandLine) {
        public String displayName() {
            return mainClass.isBlank() ? "(unbekannt)" : mainClass;
        }
    }

    private final Pattern include;
    private final Pattern exclude;
    private final java.nio.file.Path jcmd;

    LocalJvms(Pattern include, Pattern exclude, java.nio.file.Path jcmd) {
        this.include = include;
        this.exclude = exclude;
        this.jcmd = jcmd;
    }

    /** Alle freigegebenen JVMs (ohne diese App). */
    public List<Jvm> list() {
        List<Jvm> out = new ArrayList<>();
        long self = ProcessHandle.current().pid();
        for (Jvm j : discover()) {
            if (j.pid() == self || !allowed(j)) {
                continue;
            }
            out.add(j);
        }
        return out;
    }

    private List<Jvm> discover() {
        List<Jvm> out = new ArrayList<>();
        try {
            for (VirtualMachineDescriptor d : VirtualMachine.list()) {
                String name = d.displayName() == null ? "" : d.displayName();
                out.add(new Jvm(Long.parseLong(d.id()), mainClass(name), name));
            }
            return out;
        } catch (RuntimeException | LinkageError e) {
            // Attach-API nicht verfügbar -> jcmd -l
        }
        var r = CommandRunner.run(List.of(jcmd.toString(), "-l"), Duration.ofSeconds(20));
        for (String line : r.output().split("\\R")) {
            int sp = line.indexOf(' ');
            if (sp > 0) {
                try {
                    String rest = line.substring(sp + 1).trim();
                    if (!rest.startsWith("jdk.jcmd/")) {
                        out.add(new Jvm(Long.parseLong(line.substring(0, sp).trim()), mainClass(rest), rest));
                    }
                } catch (NumberFormatException ignored) {
                    // Kopfzeile o.ä.
                }
            }
        }
        return out;
    }

    public boolean allowed(Jvm j) {
        String hay = j.commandLine();
        if (include != null && !include.matcher(hay).find()) {
            return false;
        }
        return exclude == null || !exclude.matcher(hay).find();
    }

    /** PID oder eindeutiger Teil der Hauptklasse/Kommandozeile. */
    public Jvm find(String pidOrName) {
        List<Jvm> all = list();
        String q = pidOrName.trim();
        if (q.matches("\\d+")) {
            long pid = Long.parseLong(q);
            return all.stream().filter(j -> j.pid() == pid).findFirst().orElseThrow(() ->
                    new IllegalArgumentException("Keine freigegebene JVM mit PID " + pid + ". Verfügbar: " + summary(all)));
        }
        String lower = q.toLowerCase(Locale.ROOT);
        List<Jvm> hits = all.stream().filter(j -> j.commandLine().toLowerCase(Locale.ROOT).contains(lower)).toList();
        if (hits.size() == 1) {
            return hits.getFirst();
        }
        // exakte Hauptklasse bevorzugen
        List<Jvm> exact = hits.stream().filter(j -> j.mainClass().equalsIgnoreCase(q)
                || j.mainClass().toLowerCase(Locale.ROOT).endsWith("." + lower)).toList();
        if (exact.size() == 1) {
            return exact.getFirst();
        }
        if (hits.isEmpty()) {
            throw new IllegalArgumentException("Keine freigegebene JVM passt zu '" + q + "'. Verfügbar: " + summary(all));
        }
        throw new IllegalArgumentException("'" + q + "' ist mehrdeutig, bitte PID angeben: " + summary(hits));
    }

    private static String summary(List<Jvm> list) {
        if (list.isEmpty()) {
            return "(keine)";
        }
        return list.stream().map(j -> j.pid() + " " + j.displayName()).toList().toString();
    }

    static String mainClass(String displayName) {
        String first = displayName.trim().split("\\s+", 2)[0];
        int slash = first.indexOf('/');
        if (slash > 0 && !first.endsWith(".jar")) {
            first = first.substring(slash + 1); // modul/klasse
        }
        return first;
    }
}
