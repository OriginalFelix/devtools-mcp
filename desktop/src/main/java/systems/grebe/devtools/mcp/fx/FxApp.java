package systems.grebe.devtools.mcp.fx;

import java.util.List;
import java.util.Optional;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Tab;
import javafx.scene.control.TextArea;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ConfigurableApplicationContext;
import systems.grebe.devtools.mcp.DevToolsMcpApplication;
import systems.grebe.devtools.mcp.config.SettingsStore;
import systems.grebe.devtools.mcp.core.ToolInvocationLog;
import systems.grebe.devtools.mcp.core.ToolRegistry;
import systems.grebe.devtools.mcp.modules.java.JavaEnvironmentProvider;
import systems.grebe.devtools.mcp.modules.memories.MemoryBackend;
import systems.grebe.devtools.mcp.modules.scripts.ScriptManager;
import systems.grebe.devtools.mcp.modules.skills.SkillBackend;
import systems.grebe.devtools.mcp.modules.visualvm.VisualVmModule;
import systems.grebe.devtools.mcp.plugin.PluginManager;
import systems.grebe.devtools.mcp.plugin.store.PluginStore;
import systems.grebe.devtools.mcp.remote.BackendConnection;
import systems.grebe.devtools.mcp.ui.AppIcons;
import systems.grebe.devtools.mcp.ui.ArtifactsView;
import systems.grebe.devtools.mcp.ui.BackendView;
import systems.grebe.devtools.mcp.ui.MainView;
import systems.grebe.devtools.mcp.ui.MemoriesView;
import systems.grebe.devtools.mcp.ui.PluginsView;
import systems.grebe.devtools.mcp.ui.ScriptsView;
import systems.grebe.devtools.mcp.ui.SkillsView;
import systems.grebe.devtools.mcp.ui.TrayManager;

/**
 * JavaFX-Lebenszyklus: {@link #init()} startet Spring (inkl. MCP-Server) im Launcher-Thread,
 * {@link #start(Stage)} baut das Fenster, {@link #stop()} fährt Spring herunter.
 */
public class FxApp extends Application {

    private static final Logger LOG = LoggerFactory.getLogger(FxApp.class);

    private ConfigurableApplicationContext context;
    private Throwable startupError;
    private TrayManager tray;

    @Override
    public void init() {
        try {
            context = DevToolsMcpApplication.startSpring(getParameters().getRaw().toArray(String[]::new));
        } catch (Throwable t) {
            LOG.error("Start fehlgeschlagen", t);
            startupError = t;
        }
    }

    @Override
    public void start(Stage stage) {
        if (startupError != null) {
            showStartupError(startupError);
            Platform.exit();
            return;
        }
        SettingsStore store = context.getBean(SettingsStore.class);
        ToolRegistry registry = context.getBean(ToolRegistry.class);
        ToolInvocationLog log = context.getBean(ToolInvocationLog.class);
        int port = Integer.parseInt(context.getEnvironment().getProperty("local.server.port",
                String.valueOf(store.server().port())));
        String endpoint = "http://127.0.0.1:" + port
                + context.getEnvironment().getProperty("spring.ai.mcp.server.streamable-http.mcp-endpoint", "/mcp");

        BackendConnection backend = context.getBean(BackendConnection.class);
        ScriptsView scripts = scriptsView(backend, registry);
        Tab scriptsTab = new Tab("Skripte", scripts);
        scriptsTab.selectedProperty().addListener((o, was, selected) -> {
            if (selected) {
                scripts.prepareEditor();
            }
        });
        MainView view = new MainView(registry, log, store, endpoint, stage, List.of(
                new Tab("Skills", skillsView(backend)),
                new Tab("Memories", memoriesView(backend)),
                scriptsTab,
                new Tab("Artefakte", new ArtifactsView(context.getBean(JavaEnvironmentProvider.class), getHostServices(),
                        context.getBean(VisualVmModule.class)::openFile)),
                new Tab("Plugins", new PluginsView(context.getBean(PluginManager.class),
                        context.getBean(PluginStore.class))),
                new Tab("Backend", new BackendView(backend))));
        Scene scene = new Scene(view, 1180, 760);
        scene.getStylesheets().add(getClass().getResource("/ui/app.css").toExternalForm());
        stage.setScene(scene);
        stage.setTitle("DevTools MCP – " + endpoint);
        stage.getIcons().add(AppIcons.fxIcon(64));
        stage.setMinWidth(900);
        stage.setMinHeight(560);

        tray = new TrayManager(stage, this::exit);
        boolean trayAvailable = tray.install();
        Platform.setImplicitExit(!trayAvailable);
        stage.setOnCloseRequest(e -> {
            if (trayAvailable && store.server().closeToTray()) {
                e.consume();
                stage.hide();
                tray.notifyMinimized();
            } else {
                exit();
            }
        });
        if (!(trayAvailable && store.server().startMinimized())) {
            stage.show();
        }
    }

    /** Skills-Ansicht; lädt neu, wenn sich Konto oder Verbindung ändern. */
    private SkillsView skillsView(BackendConnection backend) {
        SkillsView v = new SkillsView(context.getBean(SkillBackend.class), backend::me);
        backend.addListener(() -> Platform.runLater(v::refresh));
        return v;
    }

    /** Memories-Ansicht; lädt neu, wenn sich Konto oder Verbindung ändern. */
    private MemoriesView memoriesView(BackendConnection backend) {
        MemoriesView v = new MemoriesView(context.getBean(MemoryBackend.class));
        backend.addListener(() -> Platform.runLater(v::refresh));
        return v;
    }

    /** Skript-Editor; Zustand der Module (Schalter, Fehler) und Konto aktualisieren die Liste. */
    private ScriptsView scriptsView(BackendConnection backend, ToolRegistry registry) {
        ScriptsView v = new ScriptsView(context.getBean(ScriptManager.class), backend::me);
        registry.addChangeListener(() -> Platform.runLater(v::refresh));
        backend.addListener(() -> Platform.runLater(v::refresh));
        return v;
    }

    private void exit() {
        Platform.runLater(() -> {
            Optional.ofNullable(tray).ifPresent(TrayManager::uninstall);
            Platform.exit();
        });
    }

    @Override
    public void stop() {
        if (context != null) {
            context.close();
        }
        // AWT-Tray-Thread beenden
        System.exit(0);
    }

    private static void showStartupError(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        String hint = String.valueOf(root.getMessage()).toLowerCase().contains("address already in use")
                || root.getClass().getSimpleName().contains("PortInUse")
                ? "\n\nDer Port ist bereits belegt – läuft die App schon? Den Port kannst du in "
                + SettingsStore.defaultHome().resolve("settings.json") + " (server.port) ändern."
                : "";
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("DevTools MCP");
        alert.setHeaderText("Der MCP-Server konnte nicht gestartet werden." + hint);
        TextArea details = new TextArea(root.getClass().getName() + ": " + root.getMessage());
        details.setEditable(false);
        details.setWrapText(true);
        alert.getDialogPane().setContent(details);
        alert.showAndWait();
    }
}
