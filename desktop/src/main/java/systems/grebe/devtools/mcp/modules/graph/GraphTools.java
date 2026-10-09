package systems.grebe.devtools.mcp.modules.graph;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.ToolHints;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Kind;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Node;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.Relation;
import systems.grebe.devtools.mcp.modules.graph.GraphReader.Direction;
import systems.grebe.devtools.mcp.modules.graph.GraphProvider.Key;
import systems.grebe.devtools.mcp.modules.graph.GraphProvider.Stored;

/** Tools des Graph-Moduls: Code-Graph je Projekt und Branch bauen (Graph-Storage des Backends oder Datei) und abfragen. */
@ToolHints(readOnly = true, openWorld = false)
public class GraphTools {

    private static final String PROJECT_PARAM = "Projektname (Ordnername) oder Pfad; Worktree als <projekt>/<ordner> "
            + "oder Pfad darin (eigener Branch); leer = Standardprojekt";
    private static final String BRANCH_PARAM = "Git-Branch; leer = ausgecheckter Branch. Andere Branches nur, wenn "
            + "ihr Graph gespeichert ist (graph_branches)";
    private static final String NODE_PARAM = "Knoten: Typ ('OrderService' oder FQN), Member ('OrderService#save', "
            + "'OrderService#save(Order)', Konstruktor 'OrderService#<init>') oder Dateipfad";
    private static final String REL_PARAM = "Relationen: calls, instantiates, extends, implements, overrides, has_type, "
            + "annotated_with, imports, contains oder all";

    private static final Set<Relation> DEFAULT_NEIGHBOR_RELATIONS = EnumSet.of(Relation.CALLS, Relation.INSTANTIATES,
            Relation.EXTENDS, Relation.IMPLEMENTS, Relation.OVERRIDES, Relation.HAS_TYPE, Relation.ANNOTATED_WITH);
    private static final Set<Relation> DEFAULT_PATH_RELATIONS = EnumSet.of(Relation.CALLS, Relation.INSTANTIATES,
            Relation.EXTENDS, Relation.IMPLEMENTS, Relation.OVERRIDES, Relation.HAS_TYPE, Relation.CONTAINS);

    /** Schreibende Cypher-Klauseln – zusätzlich zur lesenden Abfrage der Datenbank, für eine verständliche Meldung. */
    private static final Pattern WRITE_CLAUSE = Pattern.compile(
            "\\b(CREATE|MERGE|DELETE|DETACH|SET|REMOVE|DROP|LOAD\\s+CSV|FOREACH)\\b|\\bCALL\\s+(db|dbms|apoc)\\.(?!labels|"
                    + "relationshipTypes|propertyKeys|schema)", Pattern.CASE_INSENSITIVE);

    private final GraphService service;

    GraphTools(GraphService service) {
        this.service = service;
    }

    /**
     * Graph zum Abfragen; fehlt der des ausgecheckten Branches, wird er gebaut und {@link GraphService.Opened#note()}
     * meldet das in der Ausgabe.
     */
    private GraphService.Opened open(String project, String branch) {
        return service.open(project, branch);
    }

