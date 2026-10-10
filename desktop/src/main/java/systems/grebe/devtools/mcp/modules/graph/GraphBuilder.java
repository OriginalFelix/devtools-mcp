package systems.grebe.devtools.mcp.modules.graph;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import systems.grebe.devtools.mcp.core.ModuleAction;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Community;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Confidence;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Edge;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.FileEntry;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.GraphFile;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Kind;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Node;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Relation;
import systems.grebe.devtools.mcp.modules.graph.JavaExtractor.FileDecl;
import systems.grebe.devtools.mcp.modules.graph.JavaExtractor.MemberDecl;
import systems.grebe.devtools.mcp.modules.graph.JavaExtractor.RawEdge;
import systems.grebe.devtools.mcp.modules.graph.JavaExtractor.TypeDecl;

/**
 * Baut aus allen Dateien eines Projekts den {@link CodeGraph}: {@code .java}-Dateien liest tree-sitter (Typen, Member,
 * Aufrufe …), alle übrigen – Build-Skripte, Konfiguration, Ressourcen, Doku, auch Binärdateien – werden als
 * Dateiknoten mit Länge und Prüfsumme aufgenommen, damit {@code graph_files}/{@code graph_read} sie finden.
 */
final class GraphBuilder {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(GraphBuilder.class);

    static final String GENERATOR = "devtools-mcp graph (tree-sitter-java, alle Dateien) 4";

    /** So viele Bytes am Anfang einer Datei entscheiden, ob sie binär ist (enthält ein NUL-Byte). */
    static final int BINARY_PROBE = 8192;

    /** Relationen, die für die Community-Erkennung zählen – mit Gewicht. */
    private static final Map<Relation, Double> COMMUNITY_WEIGHT = Map.of(
            Relation.CALLS, 1.0, Relation.INSTANTIATES, 1.0, Relation.EXTENDS, 2.0, Relation.IMPLEMENTS, 2.0,
            Relation.OVERRIDES, 1.0, Relation.HAS_TYPE, 1.0, Relation.IMPORTS, 0.5, Relation.ANNOTATED_WITH, 0.25);

    private final Path root;
    private final List<String> excludes;
    private final boolean includeTests;
    private final int maxFiles;

    /** Nur für Tests: feste Zahl Worker-Threads (0 = je nach CPU, höchstens 8). */
    private int threads;

    GraphBuilder threads(int n) {
        this.threads = n;
        return this;
    }

    GraphBuilder(Path root, List<String> excludes, boolean includeTests, int maxFiles) {
        this.root = root;
        this.excludes = excludes.stream().map(GraphBuilder::normalizeExclude).filter(s -> !s.isEmpty()).toList();
        this.includeTests = includeTests;
        this.maxFiles = maxFiles;
    }

    static String fileId(String path) {
        return "file:" + path;
    }

    static String packageId(String pkg) {
        return "pkg:" + (pkg.isEmpty() ? "(default)" : pkg);
    }

