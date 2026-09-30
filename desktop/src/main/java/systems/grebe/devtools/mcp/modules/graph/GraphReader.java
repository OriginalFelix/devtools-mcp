package systems.grebe.devtools.mcp.modules.graph;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Community;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Edge;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Kind;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Node;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Relation;

/**
 * Lesezugriff auf einen gespeicherten Code-Graphen (ein Projekt, ein Branch). {@link GraphQueries} formuliert alle
 * Abfragen über diese Schnittstelle: {@link Neo4jGraphReader} beantwortet sie mit Cypher direkt in der Datenbank,
 * {@link MemoryGraphReader} auf dem geladenen Graphen der Datei-Ablage.
 *
 * <p>Alle Methoden liefern nur Knoten und Kanten dieses einen Graphen.
 */
interface GraphReader {

    enum Direction { OUT, IN, BOTH }

    /** Kopfdaten: Projekt, Branch, Stand, Statistik und Communities. */
    GraphInfo info();

    /** Knoten mit genau dieser ID oder {@code null}. */
    Node node(String id);

    /** Vorhandene Knoten zu den IDs (fehlende fehlen in der Map). */
    Map<String, Node> nodes(Collection<String> ids);

    /**
     * Kanten an den Knoten.
     *
     * @param rels {@code null} = alle Relationen
     */
    List<Edge> edges(Collection<String> ids, Direction dir, Set<Relation> rels);

    default List<Edge> edges(String id, Direction dir, Set<Relation> rels) {
        return edges(List.of(id), dir, rels);
    }

    /** Anzahl Kanten ohne {@code contains} je Knoten. */
    Map<String, Integer> degrees(Collection<String> ids);

    /**
     * Typen, deren ID gleich {@code name} ist oder auf {@code "." + name} endet (auch {@code Outer.Inner}); gibt es
     * keinen Projekttyp, die passenden externen Typen.
     */
    List<Node> typesNamed(String name);

    /** Dateiknoten, deren Pfad {@code name} ist oder auf {@code "/" + name} endet. */
    List<Node> filesNamed(String name);

    /** Knoten nach Namen/ID/Doku, siehe {@link NodeSearch}. */
    List<Node> search(NodeSearch search);

    /** Typen nach Grad (Kanten ihrer Member auf den Typ hochgezählt, ohne contains/imports), absteigend. */
    List<Map.Entry<String, Integer>> topTypes(int limit);

    /** Meistaufgerufene Knoten (Summe der Aufrufstellen eingehender {@code calls}), absteigend. */
    List<Map.Entry<String, Integer>> mostCalled(int limit);

    /**
     * Verbindungen ({@code calls}, {@code instantiates}, {@code has_type}) zwischen Typen verschiedener Communities,
     * je Typpaar zusammengefasst.
     */
    List<TypeLink> typeLinks();

    /** Aufrufe/Überschreibungen von Membern des Typs aus anderen Typen: Typ-ID → Anzahl Stellen. */
    Map<String, Integer> callersByType(String typeId);

    /** Dateien mit Syntax- oder Lesefehlern. */
    List<String> parseErrorFiles(int limit);

    /**
     * Kürzester Weg (ohne Abkürzung über externe Typen und Pakete). Gibt es mehrere gleich lange, gilt der
     * schrittweise kleinste nach {@link #stepOrder(String)} – so liefern alle Ablagen denselben Weg.
     *
     * @return Kanten in Wegreihenfolge oder {@code null}, wenn es keinen Weg gibt
     */
    List<Edge> shortestPath(String from, String to, Set<Relation> rels, boolean directed, int maxDepth);

    /** Reihenfolge der Schritte ab {@code from}: sichere Kanten zuerst, dann Zielknoten-ID, dann Relation. */
    static java.util.Comparator<Edge> stepOrder(String from) {
        return java.util.Comparator.comparing(Edge::conf)
                .thenComparing(e -> e.from().equals(from) ? e.to() : e.from())
                .thenComparing(e -> e.rel().name())
                .thenComparing(e -> e.from().equals(from) ? 0 : 1);
    }

    /** Kleinster Weg nach {@link #stepOrder(String)} unter gleich langen Kandidaten. */
    static List<Edge> smallest(Collection<List<Edge>> paths, String from) {
        List<Edge> best = null;
        for (List<Edge> p : paths) {
            if (best == null || compare(p, best, from) < 0) {
                best = p;
            }
        }
        return best;
    }

    private static int compare(List<Edge> a, List<Edge> b, String from) {
        String cur = from;
        for (int i = 0; i < Math.min(a.size(), b.size()); i++) {
            int c = stepOrder(cur).compare(a.get(i), b.get(i));
            if (c != 0) {
                return c;
            }
            cur = a.get(i).from().equals(cur) ? a.get(i).to() : a.get(i).from();
        }
        return Integer.compare(a.size(), b.size());
    }

    /** Kopfdaten eines gespeicherten Graphen. */
    record GraphInfo(String project, String branch, String commit, String root, String builtAt, String generator,
                     Map<String, Object> stats, List<Community> communities, String location) {

        long stat(String key) {
            return stats != null && stats.get(key) instanceof Number n ? n.longValue() : 0;
        }
    }

    /**
     * Knotensuche. Treffer: Art passt ({@code kinds}, {@code null} = alle) und
     * <ul>
     *   <li>ein Begriff aus {@code needles} ist im kleingeschriebenen Namen oder in der ID enthalten
     *       ({@code withDoc}: auch in der Doku), oder</li>
     *   <li>{@code nameRegex} passt auf den ganzen kleingeschriebenen Namen bzw. {@code idRegex} auf die ganze
     *       kleingeschriebene ID.</li>
     * </ul>
     */
    record NodeSearch(Set<Kind> kinds, List<String> needles, boolean withDoc, String nameRegex, String idRegex,
                      int limit) {
    }

    /** Verbindung zweier Typen ({@code a} → {@code b}); {@code sample} = kleinste Kante nach (von, nach). */
    record TypeLink(String a, String b, int weight, Edge sample) {
    }
}
