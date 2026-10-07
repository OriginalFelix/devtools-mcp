package systems.grebe.devtools.mcp.modules.graph;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Community;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Confidence;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Edge;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Kind;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Node;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Relation;
import systems.grebe.devtools.mcp.modules.graph.GraphReader.Direction;
import systems.grebe.devtools.mcp.modules.graph.GraphReader.GraphInfo;
import systems.grebe.devtools.mcp.modules.graph.GraphReader.NodeSearch;
import systems.grebe.devtools.mcp.modules.graph.GraphReader.TypeLink;

/**
 * Abfragen auf einem gespeicherten Code-Graphen; Ergebnisse als kompakter Text für das LLM. Alle Zugriffe laufen über
 * {@link GraphReader} – bei der Neo4j-Ablage also als Cypher in der Datenbank, ohne den Graphen zu laden.
 */
final class GraphQueries {

    private static final Pattern CAMEL = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])|[^A-Za-z0-9]+");
    private static final Set<String> STOP_WORDS = Set.of("the", "a", "an", "and", "or", "of", "to", "in", "is", "are",
            "what", "which", "who", "how", "where", "does", "do", "with", "from", "for", "by", "on", "der", "die",
            "das", "und", "oder", "wie", "wo", "was", "wer", "welche", "welcher", "wird", "werden", "von", "mit", "im",
            "den", "dem", "des", "ein", "eine", "ist", "sind", "zu", "auf", "fuer", "für", "aus", "nach", "class",
            "klasse", "methode", "method");

    /** Obergrenze der Kandidaten, die {@code find}/{@code query} aus dem Graphen holen und dann bewerten. */
    static final int MAX_CANDIDATES = 20_000;

    private static final Set<Relation> CONTAINS = EnumSet.of(Relation.CONTAINS);

    private final GraphReader g;
    private final Map<Integer, Community> communities = new HashMap<>();

    GraphQueries(GraphReader g) {
        this.g = g;
        List<Community> list = g.info().communities();
        if (list != null) {
            list.forEach(c -> communities.put(c.id(), c));
        }
    }

    GraphQueries(CodeGraph g) {
        this(new MemoryGraphReader(g, null, null, g.data().root()));
    }

    private Community community(Integer id) {
        return id == null ? null : communities.get(id);
    }

    // ------------------------------------------------------------------ Knoten finden

    /**
     * Löst eine Knotenangabe auf: exakte ID, {@code Typ}, {@code Typ#methode}, {@code Typ.methode}, {@code Typ#methode(int)}
     * oder ein Dateipfad – jeweils auch mit einfachem Typnamen.
     *
     * @throws IllegalArgumentException mit Kandidatenliste, wenn nichts oder mehrere Knoten passen
     */
    Node resolve(String spec) {
        List<Node> hits = resolveAll(spec);
        if (hits.size() == 1) {
            return hits.getFirst();
        }
        List<String> ids = hits.stream().limit(15).map(n -> n.id() + " [" + n.kind().label() + "]").toList();
        throw new IllegalArgumentException("'" + spec + "' ist mehrdeutig (" + hits.size() + " Treffer): "
                + String.join(", ", ids) + (hits.size() > 15 ? " …" : "") + ". Genaue ID angeben.");
    }

    /**
     * Alle Knoten zu einer Knotenangabe (z.B. sämtliche Überladungen von {@code Typ#methode}).
     *
     * @throws IllegalArgumentException mit ähnlichen Namen, wenn nichts passt
     */
    List<Node> resolveAll(String spec) {
        List<Node> hits = candidates(spec);
        if (hits.isEmpty()) {
            List<Node> similar = find(spec.replaceAll("[#(].*", "").replaceAll(".*\\.", ""), null, 8);
            throw new IllegalArgumentException("Kein Knoten '" + spec + "' im Graphen."
                    + (similar.isEmpty() ? " Mit graph_find nach dem Namen suchen." : " Ähnlich: "
                    + String.join(", ", similar.stream().map(Node::id).toList()) + ". Genaue ID aus graph_find übernehmen."));
        }
        return hits;
    }

    private List<Node> candidates(String raw) {
        String spec = raw == null ? "" : raw.strip();
        if (spec.isEmpty()) {
            throw new IllegalArgumentException("Knoten fehlt, z.B. 'OrderService' oder 'OrderService#save'.");
        }
        Node exact = g.node(spec);
        if (exact != null) {
            return List.of(exact);
        }
        Node file = g.node("file:" + spec.replace('\\', '/'));
        if (file != null) {
            return List.of(file);
        }
        if (spec.endsWith(".java")) {
            List<Node> files = g.filesNamed(spec.replace('\\', '/'));
            if (!files.isEmpty()) {
                return files; // 'OrderService.java' – sonst hielte die Auflösung 'java' für einen Member
            }
        }
        String typePart;
        String memberPart;
        int hash = spec.indexOf('#');
        if (hash >= 0) {
            typePart = spec.substring(0, hash);
            memberPart = spec.substring(hash + 1);
        } else {
            String noParams = spec.replaceAll("\\(.*", "");
            int dot = noParams.lastIndexOf('.');
            if (dot > 0 && dot + 1 < noParams.length() && Character.isLowerCase(noParams.charAt(dot + 1))
                    && g.typesNamed(noParams).isEmpty() && !g.typesNamed(noParams.substring(0, dot)).isEmpty()) {
                typePart = noParams.substring(0, dot);
                memberPart = spec.substring(dot + 1);
            } else {
                typePart = spec;
                memberPart = null;
            }
        }
        List<Node> owners = g.typesNamed(typePart);
        if (memberPart == null) {
            return owners.isEmpty() ? g.filesNamed(spec) : owners;
        }
        String name = memberPart.replaceAll("\\(.*", "");
        String params = memberPart.contains("(") ? memberPart.substring(memberPart.indexOf('(')).replace(" ", "") : null;
        List<String> ownerIds = owners.stream().map(Node::id).toList();
        List<Edge> contains = g.edges(ownerIds, Direction.OUT, CONTAINS);
        Map<String, Node> members = g.nodes(contains.stream().map(Edge::to).toList());
        List<Node> out = new ArrayList<>();
        for (Edge e : contains) {
            Node m = members.get(e.to());
            if (m == null || !m.kind().isMember()) {
                continue;
            }
            String memberKey = m.id().substring(m.id().indexOf('#') + 1);
            boolean nameMatch = m.name().equals(name) || (name.equals("<init>") && m.kind() == Kind.CONSTRUCTOR);
            if (nameMatch && (params == null || memberKey.endsWith(params))) {
                out.add(m);
            }
        }
        return out;
    }

    /** Suche nach Namen: exakt vor Präfix vor Teilstring; {@code *} als Platzhalter. */
    List<Node> find(String query, Kind kind, int limit) {
        String q = query == null ? "" : query.strip();
        if (q.isEmpty()) {
            return List.of();
        }
        String[] glob = q.contains("*") ? q.toLowerCase(Locale.ROOT).split("\\*", -1) : null;
        String lower = q.toLowerCase(Locale.ROOT);
        Set<Kind> kinds = kind != null ? EnumSet.of(kind) : EnumSet.complementOf(EnumSet.of(Kind.PACKAGE));
        NodeSearch search = glob != null
                ? new NodeSearch(kinds, null, false, globRegex(glob, true), globRegex(glob, false), MAX_CANDIDATES)
                : new NodeSearch(kinds, List.of(lower), false, null, null, MAX_CANDIDATES);
        record Hit(Node n, int score) {
        }
        List<Hit> hits = new ArrayList<>();
        for (Node n : g.search(search)) {
            String name = n.name().toLowerCase(Locale.ROOT);
            String id = n.id().toLowerCase(Locale.ROOT);
            int score;
            if (glob != null) {
                score = globMatches(glob, name, true) ? 3 : globMatches(glob, id, false) ? 1 : -1;
            } else if (name.equals(lower) || id.equals(lower)) {
                score = 4;
            } else if (name.startsWith(lower)) {
                score = 3;
            } else if (name.contains(lower)) {
                score = 2;
            } else if (id.contains(lower)) {
                score = 1;
            } else {
                score = -1;
            }
            if (score >= 0) {
                hits.add(new Hit(n, score));
            }
        }
        // Grad erst für die Treffer holen – für alle Knoten wäre das zu teuer
        Map<String, Integer> degrees = g.degrees(hits.stream().map(h -> h.n().id()).toList());
        List<Hit> ranked = new ArrayList<>(hits.size());
        for (Hit h : hits) {
            ranked.add(new Hit(h.n(), h.score() * 100000 + Math.min(99999, degrees.getOrDefault(h.n().id(), 0))));
        }
        ranked.sort(Comparator.comparingInt((Hit h) -> -h.score()).thenComparing(h -> h.n().id()));
        return ranked.stream().limit(limit).map(Hit::n).toList();
    }

    /** Platzhaltermuster als Java-Regex (gilt so auch in Cypher {@code =~}): ganz verankert oder Teiltreffer. */
    static String globRegex(String[] parts, boolean whole) {
        StringBuilder sb = new StringBuilder(whole ? "" : ".*");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append(".*");
            }
            if (!parts[i].isEmpty()) {
                sb.append(Pattern.quote(parts[i]));
            }
        }
        return sb.append(whole ? "" : ".*").toString();
    }

    /**
     * Platzhalter-Vergleich ohne Regex (linear): Teile müssen in dieser Reihenfolge vorkommen.
     *
     * @param whole {@code true} = ganzer Text muss passen (Anfang/Ende ohne {@code *} sind verankert),
     *              {@code false} = Teiltreffer genügt
     */
    static boolean globMatches(String[] parts, String text, boolean whole) {
        int n = parts.length;
        int start = 0;
        int end = text.length();
        if (whole) {
            if (!text.startsWith(parts[0])) {
                return false;
            }
            start = parts[0].length();
            String last = parts[n - 1];
            if (n == 1) {
                return text.equals(parts[0]);
            }
            if (!text.endsWith(last) || end - last.length() < start) {
                return false;
            }
            end -= last.length();
        }
        int from = whole ? 1 : 0;
        int to = whole ? n - 1 : n;
        int pos = start;
        for (int i = from; i < to; i++) {
            String p = parts[i];
            if (p.isEmpty()) {
                continue;
            }
            int at = text.indexOf(p, pos);
            if (at < 0 || at + p.length() > end) {
                return false;
            }
            pos = at + p.length();
        }
        return true;
    }

    // ------------------------------------------------------------------ Ausgabe-Bausteine

    String line(Node n) {
        StringBuilder sb = new StringBuilder();
        sb.append(n.id()).append("  [").append(n.kind().label()).append(']');
        if (n.signature() != null && n.kind().isMember()) {
            sb.append("  ").append(n.signature());
        }
        if (n.file() != null && n.kind() != Kind.FILE) {
            sb.append("  ").append(n.location());
        }
        return sb.toString();
    }

    static String edgeTag(Edge e) {
        StringBuilder sb = new StringBuilder();
        if (e.conf() != Confidence.EXTRACTED) {
            sb.append(e.conf().name());
            if (e.score() != null) {
                sb.append(' ').append(e.score());
            }
        }
        if (e.countValue() > 1) {
            sb.append(sb.isEmpty() ? "" : ", ").append(e.countValue()).append('×');
        }
        if (e.line() != null) {
            sb.append(sb.isEmpty() ? "" : ", ").append('Z').append(e.line());
        }
        return sb.isEmpty() ? "" : " (" + sb + ")";
    }

    /** Kurzname; für unbekannte IDs (Knoten fehlt) die ID selbst. */
    private static String shortName(Map<String, Node> nodes, String id) {
        Node n = nodes.get(id);
        return n == null ? id : CodeGraph.shortName(n);
    }

    // ------------------------------------------------------------------ Bericht

    String report(int topN) {
        StringBuilder sb = new StringBuilder();
        GraphInfo d = g.info();
        sb.append("# Code-Graph ").append(d.project()).append(d.branch() == null ? "" : " (Branch " + d.branch()
                + (d.commit() == null ? "" : " @ " + d.commit()) + ")").append("\n");
        sb.append("Gebaut ").append(d.builtAt()).append(" · ").append(d.location()).append('\n');
        Map<String, Object> s = d.stats();
        sb.append(s.get("files")).append(" Dateien, ").append(s.get("nodes")).append(" Knoten, ")
                .append(s.get("edges")).append(" Kanten, ").append(s.get("communities")).append(" Communities\n");
        sb.append("Knoten: ").append(s.get("nodesByKind")).append('\n');
        sb.append("Kanten: ").append(s.get("edgesByRelation")).append('\n');
        sb.append("Sicherheit: ").append(s.get("edgesByConfidence"))
                .append(" (EXTRACTED = steht im Code, INFERRED = abgeleitet, AMBIGUOUS = mehrere mögliche Ziele)\n");
        long errors = d.stat("filesWithParseErrors");
        if (errors > 0) {
            sb.append("Achtung: ").append(errors).append(" Datei(en) mit Syntax- oder Lesefehlern – dort fehlen evtl. ")
                    .append("Kanten: ").append(String.join(", ", g.parseErrorFiles(5))).append(errors > 5 ? " …" : "")
                    .append('\n');
        }

        sb.append("\n## God Nodes (meistverbundene Typen)\n");
        List<Map.Entry<String, Integer>> top = g.topTypes(topN);
        Map<String, Node> topNodes = g.nodes(top.stream().map(Map.Entry::getKey).toList());
        top.forEach(e -> {
            Node n = topNodes.get(e.getKey());
            sb.append("- ").append(shortName(topNodes, e.getKey())).append(" – ").append(e.getValue()).append(" Kanten, ")
                    .append(n == null ? "" : n.location()).append(n == null || n.doc() == null ? "" : " – " + n.doc())
                    .append('\n');
        });

        sb.append("\n## Meistaufgerufene Methoden\n");
        List<Map.Entry<String, Integer>> called = g.mostCalled(topN);
        Map<String, Node> calledNodes = g.nodes(called.stream().map(Map.Entry::getKey).toList());
        called.forEach(e -> sb.append("- ").append(shortName(calledNodes, e.getKey())).append(" – ")
                .append(e.getValue()).append(" Aufrufe\n"));

        sb.append("\n## Communities (größte zuerst)\n");
        List<Community> comms = d.communities() == null ? List.of() : d.communities();
        comms.stream().filter(c -> c.size() > 1).limit(topN).forEach(c -> sb.append("- #").append(c.id()).append(' ')
                .append(c.label()).append(" (").append(c.size()).append(" Typen): ")
                .append(String.join(", ", c.top())).append('\n'));
        long singles = comms.stream().filter(c -> c.size() == 1).count();
        if (singles > 0) {
            sb.append("- ").append(singles).append(" isolierte Typen ohne Verbindung zu anderen\n");
        }

        sb.append("\n## Überraschende Verbindungen (zwischen Communities in verschiedenen Paketen)\n");
        List<String> surprising = surprising(topN);
        if (surprising.isEmpty()) {
            sb.append("- keine\n");
        } else {
            surprising.forEach(x -> sb.append("- ").append(x).append('\n'));
        }

        sb.append("\n## Unsichere Kanten zur Prüfung\n");
        sb.append("- ").append(confidenceCount(s, Confidence.AMBIGUOUS)).append(" AMBIGUOUS, ")
                .append(confidenceCount(s, Confidence.INFERRED))
                .append(" INFERRED – bei Zweifel im Quelltext an der angegebenen Zeile nachsehen.\n");

        sb.append("\n## Nächste Schritte\n")
                .append("- graph_explain <Typ|Typ#methode> – Details und alle Beziehungen eines Knotens\n")
                .append("- graph_neighbors <Knoten> direction=in relations=[calls] depth=2 – wer ruft das auf\n")
                .append("- graph_path <von> <nach> – wie hängen zwei Stellen zusammen\n")
                .append("- graph_query \"Frage\" – relevanten Teilgraphen zu Stichworten\n");
        return sb.toString().stripTrailing();
    }

    private static long confidenceCount(Map<String, Object> stats, Confidence c) {
        return stats.get("edgesByConfidence") instanceof Map<?, ?> m && m.get(c.name()) instanceof Number n
                ? n.longValue() : 0;
    }

    private List<String> surprising(int limit) {
        List<TypeLink> links = new ArrayList<>();
        for (TypeLink l : g.typeLinks()) {
            String pa = pkg(l.a());
            String pb = pkg(l.b());
            if (pa.equals(pb) || commonPrefix(pa, pb) >= Math.min(depth(pa), depth(pb)) - 1) {
                continue; // benachbarte Pakete sind nicht überraschend
            }
            links.add(l);
        }
        Set<String> ids = new HashSet<>();
        links.forEach(l -> {
            ids.add(l.a());
            ids.add(l.b());
            ids.add(l.sample().from());
            ids.add(l.sample().to());
        });
        Map<String, Node> nodes = g.nodes(ids);
        // selten ist überraschend: Paare, die genau einmal verbunden sind, zwischen großen Communities
        return links.stream()
                .sorted(Comparator.comparingInt(TypeLink::weight)
                        .thenComparing(l -> -sizeOf(nodes.get(l.a())) - sizeOf(nodes.get(l.b())))
                        .thenComparing(TypeLink::a).thenComparing(TypeLink::b))
                .limit(limit)
                .map(l -> {
                    Node from = nodes.get(l.sample().from());
                    return shortName(nodes, l.sample().from()) + " --" + l.sample().rel().label() + "--> "
                            + shortName(nodes, l.sample().to()) + "  (Community #" + communityOf(nodes.get(l.a()))
                            + " → #" + communityOf(nodes.get(l.b())) + ", " + (from == null ? "" : from.location()) + ")";
                })
                .toList();
    }

    private static Integer communityOf(Node n) {
        return n == null ? null : n.community();
    }

    private int sizeOf(Node n) {
        Community c = n == null ? null : community(n.community());
        return c == null ? 0 : c.size();
    }

    private static String pkg(String fqn) {
        int i = fqn.lastIndexOf('.');
        return i < 0 ? "" : fqn.substring(0, i);
    }

    private static int depth(String pkg) {
        return pkg.isEmpty() ? 0 : pkg.split("\\.").length;
    }

    private static int commonPrefix(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        int i = 0;
        while (i < x.length && i < y.length && x[i].equals(y[i])) {
            i++;
        }
        return i;
    }

    // ------------------------------------------------------------------ explain

    String explain(Node n, int maxPerRelation) {
        StringBuilder sb = new StringBuilder(line(n)).append('\n');
        if (n.modifiers() != null) {
            sb.append("Modifier: ").append(n.modifiers()).append('\n');
        }
        if (n.doc() != null) {
            sb.append("Doku: ").append(n.doc()).append('\n');
        }
        Community c = community(n.community());
        if (c != null) {
            sb.append("Community #").append(c.id()).append(": ").append(c.label()).append(" (").append(c.size())
                    .append(" Typen)\n");
        }
        if (n.kind().isMember()) {
            Node owner = g.node(CodeGraph.ownerOf(n.id()));
            if (owner != null) {
                sb.append("Gehört zu: ").append(owner.id()).append('\n');
            }
        }
        List<Edge> outgoing = g.edges(n.id(), Direction.OUT, null);
        List<Edge> incoming = g.edges(n.id(), Direction.IN, null);
        Set<String> ids = new HashSet<>();
        outgoing.forEach(e -> ids.add(e.to()));
        incoming.forEach(e -> ids.add(e.from()));
        Map<String, Node> nodes = g.nodes(ids);
        if (n.kind().isType() || n.kind() == Kind.FILE || n.kind() == Kind.PACKAGE) {
            List<Node> children = new ArrayList<>();
            for (Edge e : outgoing) {
                if (e.rel() == Relation.CONTAINS && nodes.containsKey(e.to())) {
                    children.add(nodes.get(e.to()));
                }
            }
            // Quelltextreihenfolge – unabhängig davon, in welcher Reihenfolge die Ablage die Kanten liefert
            children.sort(Comparator.comparing((Node ch) -> ch.file() == null ? "" : ch.file())
                    .thenComparing(ch -> ch.line() == null ? Integer.MAX_VALUE : ch.line()).thenComparing(Node::id));
            if (!children.isEmpty()) {
                sb.append("\nEnthält (").append(children.size()).append("):\n");
                children.stream().limit(maxPerRelation * 2L).forEach(ch -> sb.append("  ")
                        .append(ch.kind().label()).append(' ')
                        .append(ch.signature() != null ? ch.signature() : ch.name())
                        .append(ch.line() == null ? "" : "  Z" + ch.line()).append('\n'));
                if (children.size() > maxPerRelation * 2) {
                    sb.append("  … ").append(children.size() - maxPerRelation * 2).append(" weitere\n");
                }
            }
        }
        appendRelations(sb, "Ausgehend", outgoing, nodes, true, maxPerRelation);
        appendRelations(sb, "Eingehend", incoming, nodes, false, maxPerRelation);
        if (n.kind().isType()) {
            // Aufrufe von außen auf Member dieses Typs zusammenfassen
            Map<String, Integer> byType = g.callersByType(n.id());
            Map<String, Node> typeNodes = g.nodes(byType.keySet());
            Map<String, Integer> callersByType = new TreeMap<>();
            byType.forEach((t, count) -> callersByType.merge(shortName(typeNodes, t), count, Integer::sum));
            int total = callersByType.values().stream().mapToInt(Integer::intValue).sum();
            if (!callersByType.isEmpty()) {
                sb.append("\nMember werden verwendet von ").append(callersByType.size()).append(" Typen (")
                        .append(total).append(" Aufrufe): ");
                sb.append(String.join(", ", callersByType.entrySet().stream()
                        .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(maxPerRelation)
                        .map(e -> e.getKey() + " " + e.getValue() + "×").toList()));
                if (callersByType.size() > maxPerRelation) {
                    sb.append(" …");
                }
                sb.append('\n');
            }
        }
        return sb.toString().stripTrailing();
    }

    private void appendRelations(StringBuilder sb, String title, List<Edge> edges, Map<String, Node> nodes,
                                 boolean outgoing, int max) {
        Map<Relation, List<Edge>> byRel = new TreeMap<>();
        for (Edge e : edges) {
            if (e.rel() != Relation.CONTAINS) {
                byRel.computeIfAbsent(e.rel(), k -> new ArrayList<>()).add(e);
            }
        }
        if (byRel.isEmpty()) {
            return;
        }
        sb.append('\n').append(title).append(":\n");
        byRel.forEach((rel, list) -> {
            sb.append("  ").append(rel.label()).append(" (").append(list.size()).append("):\n");
            list.stream().sorted(Comparator.comparing(e -> outgoing ? e.to() : e.from())).limit(max).forEach(e -> {
                String otherId = outgoing ? e.to() : e.from();
                Node other = nodes.get(otherId);
                sb.append("    ").append(other == null ? otherId : describeShort(other))
                        .append(edgeTag(e)).append('\n');
            });
            if (list.size() > max) {
                sb.append("    … ").append(list.size() - max).append(" weitere\n");
            }
        });
    }

    private static String describeShort(Node n) {
        String loc = n.file() == null ? "" : "  " + n.location();
        return (n.kind() == Kind.EXTERNAL ? n.id() + " [extern]" : n.id()) + loc;
    }

    // ------------------------------------------------------------------ Nachbarn / Aufrufbäume

    String neighbors(Node start, Direction dir, Set<Relation> relations, int depth, int limit) {
        StringBuilder sb = new StringBuilder(line(start)).append('\n');
        Set<String> seen = new HashSet<>();
        seen.add(start.id());
        int[] printed = {0};
        walkTree(sb, start.id(), dir, relations, depth, 1, seen, printed, limit);
        if (printed[0] == 0) {
            sb.append("  (keine ").append(dir == Direction.IN ? "eingehenden" : dir == Direction.OUT ? "ausgehenden" : "")
                    .append(" Kanten vom Typ ").append(relations.stream().map(Relation::label).toList()).append(")\n");
            if (start.kind().isType() && relations.contains(Relation.CALLS)) {
                sb.append("  Hinweis: Aufrufe hängen an Methoden – z.B. '").append(CodeGraph.shortName(start))
                        .append("#methode' abfragen oder graph_explain für die Member-Liste.\n");
            }
        } else if (printed[0] >= limit) {
            sb.append("… abgeschnitten nach ").append(limit).append(" Einträgen (limit erhöhen oder depth senken)\n");
        }
        return sb.toString().stripTrailing();
    }

    private void walkTree(StringBuilder sb, String id, Direction dir, Set<Relation> relations, int maxDepth, int level,
                          Set<String> seen, int[] printed, int limit) {
        if (level > maxDepth) {
            return;
        }
        List<Edge> edges = new ArrayList<>(g.edges(id, dir, relations));
        edges.sort(Comparator.comparing((Edge e) -> e.rel()).thenComparing(e -> e.from().equals(id) ? e.to() : e.from()));
        Map<String, Node> nodes = g.nodes(edges.stream().map(e -> e.from().equals(id) ? e.to() : e.from()).toList());
        for (Edge e : edges) {
            if (printed[0] >= limit) {
                return;
            }
            String other = e.from().equals(id) ? e.to() : e.from();
            boolean repeat = !seen.add(other);
            Node n = nodes.get(other);
            sb.append("  ".repeat(level)).append(e.from().equals(id) ? "-->" : "<--").append(' ')
                    .append(e.rel().label()).append(' ')
                    .append(n == null ? other : describeShort(n)).append(edgeTag(e))
                    .append(repeat ? "  (siehe oben)" : "").append('\n');
            printed[0]++;
            if (!repeat) {
                walkTree(sb, other, dir, relations, maxDepth, level + 1, seen, printed, limit);
            }
        }
    }

    // ------------------------------------------------------------------ Pfad

    String path(Node from, Node to, Set<Relation> relations, boolean directed, int maxDepth) {
        List<Edge> steps = from.id().equals(to.id()) ? List.of()
                : g.shortestPath(from.id(), to.id(), relations, directed, maxDepth);
        if (steps == null) {
            return "Kein Pfad von " + from.id() + " nach " + to.id() + " (max. " + maxDepth + " Schritte, "
                    + (directed ? "nur in Pfeilrichtung" : "beide Richtungen") + ", Relationen "
                    + relations.stream().map(Relation::label).toList() + ")."
                    + (directed ? " Mit directed=false auch rückwärts suchen." : "");
        }
        Set<String> ids = new HashSet<>();
        steps.forEach(e -> {
            ids.add(e.from());
            ids.add(e.to());
        });
        Map<String, Node> nodes = g.nodes(ids);
        StringBuilder sb = new StringBuilder("Pfad (").append(steps.size()).append(" Schritte):\n");
        String cur = from.id();
        sb.append("  ").append(describeShort(from)).append('\n');
        for (Edge e : steps) {
            boolean forward = e.from().equals(cur);
            String other = forward ? e.to() : e.from();
            Node on = nodes.get(other);
            sb.append("  ").append(forward ? "--" + e.rel().label() + "-->" : "<--" + e.rel().label() + "--")
                    .append(' ').append(on == null ? other : describeShort(on)).append(edgeTag(e)).append('\n');
            cur = other;
        }
        return sb.toString().stripTrailing();
    }

    // ------------------------------------------------------------------ query (Stichwortsuche + Teilgraph)

    String query(String question, int maxNodes) {
        List<String> terms = terms(question);
        if (terms.isEmpty()) {
            throw new IllegalArgumentException("Keine Suchbegriffe in der Frage erkannt. Klassen-/Methodennamen oder "
                    + "Fachbegriffe angeben, z.B. 'Wie wird ein Auftrag gespeichert?' → Begriffe auftrag, gespeichert.");
        }
        // Kandidaten: Name/ID/Doku enthält einen Begriff oder dessen Stamm (ein Stamm ist immer Teil des Namens)
        List<Scored> scored = rank(terms, needles(terms), EnumSet.complementOf(EnumSet.of(Kind.PACKAGE,
                Kind.FILE, Kind.EXTERNAL)));
        if (scored.isEmpty()) {
            return "Kein Knoten passt zu " + terms + ". Mit graph_find nach Namensteilen suchen.";
        }
        int seeds = Math.max(1, Math.min(8, maxNodes / 3));
        List<Node> seedNodes = scored.stream().limit(seeds).map(Scored::n).toList();

        // Teilgraph: Seeds + direkte Nachbarn über fachliche Kanten, bevorzugt andere Treffer
        Set<String> scoredIds = new HashSet<>();
        scored.stream().limit(60).forEach(x -> scoredIds.add(x.n().id()));
        Set<String> nodes = new LinkedHashSet<>();
        seedNodes.forEach(n -> nodes.add(n.id()));
        EnumSet<Relation> rels = EnumSet.of(Relation.CALLS, Relation.INSTANTIATES, Relation.EXTENDS,
                Relation.IMPLEMENTS, Relation.OVERRIDES, Relation.HAS_TYPE);
        List<String> scope = new ArrayList<>();
        List<String> typeSeeds = new ArrayList<>();
        for (Node seed : seedNodes) {
            scope.add(seed.id());
            if (seed.kind().isType()) {
                typeSeeds.add(seed.id());
            }
        }
        if (!typeSeeds.isEmpty()) {
            g.edges(typeSeeds, Direction.OUT, CONTAINS).forEach(e -> scope.add(e.to()));
        }
        List<Edge> candidatesEdges = new ArrayList<>(g.edges(scope, Direction.BOTH, rels));
        Set<String> endpoints = new HashSet<>();
        candidatesEdges.forEach(e -> {
            endpoints.add(e.from());
            endpoints.add(e.to());
        });
        Map<String, Node> known = new HashMap<>(g.nodes(endpoints));
        seedNodes.forEach(n -> known.put(n.id(), n));
        candidatesEdges.sort(Comparator.comparingInt((Edge e) -> (scoredIds.contains(e.from()) && scoredIds.contains(e.to())) ? 0 : 1)
                .thenComparingInt(e -> e.rel() == Relation.HAS_TYPE ? 1 : 0) // Aufrufe/Vererbung erklären mehr
                .thenComparingInt(e -> known.get(e.from()) != null && isTestCode(known.get(e.from())) ? 1 : 0)
                .thenComparing(e -> e.conf()).thenComparing(e -> -e.countValue()).thenComparing(e -> e.from() + e.to()));
        List<Edge> shown = new ArrayList<>();
        Set<String> edgeKeys = new HashSet<>();
        for (Edge e : candidatesEdges) {
            if (!edgeKeys.add(e.from() + "|" + e.to() + "|" + e.rel())) {
                continue;
            }
            Node a = known.get(e.from());
            Node b = known.get(e.to());
            if (a == null || b == null || b.kind() == Kind.EXTERNAL) {
                continue;
            }
            Set<String> added = new LinkedHashSet<>(nodes);
            added.add(e.from());
            added.add(e.to());
            if (added.size() > maxNodes) {
                continue;
            }
            nodes.addAll(added);
            shown.add(e);
            if (shown.size() >= maxNodes * 2) {
                break;
            }
        }

        StringBuilder sb = new StringBuilder("Suchbegriffe: ").append(terms).append('\n');
        sb.append("\nBeste Treffer:\n");
        scored.stream().limit(seeds).forEach(x -> sb.append("  ").append(line(x.n()))
                .append(x.n().doc() == null ? "" : "\n      " + x.n().doc()).append('\n'));
        if (scored.size() > seeds) {
            sb.append("  weitere Treffer: ").append(String.join(", ", scored.stream().skip(seeds).limit(12)
                    .map(x -> CodeGraph.shortName(x.n())).toList())).append(scored.size() > seeds + 12 ? " …" : "")
                    .append('\n');
        }
        if (!shown.isEmpty()) {
            sb.append("\nZusammenhang (").append(shown.size()).append(" Kanten):\n");
            for (Edge e : shown) {
                sb.append("  ").append(shortName(known, e.from())).append(" --").append(e.rel().label())
                        .append("--> ").append(shortName(known, e.to())).append(edgeTag(e)).append('\n');
            }
        }
        Map<Integer, Integer> comms = new TreeMap<>();
        for (String id : nodes) {
            Node n = known.get(id);
            if (n != null && n.community() != null) {
                comms.merge(n.community(), 1, Integer::sum);
            }
        }
        if (!comms.isEmpty()) {
            sb.append("\nCommunities: ").append(String.join(", ", comms.keySet().stream().map(c -> {
                Community cm = community(c);
                return "#" + c + (cm == null ? "" : " " + cm.label());
            }).toList())).append('\n');
        }
        sb.append("\nVertiefen: graph_explain <Knoten>, graph_neighbors <Knoten>, graph_path <von> <nach>.");
        return sb.toString();
    }

    /** Bewerteter Treffer einer Stichwortsuche. */
    record Scored(Node n, double score) {
    }

    /** Suchbegriffe samt Wortstämmen – die Nadeln für {@link NodeSearch}. */
    static List<String> needles(List<String> terms) {
        Set<String> needles = new LinkedHashSet<>(terms);
        terms.forEach(t -> {
            String stem = stem(t);
            if (stem != null) {
                needles.add(stem);
            }
        });
        return new ArrayList<>(needles);
    }

    /**
     * Knoten zu Suchbegriffen, bewertet nach Name, Wortstamm, ID und Doku plus Vernetzung; Typen leicht bevorzugt,
     * Testcode abgewertet. Absteigend sortiert.
     */
    List<Scored> rank(List<String> terms, List<String> needles, Set<Kind> kinds) {
        List<Node> candidates = g.search(new NodeSearch(kinds, needles, true, null, null, MAX_CANDIDATES));
        List<Scored> raw = new ArrayList<>();
        for (Node n : candidates) {
            double s = 0;
            Set<String> nameParts = new HashSet<>(terms(n.name()));
            String nameLower = n.name().toLowerCase(Locale.ROOT);
            String idLower = n.id().toLowerCase(Locale.ROOT);
            String doc = n.doc() == null ? "" : n.doc().toLowerCase(Locale.ROOT);
            for (String t : terms) {
                String stem = stem(t);
                if (nameLower.equals(t)) {
                    s += 6;
                } else if (nameParts.contains(t)) {
                    s += 3;
                } else if (nameLower.contains(t)) {
                    s += 2;
                } else if (stem != null && nameParts.stream().anyMatch(part -> stem.equals(stem(part)))) {
                    s += 2; // gebucht ~ buchen, gespeichert ~ speichern
                } else if (idLower.contains(t)) {
                    s += 0.5;
                }
                if (doc.contains(t)) {
                    s += 1;
                }
            }
            if (s > 0) {
                raw.add(new Scored(n, s));
            }
        }
        if (raw.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> degrees = g.degrees(raw.stream().map(x -> x.n().id()).toList());
        List<Scored> scored = new ArrayList<>(raw.size());
        for (Scored x : raw) {
            double s = x.score() + Math.log1p(degrees.getOrDefault(x.n().id(), 0)) * 0.3;
            if (x.n().kind().isType()) {
                s += 0.5;
            }
            if (isTestCode(x.n())) {
                s *= 0.4; // Tests nennen Fachbegriffe oft im Namen, erklären den Ablauf aber selten
            }
            scored.add(new Scored(x.n(), s));
        }
        scored.sort(Comparator.comparingDouble((Scored x) -> -x.score()).thenComparing(x -> x.n().id()));
        return scored;
    }

    /**
     * Sehr einfacher Wortstamm für deutsche und englische Verbformen – genug, um Frageformen auf Methodennamen
     * abzubilden: {@code gebucht}/{@code buchen}/{@code bucht} → {@code buch}, {@code saving}/{@code saved} → {@code sav}.
     *
     * @return Stamm mit mindestens 3 Zeichen oder {@code null}
     */
    static String stem(String word) {
        String w = word.toLowerCase(Locale.ROOT);
        if (w.startsWith("ge") && w.length() > 6 && (w.endsWith("t") || w.endsWith("en"))) {
            w = w.substring(2); // Partizip: gebucht, gespeichert, gelesen
        }
        for (String suffix : List.of("ungen", "ung", "en", "er", "et", "st", "te", "t", "e", "n",
                "ing", "ed", "es", "s")) {
            if (w.endsWith(suffix) && w.length() - suffix.length() >= 3) {
                w = w.substring(0, w.length() - suffix.length());
                break;
            }
        }
        if (w.endsWith("er") && w.length() > 5) {
            w = w.substring(0, w.length() - 2); // speicher(n) / gespeicher(t) → speich
        }
        return w.length() >= 3 ? w : null;
    }

    /** Liegt in einem Testquellordner oder heißt wie eine Testklasse. */
    static boolean isTestCode(Node n) {
        String f = n.file();
        if (f != null && (f.startsWith("src/test/") || f.contains("/src/test/"))) {
            return true;
        }
        String owner = n.kind().isMember() ? CodeGraph.ownerOf(n.id()) : n.id();
        String simple = owner.substring(owner.lastIndexOf('.') + 1);
        return simple.endsWith("Test") || simple.endsWith("Tests") || simple.endsWith("IT");
    }

    /** Suchbegriffe: CamelCase und Trennzeichen aufteilen, klein, ohne Stoppwörter, min. 3 Zeichen. */
    static List<String> terms(String text) {
        if (text == null) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String word : text.split("\\s+")) {
            String w = word.replaceAll("[^\\p{L}\\p{N}_#.]", "");
            if (w.length() >= 3 && !STOP_WORDS.contains(w.toLowerCase(Locale.ROOT))) {
                String whole = w.replaceAll("[#.].*", "").toLowerCase(Locale.ROOT);
                if (whole.length() >= 3) {
                    out.add(whole);
                }
            }
            for (String part : CAMEL.split(word)) {
                String p = part.toLowerCase(Locale.ROOT);
                if (p.length() >= 3 && !STOP_WORDS.contains(p)) {
                    out.add(p);
                }
            }
        }
        return new ArrayList<>(out);
    }

    // ------------------------------------------------------------------ Hilfen für Tools

    static Set<Relation> relations(List<String> names, Set<Relation> fallback) {
        if (names == null || names.isEmpty()) {
            return fallback;
        }
        EnumSet<Relation> out = EnumSet.noneOf(Relation.class);
        for (String n : names) {
            String key = n.strip().toUpperCase(Locale.ROOT).replace('-', '_');
            if (key.equals("ALL") || key.equals("*")) {
                return EnumSet.allOf(Relation.class);
            }
            try {
                out.add(Relation.valueOf(key));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unbekannte Relation '" + n + "'. Erlaubt: "
                        + EnumSet.allOf(Relation.class).stream().map(Relation::label).toList() + " oder all.");
            }
        }
        return out;
    }

    static Kind kind(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return Kind.valueOf(name.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unbekannte Knotenart '" + name + "'. Erlaubt: "
                    + EnumSet.allOf(Kind.class).stream().map(Kind::label).toList());
        }
    }
}
