package systems.grebe.devtools.mcp.modules.graph;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Kind;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Node;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Relation;

/** Tools des Graph-Moduls: Code-Graph bauen ({@code devtools-fileinfo.graph}) und abfragen. */
public class GraphTools {

    private static final String PROJECT_PARAM = "Projektname (Ordnername) oder Pfad; leer = Standardprojekt";
    private static final String NODE_PARAM = "Knoten: Typ ('OrderService' oder FQN), Member ('OrderService#save', "
            + "'OrderService#save(Order)', Konstruktor 'OrderService#<init>') oder Dateipfad";
    private static final String REL_PARAM = "Relationen: calls, instantiates, extends, implements, overrides, has_type, "
            + "annotated_with, imports, contains oder all";

    private static final Set<Relation> DEFAULT_NEIGHBOR_RELATIONS = EnumSet.of(Relation.CALLS, Relation.INSTANTIATES,
            Relation.EXTENDS, Relation.IMPLEMENTS, Relation.OVERRIDES, Relation.HAS_TYPE, Relation.ANNOTATED_WITH);
    private static final Set<Relation> DEFAULT_PATH_RELATIONS = EnumSet.of(Relation.CALLS, Relation.INSTANTIATES,
            Relation.EXTENDS, Relation.IMPLEMENTS, Relation.OVERRIDES, Relation.HAS_TYPE, Relation.CONTAINS);

    private final GraphService service;

    GraphTools(GraphService service) {
        this.service = service;
    }

    @Tool(name = "build", description = "Baut den Code-Graphen eines Java-Projekts (tree-sitter-AST, lokal, ohne LLM): "
            + "Pakete, Dateien, Klassen/Interfaces/Enums/Records, Methoden, Konstruktoren, Felder, Imports, Vererbung, "
            + "Überschreibungen, Aufrufgraph und Communities. Schreibt alles in eine Datei 'devtools-fileinfo.graph' im "
            + "Projektwurzelverzeichnis. Baut nur neu, wenn sich Quelldateien geändert haben (SHA-256), außer force=true. "
            + "Liefert danach den Bericht wie graph_report." + ShellHints.GRAPH)
    public String build(
            @ToolParam(required = false, description = PROJECT_PARAM) String project,
            @ToolParam(required = false, description = "true = immer komplett neu bauen") Boolean force) {
        GraphService.BuildResult r = service.build(project, Boolean.TRUE.equals(force));
        StringBuilder sb = new StringBuilder();
        if (r.rebuilt()) {
            sb.append("Graph gebaut in ").append(r.duration().toMillis()).append(" ms → ")
                    .append(GraphStore.fileFor(r.root()));
            if (r.changedFiles() + r.addedFiles() + r.removedFiles() > 0) {
                sb.append(" (geändert ").append(r.changedFiles()).append(", neu ").append(r.addedFiles())
                        .append(", entfernt ").append(r.removedFiles()).append(')');
            }
        } else {
            sb.append("Graph ist aktuell (keine Quelldatei geändert) → ").append(GraphStore.fileFor(r.root()));
        }
        return sb.append("\n\n").append(new GraphQueries(r.graph()).report(10)).toString();
    }

    @Tool(name = "report", description = "Überblick über den Code-Graphen: Größe, God Nodes (meistverbundene Typen), "
            + "meistaufgerufene Methoden, Communities, überraschende Verbindungen zwischen Paketen und Anzahl unsicherer "
            + "Kanten. Vor Architekturfragen zu einem Projekt aufrufen. Fehlt der Graph, wird er gebaut."
            + ShellHints.GRAPH)
    public String report(
            @ToolParam(required = false, description = PROJECT_PARAM) String project,
            @ToolParam(required = false, description = "Einträge je Abschnitt (Standard 10)") Integer top) {
        return new GraphQueries(service.graph(project)).report(top == null ? 10 : Math.max(1, Math.min(top, 50)));
    }

    @Tool(name = "find", description = "Sucht Knoten im Code-Graphen nach Namen (exakt vor Präfix vor Teilstring, '*' als "
            + "Platzhalter), optional nach Art gefiltert. Liefert IDs für graph_explain/graph_neighbors/graph_path."
            + " Statt `grep -r 'class Foo'` oder `find -name` verwenden." + ShellHints.GRAPH)
    public String find(
            @ToolParam(required = false, description = PROJECT_PARAM) String project,
            @ToolParam(description = "Name oder Teil davon, z.B. 'OrderService', 'save', '*Dao'") String name,
            @ToolParam(required = false, description = "Art: class, interface, enum, record, annotation, method, "
                    + "constructor, field, file, external") String kind,
            @ToolParam(required = false, description = "Max. Treffer (Standard 30)") Integer limit) {
        CodeGraph g = service.graph(project);
        GraphQueries q = new GraphQueries(g);
        Kind k = GraphQueries.kind(kind);
        int max = limit == null ? 30 : Math.max(1, Math.min(limit, 200));
        List<Node> hits = q.find(name, k, max);
        if (hits.isEmpty()) {
            return "Keine Treffer für '" + name + "'" + (k == null ? "" : " (Art " + k.label() + ")") + ".";
        }
        StringBuilder sb = new StringBuilder(hits.size() + " Treffer:\n");
        hits.forEach(n -> sb.append(q.line(n)).append('\n'));
        return sb.toString().stripTrailing();
    }

