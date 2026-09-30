package systems.grebe.devtools.mcp.modules.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Edge;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Kind;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Node;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Relation;

/** {@link GraphReader} auf einem vollständig geladenen {@link CodeGraph} (Datei-Ablage). */
final class MemoryGraphReader implements GraphReader {

    private final CodeGraph g;
    private final GraphInfo info;

    MemoryGraphReader(CodeGraph g, String branch, String commit, String location) {
        this.g = g;
        var d = g.data();
        this.info = new GraphInfo(d.project(), branch, commit, d.root(), d.builtAt(), d.generator(), d.stats(),
                d.communities() == null ? List.of() : d.communities(), location);
    }

    CodeGraph graph() {
        return g;
    }

    @Override
    public GraphInfo info() {
        return info;
    }

    @Override
    public Node node(String id) {
        return g.node(id);
    }

    @Override
    public Map<String, Node> nodes(Collection<String> ids) {
        Map<String, Node> out = new LinkedHashMap<>();
        for (String id : ids) {
            Node n = g.node(id);
            if (n != null) {
                out.put(id, n);
            }
        }
        return out;
    }

    @Override
    public List<Edge> edges(Collection<String> ids, Direction dir, Set<Relation> rels) {
        List<Edge> out = new ArrayList<>();
        for (String id : ids) {
            if (dir != Direction.IN) {
                g.outgoing(id).stream().filter(e -> rels == null || rels.contains(e.rel())).forEach(out::add);
            }
            if (dir != Direction.OUT) {
                g.incoming(id).stream().filter(e -> rels == null || rels.contains(e.rel())).forEach(out::add);
            }
        }
        return out;
    }

    @Override
    public Map<String, Integer> degrees(Collection<String> ids) {
        Map<String, Integer> out = new HashMap<>();
        ids.forEach(id -> out.put(id, g.degree(id)));
        return out;
    }

    @Override
    public List<Node> typesNamed(String name) {
        Node exact = g.node(name);
        if (exact != null && (exact.kind().isType() || exact.kind() == Kind.EXTERNAL)) {
            return List.of(exact);
        }
        String suffix = "." + name;
        List<Node> out = new ArrayList<>();
        for (Node n : g.nodes()) {
            if (n.kind().isType() && (n.id().endsWith(suffix) || n.id().equals(name))) {
                out.add(n);
            }
        }
        if (out.isEmpty()) {
            for (Node n : g.nodes()) {
                if (n.kind() == Kind.EXTERNAL && (n.id().endsWith(suffix) || n.id().equals(name))) {
                    out.add(n);
                }
            }
        }
        return out;
    }

    @Override
    public List<Node> filesNamed(String name) {
        return g.nodes().stream().filter(n -> n.kind() == Kind.FILE
                && (n.name().endsWith("/" + name) || n.name().equals(name))).toList();
    }

    @Override
    public List<Node> search(NodeSearch s) {
        Pattern nameRx = s.nameRegex() == null ? null : Pattern.compile(s.nameRegex());
        Pattern idRx = s.idRegex() == null ? null : Pattern.compile(s.idRegex());
        List<Node> out = new ArrayList<>();
        for (Node n : g.nodes()) {
            if (s.kinds() != null && !s.kinds().contains(n.kind())) {
                continue;
            }
            String name = n.name().toLowerCase(Locale.ROOT);
            String id = n.id().toLowerCase(Locale.ROOT);
            String doc = s.withDoc() && n.doc() != null ? n.doc().toLowerCase(Locale.ROOT) : null;
            boolean hit = (nameRx != null && nameRx.matcher(name).matches())
                    || (idRx != null && idRx.matcher(id).matches());
            if (!hit && s.needles() != null) {
                for (String needle : s.needles()) {
                    if (name.contains(needle) || id.contains(needle) || (doc != null && doc.contains(needle))) {
                        hit = true;
                        break;
                    }
                }
            }
            if (hit) {
                out.add(n);
                if (out.size() >= s.limit()) {
                    break;
                }
            }
        }
        return out;
    }

    /** Projekttyp eines Knotens (Member → Besitzer), sonst {@code null}. */
    private String typeOf(String id) {
        Node n = g.node(id);
        if (n == null) {
            return null;
        }
        if (n.kind().isType()) {
            return id;
        }
        if (n.kind().isMember()) {
            return CodeGraph.ownerOf(id);
        }
        return null;
    }

    @Override
    public List<Map.Entry<String, Integer>> topTypes(int limit) {
        Map<String, Integer> deg = new HashMap<>();
        for (Edge e : g.edges()) {
            if (e.rel() == Relation.CONTAINS || e.rel() == Relation.IMPORTS) {
                continue;
            }
            String a = typeOf(e.from());
            String b = typeOf(e.to());
            if (a != null && a.equals(b)) {
                continue;
            }
            if (a != null) {
                deg.merge(a, 1, Integer::sum);
            }
            if (b != null) {
                deg.merge(b, 1, Integer::sum);
            }
        }
        return top(deg, limit);
    }

