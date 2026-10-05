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
import systems.grebe.devtools.mcp.api.Me;
import systems.grebe.devtools.mcp.api.Permission;
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
import systems.grebe.devtools.mcp.ui.LoginWindow;
import systems.grebe.devtools.mcp.ui.MainView;
import systems.grebe.devtools.mcp.ui.MemoriesView;
import systems.grebe.devtools.mcp.ui.PluginsView;
import systems.grebe.devtools.mcp.ui.ScriptsView;
import systems.grebe.devtools.mcp.ui.SkillsView;
import systems.grebe.devtools.mcp.ui.TrayManager;
import systems.grebe.devtools.mcp.ui.UsersAdminView;

/**
 * JavaFX-Lebenszyklus: {@link #init()} startet Spring (inkl. MCP-Server) im Launcher-Thread,
 * {@link #start(Stage)} baut das Fenster und fragt vorher nach der Anmeldung ({@link LoginWindow}) – ohne Anmeldung
 * gibt es keine Tools. Nach dem Abmelden (oder einer abgelaufenen Anmeldung) verschwindet das Hauptfenster, bis sich
 * wieder jemand angemeldet hat. {@link #stop()} fährt Spring herunter.
 */
public class FxApp extends Application {

    private static final Logger LOG = LoggerFactory.getLogger(FxApp.class);

    private ConfigurableApplicationContext context;
    private Throwable startupError;
    private TrayManager tray;
    private BackendConnection backend;
    private boolean loginOpen;
    private boolean exiting;

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

        backend = context.getBean(BackendConnection.class);
        UsersAdminView usersView = new UsersAdminView(backend, registry);
        Tab usersTab = new Tab("Benutzer", usersView);
        MainView view = new MainView(registry, log, store, endpoint, stage, List.of(
                new Tab("Skills", skillsView(backend)),
                new Tab("Memories", memoriesView(backend)),
                new Tab("Skripte", scriptsView(backend, registry)),
                new Tab("Artefakte", new ArtifactsView(context.getBean(JavaEnvironmentProvider.class), getHostServices(),
                        context.getBean(VisualVmModule.class)::openFile)),
                new Tab("Plugins", new PluginsView(context.getBean(PluginManager.class),
                        context.getBean(PluginStore.class))),
                new Tab("Backend", new BackendView(backend))));
        Scene scene = new Scene(view, 1180, 760);
        String css = getClass().getResource("/ui/app.css").toExternalForm();
        scene.getStylesheets().add(css);
        stage.setScene(scene);
        stage.setTitle("DevTools MCP – " + endpoint);
        Runnable account = () -> {
            Me me = backend.me().orElse(null);
            view.setUser(me == null ? null : "Benutzer: " + me.username());
            stage.setTitle("DevTools MCP – " + (me == null ? "" : me.username() + " – ") + endpoint);
            boolean admin = me != null && me.grants().has(Permission.USERS_MANAGE);
            view.showTab(usersTab, admin);
            if (admin) {
                usersView.refresh();
            }
        };
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
        boolean showMain = !(trayAvailable && store.server().startMinimized());
        backend.addListener(() -> Platform.runLater(() -> {
            account.run();
            if (!backend.signedIn() && !loginOpen && !exiting && !backend.passwordChangePending()) {
                // abgemeldet oder Anmeldung abgelaufen: neu anmelden, so lange ohne Hauptfenster (erst das
                // Anmeldefenster öffnen, sonst beendet JavaFX ohne Tray die App mit dem letzten Fenster)
                whenSignedIn(css, stage::show);
                stage.hide();
            }
        }));
        account.run();
        whenSignedIn(css, () -> {
            account.run();
            if (showMain) {
                stage.show();
            }
        });
    }

    /** Führt {@code then} aus, sobald jemand angemeldet ist – sonst erst nach dem Anmeldefenster. */
    private void whenSignedIn(String css, Runnable then) {
        if (backend.signedIn()) {
            then.run();
            return;
        }
        loginOpen = true;
        LoginWindow.show(backend, css, backend.message().isBlank() || backend.message().equals("Abgemeldet.") ? ""
                : backend.message(), () -> {
                    loginOpen = false;
                    then.run();
                }, () -> {
                    loginOpen = false;
                    exit();
                });
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
        exiting = true;
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
