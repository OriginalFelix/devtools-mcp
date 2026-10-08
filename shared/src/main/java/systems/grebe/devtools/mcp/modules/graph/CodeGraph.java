package systems.grebe.devtools.mcp.modules.graph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Code-Graph eines Projekts: Knoten (Pakete, Dateien, Typen, Member, externe Typen) und gerichtete Kanten.
 *
 * <p>Jede Kante trägt, wie sicher sie ist: {@link Confidence#EXTRACTED} steht so im Quelltext,
 * {@link Confidence#INFERRED} ist eine Ableitung (z.B. über den Rückgabetyp einer Aufrufkette),
 * {@link Confidence#AMBIGUOUS} hat mehrere mögliche Ziele (z.B. Überladungen gleicher Stelligkeit).
 */
public final class CodeGraph {

    public static final String FORMAT = "devtools-fileinfo-graph";
    public static final int VERSION = 2;

    public enum Kind {
        PACKAGE, FILE, CLASS, INTERFACE, ENUM, RECORD, ANNOTATION, CONSTRUCTOR, METHOD, FIELD, EXTERNAL;

        public boolean isType() {
            return this == CLASS || this == INTERFACE || this == ENUM || this == RECORD || this == ANNOTATION;
        }

        public boolean isMember() {
            return this == CONSTRUCTOR || this == METHOD || this == FIELD;
        }

        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public enum Relation {
        CONTAINS, IMPORTS, EXTENDS, IMPLEMENTS, OVERRIDES, CALLS, INSTANTIATES, HAS_TYPE, ANNOTATED_WITH;

        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public enum Confidence { EXTRACTED, INFERRED, AMBIGUOUS }

    /** Knoten. {@code community} gilt für Typen und Member (vom Typ geerbt). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Node(String id, Kind kind, String name, String file, Integer line, Integer endLine, String modifiers,
                String signature, String doc, Integer community) {

        public Node withCommunity(Integer c) {
            return new Node(id, kind, name, file, line, endLine, modifiers, signature, doc, c);
        }

        public String location() {
            return file == null ? "" : file + (line == null ? "" : ":" + line);
        }
    }

    /** Gerichtete Kante; {@code score} fehlt bei 1.0, {@code count} (Anzahl Fundstellen) fehlt bei 1. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Edge(String from, String to, Relation rel, Confidence conf, Double score, Integer count, Integer line) {

        public double scoreValue() {
            return score == null ? 1.0 : score;
        }

        public int countValue() {
            return count == null ? 1 : count;
        }
    }

    /** Eingelesene Quelldatei mit SHA-256 – daran erkennt {@code graph_build}, ob neu gebaut werden muss. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FileEntry(String path, String sha256, int lines, Boolean parseErrors) {
    }

    public record Community(int id, String label, int size, List<String> top) {
    }

    /** Inhalt eines gespeicherten Graphen; {@code branch}/{@code commit} fehlen außerhalb von Git. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GraphFile(String format, int version, String project, String root, String branch, String commit,
                     String builtAt, String generator, Map<String, Object> stats, List<FileEntry> files,
                     List<Community> communities, List<Node> nodes, List<Edge> edges) {

        public GraphFile withBranch(String newBranch, String newCommit) {
            return new GraphFile(format, version, project, root, newBranch, newCommit, builtAt, generator, stats, files,
                    communities, nodes, edges);
        }
    }

    private final GraphFile data;
    private final Map<String, Node> byId = new HashMap<>();
    private final Map<String, List<Edge>> out = new HashMap<>();
    private final Map<String, List<Edge>> in = new HashMap<>();
    private final Map<Integer, Community> communities = new HashMap<>();

    public CodeGraph(GraphFile data) {
        this.data = data;
        for (Node n : data.nodes()) {
            byId.put(n.id(), n);
        }
        for (Edge e : data.edges()) {
            out.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e);
            in.computeIfAbsent(e.to(), k -> new ArrayList<>()).add(e);
        }
        if (data.communities() != null) {
            data.communities().forEach(c -> communities.put(c.id(), c));
        }
    }

    public GraphFile data() {
        return data;
    }

    public List<Node> nodes() {
        return data.nodes();
    }

    public List<Edge> edges() {
        return data.edges();
    }

    public Node node(String id) {
        return byId.get(id);
    }

    public List<Edge> outgoing(String id) {
        return out.getOrDefault(id, Collections.emptyList());
    }

    public List<Edge> incoming(String id) {
        return in.getOrDefault(id, Collections.emptyList());
    }

    /** Anzahl Kanten ohne {@code contains}. */
    public int degree(String id) {
        int d = 0;
        for (Edge e : outgoing(id)) {
            if (e.rel() != Relation.CONTAINS) {
                d++;
            }
        }
        for (Edge e : incoming(id)) {
            if (e.rel() != Relation.CONTAINS) {
                d++;
            }
        }
        return d;
    }

    public Community community(Integer id) {
        return id == null ? null : communities.get(id);
    }

    /** Besitzer eines Members (Typ-ID vor dem '#'). */
    public static String ownerOf(String memberId) {
        int i = memberId.indexOf('#');
        return i < 0 ? memberId : memberId.substring(0, i);
    }

    /** Kurzname für Ausgaben: Typen einfach, Member als {@code Typ#name(…)}. */
    public static String shortName(Node n) {
        if (n.kind().isMember()) {
            String owner = ownerOf(n.id());
            String simpleOwner = owner.substring(owner.lastIndexOf('.') + 1);
            return simpleOwner + n.id().substring(n.id().indexOf('#'));
        }
        if (n.kind() == Kind.FILE || n.kind() == Kind.PACKAGE) {
            return n.name();
        }
        return n.id().substring(n.id().lastIndexOf('.') + 1);
    }
}
