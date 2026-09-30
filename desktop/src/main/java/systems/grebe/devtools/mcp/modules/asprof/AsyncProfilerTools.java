package systems.grebe.devtools.mcp.modules.asprof;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.CommandRunner;
import systems.grebe.devtools.mcp.core.Text;
import systems.grebe.devtools.mcp.modules.java.JavaEnvironment;
import systems.grebe.devtools.mcp.modules.java.JvmTarget;
import systems.grebe.devtools.mcp.modules.java.StackProfile;
import systems.grebe.devtools.mcp.core.ShellHints;

/** async-profiler-Tools. Ergebnis wird immer als JFR geschrieben und einheitlich zu Flame Graph + Text ausgewertet. */
public class AsyncProfilerTools {

    private static final String TARGET = "Ziel: container:<name>[:<pid>] (unter Windows nötig) oder lokale PID/Name (Linux/macOS)";
    private static final String EVENT = "cpu (Standard; im Container ohne perf-Rechte automatisch itimer), wall, alloc, lock";

    private final Supplier<JavaEnvironment> env;
    private final AsprofInstaller installer;
    private final int maxSeconds;

    AsyncProfilerTools(Supplier<JavaEnvironment> env, AsprofInstaller installer, int maxSeconds) {
        this.env = env;
        this.installer = installer;
        this.maxSeconds = maxSeconds;
    }

    @Tool(name = "profile", description = "Profilt eine JVM für N Sekunden mit async-profiler und liefert heißeste Methoden, "
            + "Aufrufpfade und einen Flame Graph (HTML). Die .jfr-Datei lässt sich zusätzlich mit jfr_analyze auswerten."
            + " Statt `asprof -d <s>` verwenden." + ShellHints.ASPROF)
    public String profile(
            @ToolParam(required = false, description = TARGET) String target,
            @ToolParam(required = false, description = EVENT) String event,
            @ToolParam(required = false, description = "Dauer in Sekunden (Standard 30)") Integer seconds,
            @ToolParam(required = false, description = "Nur Stacks mit passenden Frames (regulärer Ausdruck), z.B. com\\.acme\\..*") String include,
            @ToolParam(required = false, description = "true = Threads getrennt ausweisen") Boolean perThread) {
        JavaEnvironment e = env.get();
        JvmTarget t = e.target(target);
        int secs = seconds == null ? 30 : Math.max(1, Math.min(maxSeconds, seconds));
        StackProfile.Kind kind = StackProfile.Kind.parse(event);
        Run run = Run.of(this, t);
        List<String> args = new ArrayList<>(List.of("-d", String.valueOf(secs), "-e", run.event(kind), "-o", "jfr"));
        if (Boolean.TRUE.equals(perThread)) {
            args.add("-t");
        }
        String note = run.eventNote(kind);
        CommandRunner.Result r = run.asprof(args, Duration.ofSeconds(secs + 90), true);
        if (!r.ok()) {
            throw new IllegalStateException("async-profiler fehlgeschlagen: " + hint(r.output()));
        }
        Path jfr = run.fetchOutput();
        StackProfile.Result res = StackProfile.fromJfr(jfr, kind, include, null,
                "async-profiler " + kind.name().toLowerCase(Locale.ROOT) + " – " + t.label(), e.artifacts());
        return Text.limitLines(t.describe() + " – " + secs + " s, Event " + run.event(kind) + note + "\n"
                + "JFR: " + jfr + "\nFlame Graph: " + res.flameGraph() + "\n\n" + res.profile().summary(20, 5, 14), e.maxLines());
    }

    @Tool(name = "start", description = "Startet async-profiler ohne festes Ende (z.B. während eines Lasttests). "
            + "Mit asprof_stop beenden und auswerten."
            + " Statt `asprof start` verwenden." + ShellHints.ASPROF)
    public String start(
            @ToolParam(required = false, description = TARGET) String target,
            @ToolParam(required = false, description = EVENT) String event) {
        JvmTarget t = env.get().target(target);
        Run run = Run.of(this, t);
        StackProfile.Kind kind = StackProfile.Kind.parse(event);
        // JFR-Ausgabe muss bei async-profiler schon beim Start feststehen (wird fortlaufend geschrieben)
        List<String> args = new ArrayList<>(List.of("start", "-e", run.event(kind), "-o", "jfr", "-f", run.sessionFile(true)));
        CommandRunner.Result r = run.asprof(args, Duration.ofSeconds(60), false);
        if (!r.ok()) {
            throw new IllegalStateException("Start fehlgeschlagen: " + hint(r.output()));
        }
        return t.describe() + ": Profiling gestartet (Event " + run.event(kind) + run.eventNote(kind) + "). Beenden mit asprof_stop.";
    }

