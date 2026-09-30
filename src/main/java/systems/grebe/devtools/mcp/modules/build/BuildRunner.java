package systems.grebe.devtools.mcp.modules.build;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.Workspaces;
import systems.grebe.devtools.mcp.modules.build.BuildModule.BuildTool;

/** Startet Gradle/Maven als Kindprozess (ohne Shell-Interpretation der Argumente). */
final class BuildRunner {

    /** Erlaubte Zeichen in Argumenten – verhindert Shell-Metazeichen bei cmd.exe (gradlew.bat/mvnw.cmd). */
    private static final Pattern SAFE_ARG = Pattern.compile("[A-Za-z0-9_:.,=/@*+\\-#]+");
    private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    private static final Map<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    private final Workspaces projects;
    private final String defaultProject;
    private final Set<String> allowedTasks;
    private final String javaHome;
    private final List<String> extraArgs;
    private final Duration timeout;
    private final int maxLines;

    BuildRunner(ModuleConfig config) {
        this.projects = new Workspaces(config.getList(BuildModule.PROJECTS), BuildModule::isProject, "Build-Projekte");
        this.defaultProject = config.getString(BuildModule.DEFAULT_PROJECT, null);
        this.allowedTasks = Set.copyOf(config.getList(BuildModule.ALLOWED_TASKS));
        this.javaHome = config.getString(BuildModule.JAVA_HOME, null);
        this.extraArgs = config.get(BuildModule.EXTRA_ARGS).map(s -> List.of(s.trim().split("\\s+"))).orElse(List.of());
        this.timeout = Duration.ofMinutes(Math.max(1, config.getInt(BuildModule.TIMEOUT, 10)));
        this.maxLines = Math.max(20, config.getInt(BuildModule.MAX_LINES, 200));
    }

    Workspaces projects() {
        return projects;
    }

    int maxLines() {
        return maxLines;
    }

    Set<String> allowedTasks() {
        return allowedTasks;
    }

    Path resolve(String project) {
        return projects.resolve(project, defaultProject);
    }

    record Result(Path project, BuildTool tool, List<String> command, int exitCode, boolean timedOut,
                  Duration duration, List<String> output) {
        boolean success() {
            return !timedOut && exitCode == 0;
        }
    }

    Result run(String project, List<String> tasks, List<String> args) {
        Path dir = resolve(project);
        Workspaces.requireWritable(dir); // Build führt Code des Projekts aus und schreibt build/ bzw. target/
        BuildTool tool = BuildTool.detect(dir);
        if (tasks == null || tasks.isEmpty()) {
            throw new IllegalArgumentException("Mindestens ein Task/Goal angeben.");
        }
        List<String> safeArgs = args == null ? List.of() : args;
        for (String t : tasks) {
            checkArg(t);
            if (!allowedTasks.isEmpty() && !allowedTasks.contains(t) && !allowedTasks.contains(taskName(t))) {
                throw new IllegalArgumentException("Task '" + t + "' ist nicht freigegeben. Erlaubt: " + allowedTasks);
            }
        }
        safeArgs.forEach(BuildRunner::checkArg);
        extraArgs.forEach(BuildRunner::checkArg);

        List<String> cmd = new ArrayList<>(launcher(dir, tool));
        if (tool == BuildTool.GRADLE) {
            cmd.add("--console=plain");
        } else {
            cmd.add("-B"); // Batch-Modus: keine Farben/Fortschrittsbalken
        }
        cmd.addAll(extraArgs);
        cmd.addAll(tasks);
        cmd.addAll(safeArgs);

        ReentrantLock lock = LOCKS.computeIfAbsent(dir, k -> new ReentrantLock());
        if (!lock.tryLock()) {
            throw new IllegalStateException("Für " + dir.getFileName() + " läuft bereits ein Build.");
        }
        try {
            return execute(dir, tool, cmd);
        } finally {
            lock.unlock();
        }
    }

    private Result execute(Path dir, BuildTool tool, List<String> cmd) {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
        if (javaHome != null && !javaHome.isBlank()) {
            pb.environment().put("JAVA_HOME", javaHome);
        }
        pb.environment().put("TERM", "dumb");
        long start = System.nanoTime();
        List<String> output = Collections.synchronizedList(new ArrayList<>());
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new IllegalStateException("Build konnte nicht gestartet werden (" + String.join(" ", cmd) + "): "
                    + e.getMessage(), e);
        }
        Charset cs = Charset.forName(System.getProperty("native.encoding", Charset.defaultCharset().name()));
        Thread reader = Thread.ofVirtual().start(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), cs))) {
                String line;
                while ((line = r.readLine()) != null) {
                    output.add(line);
                }
            } catch (IOException ignored) {
                // Prozess beendet
            }
        });
        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
            reader.join(Duration.ofSeconds(5));
        } catch (InterruptedException e) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Build abgebrochen", e);
        }
        Duration duration = Duration.ofNanos(System.nanoTime() - start);
        int exit = finished ? process.exitValue() : -1;
        synchronized (output) {
            return new Result(dir, tool, cmd, exit, !finished, duration, List.copyOf(output));
        }
    }

    private static List<String> launcher(Path dir, BuildTool tool) {
        return switch (tool) {
            case GRADLE -> wrapperOrGlobal(dir, "gradlew", "gradle");
            case MAVEN -> wrapperOrGlobal(dir, "mvnw", "mvn");
            case NONE -> throw new IllegalStateException("Kein Gradle-/Maven-Projekt: " + dir);
        };
    }

    private static List<String> wrapperOrGlobal(Path dir, String wrapper, String global) {
        if (WINDOWS) {
            String ext = wrapper.equals("gradlew") ? ".bat" : ".cmd";
            Path w = dir.resolve(wrapper + ext);
            // .bat/.cmd müssen über cmd.exe laufen; Argumente sind per SAFE_ARG gegen Metazeichen geprüft
            return List.of("cmd.exe", "/c", Files.exists(w) ? w.toString() : global + (global.equals("mvn") ? ".cmd" : ".bat"));
        }
        Path w = dir.resolve(wrapper);
        if (Files.exists(w)) {
            return Files.isExecutable(w) ? List.of(w.toString()) : List.of("sh", w.toString());
        }
        return List.of(global);
    }

    static void checkArg(String arg) {
        if (arg == null || !SAFE_ARG.matcher(arg).matches()) {
            throw new IllegalArgumentException("Unzulässiges Argument: '" + arg
                    + "'. Erlaubt sind Buchstaben, Ziffern und _:.,=/@*+-#");
        }
    }

    /** ':app:test' -> 'test'; Maven 'dependency:tree' bleibt erhalten. */
    static String taskName(String task) {
        if (task.startsWith(":")) {
            return task.substring(task.lastIndexOf(':') + 1);
        }
        return task;
    }
}
