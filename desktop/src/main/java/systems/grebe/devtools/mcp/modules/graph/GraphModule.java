package systems.grebe.devtools.mcp.modules.graph;

import java.util.List;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.ConnectionTestResult;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.core.ModuleAction;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolModule;
import systems.grebe.devtools.mcp.core.Workspaces;

/**
 * Code-Graph für Java-Projekte, angelehnt an den AST-Durchlauf von graphify: tree-sitter liest Klassen, Methoden,
 * Imports und den Aufrufgraphen lokal und deterministisch. Gespeichert wird je Projekt und Git-Branch – standardmäßig
 * in Neo4j (Spring Data Neo4j), wahlweise als {@code devtools-fileinfo*.graph} im Projekt – und über die
 * {@code graph_*}-Tools abgefragt.
 */
@Component
public class GraphModule implements ToolModule {

    static final String PROJECTS = "projects";
    static final String DEFAULT_PROJECT = "defaultProject";
    static final String EXCLUDES = "excludes";
    static final String INCLUDE_TESTS = "includeTests";
    static final String MAX_FILES = "maxFiles";
    static final String STORAGE = "storage";
    static final String STORAGE_NEO4J = "neo4j";
    static final String STORAGE_FILE = "file";
    static final String NEO4J_URI = "neo4jUri";
    static final String NEO4J_USER = "neo4jUser";
    static final String NEO4J_PASSWORD = "neo4jPassword";
    static final String NEO4J_DATABASE = "neo4jDatabase";

    @Override
    public String id() {
        return "graph";
    }

    @Override
    public String displayName() {
        return "Code-Graph (Java)";
    }

    @Override
    public String description() {
        return "Baut per tree-sitter einen Graphen aus Klassen, Methoden, Imports, Vererbung und Aufrufen – je Projekt "
                + "und Git-Branch in Neo4j (oder als Datei im Projekt) – und beantwortet Struktur- und Aufruffragen "
                + "daraus per Cypher.";
    }

    @Override
    public String instructions() {
        return """
                Für Struktur- und Architekturfragen zu freigegebenen Java-Projekten den Code-Graphen verwenden, statt \
                Dateien mit `grep`, `find` oder vielen Einzel-Reads zu durchsuchen:
                - `graph_report`: Überblick (God Nodes, Communities, überraschende Verbindungen) – vor Architekturfragen.
                - `graph_query`: Frage in Stichworten → passende Typen/Methoden und ihr Zusammenhang.
                - `graph_find`: Klassen/Methoden nach Namen (statt `grep -r "class Foo"`).
                - `graph_explain`: alles zu einem Knoten; `graph_neighbors` (direction=in, relations=[calls]): wer ruft das \
                auf (statt `grep` nach Aufrufstellen); `graph_path`: wie hängen zwei Stellen zusammen.
                - `graph_build`: nach größeren Änderungen; baut nur neu, wenn sich Quelldateien geändert haben.
                - Graphen gibt es je Git-Branch: ohne `branch` gilt der ausgecheckte; `graph_branches` listet die \
                gespeicherten, mit `branch` lassen sich andere abfragen (z.B. Vergleich mit master).
                - `graph_cypher`: lesendes Cypher für alles, was die übrigen Tools nicht abdecken (nur Neo4j-Ablage).
                Kanten sind als EXTRACTED (steht im Code), INFERRED (abgeleitet) oder AMBIGUOUS (mehrere Ziele) markiert – \
                bei INFERRED/AMBIGUOUS die angegebene Zeile im Quelltext prüfen, bevor darauf eine Aussage beruht.""";
    }