    @Override
    public List<Map.Entry<String, Integer>> mostCalled(int limit) {
        Map<String, Integer> called = new HashMap<>();
        for (Edge e : g.edges()) {
            if (e.rel() == Relation.CALLS) {
                called.merge(e.to(), e.countValue(), Integer::sum);
            }
        }
        return top(called, limit);
    }

    static List<Map.Entry<String, Integer>> top(Map<String, Integer> values, int limit) {
        return values.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(limit).map(e -> Map.entry(e.getKey(), e.getValue())).toList();
    }

    @Override
    public List<TypeLink> typeLinks() {
        Map<String, TypeLink> links = new TreeMap<>();
        for (Edge e : g.edges()) {
            if (e.rel() != Relation.CALLS && e.rel() != Relation.INSTANTIATES && e.rel() != Relation.HAS_TYPE) {
                continue;
            }
            String ta = typeOf(e.from());
            String tb = typeOf(e.to());
            if (ta == null || tb == null) {
                continue;
            }
            Integer ca = g.node(ta).community();
            Integer cb = g.node(tb).community();
            if (ca == null || cb == null || ca.equals(cb)) {
                continue;
            }
            String key = ta + "\u0000" + tb;
            TypeLink old = links.get(key);
            Edge sample = old == null || sampleOrder().compare(e, old.sample()) < 0 ? e : old.sample();
            links.put(key, new TypeLink(ta, tb, (old == null ? 0 : old.weight()) + e.countValue(), sample));
        }
        return new ArrayList<>(links.values());
    }

    static Comparator<Edge> sampleOrder() {
        return Comparator.comparing(Edge::from).thenComparing(Edge::to).thenComparing(e -> e.rel().name());
    }

    @Override
    public Map<String, Integer> callersByType(String typeId) {
        Map<String, Integer> out = new HashMap<>();
        for (Edge e : g.outgoing(typeId)) {
            if (e.rel() != Relation.CONTAINS) {
                continue;
            }
            for (Edge in : g.incoming(e.to())) {
                if (in.rel() == Relation.CALLS || in.rel() == Relation.OVERRIDES) {
                    String t = typeOf(in.from());
                    if (t != null && !t.equals(typeId)) {
                        out.merge(t, in.countValue(), Integer::sum);
                    }
                }
            }
        }
        return out;
    }

    @Override
    public List<String> parseErrorFiles(int limit) {
        return g.data().files().stream().filter(f -> Boolean.TRUE.equals(f.parseErrors()))
                .map(CodeGraph.FileEntry::path).limit(limit).toList();
    }

    @Override
    public List<Edge> shortestPath(String from, String to, Set<Relation> rels, boolean directed, int maxDepth) {
        // Abstand jedes Knotens zum Ziel (rückwärts); externe Typen/Pakete sind kein Zwischenstopp
        Map<String, Integer> distToTarget = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        distToTarget.put(to, 0);
        queue.add(to);
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            int d = distToTarget.get(cur);
            if (cur.equals(from) || d >= maxDepth || (!cur.equals(to) && blocked(cur))) {
                continue;
            }
            for (Edge e : steps(cur, rels, directed, true)) {
                String other = e.from().equals(cur) ? e.to() : e.from();
                if (!distToTarget.containsKey(other)) {
                    distToTarget.put(other, d + 1);
                    queue.add(other);
                }
            }
        }
        Integer total = distToTarget.get(from);
        if (total == null) {
            return null;
        }
        // vorwärts jeweils den kleinsten Schritt, der auf einem kürzesten Weg bleibt
        List<Edge> path = new ArrayList<>();
        String cur = from;
        for (int remaining = total; remaining > 0; remaining--) {
            String here = cur;
            int want = remaining - 1;
            Edge best = steps(here, rels, directed, false).stream()
                    .filter(e -> {
                        String other = e.from().equals(here) ? e.to() : e.from();
                        return Integer.valueOf(want).equals(distToTarget.get(other))
                                && (other.equals(to) || !blocked(other));
                    })
                    .min(GraphReader.stepOrder(here)).orElseThrow();
            path.add(best);
            cur = best.from().equals(here) ? best.to() : best.from();
        }
        return path;
    }

    private boolean blocked(String id) {
        Node n = g.node(id);
        return n != null && (n.kind() == Kind.EXTERNAL || n.kind() == Kind.PACKAGE);
    }

    /**
     * Kanten, über die man von {@code id} weitergeht: vorwärts die ausgehenden, rückwärts ({@code backward}) die
     * eingehenden; ungerichtet beide.
     */
    private List<Edge> steps(String id, Set<Relation> rels, boolean directed, boolean backward) {
        List<Edge> out = new ArrayList<>();
        if (!backward || !directed) {
            g.outgoing(id).stream().filter(e -> rels.contains(e.rel())).forEach(out::add);
        }
        if (backward || !directed) {
            g.incoming(id).stream().filter(e -> rels.contains(e.rel())).forEach(out::add);
        }
        return out;
    }
}
