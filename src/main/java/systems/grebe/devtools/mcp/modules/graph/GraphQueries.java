package systems.grebe.devtools.mcp.modules.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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

/** Abfragen auf einem geladenen {@link CodeGraph}; Ergebnisse als kompakter Text für das LLM. */
final class GraphQueries {

    enum Direction { OUT, IN, BOTH }

    private static final Pattern CAMEL = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])|[^A-Za-z0-9]+");
    private static final Set<String> STOP_WORDS = Set.of("the", "a", "an", "and", "or", "of", "to", "in", "is", "are",
            "what", "which", "who", "how", "where", "does", "do", "with", "from", "for", "by", "on", "der", "die",
            "das", "und", "oder", "wie", "wo", "was", "wer", "welche", "welcher", "wird", "werden", "von", "mit", "im",
            "den", "dem", "des", "ein", "eine", "ist", "sind", "zu", "auf", "fuer", "für", "aus", "nach", "class",
            "klasse", "methode", "method");

    private final CodeGraph g;

    GraphQueries(CodeGraph g) {
        this.g = g;
    }

    // ------------------------------------------------------------------ Knoten finden

    /**
     * Löst eine Knotenangabe auf: exakte ID, {@code Typ}, {@code Typ#methode}, {@code Typ.methode}, {@code Typ#methode(int)}
     * oder ein Dateipfad – jeweils auch mit einfachem Typnamen.
     *
     * @throws IllegalArgumentException mit Kandidatenliste, wenn nichts oder mehrere Knoten passen
     */
    Node resolve(String spec) {
        List<Node> hits = candidates(spec);
        if (hits.size() == 1) {
            return hits.getFirst();
        }
        if (hits.isEmpty()) {
            List<Node> similar = find(spec.replaceAll("[#(].*", "").replaceAll(".*\\.", ""), null, 8);
            throw new IllegalArgumentException("Kein Knoten '" + spec + "' im Graphen."
                    + (similar.isEmpty() ? " Mit graph_find nach dem Namen suchen." : " Ähnlich: "
                    + String.join(", ", similar.stream().map(Node::id).toList()) + ". Genaue ID aus graph_find übernehmen."));
        }
        List<String> ids = hits.stream().limit(15).map(n -> n.id() + " [" + n.kind().label() + "]").toList();
        throw new IllegalArgumentException("'" + spec + "' ist mehrdeutig (" + hits.size() + " Treffer): "
                + String.join(", ", ids) + (hits.size() > 15 ? " …" : "") + ". Genaue ID angeben.");
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
                    && types(noParams).isEmpty() && !types(noParams.substring(0, dot)).isEmpty()) {
                typePart = noParams.substring(0, dot);
                memberPart = spec.substring(dot + 1);
            } else {
                typePart = spec;
                memberPart = null;
            }
        }
        List<Node> owners = types(typePart);
        if (memberPart == null) {
            if (!owners.isEmpty()) {
                return owners;
            }
            // Dateiname ohne Pfad
            List<Node> files = g.nodes().stream().filter(n -> n.kind() == Kind.FILE
                    && (n.name().endsWith("/" + spec) || n.name().equals(spec))).toList();
            return files;
        }
        String name = memberPart.replaceAll("\\(.*", "");
        String params = memberPart.contains("(") ? memberPart.substring(memberPart.indexOf('(')).replace(" ", "") : null;
        List<Node> out = new ArrayList<>();
        for (Node owner : owners) {
            for (Edge e : g.outgoing(owner.id())) {
                if (e.rel() != Relation.CONTAINS) {
                    continue;
                }
                Node m = g.node(e.to());
                if (m == null || !m.kind().isMember()) {
                    continue;
                }
                String memberKey = m.id().substring(m.id().indexOf('#') + 1);
                boolean nameMatch = m.name().equals(name) || (name.equals("<init>") && m.kind() == Kind.CONSTRUCTOR);
                if (nameMatch && (params == null || memberKey.endsWith(params))) {
                    out.add(m);
                }
            }
        }
        return out;
    }

    /** Typen mit diesem FQN oder einfachem Namen (auch {@code Outer.Inner}). */
    private List<Node> types(String name) {
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

    /** Suche nach Namen: exakt vor Präfix vor Teilstring; {@code *} als Platzhalter. */
    List<Node> find(String query, Kind kind, int limit) {
        String q = query == null ? "" : query.strip();
        if (q.isEmpty()) {
            return List.of();
        }
        String[] glob = q.contains("*") ? q.toLowerCase(Locale.ROOT).split("\\*", -1) : null;
        String lower = q.toLowerCase(Locale.ROOT);
        record Hit(Node n, int score) {
        }
        List<Hit> hits = new ArrayList<>();
        for (Node n : g.nodes()) {
            if (kind != null && n.kind() != kind) {
                continue;
            }
            if (kind == null && (n.kind() == Kind.PACKAGE)) {
                continue;
            }
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
        // Grad erst für die Treffer berechnen – bei vielen Knoten wäre das je Knoten zu teuer
        List<Hit> ranked = new ArrayList<>(hits.size());
        for (Hit h : hits) {
            ranked.add(new Hit(h.n(), h.score() * 100000 + Math.min(99999, g.degree(h.n().id()))));
        }
        ranked.sort(Comparator.comparingInt((Hit h) -> -h.score()).thenComparing(h -> h.n().id()));
        return ranked.stream().limit(limit).map(Hit::n).toList();
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

    private static String edgeTag(Edge e) {
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

    // ------------------------------------------------------------------ Bericht

    String report(int topN) {
        StringBuilder sb = new StringBuilder();
        var d = g.data();
        sb.append("# Code-Graph ").append(d.project()).append("\n");
        sb.append("Gebaut ").append(d.builtAt()).append(" · ").append(d.root()).append('/').append(GraphStore.FILE_NAME)
                .append('\n');
        Map<String, Object> s = d.stats();
        sb.append(s.get("files")).append(" Dateien, ").append(s.get("nodes")).append(" Knoten, ")
                .append(s.get("edges")).append(" Kanten, ").append(s.get("communities")).append(" Communities\n");
        sb.append("Knoten: ").append(s.get("nodesByKind")).append('\n');
        sb.append("Kanten: ").append(s.get("edgesByRelation")).append('\n');
        sb.append("Sicherheit: ").append(s.get("edgesByConfidence"))
                .append(" (EXTRACTED = steht im Code, INFERRED = abgeleitet, AMBIGUOUS = mehrere mögliche Ziele)\n");
        Object errors = s.get("filesWithParseErrors");
        if (errors instanceof Number num && num.longValue() > 0) {
            List<String> broken = d.files().stream().filter(f -> Boolean.TRUE.equals(f.parseErrors()))
                    .map(CodeGraph.FileEntry::path).limit(5).toList();
            sb.append("Achtung: ").append(errors).append(" Datei(en) mit Syntax- oder Lesefehlern – dort fehlen evtl. ")
                    .append("Kanten: ").append(String.join(", ", broken)).append(num.longValue() > 5 ? " …" : "")
                    .append('\n');
        }

        sb.append("\n## God Nodes (meistverbundene Typen)\n");
        typeDegrees().entrySet().stream().limit(topN).forEach(e -> {
            Node n = g.node(e.getKey());
            sb.append("- ").append(CodeGraph.shortName(n)).append(" – ").append(e.getValue()).append(" Kanten, ")
                    .append(n.location()).append(n.doc() == null ? "" : " – " + n.doc()).append('\n');
        });

        sb.append("\n## Meistaufgerufene Methoden\n");
        Map<String, Integer> called = new HashMap<>();
        for (Edge e : g.edges()) {
            if (e.rel() == Relation.CALLS) {
                called.merge(e.to(), e.countValue(), Integer::sum);
            }
        }
        called.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey())).limit(topN)
                .forEach(e -> sb.append("- ").append(CodeGraph.shortName(g.node(e.getKey()))).append(" – ")
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
        long ambiguous = g.edges().stream().filter(e -> e.conf() == Confidence.AMBIGUOUS).count();
        long inferred = g.edges().stream().filter(e -> e.conf() == Confidence.INFERRED).count();
        sb.append("- ").append(ambiguous).append(" AMBIGUOUS, ").append(inferred)
                .append(" INFERRED – bei Zweifel im Quelltext an der angegebenen Zeile nachsehen.\n");

        sb.append("\n## Nächste Schritte\n")
                .append("- graph_explain <Typ|Typ#methode> – Details und alle Beziehungen eines Knotens\n")
                .append("- graph_neighbors <Knoten> direction=in relations=[calls] depth=2 – wer ruft das auf\n")
                .append("- graph_path <von> <nach> – wie hängen zwei Stellen zusammen\n")
                .append("- graph_query \"Frage\" – relevanten Teilgraphen zu Stichworten\n");
        return sb.toString().stripTrailing();
    }

    /** Typen nach Grad (Kanten ihrer Member auf den Typ hochgezählt, ohne contains/imports). */
    private LinkedHashMap<String, Integer> typeDegrees() {
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
        LinkedHashMap<String, Integer> out = new LinkedHashMap<>();
        deg.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .forEach(e -> out.put(e.getKey(), e.getValue()));
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

    private List<String> surprising(int limit) {
        record Link(String a, String b, int weight, Edge sample) {
        }
        Map<String, Link> links = new HashMap<>();
        for (Edge e : g.edges()) {
            if (e.rel() != Relation.CALLS && e.rel() != Relation.INSTANTIATES && e.rel() != Relation.HAS_TYPE) {
                continue;
            }
            String ta = typeOf(e.from());
            String tb = typeOf(e.to());
            if (ta == null || tb == null) {
                continue;
            }
            Node na = g.node(ta);
            Node nb = g.node(tb);
            if (na.community() == null || nb.community() == null || na.community().equals(nb.community())) {
                continue;
            }
            String pa = pkg(ta);
            String pb = pkg(tb);
            if (pa.equals(pb) || commonPrefix(pa, pb) >= Math.min(depth(pa), depth(pb)) - 1) {
                continue; // benachbarte Pakete sind nicht überraschend
            }
            String key = ta + "→" + tb;
            Link old = links.get(key);
            links.put(key, new Link(ta, tb, (old == null ? 0 : old.weight()) + e.countValue(), old == null ? e : old.sample()));
        }
        // selten ist überraschend: Paare, die genau einmal verbunden sind, zwischen großen Communities
        return links.values().stream()
                .sorted(Comparator.comparingInt((Link l) -> l.weight())
                        .thenComparing(l -> -sizeOf(g.node(l.a()).community()) - sizeOf(g.node(l.b()).community()))
                        .thenComparing(Link::a))
                .limit(limit)
                .map(l -> CodeGraph.shortName(g.node(l.sample().from())) + " --" + l.sample().rel().label() + "--> "
                        + CodeGraph.shortName(g.node(l.sample().to())) + "  (Community #" + g.node(l.a()).community()
                        + " → #" + g.node(l.b()).community() + ", " + g.node(l.sample().from()).location() + ")")
                .toList();
    }

    private int sizeOf(Integer community) {
        Community c = g.community(community);
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
        Community c = g.community(n.community());
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
        if (n.kind().isType() || n.kind() == Kind.FILE || n.kind() == Kind.PACKAGE) {
            List<Node> children = new ArrayList<>();
            for (Edge e : g.outgoing(n.id())) {
                if (e.rel() == Relation.CONTAINS) {
                    children.add(g.node(e.to()));
                }
            }
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
        appendRelations(sb, "Ausgehend", g.outgoing(n.id()), true, maxPerRelation);
        appendRelations(sb, "Eingehend", g.incoming(n.id()), false, maxPerRelation);
        if (n.kind().isType()) {
            // Aufrufe von außen auf Member dieses Typs zusammenfassen
            Map<String, Integer> callersByType = new TreeMap<>();
            int total = 0;
            for (Edge e : g.outgoing(n.id())) {
                if (e.rel() != Relation.CONTAINS) {
                    continue;
                }
                for (Edge in : g.incoming(e.to())) {
                    if (in.rel() == Relation.CALLS || in.rel() == Relation.OVERRIDES) {
                        String t = typeOf(in.from());
                        if (t != null && !t.equals(n.id())) {
                            callersByType.merge(CodeGraph.shortName(g.node(t)), in.countValue(), Integer::sum);
                            total += in.countValue();
                        }
                    }
                }
            }
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

    private void appendRelations(StringBuilder sb, String title, List<Edge> edges, boolean outgoing, int max) {
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
                Node other = g.node(outgoing ? e.to() : e.from());
                sb.append("    ").append(other == null ? (outgoing ? e.to() : e.from()) : describeShort(other))
                        .append(edgeTag(e)).append('\n');
            });
            if (list.size() > max) {
                sb.append("    … ").append(list.size() - max).append(" weitere\n");
            }
        });
    }

    private String describeShort(Node n) {
        String loc = n.file() == null ? "" : "  " + n.location();
        return (n.kind() == Kind.EXTERNAL ? n.id() + " [extern]" : n.id()) + loc;
    }

    // ------------------------------------------------------------------ Nachbarn / Aufrufbäume

    String neighbors(Node start, Direction dir, Set<Relation> relations, int depth, int limit) {
        StringBuilder sb = new StringBuilder(line(start)).append('\n');
        String arrow = dir == Direction.IN ? "<--" : dir == Direction.OUT ? "-->" : "---";
        Set<String> seen = new HashSet<>();
        seen.add(start.id());
        int[] printed = {0};
        walkTree(sb, start.id(), dir, relations, depth, 1, seen, printed, limit, arrow);
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
                          Set<String> seen, int[] printed, int limit, String arrow) {
        if (level > maxDepth) {
            return;
        }
        List<Edge> edges = new ArrayList<>();
        if (dir != Direction.IN) {
            g.outgoing(id).stream().filter(e -> relations.contains(e.rel())).forEach(edges::add);
        }
        if (dir != Direction.OUT) {
            g.incoming(id).stream().filter(e -> relations.contains(e.rel())).forEach(edges::add);
        }
        edges.sort(Comparator.comparing((Edge e) -> e.rel()).thenComparing(e -> e.from().equals(id) ? e.to() : e.from()));
        for (Edge e : edges) {
            if (printed[0] >= limit) {
                return;
            }
            String other = e.from().equals(id) ? e.to() : e.from();
            boolean repeat = !seen.add(other);
            Node n = g.node(other);
            sb.append("  ".repeat(level)).append(e.from().equals(id) ? "-->" : "<--").append(' ')
                    .append(e.rel().label()).append(' ')
                    .append(n == null ? other : describeShort(n)).append(edgeTag(e))
                    .append(repeat ? "  (siehe oben)" : "").append('\n');
            printed[0]++;
            if (!repeat) {
                walkTree(sb, other, dir, relations, maxDepth, level + 1, seen, printed, limit, arrow);
            }
        }
    }

    // ------------------------------------------------------------------ Pfad

    String path(Node from, Node to, Set<Relation> relations, boolean directed, int maxDepth) {
        Map<String, Edge> via = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        Map<String, Integer> dist = new HashMap<>();
        queue.add(from.id());
        dist.put(from.id(), 0);
        while (!queue.isEmpty()) {
            String cur = queue.poll();
            if (cur.equals(to.id())) {
                break;
            }
            int d = dist.get(cur);
            if (d >= maxDepth) {
                continue;
            }
            List<Edge> next = new ArrayList<>();
            g.outgoing(cur).stream().filter(e -> relations.contains(e.rel())).forEach(next::add);
            if (!directed) {
                g.incoming(cur).stream().filter(e -> relations.contains(e.rel())).forEach(next::add);
            }
            next.sort(Comparator.comparing((Edge e) -> e.conf()).thenComparing(e -> e.from().equals(cur) ? e.to() : e.from()));
            for (Edge e : next) {
                String other = e.from().equals(cur) ? e.to() : e.from();
                if (!dist.containsKey(other)) {
                    Node on = g.node(other);
                    // nicht über externe Typen oder Pakete abkürzen (java.lang.String verbindet sonst alles)
                    if (on != null && (on.kind() == Kind.EXTERNAL || on.kind() == Kind.PACKAGE) && !other.equals(to.id())) {
                        continue;
                    }
                    dist.put(other, d + 1);
                    via.put(other, e);
                    queue.add(other);
                }
            }
        }
        if (!dist.containsKey(to.id())) {
            return "Kein Pfad von " + from.id() + " nach " + to.id() + " (max. " + maxDepth + " Schritte, "
                    + (directed ? "nur in Pfeilrichtung" : "beide Richtungen") + ", Relationen "
                    + relations.stream().map(Relation::label).toList() + ")."
                    + (directed ? " Mit directed=false auch rückwärts suchen." : "");
        }
        List<Edge> steps = new ArrayList<>();
        for (String cur = to.id(); !cur.equals(from.id()); ) {
            Edge e = via.get(cur);
            steps.addFirst(e);
            cur = e.from().equals(cur) ? e.to() : e.from();
        }
        StringBuilder sb = new StringBuilder("Pfad (").append(steps.size()).append(" Schritte):\n");
        String cur = from.id();
        sb.append("  ").append(describeShort(from)).append('\n');
        for (Edge e : steps) {
            boolean forward = e.from().equals(cur);
            String other = forward ? e.to() : e.from();
            sb.append("  ").append(forward ? "--" + e.rel().label() + "-->" : "<--" + e.rel().label() + "--")
                    .append(' ').append(describeShort(g.node(other))).append(edgeTag(e)).append('\n');
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
        record Scored(Node n, double score) {
        }
        List<Scored> scored = new ArrayList<>();
        for (Node n : g.nodes()) {
            if (n.kind() == Kind.PACKAGE || n.kind() == Kind.FILE || n.kind() == Kind.EXTERNAL) {
                continue;
            }
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
                s += Math.log1p(g.degree(n.id())) * 0.3;
                if (n.kind().isType()) {
                    s += 0.5;
                }
                if (isTestCode(n)) {
                    s *= 0.4; // Tests nennen Fachbegriffe oft im Namen, erklären den Ablauf aber selten
                }
                scored.add(new Scored(n, s));
            }
        }
        if (scored.isEmpty()) {
            return "Kein Knoten passt zu " + terms + ". Mit graph_find nach Namensteilen suchen.";
        }
        scored.sort(Comparator.comparingDouble((Scored x) -> -x.score()).thenComparing(x -> x.n().id()));
        int seeds = Math.max(1, Math.min(8, maxNodes / 3));
        List<Node> seedNodes = scored.stream().limit(seeds).map(Scored::n).toList();

        // Teilgraph: Seeds + direkte Nachbarn über fachliche Kanten, bevorzugt andere Treffer
        Set<String> scoredIds = new HashSet<>();
        scored.stream().limit(60).forEach(x -> scoredIds.add(x.n().id()));
        Set<String> nodes = new LinkedHashSet<>();
        seedNodes.forEach(n -> nodes.add(n.id()));
        EnumSet<Relation> rels = EnumSet.of(Relation.CALLS, Relation.INSTANTIATES, Relation.EXTENDS,
                Relation.IMPLEMENTS, Relation.OVERRIDES, Relation.HAS_TYPE);
        List<Edge> candidates = new ArrayList<>();
        for (Node seed : seedNodes) {
            List<String> scope = new ArrayList<>(List.of(seed.id()));
            if (seed.kind().isType()) {
                for (Edge e : g.outgoing(seed.id())) {
                    if (e.rel() == Relation.CONTAINS) {
                        scope.add(e.to());
                    }
                }
            }
            for (String id : scope) {
                g.outgoing(id).stream().filter(e -> rels.contains(e.rel())).forEach(candidates::add);
                g.incoming(id).stream().filter(e -> rels.contains(e.rel())).forEach(candidates::add);
            }
        }
        candidates.sort(Comparator.comparingInt((Edge e) -> (scoredIds.contains(e.from()) && scoredIds.contains(e.to())) ? 0 : 1)
                .thenComparingInt(e -> e.rel() == Relation.HAS_TYPE ? 1 : 0) // Aufrufe/Vererbung erklären mehr
                .thenComparingInt(e -> isTestCode(g.node(e.from())) ? 1 : 0)
                .thenComparing(e -> e.conf()).thenComparing(e -> -e.countValue()).thenComparing(e -> e.from() + e.to()));
        List<Edge> shown = new ArrayList<>();
        Set<String> edgeKeys = new HashSet<>();
        for (Edge e : candidates) {
            if (!edgeKeys.add(e.from() + "|" + e.to() + "|" + e.rel())) {
                continue;
            }
            Node a = g.node(e.from());
            Node b = g.node(e.to());
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
                sb.append("  ").append(CodeGraph.shortName(g.node(e.from()))).append(" --").append(e.rel().label())
                        .append("--> ").append(CodeGraph.shortName(g.node(e.to()))).append(edgeTag(e)).append('\n');
            }
        }
        Map<Integer, Integer> comms = new TreeMap<>();
        for (String id : nodes) {
            Node n = g.node(id);
            if (n != null && n.community() != null) {
                comms.merge(n.community(), 1, Integer::sum);
            }
        }
        if (!comms.isEmpty()) {
            sb.append("\nCommunities: ").append(String.join(", ", comms.keySet().stream().map(c -> {
                Community cm = g.community(c);
                return "#" + c + (cm == null ? "" : " " + cm.label());
            }).toList())).append('\n');
        }
        sb.append("\nVertiefen: graph_explain <Knoten>, graph_neighbors <Knoten>, graph_path <von> <nach>.");
        return sb.toString();
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
