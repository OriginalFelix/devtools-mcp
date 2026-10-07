package systems.grebe.devtools.mcp.modules.graph;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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
 * in der Graph-Storage des Backends ({@link GraphProvider}: ArcadeDB, im Local-Mode direkt, mit Team-Server über
 * GraphQL), wahlweise als {@code devtools-fileinfo*.graph} im Projekt – und über die
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
    static final String STORAGE_DATABASE = "database";
    static final String STORAGE_FILE = "file";

    private final Supplier<GraphProvider> database;
    private final Supplier<GraphProjects> identities;

    /** Ohne Graph-Storage – nur die Datei-Ablage (Tests). */
    public GraphModule() {
        this(() -> null, () -> null);
    }

    @Autowired
    public GraphModule(ObjectProvider<GraphProvider> database, ObjectProvider<GraphProjects> identities) {
        this(database::getIfAvailable, identities::getIfAvailable);
    }

    GraphModule(Supplier<GraphProvider> database) {
        this(database, () -> null);
    }

    GraphModule(Supplier<GraphProvider> database, Supplier<GraphProjects> identities) {
        this.database = database;
        this.identities = identities;
    }

    /** Dienst mit der Konfiguration, der Graph-Storage und den Projekten der App. */
    GraphService service(ModuleConfig config) {
        return new GraphService(config, database, identities.get());
    }

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
                + "und Git-Branch in der Graph-Datenbank des Backends (ArcadeDB) oder als Datei im Projekt – und "
                + "beantwortet Struktur- und Aufruffragen daraus per OpenCypher.";
    }

    @Override
    public String instructions() {
        return """
                Für Struktur- und Architekturfragen zu freigegebenen Java-Projekten den Code-Graphen verwenden, statt \
                Dateien mit `grep`, `find` oder vielen Einzel-Reads zu durchsuchen:
                - `graph_report`: Überblick (God Nodes, Communities, überraschende Verbindungen) – vor Architekturfragen.
                - `graph_query`: Frage in Stichworten → passende Typen/Methoden und ihr Zusammenhang.
                - `graph_find`: Klassen/Methoden nach Namen (statt `grep -r "class Foo"`).
                - `graph_files`: Dateien finden – nach Name, Stichworten, Muster oder Pfad (`query`) oder über Kanten \
                (`related`, z.B. wer einen Typ verwendet) – mit Zeilenbereichen je Treffer (statt `find`/`grep -rl`/Glob).
                - `graph_read`: nur die nötige Stelle lesen – Methode (`Typ#methode`), Typ, Gliederung einer Datei oder \
                `lines='von-bis'` – statt ganzer Dateien. Große Typen/Dateien kommen als Gliederung.
                - `graph_explain`: alles zu einem Knoten; `graph_neighbors` (direction=in, relations=[calls]): wer ruft das \
                auf (statt `grep` nach Aufrufstellen); `graph_path`: wie hängen zwei Stellen zusammen.
                - Gibt es für das Projekt noch keinen Graphen (ausgecheckter Branch), bauen die Abfrage-Tools ihn beim ersten \
                Aufruf automatisch und suchen dann – vorher kein `graph_build` nötig.
                - `graph_build`: nach größeren Änderungen; baut nur neu, wenn sich Quelldateien geändert haben.
                - Graphen gibt es je Git-Branch: ohne `branch` gilt der ausgecheckte; `graph_branches` listet die \
                gespeicherten, mit `branch` lassen sich andere abfragen (z.B. Vergleich mit master).
                - `graph_cypher`: lesendes OpenCypher für alles, was die übrigen Tools nicht abdecken (nur Datenbank-Ablage).
                Kanten sind als EXTRACTED (steht im Code), INFERRED (abgeleitet) oder AMBIGUOUS (mehrere Ziele) markiert – \
                bei INFERRED/AMBIGUOUS die angegebene Zeile im Quelltext prüfen, bevor darauf eine Aussage beruht.""";
    }

    @Override
    public Set<String> sharedDirectoryFields() {
        return Set.of(PROJECTS);
    }

    @Override
    public List<ConfigField> configSchema() {
        return List.of(
                ConfigField.of(PROJECTS, "Projekte", FieldType.DIRECTORY_LIST)
                        .withHelp("Projektverzeichnisse oder Sammelordner (Unterordner mit build.gradle(.kts), pom.xml, "
                                + ".git oder src werden übernommen); dazu die unter „Freigaben“ global freigegebenen. Je "
                                + "Projekt und Git-Branch gibt es einen Graphen."),
                ConfigField.of(STORAGE, "Ablage", FieldType.ENUM).withDefault(STORAGE_DATABASE)
                        .withOptions(STORAGE_DATABASE, STORAGE_FILE)
                        .withHelp("database = Graph-Storage des Backends (ArcadeDB; ohne Team-Server eingebettet auf "
                                + "diesem Rechner, sonst auf dem Server – Abfragen per OpenCypher, graph_cypher), "
                                + "file = Datei devtools-fileinfo@<branch>.graph im Projekt. Eingebettete oder externe "
                                + "ArcadeDB wählt das Backend (devtools.graph.*)."),
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
        return List.of(ToolCallbacks.from(new GraphTools(service(config))));
    }

    @Override
    public ConnectionTestResult testConnection(ModuleConfig config) {
        List<String> errors = config.validate();
        if (!errors.isEmpty()) {
            return ConnectionTestResult.failed(String.join("\n", errors));
        }
        GraphService service = service(config);
        Workspaces projects = service.projects();
        if (projects.isEmpty() && !Workspaces.unrestricted()) {
            return ConnectionTestResult.failed("Keine Projekte gefunden.");
        }
        GraphProvider storage;
        StringBuilder sb = new StringBuilder();
        try {
            storage = service.storage();
            sb.append(GraphService.usesDatabase(config) ? "Verbunden: " + storage.check()
                    : "Ablage: " + storage.describe()).append('\n');
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ConnectionTestResult.failed(e.getMessage());
        } catch (RuntimeException e) {
            return ConnectionTestResult.failed("Graph-Storage nicht erreichbar: " + rootMessage(e));
        }
        sb.append(projects.all().size()).append(" Projekt(e) gefunden:\n");
        projects.all().forEach((name, dir) -> {
            List<String> branches = storage.branches(service.key(dir.toAbsolutePath().normalize(), null)).stream()
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
        return List.of(new GraphIndexAction(this::service));
    }

    @Override
    public int order() {
        return 120;
    }
}