    @Override
    public List<ConfigField> configSchema() {
        return List.of(
                ConfigField.of(PROJECTS, "Projekte", FieldType.DIRECTORY_LIST).asRequired()
                        .withHelp("Projektverzeichnisse oder Sammelordner (Unterordner mit build.gradle(.kts), pom.xml, "
                                + ".git oder src werden übernommen). Je Projekt und Git-Branch gibt es einen Graphen."),
                ConfigField.of(STORAGE, "Ablage", FieldType.ENUM).withDefault(STORAGE_NEO4J)
                        .withOptions(STORAGE_NEO4J, STORAGE_FILE)
                        .withHelp("neo4j = Neo4j-Datenbank (Abfragen per Cypher, graph_cypher), file = Datei "
                                + "devtools-fileinfo@<branch>.graph im Projekt."),
                ConfigField.of(NEO4J_URI, "Neo4j-URI", FieldType.STRING).withDefault("bolt://localhost:7687")
                        .withHelp("bolt:// oder neo4j:// (Cluster). Gilt sofort."),
                ConfigField.of(NEO4J_USER, "Neo4j-Benutzer", FieldType.STRING).withDefault("neo4j"),
                ConfigField.of(NEO4J_PASSWORD, "Neo4j-Passwort", FieldType.SECRET)
                        .withHelp("Wird verschlüsselt gespeichert. Leer = ohne Anmeldung."),
                ConfigField.of(NEO4J_DATABASE, "Neo4j-Datenbank", FieldType.STRING)
                        .withHelp("Leer = Standarddatenbank des Servers (Community Edition: nur eine)."),
                ConfigField.of(DEFAULT_PROJECT, "Standardprojekt", FieldType.STRING)
                        .withHelp("Ordnername des Projekts, das ohne Angabe verwendet wird."),
                ConfigField.of(EXCLUDES, "Ausschlüsse", FieldType.STRING_LIST)
                        .withDefault("build\ntarget\nout\nbin\nnode_modules")
                        .withHelp("Eine Zeile je Eintrag: Ordnername (außerhalb von src/, z.B. Build-Ausgaben), relativer "
                                + "Pfad (z.B. src/gen) oder Dateimuster (*.gen.java). Versteckte Ordner (.git, .gradle …) "
                                + "sind immer ausgeschlossen."),
                ConfigField.of(INCLUDE_TESTS, "Tests einbeziehen", FieldType.BOOLEAN).withDefault("true")
                        .withHelp("Aus = Ordner src/test/… werden übersprungen."),
                ConfigField.of(MAX_FILES, "Max. Dateien", FieldType.INT).withDefault("30000")
                        .withHelp("Schutz gegen versehentlich riesige Verzeichnisse."));
    }

    @Override
    public List<ToolCallback> createTools(ModuleConfig config) {
        return List.of(ToolCallbacks.from(new GraphTools(new GraphService(config))));
    }

    @Override
    public ConnectionTestResult testConnection(ModuleConfig config) {
        List<String> errors = config.validate();
        if (!errors.isEmpty()) {
            return ConnectionTestResult.failed(String.join("\n", errors));
        }
        GraphService service = new GraphService(config);
        Workspaces projects = service.projects();
        if (projects.isEmpty()) {
            return ConnectionTestResult.failed("Keine Projekte gefunden.");
        }
        GraphStorage storage;
        StringBuilder sb = new StringBuilder();
        try {
            storage = service.storage();
            if (storage instanceof Neo4jGraphStorage neo) {
                neo.connection().ensureSchema();
                String version = neo.connection().client.query("CALL dbms.components() YIELD name, versions, edition "
                                + "RETURN name + ' ' + versions[0] + ' ' + edition AS v").fetchAs(String.class)
                        .mappedBy((t, r) -> r.get("v").asString()).first().orElse("?");
                sb.append("Verbunden: ").append(version).append(" – ").append(neo.connection().settings).append('\n');
            } else {
                sb.append("Ablage: ").append(storage.describe()).append('\n');
            }
        } catch (IllegalStateException e) {
            return ConnectionTestResult.failed(e.getMessage());
        } catch (RuntimeException e) {
            return ConnectionTestResult.failed("Neo4j " + Neo4jConnection.Settings.from(config) + " nicht erreichbar: "
                    + rootMessage(e));
        }
        sb.append(projects.all().size()).append(" Projekt(e) gefunden:\n");
        projects.all().forEach((name, dir) -> {
            List<String> branches = storage.branches(dir).stream()
                    .map(s -> s.branch() == null ? "(ohne Git)" : s.branch()).toList();
            sb.append(name).append("  ").append(dir)
                    .append(branches.isEmpty() ? "" : "  [Graph: " + String.join(", ", branches) + "]").append('\n');
        });
        return ConnectionTestResult.ok(sb.toString().trim());
    }

    static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    @Override
    public List<ModuleAction> actions() {
        return List.of(new GraphIndexAction());
    }

    @Override
    public int order() {
        return 120;
    }
}
