package systems.grebe.devtools.mcp.ui;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
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
import org.kordamp.ikonli.javafx.FontIcon;
import systems.grebe.devtools.mcp.channel.BridgeJar;
import systems.grebe.devtools.mcp.config.ServerSettings;
import systems.grebe.devtools.mcp.config.SettingsStore;
import systems.grebe.devtools.mcp.core.ToolInvocationLog;
import systems.grebe.devtools.mcp.core.ToolModule;
import systems.grebe.devtools.mcp.core.ToolRegistry;
import systems.grebe.devtools.mcp.modules.scripts.ScriptToolModule;
import systems.grebe.devtools.mcp.plugin.PluginToolModule;

/**
 * Hauptfenster: Seitenleiste mit den Bereichen, Kopfzeile mit Serverstatus, darunter der gewählte Bereich. Die Bereiche
 * sind Tabs eines TabPane ohne Reiterleiste – so bleiben {@link Tab#selectedProperty()} und {@link #showTab} unverändert.
 */
public class MainView extends BorderPane {

    /** Icon je Bereich (Feather); unbekannte Bereiche bekommen einen Punkt. */
    private static final Map<String, String> ICONS = Map.of("Module", "fth-grid", "Aufrufe", "fth-activity",
            "Skills", "fth-zap", "Memories", "fth-bookmark", "Skripte", "fth-code", "Artefakte", "fth-archive",
            "Plugins", "fth-package", "Backend", "fth-server", "Benutzer", "fth-users");

    private final ToolRegistry registry;
    private final SettingsStore store;
    private final String endpoint;
    private final Stage stage;
    private final Map<String, ModuleDetailPane> details = new HashMap<>();
    private final ListView<ToolModule> moduleList = new ListView<>();
    private final TextField moduleFilter = new TextField();
    private List<ToolModule> allModules = List.of();
    private final StackPane detailHolder = new StackPane();
    private final Label toolCount = new Label();
    private final Label authBadge = new Label();
    private final Label userBadge = new Label();
    private final Label pageTitle = new Label();
    private final TabPane tabs = new TabPane();
    private final VBox nav = new VBox(2);
    private final ToggleGroup navGroup = new ToggleGroup();

    public MainView(ToolRegistry registry, ToolInvocationLog log, SettingsStore store, String endpoint, Stage stage,
                    List<Tab> extraTabs) {
        this.registry = registry;
        this.store = store;
        this.endpoint = endpoint;
        this.stage = stage;
        getStyleClass().add("main");

        tabs.getStyleClass().add("main-tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addListener((ListChangeListener<Tab>) c -> rebuildNav());
        tabs.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> selectNav(now));
        tabs.getTabs().add(new Tab("Module", modulesPane()));
        tabs.getTabs().add(new Tab("Aufrufe", new InvocationLogView(log)));
        tabs.getTabs().addAll(extraTabs);

        setLeft(sidebar());
        BorderPane workspace = new BorderPane(tabs);
        workspace.setTop(topBar());
        setCenter(workspace);

        registry.addChangeListener(() -> Platform.runLater(this::refresh));
        refresh();
    }

    private VBox sidebar() {
        ImageView logo = new ImageView(AppIcons.fxIcon(64));
        logo.setFitWidth(26);
        logo.setFitHeight(26);
        Label title = new Label("DevTools MCP");
        title.getStyleClass().add("app-title");
        HBox brand = new HBox(10, logo, title);
        brand.setAlignment(Pos.CENTER_LEFT);
        brand.getStyleClass().add("brand");

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        userBadge.getStyleClass().add("sidebar-user");
        userBadge.setGraphic(new FontIcon("fth-user"));
        userBadge.setVisible(false);
        userBadge.setManaged(false);
        Button settings = new Button("Einstellungen", new FontIcon("fth-settings"));
        settings.getStyleClass().add("nav-item");
        settings.setMaxWidth(Double.MAX_VALUE);
        settings.setAlignment(Pos.CENTER_LEFT);
        settings.setOnAction(e -> openSettings());

        VBox box = new VBox(4, brand, nav, spacer, userBadge, settings);
        box.getStyleClass().add("sidebar");
        box.setPrefWidth(208);
        box.setMinWidth(208);
        return box;
    }

