package systems.grebe.devtools.mcp.modules.decompile;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.eclipse.aether.util.version.GenericVersionScheme;
import org.eclipse.aether.version.InvalidVersionSpecificationException;
import org.eclipse.aether.version.Version;

/**
 * Sucht Klassen im JDK und in den JARs der Suchpfade (lokales Maven-Repository, Gradle-Cache …). Die JAR-Liste wird
 * kurz zwischengespeichert, damit aufeinanderfolgende Aufrufe nicht jedes Mal den Cache-Baum durchlaufen.
 */
final class ClassFinder {

    /** Fundstelle: {@code source} ist ein Pfad oder {@code jrt:/<modul>} und kann direkt als Quelle übergeben werden. */
    record Hit(String source, String className, String version) {
    }

    private static final long CACHE_MILLIS = 60_000;
    private static final Pattern HASH_DIR = Pattern.compile("[0-9a-f]{30,}");
    private static final List<String> ROOTS = List.of("", "BOOT-INF/classes/", "WEB-INF/classes/");
    private static final Map<List<Path>, Listing> JARS = new ConcurrentHashMap<>();
    private static final GenericVersionScheme VERSIONS = new GenericVersionScheme();

    private record Listing(long at, List<Path> jars) {
    }

    private final List<Path> roots;

    ClassFinder(List<Path> roots) {
        this.roots = roots;
    }

    List<Path> roots() {
        return roots;
    }

    List<Path> jars() {
        Listing cached = JARS.get(roots);
        if (cached != null && System.currentTimeMillis() - cached.at() < CACHE_MILLIS) {
            return cached.jars();
        }
        List<Path> jars = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root, 12, FileVisitOption.FOLLOW_LINKS)) {
                files.filter(ClassFinder::isArchive).forEach(jars::add);
            } catch (IOException | RuntimeException ignored) {
                // nicht lesbare Teilbäume überspringen
            }
        }
        JARS.put(roots, new Listing(System.currentTimeMillis(), jars));
        return jars;
    }

    private static boolean isArchive(Path p) {
        String n = p.getFileName().toString();
        return (n.endsWith(".jar") || n.endsWith(".war")) && !n.endsWith("-sources.jar") && !n.endsWith("-javadoc.jar")
                && Files.isRegularFile(p);
    }

    /**
     * @param candidates interne Namen (mit Paket), die gesucht werden – mehrere, wenn offen ist, ob ein Punkt Paket
     *                   oder verschachtelte Klasse trennt
     * @param simpleName stattdessen: einfacher Klassenname ohne Paket (durchsucht alle Einträge, langsamer)
     */
    List<Hit> find(List<String> candidates, String simpleName, int limit) throws IOException {
        List<Hit> hits = new ArrayList<>();
        ClassSource.Jdk jdk = new ClassSource.Jdk(null);
        String jdkVersion = "JDK " + Runtime.version().feature();
        if (simpleName == null) {
            for (String c : candidates) {
                String mod = jdk.moduleOf(c);
                if (mod != null) {
                    hits.add(new Hit("jrt:/" + mod, c, jdkVersion));
                }
            }
        } else {
            findInJdk(simpleName, jdkVersion, hits);
        }
        jars().parallelStream()
                .flatMap(jar -> scan(jar, candidates, simpleName).stream())
                .sorted(Comparator.comparing(Hit::className).thenComparing(Hit::source))
                .forEachOrdered(hits::add);
        return hits.size() > limit ? hits.subList(0, limit) : hits;
    }

    private static void findInJdk(String simpleName, String version, List<Hit> hits) throws IOException {
        Path modules = ClassSource.Jdk.jrt().getPath("/modules");
        try (Stream<Path> mods = Files.list(modules)) {
            for (Path mod : mods.sorted().toList()) {
                try (Stream<Path> files = Files.walk(mod)) {
                    files.filter(p -> p.getFileName() != null && p.getFileName().toString().equals(simpleName + ClassSource.CLASS))
                            .forEach(p -> {
                                String rel = mod.relativize(p).toString();
                                hits.add(new Hit("jrt:/" + mod.getFileName(), rel.substring(0, rel.length() - 6), version));
                            });
                }
            }
        }
    }

    private static List<Hit> scan(Path jar, List<String> candidates, String simpleName) {
        List<Hit> hits = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            if (simpleName == null) {
                for (String c : candidates) {
                    for (String root : ROOTS) {
                        if (zip.getEntry(root + c + ClassSource.CLASS) != null) {
                            hits.add(new Hit(jar.toString(), c, version(jar)));
                            break;
                        }
                    }
                }
            } else {
                String suffix = "/" + simpleName + ClassSource.CLASS;
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    if (name.endsWith(suffix) || name.equals(simpleName + ClassSource.CLASS)) {
                        for (String root : ROOTS) {
                            if (!root.isEmpty() && name.startsWith(root)) {
                                name = name.substring(root.length());
                            }
                        }
                        hits.add(new Hit(jar.toString(), name.substring(0, name.length() - 6), version(jar)));
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // kein gültiges Archiv
        }
        return hits;
    }

    /** Version aus dem Pfad: Maven {@code …/<artefakt>/<version>/x.jar}, Gradle {@code …/<version>/<hash>/x.jar}. */
    static String version(Path jar) {
        Path dir = jar.getParent();
        if (dir != null && dir.getFileName() != null && HASH_DIR.matcher(dir.getFileName().toString()).matches()) {
            dir = dir.getParent();
        }
        return dir == null || dir.getFileName() == null ? "" : dir.getFileName().toString();
    }

    /** Beste Fundstelle zum Dekompilieren: das JDK, sonst die höchste Version. */
    static Hit best(List<Hit> hits) {
        return hits.stream().filter(h -> h.source().startsWith("jrt:")).findFirst()
                .orElseGet(() -> hits.stream().max(Comparator.comparing(h -> parse(h.version()))).orElseThrow());
    }

    private static Version parse(String v) {
        try {
            return VERSIONS.parseVersion(Objects.requireNonNullElse(v, ""));
        } catch (InvalidVersionSpecificationException e) {
            try {
                return VERSIONS.parseVersion("0");
            } catch (InvalidVersionSpecificationException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
    }
}
