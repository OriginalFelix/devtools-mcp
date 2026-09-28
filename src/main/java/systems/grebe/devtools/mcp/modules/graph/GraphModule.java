package systems.grebe.devtools.mcp.modules.graph;

import java.nio.file.Files;
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
 * Imports und den Aufrufgraphen lokal und deterministisch; das Ergebnis liegt als {@code devtools-fileinfo.graph} im
 * Projekt und wird über die {@code graph_*}-Tools abgefragt.
 */
@Component
public class GraphModule implements ToolModule {

    static final String PROJECTS = "projects";
    static final String DEFAULT_PROJECT = "defaultProject";
    static final String EXCLUDES = "excludes";
    static final String INCLUDE_TESTS = "includeTests";
    static final String MAX_FILES = "maxFiles";

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
        return "Baut per tree-sitter einen Graphen aus Klassen, Methoden, Imports, Vererbung und Aufrufen (Datei "
                + "devtools-fileinfo.graph im Projekt) und beantwortet Struktur- und Aufruffragen daraus.";
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
                Kanten sind als EXTRACTED (steht im Code), INFERRED (abgeleitet) oder AMBIGUOUS (mehrere Ziele) markiert – \
                bei INFERRED/AMBIGUOUS die angegebene Zeile im Quelltext prüfen, bevor darauf eine Aussage beruht.""";
    }

    @Override
    public List<ConfigField> configSchema() {
        return List.of(
                ConfigField.of(PROJECTS, "Projekte", FieldType.DIRECTORY_LIST).asRequired()
                        .withHelp("Projektverzeichnisse oder Sammelordner (Unterordner mit build.gradle(.kts), pom.xml, "
                                + ".git oder src werden übernommen). Der Graph wird als devtools-fileinfo.graph im "
                                + "jeweiligen Projekt abgelegt."),
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
        Workspaces projects = new GraphService(config).projects();
        if (projects.isEmpty()) {
            return ConnectionTestResult.failed("Keine Projekte gefunden.");
        }
        StringBuilder sb = new StringBuilder(projects.all().size() + " Projekt(e) gefunden:\n");
        projects.all().forEach((name, dir) -> sb.append(name).append("  ").append(dir)
                .append(Files.exists(GraphStore.fileFor(dir)) ? "  [Graph vorhanden]" : "").append('\n'));
        return ConnectionTestResult.ok(sb.toString().trim());
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
