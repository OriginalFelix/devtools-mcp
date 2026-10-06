package systems.grebe.devtools.mcp.modules.scripts.assist;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import systems.grebe.devtools.mcp.modules.scripts.JavaClasspath;

/**
 * Alle Klassennamen des JDK ({@code java.*}/{@code javax.*}) und des Klassenpfads der App – für die Vervollständigung
 * von Klassennamen mit automatischem Import. Gelesen werden nur die Verzeichnisse der Jars und des jrt-Dateisystems,
 * keine Klassen. Der Index entsteht einmal im Hintergrund; bis dahin gibt es keine Klassenvorschläge.
 */
final class ClassIndex {

    private static final Logger LOG = LoggerFactory.getLogger(ClassIndex.class);
    private static final ClassIndex SHARED = new ClassIndex();

    /** Eine Klasse (nur oberste Ebene, keine inneren Klassen). */
    record Entry(String simpleName, String packageName) {

        String qualifiedName() {
            return packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
        }
    }

    private final AtomicBoolean started = new AtomicBoolean();
    private final CountDownLatch ready = new CountDownLatch(1);
    private volatile List<Entry> entries = List.of();
    private volatile Set<String> packages = Set.of();

    static ClassIndex shared() {
        return SHARED;
    }

    /** Baut den Index im Hintergrund auf (nur beim ersten Aufruf). */
    void startLoading() {
        if (started.compareAndSet(false, true)) {
            Thread.ofVirtual().name("script-class-index").start(this::build);
        }
    }

    boolean ready() {
        return ready.getCount() == 0;
    }

    /** Wartet höchstens so lange auf den Index (für Tests). */
    boolean await(long millis) {
        startLoading();
        try {
            return ready.await(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Alle Klassen, nach einfachem Namen sortiert; leer, solange der Index lädt. */
    List<Entry> entries() {
        return entries;
    }

    /** Alle Pakete samt übergeordneten ({@code java}, {@code java.util} …). */
    Set<String> packages() {
        return packages;
    }

    /** Ergebnis der Klassensuche; {@code truncated}, wenn mehr als das Limit passten. */
    record Hits(List<Entry> entries, boolean truncated) {
    }

    /** Die am besten zur Eingabe passenden Klassen (CamelHumps wie in der Lookup-Liste), höchstens {@code limit}. */
    Hits search(String typed, int limit) {
        record Scored(Entry entry, CamelMatcher.Match match) {
        }
        List<Scored> hits = new ArrayList<>();
        for (Entry e : entries) {
            CamelMatcher.Match m = CamelMatcher.match(typed, e.simpleName());
            if (m != null) {
                hits.add(new Scored(e, m));
            }
        }
        hits.sort(Comparator.comparingInt((Scored s) -> s.match().quality())
                .thenComparing(Comparator.comparingInt((Scored s) -> s.match().score()).reversed())
                .thenComparingInt(s -> s.entry().simpleName().length()));
        return new Hits(hits.stream().limit(limit).map(Scored::entry).toList(), hits.size() > limit);
    }

    /** Klassen mit genau diesem einfachen Namen. */
    List<Entry> bySimpleName(String simpleName) {
        List<Entry> all = entries;
        int lo = Collections.binarySearch(all, new Entry(simpleName, ""), Comparator.comparing(Entry::simpleName));
        if (lo < 0) {
            return List.of();
        }
        int start = lo;
        while (start > 0 && all.get(start - 1).simpleName().equals(simpleName)) {
            start--;
        }
        int end = lo + 1;
        while (end < all.size() && all.get(end).simpleName().equals(simpleName)) {
            end++;
        }
        return all.subList(start, end);
    }

    private void build() {
        long t0 = System.nanoTime();
        Set<String> seen = new HashSet<>();
        List<Entry> out = new ArrayList<>();
        Set<String> pkgs = new HashSet<>();
        try {
            jdk(seen, out, pkgs);
            for (String entry : JavaClasspath.get().split(File.pathSeparator)) {
                if (!entry.isBlank()) {
                    classpathEntry(Path.of(entry), seen, out, pkgs);
                }
            }
        } catch (RuntimeException e) {
            LOG.warn("Klassenindex für die Skript-Vervollständigung unvollständig: {}", e.getMessage());
        }
        out.sort(Comparator.comparing(Entry::simpleName));
        entries = List.copyOf(out);
        packages = Set.copyOf(pkgs);
        ready.countDown();
        LOG.debug("Klassenindex für Skripte: {} Klassen in {} ms", out.size(), (System.nanoTime() - t0) / 1_000_000);
    }

    private static void jdk(Set<String> seen, List<Entry> out, Set<String> pkgs) {
        try {
            FileSystem jrt = FileSystems.getFileSystem(URI.create("jrt:/"));
            try (Stream<Path> modules = Files.list(jrt.getPath("/modules"))) {
                for (Path module : modules.filter(m -> m.getFileName().toString().startsWith("java.")).toList()) {
                    try (Stream<Path> files = Files.walk(module)) {
                        files.forEach(f -> {
                            String rel = module.relativize(f).toString();
                            if (rel.startsWith("java/") || rel.startsWith("javax/")) {
                                add(rel, seen, out, pkgs);
                            }
                        });
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            LOG.debug("JDK-Klassen nicht lesbar: {}", e.toString());
        }
    }

    private static void classpathEntry(Path path, Set<String> seen, List<Entry> out, Set<String> pkgs) {
        try {
            if (Files.isDirectory(path)) {
                try (Stream<Path> files = Files.walk(path)) {
                    files.filter(Files::isRegularFile)
                            .forEach(f -> add(path.relativize(f).toString().replace('\\', '/'), seen, out, pkgs));
                }
            } else if (path.toString().endsWith(".jar") && Files.isRegularFile(path)) {
                try (JarFile jar = new JarFile(path.toFile())) {
                    jar.stream().forEach(e -> add(e.getName(), seen, out, pkgs));
                }
            }
        } catch (IOException | RuntimeException e) {
            LOG.debug("Klassenpfad-Eintrag {} nicht lesbar: {}", path, e.toString());
        }
    }

    /** {@code a/b/C.class} → Eintrag; innere, anonyme und interne Klassen bleiben draußen. */
    private static void add(String path, Set<String> seen, List<Entry> out, Set<String> pkgs) {
        if (!path.endsWith(".class") || path.indexOf('$') >= 0 || path.startsWith("META-INF")
                || path.endsWith("module-info.class") || path.endsWith("package-info.class")) {
            return;
        }
        String name = path.substring(0, path.length() - ".class".length()).replace('/', '.');
        int dot = name.lastIndexOf('.');
        if (dot <= 0) {
            return;
        }
        String pkg = name.substring(0, dot);
        if (pkg.startsWith("sun.") || pkg.startsWith("com.sun.") || pkg.startsWith("jdk.")
                || pkg.contains(".internal") || pkg.contains(".shaded.") || pkg.contains("-")
                || !seen.add(name)) {
            return;
        }
        String simple = name.substring(dot + 1);
        if (simple.isEmpty() || !Character.isJavaIdentifierStart(simple.charAt(0))) {
            return;
        }
        out.add(new Entry(simple, pkg));
        for (String p = pkg; !pkgs.contains(p); ) {
            pkgs.add(p);
            int d = p.lastIndexOf('.');
            if (d < 0) {
                break;
            }
            p = p.substring(0, d);
        }
    }
}