    @Tool(name = "stop", description = "Beendet ein mit asprof_start gestartetes Profiling und liefert Auswertung + Flame Graph."
            + " Statt `asprof stop` verwenden." + ShellHints.ASPROF)
    public String stop(
            @ToolParam(required = false, description = TARGET) String target,
            @ToolParam(required = false, description = "Profilart des gestarteten Laufs: " + EVENT) String event,
            @ToolParam(required = false, description = "Nur Stacks mit passenden Frames (regulärer Ausdruck)") String include) {
        JavaEnvironment e = env.get();
        JvmTarget t = e.target(target);
        Run run = Run.of(this, t);
        String session = run.sessionFile(false);
        CommandRunner.Result r = run.asprof(List.of("stop", "-o", "jfr", "-f", session), Duration.ofMinutes(2), false);
        if (!r.ok()) {
            throw new IllegalStateException("Stop fehlgeschlagen: " + hint(r.output()));
        }
        StackProfile.Kind kind = StackProfile.Kind.parse(event);
        Path jfr = run.fetchSession(session);
        StackProfile.Result res = StackProfile.fromJfr(jfr, kind, include, null, null, e.artifacts());
        return Text.limitLines(t.describe() + "\nJFR: " + jfr + "\nFlame Graph: " + res.flameGraph() + "\n\n"
                + res.profile().summary(20, 5, 14), e.maxLines());
    }

    @Tool(name = "status", description = "Zeigt, ob async-profiler in der Ziel-JVM aktiv ist, und listet die verfügbaren Events."
            + " Statt `asprof status` verwenden." + ShellHints.ASPROF)
    public String status(@ToolParam(required = false, description = TARGET) String target) {
        JvmTarget t = env.get().target(target);
        Run run = Run.of(this, t);
        String status = run.asprof(List.of("status"), Duration.ofSeconds(30), false).output().strip();
        String events = run.asprof(List.of("list"), Duration.ofSeconds(30), false).output().strip();
        return t.describe() + "\nStatus: " + status + "\n\n" + Text.limitLines(events, 60);
    }

    private static String hint(String output) {
        String o = output.strip();
        if (o.contains("perf_event_open") || o.contains("Perf events unavailable")) {
            o += "\nHinweis: perf_events im Container nicht erlaubt – Event 'cpu' fällt dann auf itimer zurück; oder Container "
                    + "mit --cap-add SYS_ADMIN bzw. kernel.perf_event_paranoid=1 starten.";
        }
        return Text.limitLines(o, 30);
    }

    // ------------------------------------------------------------------ Ausführung lokal / Container

    private abstract static class Run {
        final JvmTarget target;
        final String outFile;

        Run(JvmTarget target, String outFile) {
            this.target = target;
            this.outFile = outFile;
        }

        static Run of(AsyncProfilerTools tools, JvmTarget t) {
            return switch (t) {
                case JvmTarget.InContainer c -> new InContainer(tools, c);
                case JvmTarget.Local l -> new Local(tools, l);
                case JvmTarget.Remote r -> throw new IllegalArgumentException(
                        "async-profiler braucht Zugriff auf den Prozess (lokal oder Container). Für JMX-Ziele jfr_record verwenden.");
            };
        }

        /** Führt asprof aus; mit {@code withFile} wird {@code -f <outFile>} ergänzt. */
        abstract CommandRunner.Result asprof(List<String> args, Duration timeout, boolean withFile);

        abstract Path fetchOutput();

        /** Fester Dateiname der start/stop-Sitzung pro Ziel-PID (start und stop sind getrennte Tool-Aufrufe). */
        abstract String sessionFile(boolean create);

        abstract Path fetchSession(String session);

