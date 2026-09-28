package systems.grebe.devtools.mcp.ui;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;
import systems.grebe.devtools.mcp.config.ServerSettings;
import systems.grebe.devtools.mcp.config.SettingsStore;
import systems.grebe.devtools.mcp.core.ToolInvocationLog;
import systems.grebe.devtools.mcp.core.ToolModule;
import systems.grebe.devtools.mcp.core.ToolRegistry;

/** Hauptfenster: Kopfzeile mit Serverstatus, Tabs „Module“ und „Aufrufe“. */
public class MainView extends BorderPane {

    private final ToolRegistry registry;
    private final SettingsStore store;
    private final String endpoint;
    private final Stage stage;
    private final Map<String, ModuleDetailPane> details = new HashMap<>();
    private final ListView<ToolModule> moduleList = new ListView<>();
    private final StackPane detailHolder = new StackPane();
    private final Label toolCount = new Label();
    private final Label authBadge = new Label();

    public MainView(ToolRegistry registry, ToolInvocationLog log, SettingsStore store, String endpoint, Stage stage,
                    java.util.List<Tab> extraTabs) {
        this.registry = registry;
        this.store = store;
        this.endpoint = endpoint;
        this.stage = stage;
        getStyleClass().add("main");

        setTop(header());

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().add(new Tab("Module", modulesPane()));
        tabs.getTabs().add(new Tab("Aufrufe", new InvocationLogView(log)));
        tabs.getTabs().addAll(extraTabs);
        setCenter(tabs);

        registry.addChangeListener(() -> Platform.runLater(this::refresh));
        refresh();
    }

    private HBox header() {
        Circle dot = new Circle(6);
        dot.getStyleClass().add("status-dot");
        Label title = new Label("DevTools MCP");
        title.getStyleClass().add("app-title");
        Label url = new Label(endpoint);
        url.getStyleClass().add("endpoint");
        Button copy = new Button("URL kopieren");
        copy.setOnAction(e -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString(endpoint);
            Clipboard.getSystemClipboard().setContent(cc);
        });
        Button connect = new Button("Client verbinden…");
        connect.getStyleClass().add("accent");
        connect.setOnAction(e -> new ClientConfigDialog(stage, endpoint, store.server()).showAndWait());
        Button settings = new Button("Einstellungen…");
        settings.setOnAction(e -> openSettings());
        toolCount.getStyleClass().add("badge");
        authBadge.getStyleClass().add("badge");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox box = new HBox(10, dot, title, url, toolCount, authBadge, spacer, copy, connect, settings);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setPadding(new Insets(12, 16, 12, 16));
        box.getStyleClass().add("header");
        Tooltip.install(dot, new Tooltip("MCP-Server läuft"));
        return box;
    }

    private BorderPane modulesPane() {
        moduleList.getItems().setAll(registry.modules());
        moduleList.getStyleClass().add("module-list");
        moduleList.setPrefWidth(260);
        moduleList.setCellFactory(lv -> new ModuleCell());
        moduleList.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> showModule(n));
        detailHolder.getStyleClass().add("detail-holder");
        BorderPane pane = new BorderPane();
        pane.setLeft(moduleList);
        pane.setCenter(detailHolder);
        if (!moduleList.getItems().isEmpty()) {
            moduleList.getSelectionModel().selectFirst();
        }
        return pane;
    }

    private void showModule(ToolModule m) {
        if (m == null) {
            detailHolder.getChildren().clear();
            return;
        }
        ModuleDetailPane pane = details.computeIfAbsent(m.id(), id -> new ModuleDetailPane(registry, m));
        detailHolder.getChildren().setAll(pane);
    }

    private void refresh() {
        toolCount.setText(registry.activeToolCount() + " Tools aktiv");
        authBadge.setText(store.server().authEnabled() ? "Token-geschützt" : "ohne Token");
        moduleList.refresh();
        details.values().forEach(ModuleDetailPane::refreshState);
    }

    private void openSettings() {
        int runningPort = URI.create(endpoint).getPort();
        new SettingsDialog(stage, store.server(), runningPort).showAndWait().ifPresent((ServerSettings s) -> {
            store.saveServer(s);
            refresh();
        });
    }

    /** Listenzelle: Name, Kurzstatus, farbiger Punkt. */
    private final class ModuleCell extends ListCell<ToolModule> {
        @Override
        protected void updateItem(ToolModule m, boolean empty) {
            super.updateItem(m, empty);
            if (empty || m == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            boolean enabled = registry.settings(m.id()).enabled();
            boolean error = registry.moduleError(m.id()).isPresent();
            int total = registry.availableTools(m.id()).size();
            long active = registry.availableTools(m.id()).stream()
                    .filter(t -> registry.isToolActive(m.id(), t.name())).count();
            Circle dot = new Circle(5);
            dot.getStyleClass().addAll("module-dot", !m.hasTools() ? "settings" : error ? "error" : enabled ? "on" : "off");
            Label name = new Label(m.displayName());
            name.getStyleClass().add("module-name");
            Label sub = new Label(!m.hasTools() ? "Einstellungen" : error ? "Fehler"
                    : enabled ? active + " von " + total + " Tools aktiv" : "deaktiviert");
            sub.getStyleClass().add("module-sub");
            HBox row = new HBox(10, dot, new VBox(1, name, sub));
            row.setAlignment(Pos.CENTER_LEFT);
            setText(null);
            setGraphic(row);
        }
    }
}