    @ToolHints(destructive = false, idempotent = true, openWorld = false)
    @Tool(name = "build", description = "Baut den Code-Graphen eines Java-Projekts für den ausgecheckten Git-Branch "
            + "(tree-sitter-AST, lokal, ohne LLM): Pakete, Dateien, Klassen/Interfaces/Enums/Records, Methoden, "
            + "Konstruktoren, Felder, Imports, Vererbung, Überschreibungen, Aufrufgraph und Communities. Speichert ihn "
            + "je Projekt und Branch (Standard: Graph-Datenbank des Backends). Baut nur neu, wenn sich Quelldateien geändert haben "
            + "(SHA-256), außer force=true; entfernt Graphen von Branches, die es in Git nicht mehr gibt. Liefert danach "
            + "den Bericht wie graph_report." + ShellHints.GRAPH)
    public String build(
            @ToolParam(required = false, description = PROJECT_PARAM) String project,
            @ToolParam(required = false, description = "true = immer komplett neu bauen") Boolean force) {
        GraphService.BuildResult r = service.build(project, null, Boolean.TRUE.equals(force),
                systems.grebe.devtools.mcp.core.ModuleAction.Progress.NONE);
        StringBuilder sb = new StringBuilder();
        String where = r.graph().info().location();
        if (r.rebuilt()) {
            sb.append("Graph gebaut in ").append(r.duration().toMillis()).append(" ms (Branch ")
                    .append(r.key().branchLabel()).append(") → ").append(where);
            if (r.changedFiles() + r.addedFiles() + r.removedFiles() > 0) {
                sb.append(" (geändert ").append(r.changedFiles()).append(", neu ").append(r.addedFiles())
                        .append(", entfernt ").append(r.removedFiles()).append(')');
            }
            if (r.mode() != null) {
                sb.append("\n").append(r.mode().substring(0, 1).toUpperCase()).append(r.mode().substring(1));
            }
        } else {
            sb.append("Graph ist aktuell (keine Quelldatei geändert, Branch ").append(r.key().branchLabel())
                    .append(") → ").append(where);
        }
        if (!r.removedBranches().isEmpty()) {
            sb.append("\nEntfernt (Branch existiert nicht mehr): ").append(String.join(", ", r.removedBranches()));
        }
        return sb.append("\n\n").append(new GraphQueries(r.graph()).report(10)).toString();
    }

    @Tool(name = "branches", description = "Listet die gespeicherten Code-Graphen eines Projekts je Git-Branch "
            + "(Commit, Stand, Größe) und den ausgecheckten Branch. Für Vergleiche zwischen Branches die anderen "
            + "graph_*-Tools mit branch aufrufen." + ShellHints.GRAPH)
    public String branches(@ToolParam(required = false, description = PROJECT_PARAM) String project) {
        Key current = service.key(project, null);
        List<Stored> stored = service.storage().branches(current);
        StringBuilder sb = new StringBuilder("Projekt ").append(current.project()).append(" (").append(current.root())
                .append("), ausgecheckt: ").append(current.branchLabel()).append(", Ablage: ")
                .append(service.storage().describe()).append('\n');
        if (stored.isEmpty()) {
            return sb.append("Noch kein Graph gespeichert – graph_build aufrufen.").toString();
        }
        for (Stored s : stored) {
            String b = s.branch() == null ? "(ohne Git)" : s.branch();
            sb.append("- ").append(b).append(b.equals(current.branchLabel()) ? " *" : "")
                    .append(s.commit() == null ? "" : " @ " + s.commit()).append(" · gebaut ").append(s.builtAt())
                    .append(" · ").append(s.files()).append(" Dateien, ").append(s.nodes()).append(" Knoten, ")
                    .append(s.edges()).append(" Kanten\n");
        }
        return sb.toString().stripTrailing();
    }

    @Tool(name = "report", description = "Überblick über den Code-Graphen: Größe, God Nodes (meistverbundene Typen), "
            + "meistaufgerufene Methoden, Communities, überraschende Verbindungen zwischen Paketen und Anzahl unsicherer "
            + "Kanten. Vor Architekturfragen zu einem Projekt aufrufen. Fehlt der Graph des ausgecheckten Branches, wird "
            + "er gebaut." + ShellHints.GRAPH)
    public String report(
            @ToolParam(required = false, description = PROJECT_PARAM) String project,
            @ToolParam(required = false, description = "Einträge je Abschnitt (Standard 10)") Integer top,
            @ToolParam(required = false, description = BRANCH_PARAM) String branch) {
        GraphService.Opened g = open(project, branch);
        return g.note() + new GraphQueries(g.reader()).report(top == null ? 10 : Math.max(1, Math.min(top, 50)));
    }

