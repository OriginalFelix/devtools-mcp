package systems.grebe.devtools.mcp.modules.ide;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import systems.grebe.devtools.mcp.core.CommandRunner;
import systems.grebe.devtools.mcp.core.Text;

/**
 * Findet die Programme der IDEs (eingetragener Pfad, {@code PATH}, übliche Installationsorte) und startet sie, ohne auf
 * die IDE selbst zu warten: Die Befehlszeilen-Starter reichen einen Auftrag an die laufende IDE weiter und enden – läuft
 * noch keine, wird der Starter selbst zur IDE und läuft weiter.
 */
public final class IdePrograms {

    public static final boolean WINDOWS = CommandRunner.WINDOWS;
    public static final boolean MAC = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac");

    private IdePrograms() {
    }

    /**
     * Sucht ein Programm.
     *
     * @param configured eingetragener Pfad (Datei oder macOS-App); muss existieren, wenn gesetzt
     * @param commands   Namen im {@code PATH}, unter Windows ohne Endung ({@code .exe}, {@code .cmd} und {@code .bat}
     *                   werden ergänzt)
     * @param candidates übliche Installationsorte, {@code *} und {@code ?} als Platzhalter in jedem Abschnitt; bei
     *                   mehreren Treffern eines Musters gewinnt der alphabetisch letzte (meist die neueste Version)
     * @throws IllegalStateException wenn der eingetragene Pfad nicht existiert
     */
    public static Optional<Path> find(String configured, List<String> commands, List<String> candidates) {
        if (configured != null && !configured.isBlank()) {
            Path p = Path.of(expandHome(configured.strip()));
            if (!Files.exists(p)) {
                throw new IllegalStateException("Eingetragenes Programm nicht gefunden: " + p);
            }
            return Optional.of(p);
        }
        for (String command : commands) {
            Optional<Path> hit = onPath(command);
            if (hit.isPresent()) {
                return hit;
            }
        }
        for (String pattern : candidates) {
            List<Path> hits = glob(pattern);
            if (!hits.isEmpty()) {
                return Optional.of(hits.getLast());
            }
        }
        return Optional.empty();
    }

    /** Ausführbare Datei {@code command} im {@code PATH}. */
    public static Optional<Path> onPath(String command) {
        String path = System.getenv("PATH");
        if (path == null) {
            return Optional.empty();
        }
        List<String> names = WINDOWS ? List.of(command + ".exe", command + ".cmd", command + ".bat", command)
                : List.of(command);
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            for (String name : names) {
                try {
                    Path p = Path.of(dir.strip().replace("\"", "")).resolve(name);
                    if (Files.isRegularFile(p) && Files.isExecutable(p)) {
                        return Optional.of(p);
                    }
                } catch (InvalidPathException ignored) {
                    // kaputter PATH-Eintrag
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Existierende Pfade zu einem Muster mit {@code *}/{@code ?} in beliebigen Abschnitten (z.B.
     * {@code /Applications/IntelliJ IDEA*.app/Contents/MacOS/idea}), alphabetisch sortiert. {@code ~} am Anfang steht
     * für das Benutzerverzeichnis.
     */
    public static List<Path> glob(String pattern) {
        String p = expandHome(pattern).replace('\\', '/');
        List<String> segments = new ArrayList<>(List.of(p.split("/")));
        List<Path> current = new ArrayList<>();
        if (p.startsWith("/")) {
            current.add(Path.of("/"));
            segments.removeIf(String::isEmpty);
        } else if (!segments.isEmpty() && segments.getFirst().matches("[A-Za-z]:")) {
            current.add(Path.of(segments.removeFirst() + "\\"));
        } else {
            return List.of();
        }
        for (String segment : segments) {
            if (segment.isEmpty()) {
                continue;
            }
            List<Path> next = new ArrayList<>();
            if (segment.contains("*") || segment.contains("?")) {
                PathMatcher m = FileSystems.getDefault().getPathMatcher("glob:" + segment);
                for (Path dir : current) {
                    if (!Files.isDirectory(dir)) {
                        continue;
                    }
                    try (Stream<Path> children = Files.list(dir)) {
                        children.filter(c -> c.getFileName() != null && m.matches(c.getFileName())).forEach(next::add);
                    } catch (IOException ignored) {
                        // nicht lesbar
                    }
                }
            } else {
                for (Path dir : current) {
                    Path child = dir.resolve(segment);
                    if (Files.exists(child)) {
                        next.add(child);
                    }
                }
            }
            current = next;
            if (current.isEmpty()) {
                return List.of();
            }
        }
        return current.stream().sorted(Comparator.comparing(Path::toString)).toList();
    }

    /** Benutzerverzeichnis bzw. Unterpfad davon. */
    public static String home(String sub) {
        return System.getProperty("user.home") + "/" + sub;
    }

    /**
     * Pfad unter dem Verzeichnis aus einer Umgebungsvariablen, z.B. {@code LOCALAPPDATA}; leer, wenn sie nicht gesetzt
     * ist. Als Text, weil Muster mit {@code *} unter Windows kein gültiger {@link Path} sind.
     */
    public static Optional<String> env(String name, String sub) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? Optional.empty() : Optional.of(v + "/" + sub);
    }

    private static String expandHome(String p) {
        if (p.equals("~") || p.startsWith("~/") || p.startsWith("~\\")) {
            return System.getProperty("user.home") + p.substring(1);
        }
        return p;
    }

    /**
     * Startet {@code command} (ohne Shell) und wartet höchstens {@code wait} auf sein Ende.
     *
     * @return Ausgabe des beendeten Starters (oft leer) oder {@code null}, wenn er noch läuft – dann ist er die gerade
     *         gestartete IDE und bleibt unabhängig von diesem Aufruf offen
     * @throws IllegalStateException wenn das Programm nicht startet oder mit Fehler endet
     */
    public static String launch(List<String> command, Duration wait) {
        Path log;
        try {
            log = Files.createTempFile("devtools-ide-", ".log");
            log.toFile().deleteOnExit();
        } catch (IOException e) {
            throw new IllegalStateException("Temporäre Datei nicht anlegbar: " + e.getMessage(), e);
        }
        Process p;
        try {
            p = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            p.getOutputStream().close();
        } catch (IOException e) {
            throw new IllegalStateException("Programm nicht startbar: " + command.getFirst() + " (" + e.getMessage() + ")",
                    e);
        }
        try {
            if (!p.waitFor(wait.toMillis(), TimeUnit.MILLISECONDS)) {
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Abgebrochen", e);
        }
        String out;
        try {
            out = Files.readString(log, Charset.forName(System.getProperty("native.encoding", "UTF-8"))).strip();
            Files.deleteIfExists(log);
        } catch (IOException | RuntimeException e) {
            out = "";
        }
        if (p.exitValue() != 0) {
            throw new IllegalStateException(Path.of(command.getFirst()).getFileName() + " meldet einen Fehler (Exit-Code "
                    + p.exitValue() + ")" + (out.isEmpty() ? "" : ": " + Text.limitLines(out, 20)));
        }
        return out;
    }
}