    @Tool(name = "explain", description = "Erklärt einen Knoten des Code-Graphen: Art, Ort, Signatur, Javadoc, Community, "
            + "enthaltene Member und alle ein- und ausgehenden Beziehungen (Aufrufe, Vererbung, Überschreibungen, "
            + "Typverwendungen) mit Sicherheit (EXTRACTED/INFERRED/AMBIGUOUS) und Zeilennummer." + ShellHints.GRAPH)
    public String explain(
            @ToolParam(required = false, description = PROJECT_PARAM) String project,
            @ToolParam(description = NODE_PARAM) String node,
            @ToolParam(required = false, description = "Max. Einträge je Relation (Standard 25)") Integer limit) {
        GraphQueries q = new GraphQueries(service.graph(project));
        return q.explain(q.resolve(node), limit == null ? 25 : Math.max(1, Math.min(limit, 200)));
    }

    @Tool(name = "neighbors", description = "Durchläuft den Code-Graphen ab einem Knoten als Baum – z.B. wer eine Methode "
            + "aufruft (direction=in, relations=[calls], depth=3 für die Aufrufkette) oder was sie aufruft "
            + "(direction=out). Auch für Implementierungen eines Interfaces (direction=in, relations=[implements]). "
            + "Statt mit `grep` nach Aufrufstellen zu suchen verwenden." + ShellHints.GRAPH)
    public String neighbors(
            @ToolParam(required = false, description = PROJECT_PARAM) String project,
            @ToolParam(description = NODE_PARAM) String node,
            @ToolParam(required = false, description = "in = eingehend (wer nutzt mich), out = ausgehend, both; Standard out")
            String direction,
            @ToolParam(required = false, description = REL_PARAM + "; Standard alle außer contains/imports")
            List<String> relations,
            @ToolParam(required = false, description = "Tiefe (Standard 1, max. 6)") Integer depth,
            @ToolParam(required = false, description = "Max. Einträge (Standard 80)") Integer limit) {
        GraphQueries q = new GraphQueries(service.graph(project));
        GraphQueries.Direction dir = switch (direction == null ? "out" : direction.strip().toLowerCase()) {
            case "in", "incoming", "callers" -> GraphQueries.Direction.IN;
            case "both", "all" -> GraphQueries.Direction.BOTH;
            case "out", "outgoing", "callees", "" -> GraphQueries.Direction.OUT;
            default -> throw new IllegalArgumentException("direction muss in, out oder both sein.");
        };
        return q.neighbors(q.resolve(node), dir, GraphQueries.relations(relations, DEFAULT_NEIGHBOR_RELATIONS),
                depth == null ? 1 : Math.max(1, Math.min(depth, 6)), limit == null ? 80 : Math.max(1, Math.min(limit, 500)));
    }

    @Tool(name = "path", description = "Kürzester Weg zwischen zwei Knoten des Code-Graphen (z.B. wie ein Controller "
            + "bei einer DAO-Methode ankommt), mit Relation und Sicherheit je Schritt. directed=true folgt nur der "
            + "Pfeilrichtung (Aufrufkette), Standard beide Richtungen." + ShellHints.GRAPH)
    public String path(
            @ToolParam(required = false, description = PROJECT_PARAM) String project,
            @ToolParam(description = "Startknoten – " + NODE_PARAM) String from,
            @ToolParam(description = "Zielknoten – " + NODE_PARAM) String to,
            @ToolParam(required = false, description = "true = nur in Pfeilrichtung (z.B. echte Aufrufkette)") Boolean directed,
            @ToolParam(required = false, description = REL_PARAM + "; Standard alle außer imports/annotated_with")
            List<String> relations,
            @ToolParam(required = false, description = "Max. Schritte (Standard 8)") Integer maxDepth) {
        GraphQueries q = new GraphQueries(service.graph(project));
        return q.path(q.resolve(from), q.resolve(to), GraphQueries.relations(relations, DEFAULT_PATH_RELATIONS),
                Boolean.TRUE.equals(directed), maxDepth == null ? 8 : Math.max(1, Math.min(maxDepth, 20)));
    }

    @Tool(name = "query", description = "Beantwortet eine Frage zum Code über den Graphen: zerlegt sie in Suchbegriffe "
            + "(auch CamelCase), findet die passendsten Typen/Methoden (Name, Javadoc, Vernetzung) und liefert den "
            + "Teilgraphen, der sie verbindet. Einstieg für 'Wo/wie wird X gemacht?' – statt Dateien per `grep` zu "
            + "durchsuchen; danach mit graph_explain vertiefen." + ShellHints.GRAPH)
    public String query(
            @ToolParam(required = false, description = PROJECT_PARAM) String project,
            @ToolParam(description = "Frage oder Stichworte, z.B. 'Wie wird ein Beleg gebucht?' oder 'BelegBuchung save'")
            String question,
            @ToolParam(required = false, description = "Max. Knoten im Teilgraphen (Standard 25)") Integer maxNodes) {
        return new GraphQueries(service.graph(project)).query(question,
                maxNodes == null ? 25 : Math.max(3, Math.min(maxNodes, 100)));
    }
}