    @Tool(name = "find", description = "Sucht Knoten im Code-Graphen nach Namen (exakt vor Präfix vor Teilstring, '*' als "
            + "Platzhalter), optional nach Art gefiltert. Liefert IDs für graph_explain/graph_neighbors/graph_path."
            + " Statt `grep -r 'class Foo'` oder `find -name` verwenden." + ShellHints.GRAPH)
    public String find(
            @ToolParam(required = false, description = PROJECT_PARAM) String project,
            @ToolParam(description = "Name oder Teil davon, z.B. 'OrderService', 'save', '*Dao'") String name,
            @ToolParam(required = false, description = "Art: class, interface, enum, record, annotation, method, "
                    + "constructor, field, file, external") String kind,
            @ToolParam(required = false, description = "Max. Treffer (Standard 30)") Integer limit,
            @ToolParam(required = false, description = BRANCH_PARAM) String branch) {
        GraphService.Opened g = open(project, branch);
        GraphQueries q = new GraphQueries(g.reader());
        Kind k = GraphQueries.kind(kind);
        int max = limit == null ? 30 : Math.max(1, Math.min(limit, 200));
        List<Node> hits = q.find(name, k, max);
        if (hits.isEmpty()) {
            return g.note() + "Keine Treffer für '" + name + "'" + (k == null ? "" : " (Art " + k.label() + ")") + ".";
        }
        StringBuilder sb = new StringBuilder(g.note()).append(hits.size()).append(" Treffer:\n");
        hits.forEach(n -> sb.append(q.line(n)).append('\n'));
        return sb.toString().stripTrailing();
    }

    @Tool(name = "files", description = "Findet Dateien über den Code-Graphen und liefert je Datei Pfad, Länge und die "
            + "passenden Typen/Methoden mit Zeilenbereich (Z von-bis) – kompakt statt Trefferzeilen. Zwei Arten: "
            + "query = Name ('OrderService'), Stichworte ('Auftrag speichern', CamelCase/Wortstämme/Javadoc), "
            + "Namensmuster ('*Dao') oder Pfad/Pfadmuster ('shop/repo/*.java'); related = Knoten, deren verbundene "
            + "Dateien gesucht sind (direction=in: wer verwendet/ruft/implementiert ihn, out: was er verwendet) mit "
            + "Begründung je Datei. Danach mit graph_read nur die nötigen Stellen lesen. Statt `find`, `grep -rl`, "
            + "Glob oder dem Öffnen vieler Dateien verwenden. Fehlt der Graph des Projekts, wird er automatisch gebaut "
            + "– vorher kein graph_build nötig." + ShellHints.GRAPH)
    public String files(
            @ToolParam(required = false, description = PROJECT_PARAM) String project,
            @ToolParam(required = false, description = "Suchtext: Name, Stichworte, '*'-Muster oder Pfad(muster); "
                    + "alternativ related") String query,
            @ToolParam(required = false, description = "Knoten, deren verbundene Dateien gesucht sind – " + NODE_PARAM
                    + "; bei Typen/Dateien zählen alle Member") List<String> related,
            @ToolParam(required = false, description = "Nur mit related: in = wer verwendet den Knoten, out = was er "
                    + "verwendet, both (Standard)") String direction,
            @ToolParam(required = false, description = "Nur mit related: " + REL_PARAM + "; Standard alle außer contains")
            List<String> relations,
            @ToolParam(required = false, description = "Nur mit related: Tiefe (Standard 1, max. 3)") Integer depth,
            @ToolParam(required = false, description = "false = Testdateien ausblenden (Standard true)") Boolean tests,
            @ToolParam(required = false, description = "Max. Dateien (Standard 20)") Integer limit,
            @ToolParam(required = false, description = BRANCH_PARAM) String branch) {
        boolean hasQuery = query != null && !query.isBlank();
        boolean hasRelated = related != null && related.stream().anyMatch(r -> r != null && !r.isBlank());
        if (hasQuery == hasRelated) {
            throw new IllegalArgumentException("Genau eins angeben: query (Name, Stichworte, Muster, Pfad) oder related "
                    + "(Knoten, deren verbundene Dateien gesucht sind).");
        }
        GraphService.Opened g = open(project, branch);
        GraphFiles f = new GraphFiles(g.reader());
        boolean includeTests = !Boolean.FALSE.equals(tests);
        int max = limit == null ? 20 : Math.max(1, Math.min(limit, 200));
        if (hasQuery) {
            return g.note() + f.search(query, includeTests, max);
        }
        return g.note() + f.related(related, direction(direction, Direction.BOTH),
                GraphQueries.relations(relations, GraphFiles.DEFAULT_RELATIONS),
                depth == null ? 1 : Math.max(1, Math.min(depth, 3)), includeTests, max);
    }