    private static String normalizeExclude(String e) {
        String s = e.trim().replace('\\', '/');
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    // ------------------------------------------------------------------ Dateien

    /** Eingelesene Datei: nur Pfad und Prüfsumme – den Text liest jeder Durchlauf neu, damit er nicht im Heap bleibt. */
    record Source(String path, String sha256) {
    }

    /** Von tree-sitter gelesen? {@code module-info.java}/{@code package-info.java} bleiben reine Dateiknoten. */
    static boolean isJava(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        return name.endsWith(".java") && !name.equals("module-info.java") && !name.equals("package-info.java");
    }

    /** Relative Pfade aller einzulesenden Dateien, sortiert. */
    List<Path> scan() {
        List<Path> out = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(root) && excluded(root.relativize(dir), true)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString();
                    if (attrs.isRegularFile() && !name.startsWith(GraphStore.FILE_PREFIX)
                            && !excluded(root.relativize(file), false)) {
                        out.add(root.relativize(file));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Projekt nicht lesbar: " + root, e);
        }
        out.sort(Comparator.comparing(p -> p.toString().replace('\\', '/')));
        if (out.size() > maxFiles) {
            throw new IllegalStateException(out.size() + " Dateien gefunden, erlaubt sind " + maxFiles
                    + " (Einstellung 'Max. Dateien' im Graph-Modul). Ausschlüsse ergänzen oder Grenze erhöhen.");
        }
        return out;
    }

    private boolean excluded(Path rel, boolean dir) {
        String p = rel.toString().replace('\\', '/');
        String name = rel.getFileName() == null ? p : rel.getFileName().toString();
        if (dir && name.startsWith(".") && !name.equals(".")) {
            return true; // .git, .gradle, .idea …
        }
        if (name.equals(".git")) {
            return true; // in Worktrees und Submodulen eine Datei mit dem Pfad des Repositories
        }
        if (!includeTests && dir && name.equals("test") && p.contains("src/")) {
            return true;
        }
        for (String e : excludes) {
            if (e.contains("/")) {
                if (p.equals(e) || p.startsWith(e + "/")) {
                    return true;
                }
            } else if (e.startsWith("*.")) {
                if (!dir && name.endsWith(e.substring(1))) {
                    return true;
                }
            } else if (name.equals(e) && !insideSources(p)) {
                return true; // Build-Ausgaben liegen neben src/, nicht darin – Pakete wie '…/build' bleiben erhalten
            }
        }
        return false;
    }

    /** Liegt der Pfad unterhalb eines {@code src}-Ordners (dann ist ein Ordnername ein Paketname)? */
    private static boolean insideSources(String relPath) {
        return relPath.startsWith("src/") || relPath.contains("/src/");
    }

    /** Prüfsumme gestreamt – auch große Binärdateien landen nicht ganz im Heap. */
    Source read(Path rel) {
        try (InputStream in = Files.newInputStream(root.resolve(rel))) {
            MessageDigest md = sha256();
            byte[] buf = new byte[64 * 1024];
            for (int r; (r = in.read(buf)) > 0; ) {
                md.update(buf, 0, r);
            }
            return new Source(rel.toString().replace('\\', '/'), HexFormat.of().formatHex(md.digest()));
        } catch (IOException e) {
            throw new UncheckedIOException("Datei nicht lesbar: " + rel, e);
        }
    }

    private String text(Source s) {
        try {
            return decode(Files.readAllBytes(root.resolve(s.path())));
        } catch (IOException e) {
            throw new UncheckedIOException("Datei nicht lesbar: " + s.path(), e);
        }
    }

    /** UTF-8, bei ungültigen Bytes ISO-8859-1 (ältere Quelltexte mit Umlauten). */
    static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, Charset.forName("ISO-8859-1"));
        }
    }

    static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(sha256().digest(bytes));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Zeilen einer Nicht-Java-Datei, gezählt wie bei {@code graph_read} (Zeilenumbrüche + 1); 0 = Binärdatei
     * (NUL-Byte in den ersten {@value #BINARY_PROBE} Bytes).
     */
    int lines(Source s) {
        try (InputStream in = Files.newInputStream(root.resolve(s.path()))) {
            byte[] buf = new byte[64 * 1024];
            long read = 0;
            int lines = 1;
            for (int r; (r = in.read(buf)) > 0; read += r) {
                for (int i = 0; i < r; i++) {
                    if (buf[i] == '\n') {
                        lines++;
                    } else if (buf[i] == 0 && read + i < BINARY_PROBE) {
                        return 0;
                    }
                }
            }
            return lines;
        } catch (IOException e) {
            throw new UncheckedIOException("Datei nicht lesbar: " + s.path(), e);
        }
    }

    // ------------------------------------------------------------------ Bauen

    GraphFile build(List<Source> sources, String projectName) {
        return build(sources, projectName, ModuleAction.Progress.NONE);
    }

    GraphFile build(List<Source> sources, String projectName, ModuleAction.Progress progress) {
        return build(sources, projectName, progress, null);
    }

    /**
     * Zwischenstand je Projekt für den inkrementellen Aufbau: Deklarationen und Kanten je Datei (nach SHA-256) und der
     * Fingerabdruck aller Deklarationen, gegen den die Kanten aufgelöst wurden. Nicht threadsicher – je Projekt baut
     * immer nur einer ({@code GraphService} sperrt).
     */
    static final class ParseCache {

        /** Datei mit Prüfsumme, Deklarationen, ihrem Fingerabdruck (ohne Zeilen und Javadoc) und den Kanten. */
        record CachedFile(String sha256, FileDecl decl, String api, List<RawEdge> refs) {
        }

        private Map<String, CachedFile> files = Map.of();
        private String fingerprint;
        /** Beim letzten Aufbau neu gelesene Dateien: Deklarationen bzw. Kanten. */
        int parsedDeclarations;
        int parsedReferences;

        /** Vergessen – der nächste Aufbau liest alles (und füllt den Cache neu). */
        void clear() {
            files = Map.of();
            fingerprint = null;
        }
    }

    /**
     * Baut den Graphen; meldet den Fortschritt je Phase (Anteil 0,05–0,95). Ein Interrupt des aufrufenden Threads
     * bricht ab ({@link IllegalStateException} mit {@link InterruptedException} als Ursache).
     *
     * <p>Mit {@code cache} inkrementell: Dateien mit unveränderter Prüfsumme werden nicht neu geparst. Sind auch die
     * Deklarationen aller Dateien unverändert (nur Rümpfe, Javadoc oder Zeilen geändert – der häufige Fall bei einem
     * Commit), gelten ihre Kanten weiter; sonst werden die Kanten aller Dateien neu aufgelöst. Das Ergebnis ist in
     * beiden Fällen dasselbe wie ohne Cache.
     */
    GraphFile build(List<Source> sources, String projectName, ModuleAction.Progress progress, ParseCache cache) {
        int threads = this.threads > 0 ? this.threads
                : Math.max(1, Math.min(Runtime.getRuntime().availableProcessors(), 8));
        int n = sources.size();
        Map<String, ParseCache.CachedFile> previous = cache == null ? Map.of() : cache.files;
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<FileDecl>> declFutures = new ArrayList<>(n);
            int parsedDecls = 0;
            for (Source s : sources) {
                ParseCache.CachedFile c = previous.get(s.path());
                if (c != null && c.sha256().equals(s.sha256())) {
                    declFutures.add(CompletableFuture.completedFuture(c.decl()));
                } else if (!isJava(s.path())) {
                    declFutures.add(pool.submit(() -> plainFile(s)));
                    parsedDecls++;
                } else {
                    declFutures.add(pool.submit(() -> declarationsOrEmpty(s)));
                    parsedDecls++;
                }
            }
            List<FileDecl> decls = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                decls.add(get(pool, declFutures.get(i)));
                report(progress, "Deklarationen", i + 1, n, 0.05, 0.45);
            }
            List<String> apis = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                ParseCache.CachedFile c = previous.get(sources.get(i).path());
                apis.add(c != null && c.decl() == decls.get(i) ? c.api() : api(decls.get(i)));
            }
            String fingerprint = fingerprint(sources, apis);
            boolean sameDeclarations = cache != null && fingerprint.equals(cache.fingerprint);

            Map<String, Node> nodes = new LinkedHashMap<>();
            Map<String, EdgeAcc> edges = new LinkedHashMap<>();
            List<FileEntry> files = declarationNodes(sources, decls, nodes, edges);

            JavaResolver resolver = new JavaResolver(decls);
            List<Future<List<RawEdge>>> futures = new ArrayList<>();
            int parsedRefs = 0;
            for (int i = 0; i < sources.size(); i++) {
                FileDecl d = decls.get(i);
                Source s = sources.get(i);
                ParseCache.CachedFile c = previous.get(s.path());
                if (!isJava(s.path())) {
                    futures.add(CompletableFuture.completedFuture(List.of())); // kein Quelltext mit Referenzen
                } else if (sameDeclarations && c != null && c.decl() == d && c.refs() != null) {
                    futures.add(CompletableFuture.completedFuture(c.refs()));
                } else {
                    futures.add(pool.submit(() -> referencesOrEmpty(d, s, resolver)));
                    parsedRefs++;
                }
            }
            Map<String, ParseCache.CachedFile> next = new HashMap<>();
            for (int i = 0; i < futures.size(); i++) {
                List<RawEdge> refs = get(pool, futures.get(i));
                referenceEdges(refs, nodes, edges);
                futures.set(i, null); // Ergebnis freigeben
                if (cache != null) {
                    Source s = sources.get(i);
                    next.put(s.path(), new ParseCache.CachedFile(s.sha256(), decls.get(i), apis.get(i), refs));
                }
                report(progress, "Aufrufe und Referenzen", i + 1, n, 0.45, 0.85);
            }
            if (cache != null) {
                cache.files = next;
                cache.fingerprint = fingerprint;
                cache.parsedDeclarations = parsedDecls;
                cache.parsedReferences = parsedRefs;
            }
            dispatchEdges(edges);
            progress.update("Communities …", 0.9);
            return finish(files, nodes, edges, projectName);
        }
    }

    /**
     * Fingerabdruck der Deklarationen einer Datei, wie sie die Auflösung der Kanten sieht: ohne Zeilen und Javadoc
     * (die Zeilen eigener Deklarationen gehen nur in die Kanten dieser Datei ein, und die wird bei Änderung neu gelesen).
     */
    static String api(FileDecl d) {
        StringBuilder sb = new StringBuilder(d.pkg()).append('\n');
        for (JavaExtractor.Import i : d.imports()) {
            sb.append("i ").append(i.name()).append(i.isStatic() ? " s" : "").append(i.wildcard() ? " *" : "")
                    .append('\n');
        }
        for (TypeDecl t : d.types()) {
            sb.append("t ").append(t.fqn()).append('|').append(t.simpleName()).append('|').append(t.kind()).append('|')
                    .append(t.outer()).append('|').append(t.modifiers()).append('|').append(t.annotations()).append('|')
                    .append(t.typeParams()).append('|').append(t.superclass()).append('|').append(t.interfaces())
                    .append('\n');
            for (MemberDecl m : t.members()) {
                sb.append("m ").append(m.id()).append('|').append(m.kind()).append('|').append(m.paramTypes())
                        .append('|').append(m.varargs()).append('|').append(m.type()).append('|').append(m.modifiers())
                        .append('|').append(m.annotations()).append('|').append(m.typeParams()).append('\n');
            }
        }
        return sb.toString();
    }

    /** Fingerabdruck aller Deklarationen des Projekts (Pfad + {@link #api} je Datei). */
    private static String fingerprint(List<Source> sources, List<String> apis) {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        for (int i = 0; i < sources.size(); i++) {
            md.update(sources.get(i).path().getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(apis.get(i).getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    /** Nicht-Java-Datei: nur Pfad und Länge, keine Deklarationen; Binärdateien mit 0 Zeilen. */
    private FileDecl plainFile(Source s) {
        try {
            return new FileDecl(s.path(), "", List.of(), List.of(), lines(s), false);
        } catch (UncheckedIOException e) {
            LOG.warn("Graph: {} nicht lesbar – Datei ohne Länge übernommen", s.path(), e);
            return new FileDecl(s.path(), "", List.of(), List.of(), 0, true);
        }
    }

    /**
     * Ein Fehler in einer einzelnen Datei (Randfall im Extractor) darf nicht den ganzen Aufbau abbrechen: die Datei
     * wird ohne Inhalt übernommen, als fehlerhaft markiert und im Bericht genannt. Abbruch (Interrupt) geht durch.
     */
    private FileDecl declarationsOrEmpty(Source s) {
        try {
            return JavaExtractor.declarations(s.path(), text(s));
        } catch (RuntimeException e) {
            if (Thread.currentThread().isInterrupted()) {
                throw e;
            }
            LOG.warn("Graph: Deklarationen aus {} nicht lesbar – Datei übersprungen", s.path(), e);
            return new FileDecl(s.path(), "", List.of(), List.of(), 0, true);
        }
    }

    private List<RawEdge> referencesOrEmpty(FileDecl d, Source s, JavaResolver resolver) {
        try {
            return JavaExtractor.references(d, text(s), resolver);
        } catch (RuntimeException e) {
            if (Thread.currentThread().isInterrupted()) {
                throw e;
            }
            LOG.warn("Graph: Referenzen aus {} nicht lesbar – Kanten der Datei fehlen", s.path(), e);
            return List.of();
        }
    }

    /** Meldet höchstens ~100 Schritte je Phase, damit die UI nicht mit Aktualisierungen geflutet wird. */
    private static void report(ModuleAction.Progress p, String phase, int done, int total, double from, double to) {
        if (done == total || done % Math.max(1, total / 100) == 0) {
            p.update(phase + " " + done + "/" + total, from + (to - from) * done / Math.max(1, total));
        }
    }

    private static <T> T get(ExecutorService pool, Future<T> f) {
        try {
            return f.get();
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Graph-Aufbau abgebrochen", e);
        } catch (ExecutionException e) {
            pool.shutdownNow();
            Throwable c = e.getCause();
            throw c instanceof RuntimeException re ? re : new IllegalStateException(c);
        }
    }

    private static List<FileEntry> declarationNodes(List<Source> sources, List<FileDecl> decls, Map<String, Node> nodes,
                                                    Map<String, EdgeAcc> edges) {
        List<FileEntry> files = new ArrayList<>();

        for (int i = 0; i < decls.size(); i++) {
            FileDecl d = decls.get(i);
            Source s = sources.get(i);
            files.add(new FileEntry(d.path(), s.sha256(), d.lines(), d.errors() ? Boolean.TRUE : null));
            String fId = fileId(d.path());
            if (!isJava(d.path())) {
                // ohne Paket: Ressourcen, Build-Skripte, Doku … – über Pfad und Dateinamen auffindbar
                nodes.put(fId, new Node(fId, Kind.FILE, d.path(), d.path(), null, d.lines() > 0 ? d.lines() : null,
                        null, null, null, null));
                continue;
            }
            String pkgId = packageId(d.pkg());
            nodes.putIfAbsent(pkgId, new Node(pkgId, Kind.PACKAGE, d.pkg().isEmpty() ? "(default)" : d.pkg(),
                    null, null, null, null, null, null, null));
            nodes.put(fId, new Node(fId, Kind.FILE, d.path(), d.path(), null, d.lines(), null, null, null, null));
            edge(edges, pkgId, fId, Relation.CONTAINS, Confidence.EXTRACTED, 1, null);
            for (TypeDecl t : d.types()) {
                if (nodes.containsKey(t.fqn())) {
                    continue; // doppelt deklariert
                }
                nodes.put(t.fqn(), new Node(t.fqn(), t.kind(), t.simpleName(), d.path(), t.line(), t.endLine(),
                        t.modifiers(), null, t.doc(), null));
                edge(edges, t.outer() == null ? fId : t.outer(), t.fqn(), Relation.CONTAINS, Confidence.EXTRACTED, 1, null);
                for (MemberDecl m : t.members()) {
                    if (nodes.containsKey(m.id())) {
                        continue; // Record-Accessor explizit überschrieben o.Ä.
                    }
                    nodes.put(m.id(), new Node(m.id(), m.kind(), m.name(), d.path(), m.line(), m.endLine(),
                            m.modifiers(), m.signature(), m.doc(), null));
                    edge(edges, t.fqn(), m.id(), Relation.CONTAINS, Confidence.EXTRACTED, 1, null);
                }
            }
        }
        return files;
    }

    private static void referenceEdges(List<RawEdge> list, Map<String, Node> nodes, Map<String, EdgeAcc> edges) {
        for (RawEdge e : list) {
            if (e.from().equals(e.to())) {
                continue;
            }
            if (!nodes.containsKey(e.from())) {
                // Member einer doppelt deklarierten Klasse (gleicher FQN in zwei Modulen): nur die erste Deklaration
                // ist Knoten, Kanten ihrer übersprungenen Member hätten keinen Ursprung
                continue;
            }
            if (!nodes.containsKey(e.to())) {
                nodes.put(e.to(), new Node(e.to(), Kind.EXTERNAL, e.to().substring(e.to().lastIndexOf('.') + 1),
                        null, null, null, null, null, null, null));
            }
            edge(edges, e.from(), e.to(), e.rel(), e.conf(), e.score(), e.line());
        }
    }

    /** Höchstens so viele Implementierungen je aufgerufener Methode bekommen abgeleitete Aufrufkanten. */
    static final int MAX_DISPATCH = 12;
    /** Score der abgeleiteten Aufrufe einer Implementierung über Interface/Oberklasse. */
    static final double DISPATCH_SCORE = 0.7;

    /**
     * Aufrufe über Interfaces und Oberklassen: Ein Aufruf einer Methode, die überschrieben wird, bekommt je
     * überschreibender Methode (auch über mehrere Stufen) eine abgeleitete Kante {@code calls} (INFERRED, Score
     * {@value #DISPATCH_SCORE}, Anzahl wie beim Aufruf). So führen Aufrufketten, {@code graph_path} und „wer ruft das
     * auf“ bis in die Implementierungen. Bei mehr als {@value #MAX_DISPATCH} Implementierungen (z.B. {@code run()})
     * unterbleibt das – die Kanten wären Rauschen. Vorhandene Kanten bleiben unverändert.
     */
    static void dispatchEdges(Map<String, EdgeAcc> edges) {
        Map<String, List<String>> overriders = new HashMap<>();
        for (EdgeAcc e : edges.values()) {
            if (e.rel == Relation.OVERRIDES) {
                overriders.computeIfAbsent(e.to, k -> new ArrayList<>()).add(e.from);
            }
        }
        if (overriders.isEmpty()) {
            return;
        }
        Map<String, List<String>> implementations = new HashMap<>();
        List<EdgeAcc> calls = edges.values().stream()
                .filter(e -> e.rel == Relation.CALLS && overriders.containsKey(e.to)).toList();
        for (EdgeAcc call : calls) {
            List<String> impls = implementations.computeIfAbsent(call.to, m -> transitiveOverriders(m, overriders));
            if (impls.size() > MAX_DISPATCH) {
                continue;
            }
            double score = Math.min(DISPATCH_SCORE, call.conf == Confidence.EXTRACTED ? 1.0 : call.score);
            for (String impl : impls) {
                String key = edgeKey(call.from, impl, Relation.CALLS);
                if (impl.equals(call.from) || edges.containsKey(key)) {
                    continue; // super-Aufruf der Implementierung selbst bzw. schon direkt verbunden
                }
                EdgeAcc acc = new EdgeAcc(call.from, impl, Relation.CALLS, Confidence.INFERRED, score, call.line);
                acc.count = call.count;
                edges.put(key, acc);
            }
        }
    }

    /** Alle Methoden, die {@code method} direkt oder über Zwischenstufen überschreiben, sortiert. */
    private static List<String> transitiveOverriders(String method, Map<String, List<String>> overriders) {
        Set<String> seen = new TreeSet<>();
        Deque<String> todo = new ArrayDeque<>(overriders.getOrDefault(method, List.of()));
        while (!todo.isEmpty() && seen.size() <= MAX_DISPATCH) {
            String m = todo.pop();
            if (seen.add(m)) {
                todo.addAll(overriders.getOrDefault(m, List.of()));
            }
        }
        return new ArrayList<>(seen);
    }

    private GraphFile finish(List<FileEntry> files, Map<String, Node> nodes, Map<String, EdgeAcc> edges, String project) {
        List<Edge> edgeList = new ArrayList<>(edges.size());
        edges.values().forEach(a -> edgeList.add(a.toEdge()));
        edges.clear();
        List<Node> nodeList = new ArrayList<>(nodes.values());
        List<Community> communities = communities(nodeList, edgeList);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("files", files.size());
        stats.put("filesWithParseErrors", files.stream().filter(f -> Boolean.TRUE.equals(f.parseErrors())).count());
        stats.put("nodes", nodeList.size());
        stats.put("edges", edgeList.size());
        Map<String, Long> byKind = new TreeMap<>();
        nodeList.forEach(n -> byKind.merge(n.kind().label(), 1L, Long::sum));
        stats.put("nodesByKind", byKind);
        Map<String, Long> byRel = new TreeMap<>();
        edgeList.forEach(e -> byRel.merge(e.rel().label(), 1L, Long::sum));
        stats.put("edgesByRelation", byRel);
        Map<String, Long> byConf = new TreeMap<>();
        edgeList.forEach(e -> byConf.merge(e.conf().name(), 1L, Long::sum));
        stats.put("edgesByConfidence", byConf);
        stats.put("communities", communities.size());

        return new GraphFile(CodeGraph.FORMAT, CodeGraph.VERSION, project, root.toString(), null, null,
                Instant.now().truncatedTo(ChronoUnit.SECONDS).toString(), GENERATOR, stats, files, communities,
                nodeList, edgeList);
    }

    private static final class EdgeAcc {
        final String from;
        final String to;
        final Relation rel;
        Confidence conf;
        double score;
        int count;
        Integer line;

        EdgeAcc(String from, String to, Relation rel, Confidence conf, double score, Integer line) {
            this.from = from;
            this.to = to;
            this.rel = rel;
            this.conf = conf;
            this.score = score;
            this.line = line;
        }

        void merge(Confidence c, double s) {
            count++;
            if (c.ordinal() < conf.ordinal() || (c == conf && s > score)) {
                conf = c;
                score = s;
            }
        }

        Edge toEdge() {
            return new Edge(from, to, rel, conf, score >= 1.0 ? null : score, count > 1 ? count : null, line);
        }
    }

    private static String edgeKey(String from, String to, Relation rel) {
        return from + '\u0000' + to + '\u0000' + rel.ordinal();
    }

    private static void edge(Map<String, EdgeAcc> edges, String from, String to, Relation rel, Confidence conf,
                             double score, Integer line) {
        String key = edgeKey(from, to, rel);
        EdgeAcc acc = edges.get(key);
        if (acc == null) {
            acc = new EdgeAcc(from, to, rel, conf, score, line);
            acc.count = 1;
            edges.put(key, acc);
        } else {
            acc.merge(conf, score);
        }
    }

    // ------------------------------------------------------------------ Communities

    /**
     * Gruppiert Projekttypen über die Kanten ihrer Member (auf den Typ hochgezogen). Member erben die Community ihres
     * Typs; Pakete, Dateien und externe Typen bleiben ohne.
     */
    private static List<Community> communities(List<Node> nodes, List<Edge> edges) {
        Map<String, Integer> index = new HashMap<>();
        List<String> typeIds = new ArrayList<>();
        Map<String, Node> byId = new HashMap<>();
        for (Node n : nodes) {
            byId.put(n.id(), n);
            if (n.kind().isType()) {
                index.put(n.id(), typeIds.size());
                typeIds.add(n.id());
            }
        }
        Map<Long, Double> weights = new HashMap<>();
        for (Edge e : edges) {
            Double w = COMMUNITY_WEIGHT.get(e.rel());
            if (w == null) {
                continue;
            }
            Integer a = index.get(typeOf(e.from(), byId));
            Integer b = index.get(typeOf(e.to(), byId));
            if (a == null || b == null || a.equals(b)) {
                continue;
            }
            double weight = w * Math.min(3, e.countValue()) * e.scoreValue();
            long key = a < b ? ((long) a << 32) | b : ((long) b << 32) | a;
            weights.merge(key, weight, Double::sum);
        }
        int[] membership = Communities.detect(typeIds.size(), weights);

        Map<Integer, List<String>> members = new TreeMap<>();
        for (int i = 0; i < typeIds.size(); i++) {
            members.computeIfAbsent(membership[i], k -> new ArrayList<>()).add(typeIds.get(i));
        }
        Map<String, Integer> degree = new HashMap<>();
        for (Edge e : edges) {
            if (e.rel() != Relation.CONTAINS) {
                degree.merge(typeOf(e.to(), byId), 1, Integer::sum);
                degree.merge(typeOf(e.from(), byId), 1, Integer::sum);
            }
        }
        Map<String, Integer> commOf = new HashMap<>();
        List<Community> out = new ArrayList<>();
        for (var e : members.entrySet()) {
            List<String> ids = new ArrayList<>(e.getValue());
            ids.sort(Comparator.comparing((String id) -> -degree.getOrDefault(id, 0)).thenComparing(id -> id));
            ids.forEach(id -> commOf.put(id, e.getKey()));
            List<String> top = ids.subList(0, Math.min(5, ids.size())).stream()
                    .map(id -> id.substring(id.lastIndexOf('.') + 1)).toList();
            out.add(new Community(e.getKey(), label(ids), ids.size(), List.copyOf(top)));
        }
        for (int i = 0; i < nodes.size(); i++) {
            Node n = nodes.get(i);
            Integer c = n.kind().isType() ? commOf.get(n.id()) : n.kind().isMember() ? commOf.get(CodeGraph.ownerOf(n.id())) : null;
            if (c != null) {
                nodes.set(i, n.withCommunity(c));
            }
        }
        return out;
    }

    private static String typeOf(String id, Map<String, Node> byId) {
        Node n = byId.get(id);
        if (n == null) {
            return id;
        }
        return n.kind().isMember() ? CodeGraph.ownerOf(id) : id;
    }

    /** Bezeichnung einer Community: häufigstes Paket (verkürzt) und der zentralste Typ. */
    private static String label(List<String> ids) {
        Map<String, Integer> pkgs = new HashMap<>();
        for (String id : ids) {
            int dot = id.lastIndexOf('.');
            pkgs.merge(dot < 0 ? "" : id.substring(0, dot), 1, Integer::sum);
        }
        String pkg = pkgs.entrySet().stream()
                .max(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder())))
                .map(Map.Entry::getKey).orElse("");
        String[] parts = pkg.split("\\.");
        String shortPkg = parts.length <= 2 ? pkg : parts[parts.length - 2] + "." + parts[parts.length - 1];
        String center = ids.getFirst().substring(ids.getFirst().lastIndexOf('.') + 1);
        return (shortPkg.isEmpty() ? "" : shortPkg.toLowerCase(Locale.ROOT) + " / ") + center;
    }
}
