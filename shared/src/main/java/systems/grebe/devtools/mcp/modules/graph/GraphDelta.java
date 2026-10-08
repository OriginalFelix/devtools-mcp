package systems.grebe.devtools.mcp.modules.graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Community;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Edge;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.FileEntry;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.GraphFile;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Node;

/**
 * Unterschied zwischen zwei Ständen eines Code-Graphen – für das inkrementelle Speichern nach einem Commit: statt den
 * ganzen Graphen neu zu schreiben, wendet die Ablage nur diese Änderungen auf den gespeicherten Stand an
 * ({@link GraphProvider#update}). Kanten sind über (von, nach, Relation) identifiziert, Knoten über ihre ID.
 *
 * <p>Ein Knoten, dessen Art sich geändert hat (z.B. Klasse → Interface), steht unter {@code removedNodes} und
 * {@code addedNodes}; seine Kanten stehen dann vollständig unter {@code addedEdges}, weil das Entfernen sie mitnimmt.
 *
 * @param files        neue oder geänderte Quelldateien
 * @param changedNodes vorhandene Knoten mit geänderten Eigenschaften (gleiche Art)
 * @param changedEdges vorhandene Kanten mit geänderten Eigenschaften (Sicherheit, Score, Anzahl, Zeile)
 * @param header       Kopfdaten des neuen Stands (Commit, Statistik, Communities …) ohne Knoten und Kanten
 */
public record GraphDelta(List<FileEntry> files, List<String> removedFiles, List<Node> addedNodes,
                         List<Node> changedNodes, List<String> removedNodes, List<Edge> addedEdges,
                         List<Edge> changedEdges, List<Edge> removedEdges, Header header) {

    /** Kopfdaten des neuen Stands. */
    public record Header(String commit, String builtAt, String generator, int version, long files, long nodes,
                         long edges, Map<String, Object> stats, List<Community> communities) {

        static Header of(GraphFile g) {
            return new Header(g.commit(), g.builtAt(), g.generator(), g.version(), g.files().size(), g.nodes().size(),
                    g.edges().size(), g.stats(), g.communities() == null ? List.of() : g.communities());
        }
    }

    /** Anzahl der Änderungen (Dateien, Knoten, Kanten). */
    public int size() {
        return files.size() + removedFiles.size() + addedNodes.size() + changedNodes.size() + removedNodes.size()
                + addedEdges.size() + changedEdges.size() + removedEdges.size();
    }

    /** Schlüssel einer Kante: von, nach, Relation. */
    public static String edgeKey(Edge e) {
        return e.from() + '\u0000' + e.to() + '\u0000' + e.rel().name();
    }

    /** Änderungen von {@code old} nach {@code next}. */
    public static GraphDelta between(GraphFile old, GraphFile next) {
        Map<String, FileEntry> oldFiles = new HashMap<>();
        old.files().forEach(f -> oldFiles.put(f.path(), f));
        List<FileEntry> files = new ArrayList<>();
        Set<String> nextFiles = new HashSet<>();
        for (FileEntry f : next.files()) {
            nextFiles.add(f.path());
            if (!f.equals(oldFiles.get(f.path()))) {
                files.add(f);
            }
        }
        List<String> removedFiles = old.files().stream().map(FileEntry::path).filter(p -> !nextFiles.contains(p))
                .toList();

        Map<String, Node> oldNodes = new HashMap<>();
        old.nodes().forEach(n -> oldNodes.put(n.id(), n));
        Set<String> nextNodeIds = new HashSet<>();
        Set<String> recreated = new HashSet<>();
        List<Node> added = new ArrayList<>();
        List<Node> changed = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        for (Node n : next.nodes()) {
            nextNodeIds.add(n.id());
            Node o = oldNodes.get(n.id());
            if (o == null) {
                added.add(n);
            } else if (o.kind() != n.kind()) {
                removed.add(n.id());
                added.add(n);
                recreated.add(n.id());
            } else if (!o.equals(n)) {
                changed.add(n);
            }
        }
        old.nodes().stream().map(Node::id).filter(id -> !nextNodeIds.contains(id)).forEach(removed::add);

        Map<String, Edge> oldEdges = new HashMap<>();
        old.edges().forEach(e -> oldEdges.put(edgeKey(e), e));
        Set<String> nextEdgeKeys = new HashSet<>();
        List<Edge> addedEdges = new ArrayList<>();
        List<Edge> changedEdges = new ArrayList<>();
        for (Edge e : next.edges()) {
            String k = edgeKey(e);
            nextEdgeKeys.add(k);
            Edge o = oldEdges.get(k);
            if (o == null || recreated.contains(e.from()) || recreated.contains(e.to())) {
                addedEdges.add(e); // neu – oder ein Ende wird neu angelegt, dann ist die Kante mit weg
            } else if (!Objects.equals(o, e)) {
                changedEdges.add(e);
            }
        }
        List<Edge> removedEdges = old.edges().stream().filter(e -> !nextEdgeKeys.contains(edgeKey(e))).toList();
        return new GraphDelta(files, removedFiles, added, changed, removed, addedEdges, changedEdges, removedEdges,
                Header.of(next));
    }
}