    @Tool(name = "read", description = "Liest Quelltext gezielt über den Code-Graphen statt ganzer Dateien: eine "
            + "Methode/ein Feld/einen Konstruktor (alle Überladungen bei 'Typ#methode'), einen Typ oder eine Datei. "
            + "Typen und Dateien über " + GraphSource.AUTO_OUTLINE_LINES + " Zeilen kommen als Gliederung (Typen und "
            + "Member mit Signatur, Javadoc-Satz und Zeilenbereich, ohne Rümpfe); outline=true/false erzwingt das. "
            + "lines='von-bis' liest einen Bereich der Datei des Knotens. Ausgabe mit Zeilennummern. Mehrere Knoten in "
            + "einem Aufruf möglich. Liest das Arbeitsverzeichnis (ausgecheckter Branch). Statt `cat`/`sed -n` oder dem "
            + "vollständigen Lesen großer Dateien verwenden. Fehlt der Graph des Projekts, wird er automatisch gebaut."
            + ShellHints.GRAPH)
    public String read(
            @ToolParam(required = false, description = PROJECT_PARAM) String project,
            @ToolParam(description = "Ein oder mehrere Knoten – " + NODE_PARAM + ", z.B. ['OrderService#save', "
                    + "'OrderRepository'] oder ['OrderService.java']") List<String> node,
            @ToolParam(required = false, description = "Zeilenbereich in der Datei des (einen) Knotens: 'von-bis', "
                    + "'von+anzahl', 'von-' (bis Ende) oder 'von'") String lines,
            @ToolParam(required = false, description = "true = nur Gliederung, false = immer Quelltext; Standard nach "
                    + "Länge") Boolean outline,
            @ToolParam(required = false, description = "Zusätzliche Zeilen vor und nach dem Knoten (Standard 0, z.B. 3 "
                    + "für Javadoc/Annotationen)") Integer context,
            @ToolParam(required = false, description = "Max. Quelltextzeilen insgesamt (Standard 400, max. 3000)")
            Integer maxLines) {
        GraphService.Opened g = open(project, null);
        return g.note() + new GraphSource(g.reader(), service.key(project, null).path()).read(node, lines, outline,
                context == null ? 0 : Math.max(0, Math.min(context, 50)),
                maxLines == null ? 400 : Math.max(1, Math.min(maxLines, 3000)));
    }

    private static Direction direction(String direction, Direction fallback) {
        return switch (direction == null || direction.isBlank() ? "" : direction.strip().toLowerCase(Locale.ROOT)) {
            case "" -> fallback;
            case "in", "incoming", "callers" -> Direction.IN;
            case "both", "all" -> Direction.BOTH;
            case "out", "outgoing", "callees" -> Direction.OUT;
            default -> throw new IllegalArgumentException("direction muss in, out oder both sein.");
        };
    }

