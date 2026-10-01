package systems.grebe.devtools.mcp.modules.decompile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.ToolHints;

/** Decompiler-Tools: Klassen aus dem JDK, aus JARs und Klassenverzeichnissen als Java-Quelltext. */
@ToolHints(readOnly = true, idempotent = true, openWorld = false)
public class DecompileTools {

    private static final String SOURCE_PARAM = "Pfad zu JAR/WAR, Klassenverzeichnis oder .class-Datei; 'jdk' bzw. "
            + "'jrt:/<modul>' für das JDK (z.B. aus decompile_find)";

    private final ClassFinder finder;
    private final int maxLines;
    private final int maxSecondsPerMethod;

    DecompileTools(List<Path> searchPaths, int maxLines, int maxSecondsPerMethod) {
        this.finder = new ClassFinder(searchPaths);
        this.maxLines = maxLines;
        this.maxSecondsPerMethod = maxSecondsPerMethod;
    }

    @Tool(name = "class", description = "Dekompiliert eine Java-Klasse (inkl. verschachtelter Klassen) mit Vineflower "
            + "(Fernflower-Fork) zu Java-Quelltext – für Bibliotheken ohne Quellen-JAR, JDK-Interna oder kompilierte Klassen "
            + "eines Projekts. Ohne 'source' wird im JDK und in den Suchpfaden (Maven-/Gradle-Cache) gesucht; bei mehreren "
            + "Versionen gewinnt die höchste. Lange Ausgaben mit 'startLine' seitenweise lesen." + ShellHints.DECOMPILE)
    public String decompileClass(
            @ToolParam(required = false, description = "Voll qualifizierter Klassenname, z.B. java.util.HashMap oder "
                    + "org.foo.Outer$Inner; bei einer .class-Datei als source nicht nötig") String className,
            @ToolParam(required = false, description = SOURCE_PARAM + "; leer = automatisch suchen") String source,
            @ToolParam(required = false, description = "Originale Zeilennummern als Kommentare (Standard false)") Boolean lineNumbers,
            @ToolParam(required = false, description = "Ab dieser Zeile der Ausgabe liefern (1-basiert, Standard 1)") Integer startLine) {
        String note = "";
        ClassSource src;
        String name;
        if (source != null && !source.isBlank() && source.strip().endsWith(ClassSource.CLASS)) {
            Path file = existing(source);
            byte[] bytes = readAll(file);
            name = ClassFile.of().parse(bytes).thisClass().asInternalName();
            src = classFileSource(file, name);
        } else if (source != null && !source.isBlank()) {
            src = open(source);
            name = locate(src, candidates(className));
        } else {
            List<String> candidates = candidates(className);
            List<ClassFinder.Hit> hits = find(candidates, null, 500);
            if (hits.isEmpty()) {
                throw new IllegalArgumentException("Klasse " + className + " weder im JDK noch in den Suchpfaden gefunden. "
                        + "Mit 'source' auf ein JAR oder Klassenverzeichnis zeigen; Suchpfade: " + finderRoots());
            }
            ClassFinder.Hit hit = ClassFinder.best(hits);
            src = open(hit.source());
            name = hit.className();
            if (hits.size() > 1) {
                note = "// Weitere Fundstellen: " + (hits.size() - 1) + " (siehe decompile_find)\n";
            }
        }
        try (src) {
            String top = topLevel(src, name);
            Map<String, byte[]> classes = new LinkedHashMap<>();
            classes.put(top, src.read(top));
            for (String nested : src.classes(top + "$")) {
                classes.put(nested, src.read(nested));
            }
            List<ClassSource> libraries = new ArrayList<>(List.of(src));
            if (!(src instanceof ClassSource.Jdk)) {
                libraries.add(new ClassSource.Jdk(null));
            }
            Vineflower.Result result = Vineflower.decompile(classes, libraries, Boolean.TRUE.equals(lineNumbers),
                    maxSecondsPerMethod);
            StringBuilder header = new StringBuilder("// Quelle: ").append(src.label()).append('\n')
                    .append("// Klasse: ").append(top.replace('/', '.'));
            if (classes.size() > 1) {
                header.append(" (+ ").append(classes.size() - 1).append(" verschachtelte)");
            }
            if (!top.equals(name)) {
                header.append(" – enthält ").append(name.replace('/', '.'));
            }
            header.append("\n// Dekompiliert mit Vineflower, kein Originalquelltext.\n").append(note);
            String body = result.source().isBlank() ? "(Vineflower lieferte keinen Quelltext)" : result.source();
            String out = header + "\n" + page(body, startLine == null ? 1 : startLine);
            if (!result.messages().isEmpty()) {
                out += "\n\nMeldungen des Decompilers:\n" + result.messages().stream().limit(20)
                        .collect(Collectors.joining("\n"));
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Tool(name = "find", description = "Sucht, wo eine Klasse liegt: im JDK und in den JARs der Suchpfade (lokales "
            + "Maven-Repository, Gradle-Cache). Liefert je Fundstelle Quelle und Version – die Quelle direkt an "
            + "decompile_class übergeben." + ShellHints.DECOMPILE)
    public String find(
            @ToolParam(description = "Voll qualifizierter Name (org.foo.Bar) oder einfacher Klassenname (Bar, langsamer)") String className,
            @ToolParam(required = false, description = "Max. Treffer (Standard 50)") Integer limit) {
        String n = normalize(className);
        boolean simple = !n.contains("/");
        List<ClassFinder.Hit> hits = find(simple ? List.of() : candidates(className), simple ? n : null,
                limit == null ? 50 : Math.max(1, limit));
        if (hits.isEmpty()) {
            return "Keine Fundstelle für " + className + ". Durchsucht: JDK, " + finderRoots();
        }
        StringBuilder sb = new StringBuilder(hits.size() + " Fundstelle(n):\n");
        for (ClassFinder.Hit h : hits) {
            sb.append(h.className().replace('/', '.')).append("  ").append(h.version()).append("  ").append(h.source()).append('\n');
        }
        return sb.toString().trim();
    }

    @Tool(name = "list", description = "Listet die Klassen eines JARs, Klassenverzeichnisses oder JDK-Moduls, optional "
            + "gefiltert nach Paket bzw. Namenspräfix (verschachtelte Klassen werden nur gezählt)." + ShellHints.DECOMPILE)
    public String list(
            @ToolParam(description = SOURCE_PARAM) String source,
            @ToolParam(required = false, description = "Paket oder Namenspräfix, z.B. org.springframework.core; beim JDK "
                    + "ohne Modul Pflicht") String filter,
            @ToolParam(required = false, description = "Max. Einträge (Standard 500)") Integer limit) {
        String prefix = filter == null || filter.isBlank() ? "" : normalize(filter);
        int max = limit == null ? 500 : Math.max(1, limit);
        try (ClassSource src = source.strip().endsWith(ClassSource.CLASS)
                ? new ClassSource.Directory(existing(source).getParent(), true) : open(source)) {
            List<String> all = src.classes(prefix);
            List<String> top = all.stream().filter(c -> c.indexOf('$', c.lastIndexOf('/') + 1) < 0).toList();
            StringBuilder sb = new StringBuilder(src.label()).append(": ").append(top.size()).append(" Klasse(n)");
            if (all.size() > top.size()) {
                sb.append(", ").append(all.size() - top.size()).append(" verschachtelte");
            }
            if (!prefix.isEmpty()) {
                sb.append(" mit Präfix ").append(prefix.replace('/', '.'));
            }
            sb.append('\n');
            top.stream().limit(max).forEach(c -> sb.append(c.replace('/', '.')).append('\n'));
            if (top.size() > max) {
                sb.append("… [").append(top.size() - max).append(" weitere – mit 'filter' eingrenzen]");
            }
            return sb.toString().trim();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------ Hilfen

    private List<ClassFinder.Hit> find(List<String> candidates, String simpleName, int limit) {
        try {
            return finder.find(candidates, simpleName, limit);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String finderRoots() {
        return finder.roots().isEmpty() ? "(keine Suchpfade konfiguriert)" : finder.roots().toString();
    }

    /** Ausgabe ab {@code startLine}, höchstens {@link #maxLines} Zeilen, mit Hinweis zum Weiterlesen. */
    private String page(String text, int startLine) {
        String[] lines = text.split("\\R", -1);
        int from = Math.clamp(startLine, 1, Math.max(1, lines.length));
        int to = Math.min(lines.length, from - 1 + maxLines);
        String slice = String.join("\n", List.of(lines).subList(from - 1, to));
        if (from == 1 && to == lines.length) {
            return slice;
        }
        return (from > 1 ? "… [Zeilen " + from + "–" + to + " von " + lines.length + "]\n" : "") + slice
                + (to < lines.length ? "\n… [gekürzt: Zeilen " + from + "–" + to + " von " + lines.length
                + ", weiter mit startLine=" + (to + 1) + "]" : "");
    }

    static ClassSource open(String spec) {
        String s = spec.strip();
        if (s.equalsIgnoreCase("jdk") || s.equalsIgnoreCase("jrt") || s.equalsIgnoreCase("jrt:/")) {
            return new ClassSource.Jdk(null);
        }
        if (s.startsWith("jrt:/")) {
            return new ClassSource.Jdk(s.substring(5).replaceAll("/.*", ""));
        }
        Path p = existing(s);
        if (Files.isDirectory(p)) {
            return new ClassSource.Directory(p, false);
        }
        try {
            return new ClassSource.Archive(p);
        } catch (IOException e) {
            throw new IllegalArgumentException("Kein lesbares JAR/ZIP-Archiv: " + p + " (" + e.getMessage() + ")");
        }
    }

    private static Path existing(String path) {
        Path p;
        try {
            p = Path.of(path.strip()).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("Ungültiger Pfad: " + path);
        }
        if (!Files.exists(p)) {
            throw new IllegalArgumentException("Nicht gefunden: " + p);
        }
        return p;
    }

    private static byte[] readAll(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Liegt die .class-Datei in ihrer Paketstruktur, wird deren Wurzel die Quelle, sonst nur ihr Ordner. */
    private static ClassSource classFileSource(Path file, String internalName) {
        Path root = file;
        for (int i = internalName.split("/").length; i > 0 && root != null; i--) {
            root = root.getParent();
        }
        if (root != null && root.resolve(internalName + ClassSource.CLASS).normalize().equals(file)) {
            return new ClassSource.Directory(root, false);
        }
        return new ClassSource.Directory(file.getParent(), true);
    }

    private static String locate(ClassSource src, List<String> candidates) {
        try {
            for (String c : candidates) {
                if (src.read(c) != null) {
                    return c;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        throw new IllegalArgumentException("Klasse " + candidates.getFirst().replace('/', '.') + " nicht in " + src.label()
                + " gefunden. Vorhandene Klassen liefert decompile_list.");
    }

    /** Äußerste Klasse, die in der Quelle existiert ({@code a/B$C$1} → {@code a/B}). */
    private static String topLevel(ClassSource src, String name) throws IOException {
        int slash = name.lastIndexOf('/');
        int dollar = name.indexOf('$', slash + 1);
        while (dollar > slash + 1) {
            String outer = name.substring(0, dollar);
            if (src.read(outer) != null) {
                return outer;
            }
            dollar = name.indexOf('$', dollar + 1);
        }
        return name;
    }

    /** {@code org.foo.Bar}, {@code org/foo/Bar.class} → {@code org/foo/Bar}. */
    static String normalize(String className) {
        if (className == null || className.isBlank()) {
            throw new IllegalArgumentException("Klassenname fehlt.");
        }
        String n = className.strip().replace('\\', '/');
        if (n.endsWith(ClassSource.CLASS)) {
            n = n.substring(0, n.length() - ClassSource.CLASS.length());
        } else if (n.endsWith(".java")) {
            n = n.substring(0, n.length() - 5);
        }
        return n.contains("/") ? n : n.replace('.', '/');
    }

    /**
     * Interne Namen, die gemeint sein können: {@code java.util.Map.Entry} ist {@code java/util/Map$Entry}, wenn es
     * kein Paket {@code java.util.Map} gibt – geprüft werden die letzten zwei Punkte auch als Verschachtelung.
     */
    static List<String> candidates(String className) {
        String n = normalize(className);
        List<String> out = new ArrayList<>(List.of(n));
        String c = n;
        for (int i = 0; i < 2; i++) {
            int slash = c.lastIndexOf('/');
            if (slash <= 0) {
                break;
            }
            c = c.substring(0, slash) + "$" + c.substring(slash + 1);
            out.add(c);
        }
        return out;
    }
}