        String event(StackProfile.Kind k) {
            return switch (k) {
                case CPU -> "cpu";
                case WALL -> "wall";
                case ALLOC -> "alloc";
                case LOCK -> "lock";
            };
        }

        String eventNote(StackProfile.Kind k) {
            return "";
        }
    }

    private static final class Local extends Run {
        private final Path asprof;
        private final long pid;
        private final JavaEnvironment env;

        Local(AsyncProfilerTools tools, JvmTarget.Local t) {
            super(t, null);
            this.asprof = tools.installer.localAsprof();
            this.pid = t.pid();
            this.env = tools.env.get();
        }

        private Path file;

        @Override
        CommandRunner.Result asprof(List<String> args, Duration timeout, boolean withFile) {
            List<String> cmd = new ArrayList<>(List.of(asprof.toString()));
            cmd.addAll(args);
            if (withFile) {
                file = env.artifacts().newFile(target.label() + "-asprof", "jfr");
                cmd.addAll(List.of("-f", file.toString()));
            }
            cmd.add(String.valueOf(pid));
            return CommandRunner.run(cmd, timeout);
        }

        @Override
        Path fetchOutput() {
            return env.artifacts().commit(file);
        }

        @Override
        String sessionFile(boolean create) {
            Path p = env.artifacts().dir().resolve(".asprof-session-" + pid + ".jfr");
            if (!create && !java.nio.file.Files.exists(p)) {
                throw new IllegalStateException("Keine mit asprof_start gestartete Sitzung für PID " + pid + " gefunden.");
            }
            return p.toString();
        }

        @Override
        Path fetchSession(String session) {
            Path target = env.artifacts().newFile(this.target.label() + "-asprof", "jfr");
            try {
                java.nio.file.Files.move(Path.of(session), target);
            } catch (java.io.IOException ex) {
                throw new java.io.UncheckedIOException("Sitzungsdatei nicht übernehmbar: " + ex.getMessage(), ex);
            }
            return env.artifacts().commit(target);
        }
    }

    private static final class InContainer extends Run {
        private final JvmTarget.InContainer c;
        private final String asprof;
        private final JavaEnvironment env;
        private Boolean perfAvailable;

        InContainer(AsyncProfilerTools tools, JvmTarget.InContainer c) {
            super(c, AsprofInstaller.CONTAINER_DIR + "/profile-" + System.currentTimeMillis() + ".jfr");
            this.c = c;
            this.asprof = tools.installer.containerAsprof(c);
            this.env = tools.env.get();
        }

        @Override
        CommandRunner.Result asprof(List<String> args, Duration timeout, boolean withFile) {
            List<String> cmd = new ArrayList<>(List.of(asprof));
            cmd.addAll(args);
            if (withFile) {
                cmd.addAll(List.of("-f", outFile));
            }
            cmd.add(String.valueOf(c.pid()));
            return c.exec(timeout, cmd.toArray(String[]::new));
        }

        @Override
        String event(StackProfile.Kind k) {
            if (k == StackProfile.Kind.CPU && !perf()) {
                return "itimer";
            }
            return super.event(k);
        }

        @Override
        String eventNote(StackProfile.Kind k) {
            return k == StackProfile.Kind.CPU && !perf() ? " (perf_events im Container nicht verfügbar → itimer, ohne Kernel-Frames)" : "";
        }

        private boolean perf() {
            if (perfAvailable == null) {
                var r = c.exec(Duration.ofSeconds(10), "cat", "/proc/sys/kernel/perf_event_paranoid");
                String v = r.output().strip();
                perfAvailable = r.ok() && (v.equals("-1") || v.equals("0") || v.equals("1"));
            }
            return perfAvailable;
        }

        @Override
        Path fetchOutput() {
            return c.fetch(outFile, env.artifacts(), "jfr");
        }

        @Override
        String sessionFile(boolean create) {
            return AsprofInstaller.CONTAINER_DIR + "/session-" + c.pid() + ".jfr";
        }

        @Override
        Path fetchSession(String session) {
            Path p = c.fetch(session, env.artifacts(), "jfr");
            c.exec(Duration.ofSeconds(10), "rm", "-f", session);
            return p;
        }
    }
}