    @Tool(name = "explain", description = "Erklärt einen Knoten des Code-Graphen: Art, Ort, Signatur, Javadoc, Community, "
            + "enthaltene Member und alle ein- und ausgehenden Beziehungen (Aufrufe, Vererbung, Überschreibungen, "
            + "Typverwendungen) mit Sicherheit (EXTRACTED/INFERRED/AMBIGUOUS) und Zeilennummer." + ShellHints.GRAPH)
    public String explain(
            @ToolParam(required = false, description = PROJECT_PARAM) String project,
            @ToolParam(description = NODE_PARAM) String node,
            @ToolParam(required = false, description = "Max. Einträge je Relation (Standard 25)") Integer limit,
            @ToolParam(required = false, description = BRANCH_PARAM) String branch) {
        GraphService.Opened g = open(project, branch);
        GraphQueries q = new GraphQueries(g.reader());
        return g.note() + q.explain(q.resolve(node), limit == null ? 25 : Math.max(1, Math.min(limit, 200)));
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
            @ToolParam(required = false, description = "Max. Einträge (Standard 80)") Integer limit,
            @ToolParam(required = false, description = BRANCH_PARAM) String branch) {
        Direction dir = direction(direction, Direction.OUT);
        Set<Relation> rels = GraphQueries.relations(relations, DEFAULT_NEIGHBOR_RELATIONS);
        GraphService.Opened g = open(project, branch);
        GraphQueries q = new GraphQueries(g.reader());
        return g.note() + q.neighbors(q.resolve(node), dir, rels, depth == null ? 1 : Math.max(1, Math.min(depth, 6)),
                limit == null ? 80 : Math.max(1, Math.min(limit, 500)));
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
            @ToolParam(required = false, description = "Max. Schritte (Standard 8)") Integer maxDepth,
            @ToolParam(required = false, description = BRANCH_PARAM) String branch) {
        GraphService.Opened g = open(project, branch);
        GraphQueries q = new GraphQueries(g.reader());
        return g.note() + q.path(q.resolve(from), q.resolve(to), GraphQueries.relations(relations, DEFAULT_PATH_RELATIONS),
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
            @ToolParam(required = false, description = "Max. Knoten im Teilgraphen (Standard 25)") Integer maxNodes,
            @ToolParam(required = false, description = BRANCH_PARAM) String branch) {
        GraphService.Opened g = open(project, branch);
        return g.note() + new GraphQueries(g.reader()).query(question, maxNodes == null ? 25 : Math.max(3, Math.min(maxNodes, 100)));
    }

    @Tool(name = "cypher", description = "Lesende OpenCypher-Abfrage direkt auf dem Code-Graphen in der Graph-Datenbank "
            + "(ArcadeDB) – für Fragen, die die anderen graph_*-Tools nicht abdecken (Zählungen, Muster, Metriken). $g ist "
            + "bereits auf den Graphen von Projekt+Branch gesetzt und muss in jedem MATCH stehen. Modell: Knotentypen Class, "
            + "Interface, Enum, Record, Annotation (erben von Type), Constructor, Method, Field (erben von Member), Package, "
            + "File, External – alle erben von CodeNode {g, uid=g+'|'+id, id, kind, name, file, line, endLine, modifiers, "
            + "signature, doc, community, t=Typ-ID}; Kanten :CALLS|INSTANTIATES|EXTENDS|IMPLEMENTS|OVERRIDES|HAS_TYPE|"
            + "ANNOTATED_WITH|IMPORTS|CONTAINS {conf (null=EXTRACTED), score, count, line}; (:SourceFile {g, path, sha256, "
            + "lines, parseErrors}). Beispiel: MATCH (m:Method {g:$g})<-[c:CALLS]-() RETURN m.id, sum(coalesce(c.count,1)) "
            + "AS n ORDER BY n DESC LIMIT 10. Nur lesend; nur mit Datenbank-Ablage." + ShellHints.GRAPH)
    public String cypher(
            @ToolParam(required = false, description = PROJECT_PARAM) String project,
            @ToolParam(description = "Cypher (nur lesend), muss $g verwenden") String query,
            @ToolParam(required = false, description = "Weitere Parameter als Objekt, z.B. {\"name\": \"save\"}")
            Map<String, Object> params,
            @ToolParam(required = false, description = "Max. Zeilen (Standard 100, max. 1000)") Integer limit,
            @ToolParam(required = false, description = BRANCH_PARAM) String branch) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query fehlt.");
        }
        if (WRITE_CLAUSE.matcher(query).find()) {
            throw new IllegalArgumentException("graph_cypher ist nur lesend – schreibende Klauseln (CREATE, MERGE, SET, "
                    + "DELETE, REMOVE, …) sind nicht erlaubt. Den Graphen ändert nur graph_build.");
        }
        if (!query.contains("$g")) {
            throw new IllegalArgumentException("Die Abfrage muss den Graphen über $g eingrenzen, z.B. "
                    + "MATCH (n:CodeNode {g: $g}) – sonst liefe sie über alle Projekte und Branches.");
        }
        int max = limit == null ? 100 : Math.max(1, Math.min(limit, 1000));
        GraphReader.QueryResult r = service.graph(project, branch).query(query, params, max);
        StringBuilder sb = new StringBuilder();
        sb.append(r.rows().size()).append(r.truncated() ? "+" : "").append(" Zeile(n) · ")
                .append(String.join(" | ", r.columns())).append('\n');
        for (List<Object> row : r.rows()) {
            sb.append(String.join(" | ", row.stream().map(String::valueOf).toList())).append('\n');
        }
        if (r.truncated()) {
            sb.append("… abgeschnitten nach ").append(max).append(" Zeilen (LIMIT in der Abfrage oder limit erhöhen)\n");
        }
        return sb.toString().stripTrailing();
    }
}
