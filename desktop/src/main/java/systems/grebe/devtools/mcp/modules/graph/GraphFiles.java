package systems.grebe.devtools.mcp.modules.graph;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Edge;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Kind;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Node;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Relation;
import systems.grebe.devtools.mcp.modules.graph.GraphQueries.Scored;
import systems.grebe.devtools.mcp.modules.graph.GraphReader.Direction;
import systems.grebe.devtools.mcp.modules.graph.GraphReader.NodeSearch;

/**
 * Dateisuche über den Code-Graphen: liefert je Datei Pfad, Länge und die passenden Typen/Member mit Zeilenbereich –
 * so kann das LLM gezielt Ausschnitte lesen ({@code graph_read}), statt Dateien per {@code grep}/{@code find} zu suchen
 * und ganz zu lesen.
 */
final class GraphFiles {

    /** Alle Relationen außer {@code contains}: wer nutzt / was nutzt eine Stelle. */
    static final Set<Relation> DEFAULT_RELATIONS = EnumSet.complementOf(EnumSet.of(Relation.CONTAINS));

    private static final Set<Relation> CONTAINS = EnumSet.of(Relation.CONTAINS);
    private static final int SYMBOLS_PER_FILE = 4;
    private static final int REASONS_PER_FILE = 3;

    private final GraphReader g;
    private final GraphQueries q;

    GraphFiles(GraphReader g) {
        this.g = g;
        this.q = new GraphQueries(g);
    }

    /** Treffer je Datei: Bewertung, passende Knoten (Symbole) bzw. Kanten (Begründungen). */
    private static final class FileHit {
        final String path;
        double best;
        double sum;
        int level = Integer.MAX_VALUE;
        int count;
        final List<Scored> symbols = new ArrayList<>();
        final List<Edge> reasons = new ArrayList<>();

        FileHit(String path) {
            this.path = path;
        }

        double score() {
            return best + Math.min(best, 0.3 * (sum - best));
        }
    }

    // ------------------------------------------------------------------ Suche nach Name, Stichworten oder Pfad