    private HBox topBar() {
        pageTitle.getStyleClass().add("page-title");
        Circle dot = new Circle(4);
        dot.getStyleClass().add("status-dot");
        Tooltip.install(dot, new Tooltip("MCP-Server läuft"));
        Label url = new Label(endpoint, dot);
        url.getStyleClass().add("endpoint");
        Button copy = new Button(null, new FontIcon("fth-copy"));
        copy.getStyleClass().add("icon-button");
        copy.setTooltip(new Tooltip("URL kopieren"));
        copy.setAccessibleText("URL kopieren");
        copy.setOnAction(e -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString(endpoint);
            Clipboard.getSystemClipboard().setContent(cc);
        });
        HBox endpointPill = new HBox(2, url, copy);
        endpointPill.setAlignment(Pos.CENTER_LEFT);
        endpointPill.getStyleClass().add("endpoint-pill");
        toolCount.getStyleClass().add("badge");
        authBadge.getStyleClass().add("badge");
        Button connect = new Button("Client verbinden…", new FontIcon("fth-link"));
        connect.getStyleClass().add("accent");
        connect.setOnAction(e -> new ClientConfigDialog(stage, endpoint, store.server(),
                BridgeJar.path(store.dir())).showAndWait());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox box = new HBox(10, pageTitle, spacer, toolCount, authBadge, endpointPill, connect);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setPadding(new Insets(10, 20, 10, 24));
        box.getStyleClass().add("header");
        return box;
    }

    /** Navigation aus den Tabs neu aufbauen (Tabs kommen und gehen, z.B. „Benutzer“ je nach Recht). */
    private void rebuildNav() {
        nav.getChildren().setAll(tabs.getTabs().stream().map(tab -> {
            ToggleButton b = new ToggleButton(tab.getText(), new FontIcon(ICONS.getOrDefault(tab.getText(), "fth-circle")));
            b.getStyleClass().add("nav-item");
            b.setUserData(tab);
            b.setToggleGroup(navGroup);
            b.setMaxWidth(Double.MAX_VALUE);
            b.setAlignment(Pos.CENTER_LEFT);
            b.setContentDisplay(ContentDisplay.LEFT);
            b.setOnAction(e -> tabs.getSelectionModel().select(tab));
            return b;
        }).toList());
        selectNav(tabs.getSelectionModel().getSelectedItem());
    }

    private void selectNav(Tab tab) {
        pageTitle.setText(tab == null ? "" : tab.getText());
        nav.getChildren().forEach(n -> ((ToggleButton) n).setSelected(n.getUserData() == tab));
    }

    private BorderPane modulesPane() {
        moduleList.getStyleClass().add("module-list");
        moduleList.setCellFactory(lv -> new ModuleCell());
        moduleList.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> showModule(n));
        moduleFilter.setPromptText("Module filtern…");
        moduleFilter.getStyleClass().add("module-filter");
        moduleFilter.textProperty().addListener((o, a, b) -> applyFilter());
        FontIcon searchIcon = new FontIcon("fth-search");
        searchIcon.getStyleClass().add("module-filter-icon");
        StackPane filter = new StackPane(moduleFilter, searchIcon);
        StackPane.setAlignment(searchIcon, Pos.CENTER_LEFT);
        StackPane.setMargin(searchIcon, new Insets(0, 0, 0, 10));
        searchIcon.setMouseTransparent(true);
        filter.setPadding(new Insets(12, 12, 6, 12));
        VBox.setVgrow(moduleList, Priority.ALWAYS);
        VBox left = new VBox(filter, moduleList);
        left.getStyleClass().add("module-pane");
        left.setPrefWidth(280);
        detailHolder.getStyleClass().add("detail-holder");
        BorderPane pane = new BorderPane();
        pane.setLeft(left);
        pane.setCenter(detailHolder);
        syncModules();
        return pane;
    }

    private void showModule(ToolModule m) {
        if (m == null) {
            detailHolder.getChildren().clear();
            return;
        }
        ModuleDetailPane pane = details.get(m.id());
        if (pane == null || pane.module() != m) { // gleiche ID, neues Modul: Plugin wurde neu geladen
            pane = new ModuleDetailPane(registry, m);
            details.put(m.id(), pane);
        }
        detailHolder.getChildren().setAll(pane);
    }

    private void refresh() {
        toolCount.setText(registry.activeToolCount() + " Tools aktiv");
        authBadge.setText(store.server().authEnabled() ? "Token-geschützt" : "ohne Token");
        syncModules();
        moduleList.refresh();
        details.values().forEach(ModuleDetailPane::refreshState);
    }

    /**
     * Plugins und Skripte fügen Module zur Laufzeit hinzu oder entfernen sie: Liste abgleichen und Detailansichten
     * entfernter Module verwerfen (sie halten das Modul und damit den ClassLoader des Plugins fest).
     */
    private void syncModules() {
        List<ToolModule> current = registry.modules();
        if (current.equals(allModules)) {
            return;
        }
        allModules = current;
        java.util.Set<ToolModule> alive = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        alive.addAll(current);
        details.values().removeIf(pane -> !alive.contains(pane.module()));
        applyFilter();
    }

    /** Sichtbare Module nach dem Suchtext; die Auswahl bleibt über die ID erhalten, sonst das erste Modul. */
    private void applyFilter() {
        String q = moduleFilter.getText().strip().toLowerCase(Locale.ROOT);
        ToolModule selected = moduleList.getSelectionModel().getSelectedItem();
        String selectedId = selected == null ? null : selected.id();
        moduleList.getItems().setAll(allModules.stream().filter(m -> q.isEmpty()
                || m.displayName().toLowerCase(Locale.ROOT).contains(q) || m.id().contains(q)).toList());
        moduleList.getItems().stream().filter(m -> m.id().equals(selectedId)).findFirst()
                .ifPresentOrElse(m -> moduleList.getSelectionModel().select(m),
                        () -> moduleList.getSelectionModel().selectFirst());
    }

    /** Angemeldeter Benutzer in der Seitenleiste; {@code null} = niemand. */
    public void setUser(String label) {
        userBadge.setText(label == null ? "" : label);
        userBadge.setVisible(label != null);
        userBadge.setManaged(label != null);
    }

    /** Blendet einen der zusätzlichen Tabs ein oder aus (z.B. „Benutzer“ nur mit dem Recht dazu). */
    public void showTab(Tab tab, boolean shown) {
        if (shown && !tabs.getTabs().contains(tab)) {
            tabs.getTabs().add(tab);
        } else if (!shown) {
            tabs.getTabs().remove(tab);
        }
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
            if (empty || m == null || !registry.hasModule(m.id())) { // Plugin-Modul evtl. gerade entfernt
                setGraphic(null);
                setText(null);
                return;
            }
            boolean enabled = registry.settings(m.id()).enabled();
            boolean permitted = !m.hasTools() || registry.modulePermitted(m.id());
            boolean error = registry.moduleError(m.id()).isPresent();
            int total = registry.availableTools(m.id()).size();
            long active = registry.availableTools(m.id()).stream()
                    .filter(t -> registry.isToolActive(m.id(), t.name())).count();
            Circle dot = new Circle(4);
            dot.getStyleClass().addAll("module-dot", !m.hasTools() ? "settings" : error ? "error"
                    : enabled && permitted ? "on" : "off");
            Label name = new Label(m.displayName());
            name.getStyleClass().add("module-name");
            String state = !m.hasTools() ? "Einstellungen" : !permitted ? "keine Berechtigung" : error ? "Fehler"
                    : enabled ? active + " von " + total + " Tools aktiv" : "deaktiviert";
            Label sub = new Label(PluginToolModule.pluginOf(m).map(p -> state + " · Plugin " + p)
                    .or(() -> ScriptToolModule.scriptOf(m).map(s -> state + (s.summary().global() ? " · globales Skript"
                            : " · Skript")))
                    .orElse(state));
            sub.getStyleClass().add("module-sub");
            HBox row = new HBox(10, dot, new VBox(1, name, sub));
            row.setAlignment(Pos.CENTER_LEFT);
            setText(null);
            setGraphic(row);
        }
    }
}