    /**
     * Dateien zu einem Suchtext: Pfad bzw. Pfadmuster (enthält {@code /} oder ist ein Dateiname mit Endung wie
     * {@code Order.java}, {@code build.gradle} oder {@code *.yml}), Namensmuster
     * mit {@code *} oder Stichworte/Namen (CamelCase, Wortstämme, Javadoc wie bei {@code graph_query}).
     */
    String search(String query, boolean includeTests, int limit) {
        String text = query == null ? "" : query.strip();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("query fehlt, z.B. 'OrderService', 'Auftrag speichern', '*Dao' oder "
                    + "'shop/repo/*.java'.");
        }
        Map<String, FileHit> files = new LinkedHashMap<>();
        String lower = text.replace('\\', '/').toLowerCase(Locale.ROOT);
        String how = null;
        if (lower.contains("/") || GraphQueries.looksLikeFileName(lower)) {
            String[] glob = lower.contains("*") ? lower.split("\\*", -1) : null;
            NodeSearch search = glob != null
                    ? new NodeSearch(EnumSet.of(Kind.FILE), null, false, null, GraphQueries.globRegex(glob, false),
                    GraphQueries.MAX_CANDIDATES)
                    : new NodeSearch(EnumSet.of(Kind.FILE), List.of(lower), false, null, null, GraphQueries.MAX_CANDIDATES);
            for (Node f : g.search(search)) {
                String path = f.name().toLowerCase(Locale.ROOT);
                boolean whole = glob != null ? GraphQueries.globMatches(glob, path, true) || GraphQueries.globMatches(
                        glob, path.substring(path.lastIndexOf('/') + 1), true)
                        : path.equals(lower) || path.endsWith("/" + lower);
                add(files, f.file(), null, whole ? 2 : 1);
            }
            // ohne '/' kann ein Punkt auch 'Typ.methode' sein: ohne Dateitreffer wie Name/Stichworte weitersuchen
            if (!files.isEmpty() || lower.contains("/")) {
                how = "Pfad";
            }
        }
        if (how == null && lower.contains("*")) {
            how = "Namensmuster";
            List<Node> hits = q.find(text, null, GraphQueries.MAX_CANDIDATES);
            for (int i = 0; i < hits.size(); i++) {
                Node n = hits.get(i);
                add(files, n.file(), n.kind() == Kind.FILE ? null : n, 1 + (double) (hits.size() - i) / hits.size());
            }
        } else if (how == null) {
            List<String> terms = GraphQueries.terms(text);
            if (terms.isEmpty()) {
                throw new IllegalArgumentException("Keine Suchbegriffe erkannt (mind. 3 Zeichen, keine Füllwörter).");
            }
            how = "Stichworte " + terms;
            for (Scored s : q.rank(terms, GraphQueries.needles(terms),
                    EnumSet.complementOf(EnumSet.of(Kind.PACKAGE, Kind.EXTERNAL)))) {
                add(files, s.n().file(), s.n().kind() == Kind.FILE ? null : s.n(), s.score());
            }
        }
        List<FileHit> ranked = files.values().stream()
                .filter(f -> includeTests || !isTestPath(f.path))
                .sorted(Comparator.comparingDouble((FileHit f) -> -f.score()).thenComparing(f -> f.path))
                .toList();
        if (ranked.isEmpty()) {
            return "Keine Datei passt zu '" + text + "' (" + how + ")." + (includeTests ? ""
                    : " Tests sind ausgeblendet (tests=true).") + " Mit graph_query oder graph_find breiter suchen.";
        }
        List<FileHit> shown = ranked.stream().limit(limit).toList();
        addTypesOfFilesWithoutSymbols(shown);
        StringBuilder sb = new StringBuilder().append(ranked.size()).append(ranked.size() == 1 ? " Datei" : " Dateien")
                .append(" (").append(how).append(ranked.size() > limit ? ", die besten " + limit : "").append("):\n");
        appendFiles(sb, shown, null);
        return sb.append(readHint()).toString();
    }

    private static void add(Map<String, FileHit> files, String path, Node symbol, double score) {
        if (path == null) {
            return;
        }
        FileHit f = files.computeIfAbsent(path, FileHit::new);
        f.best = Math.max(f.best, score);
        f.sum += score;
        if (symbol != null) {
            f.symbols.add(new Scored(symbol, score));
        }
    }

    /** Für reine Pfadtreffer die Typen der Datei als Orientierung zeigen. */
    private void addTypesOfFilesWithoutSymbols(List<FileHit> files) {
        List<String> ids = files.stream().filter(f -> f.symbols.isEmpty()).map(f -> GraphBuilder.fileId(f.path)).toList();
        if (ids.isEmpty()) {
            return;
        }
        List<Edge> contains = g.edges(ids, Direction.OUT, CONTAINS);
        Map<String, Node> types = g.nodes(contains.stream().map(Edge::to).toList());
        Map<String, FileHit> byPath = new HashMap<>();
        files.forEach(f -> byPath.put(f.path, f));
        List<Node> sorted = types.values().stream().filter(t -> t.kind().isType())
                .sorted(Comparator.comparing((Node t) -> t.line() == null ? Integer.MAX_VALUE : t.line())
                        .thenComparing(Node::id))
                .toList();
        for (Node t : sorted) {
            FileHit f = byPath.get(t.file());
            if (f != null && f.symbols.isEmpty()) {
                f.symbols.add(new Scored(t, 0));
            }
        }
    }

    // ------------------------------------------------------------------ verwandte Dateien über Kanten

    /**
     * Dateien, die über Kanten mit den Knoten verbunden sind – z.B. wer einen Typ oder eine Methode verwendet
     * ({@code IN}), was sie verwendet ({@code OUT}). Bei Typen und Dateien zählen die Kanten aller enthaltenen Member.
     */
    String related(List<String> specs, Direction dir, Set<Relation> rels, int depth, boolean includeTests, int limit) {
        if (specs == null || specs.stream().allMatch(s -> s == null || s.isBlank())) {
            throw new IllegalArgumentException("related fehlt, z.B. ['OrderService'] oder ['OrderService#save'].");
        }
        List<Node> starts = new ArrayList<>();
        for (String spec : specs) {
            if (spec != null && !spec.isBlank()) {
                starts.addAll(q.resolveAll(spec));
            }
        }
        Set<String> scope = scopeOf(starts);
        Set<String> seen = new HashSet<>(scope);
        Map<String, FileHit> files = new LinkedHashMap<>();
        Set<String> frontier = scope;
        for (int level = 1; level <= depth && !frontier.isEmpty(); level++) {
            List<Edge> edges = new ArrayList<>(g.edges(frontier, dir, rels));
            Set<String> others = new LinkedHashSet<>();
            for (Edge e : edges) {
                boolean fromIn = frontier.contains(e.from());
                boolean toIn = frontier.contains(e.to());
                if (fromIn != toIn) {
                    others.add(fromIn ? e.to() : e.from());
                }
            }
            Map<String, Node> nodes = g.nodes(others);
            Set<String> next = new LinkedHashSet<>();
            edges.sort(Comparator.comparing(Edge::conf).thenComparing(e -> -e.countValue())
                    .thenComparing(e -> e.from() + "|" + e.to()));
            for (Edge e : edges) {
                boolean fromIn = frontier.contains(e.from());
                if (fromIn == frontier.contains(e.to())) {
                    continue;
                }
                String otherId = fromIn ? e.to() : e.from();
                Node other = nodes.get(otherId);
                if (other == null || other.file() == null || scope.contains(otherId)) {
                    continue; // extern oder Teil des Ausgangs
                }
                FileHit f = files.computeIfAbsent(other.file(), FileHit::new);
                f.level = Math.min(f.level, level);
                f.count += e.countValue();
                if (f.reasons.size() < REASONS_PER_FILE && f.level == level) {
                    f.reasons.add(e);
                }
                if (seen.add(otherId)) {
                    next.add(otherId);
                }
            }
            frontier = next;
        }
        Set<String> startFiles = new HashSet<>();
        starts.forEach(s -> startFiles.add(s.file()));
        List<FileHit> ranked = files.values().stream()
                .filter(f -> includeTests || !isTestPath(f.path))
                .sorted(Comparator.comparingInt((FileHit f) -> f.level)
                        .thenComparingInt(f -> isTestPath(f.path) ? 1 : 0)
                        .thenComparingInt(f -> -f.count).thenComparing(f -> f.path))
                .toList();

        StringBuilder sb = new StringBuilder("Ausgang:\n");
        starts.stream().limit(10).forEach(s -> sb.append("  ").append(s.id()).append("  [").append(s.kind().label())
                .append("]  ").append(range(s)).append('\n'));
        if (starts.size() > 10) {
            sb.append("  … ").append(starts.size() - 10).append(" weitere\n");
        }
        if (ranked.isEmpty()) {
            return sb.append("Keine verbundenen Dateien (").append(dirLabel(dir)).append(", Relationen ")
                    .append(rels.stream().map(Relation::label).toList()).append(", Tiefe ").append(depth).append(")")
                    .append(includeTests ? "." : ", Tests ausgeblendet.").toString();
        }
        sb.append('\n').append(ranked.size()).append(ranked.size() == 1 ? " verbundene Datei" : " verbundene Dateien")
                .append(" (").append(dirLabel(dir)).append(depth > 1 ? ", Tiefe " + depth : "")
                .append(ranked.size() > limit ? ", die ersten " + limit : "").append("):\n");
        appendFiles(sb, ranked.stream().limit(limit).toList(), startFiles);
        return sb.append(readHint()).toString();
    }

    /** Knoten samt allem, was sie (auch verschachtelt) enthalten. */
    private Set<String> scopeOf(List<Node> starts) {
        Set<String> scope = new LinkedHashSet<>();
        List<String> frontier = new ArrayList<>();
        for (Node s : starts) {
            if (s.kind() == Kind.EXTERNAL || s.kind() == Kind.PACKAGE) {
                throw new IllegalArgumentException(s.id() + " ist " + (s.kind() == Kind.EXTERNAL ? "ein externer Typ"
                        : "ein Paket") + " – Typ, Member oder Datei des Projekts angeben.");
            }
            if (scope.add(s.id()) && !s.kind().isMember()) {
                frontier.add(s.id());
            }
        }
        while (!frontier.isEmpty()) {
            List<String> next = new ArrayList<>();
            for (Edge e : g.edges(frontier, Direction.OUT, CONTAINS)) {
                if (scope.add(e.to())) {
                    next.add(e.to());
                }
            }
            frontier = next;
        }
        return scope;
    }

    private static String dirLabel(Direction dir) {
        return switch (dir) {
            case IN -> "verwenden den Ausgang";
            case OUT -> "vom Ausgang verwendet";
            case BOTH -> "in beide Richtungen";
        };
    }

    // ------------------------------------------------------------------ Ausgabe

    private void appendFiles(StringBuilder sb, List<FileHit> files, Set<String> startFiles) {
        Set<String> ids = new HashSet<>();
        files.forEach(f -> {
            ids.add(GraphBuilder.fileId(f.path));
            f.reasons.forEach(e -> {
                ids.add(e.from());
                ids.add(e.to());
            });
        });
        Map<String, Node> nodes = g.nodes(ids);
        for (FileHit f : files) {
            Node file = nodes.get(GraphBuilder.fileId(f.path));
            sb.append(f.path);
            if (file != null && file.endLine() != null) {
                sb.append("  (").append(file.endLine()).append(" Z.)");
            }
            if (startFiles != null && startFiles.contains(f.path)) {
                sb.append("  [Ausgangsdatei]");
            }
            if (f.level > 1 && f.level != Integer.MAX_VALUE) {
                sb.append("  [Tiefe ").append(f.level).append(']');
            }
            sb.append('\n');
            if (!f.symbols.isEmpty()) {
                List<Scored> symbols = f.symbols.stream()
                        .sorted(Comparator.comparingDouble((Scored s) -> -s.score()).thenComparing(s -> s.n().id()))
                        .limit(SYMBOLS_PER_FILE).toList();
                sb.append("  ").append(String.join(" · ", symbols.stream().map(s -> symbol(s.n())).toList()));
                if (f.symbols.size() > SYMBOLS_PER_FILE) {
                    sb.append(" · +").append(f.symbols.size() - SYMBOLS_PER_FILE);
                }
                sb.append('\n');
            }
            for (Edge e : f.reasons) {
                sb.append("  ").append(shortName(nodes, e.from())).append(" --").append(e.rel().label()).append("--> ")
                        .append(shortName(nodes, e.to())).append(GraphQueries.edgeTag(e)).append('\n');
            }
            int more = f.count - f.reasons.stream().mapToInt(Edge::countValue).sum();
            if (more > 0) {
                sb.append("  … ").append(more).append(more == 1 ? " weitere Stelle\n" : " weitere Stellen\n");
            }
        }
    }

    private static String symbol(Node n) {
        return CodeGraph.shortName(n) + (n.kind().isType() ? " [" + n.kind().label() + "]" : "") + " " + range(n);
    }

    /** {@code Z7-46} bzw. {@code Z8} für einzeilige Knoten. */
    static String range(Node n) {
        if (n.line() == null) {
            return n.kind() == Kind.FILE && n.endLine() != null ? "Z1-" + n.endLine() : "";
        }
        return n.endLine() == null || n.endLine().equals(n.line()) ? "Z" + n.line() : "Z" + n.line() + "-" + n.endLine();
    }

    private static String shortName(Map<String, Node> nodes, String id) {
        Node n = nodes.get(id);
        return n == null ? id : CodeGraph.shortName(n);
    }

    private static String readHint() {
        return "Lesen: graph_read mit Knoten (z.B. 'Typ#methode'), Datei für die Gliederung oder lines='von-bis' – "
                + "statt ganzer Dateien.";
    }

    /** Liegt in einem Testquellordner oder heißt wie eine Testklasse. */
    static boolean isTestPath(String path) {
        if (path.startsWith("src/test/") || path.contains("/src/test/")) {
            return true;
        }
        String name = path.substring(path.lastIndexOf('/') + 1);
        return name.endsWith("Test.java") || name.endsWith("Tests.java") || name.endsWith("IT.java");
    }
}
