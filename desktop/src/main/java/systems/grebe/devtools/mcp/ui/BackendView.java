package systems.grebe.devtools.mcp.ui;

import java.io.File;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.util.StringConverter;
import systems.grebe.devtools.mcp.api.Me;
import systems.grebe.devtools.mcp.api.Permission;
import systems.grebe.devtools.mcp.api.ProjectInfo;
import systems.grebe.devtools.mcp.backend.graph.GraphStorage;
import systems.grebe.devtools.mcp.config.GraphDatabaseSettings;
import systems.grebe.devtools.mcp.config.SettingsStore;
import systems.grebe.devtools.mcp.config.TeamSettings;
import systems.grebe.devtools.mcp.remote.BackendConnection;
import systems.grebe.devtools.mcp.remote.EmbeddedBackend;

/**
 * Tab „Backend“: eingebettet oder Team-Server (Adresse, wirksam nach Neustart), Verbindungsstatus, angemeldeter
 * Benutzer mit Rollen (Abmelden, Passwort ändern), aktives Profil, die Graph-Datenbank des eingebetteten Backends
 * (eingebettete oder externe ArcadeDB) und die Projekte mit ihrem lokalen Verzeichnis.
 * Netzwerkzugriffe laufen im Hintergrund.
 */
public class BackendView extends BorderPane {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final BackendConnection backend;
    private final TextField url = new TextField();
    private final Button useServer = new Button("Server eintragen");
    private final Button useEmbedded = new Button("Eingebettet verwenden");
    private final Label mode = new Label();
    private final Label status = new Label();
    private final Label user = new Label();
    private final Label roles = new Label();
    private final Button logout = new Button("Abmelden");
    private final Button changePassword = new Button("Passwort ändern…");
    private final ComboBox<Me.ProfileInfo> profile = new ComboBox<>();
    private final Button newProject = new Button("Neues Projekt…");
    private final ObservableList<ProjectInfo> projects = FXCollections.observableArrayList();
    private final TableView<ProjectInfo> table = new TableView<>(projects);
    private static final String GRAPH_EMBEDDED = "Eingebettet (in dieser App)";
    private static final String GRAPH_REMOTE = "Externer ArcadeDB-Server";

    private final GraphStorage graphs;
    private final SettingsStore store;
    private final ComboBox<String> graphMode = new ComboBox<>(FXCollections.observableArrayList(GRAPH_EMBEDDED,
            GRAPH_REMOTE));
    private final TextField graphHost = new TextField();
    private final TextField graphPort = new TextField();
    private final TextField graphDatabase = new TextField();
    private final TextField graphUser = new TextField();
    private final PasswordField graphPassword = new PasswordField();
    private final Button graphApply = new Button("Übernehmen");
    private final Label graphStatus = new Label();
    private boolean updating;

    /**
     * @param graphs Graph-Storage des eingebetteten Backends; {@code null} mit Team-Server (dann stellt der Server sie
     *               ein)
     */
    public BackendView(BackendConnection backend, GraphStorage graphs, SettingsStore store) {
        this.backend = backend;
        this.graphs = graphs;
        this.store = store;
        setPadding(new Insets(16));

        TeamSettings current = backend.serverSettings();
        url.setText(current.url());
        url.setPromptText("https://devtools.example.com – leer = eingebettetes Backend");
        HBox.setHgrow(url, Priority.ALWAYS);
        useServer.getStyleClass().add("accent");
        useServer.setOnAction(e -> background(() -> {
            backend.configureServer(url.getText());
            return url.getText().isBlank() ? "Eingebettetes Backend eingestellt" : "Server eingetragen";
        }, msg -> msg + " – wirksam nach einem Neustart der App (dort mit Benutzername und Passwort anmelden)."));
        useEmbedded.setOnAction(e -> background(() -> {
            backend.configureServer("");
            return "Eingebettetes Backend eingestellt";
        }, msg -> msg + " – wirksam nach einem Neustart der App."));
        logout.setOnAction(e -> background(() -> {
            backend.logout();
            return "";
        }, msg -> msg));
        changePassword.setOnAction(e -> changePassword());
        roles.setWrapText(true);
        roles.getStyleClass().add("form-help");

        Label help = new Label("Ohne Server läuft das Backend (Benutzer, Profile, Einstellungen, Projekte, Skills, Memories) "
                + "eingebettet in dieser App. Mit einem Team-Server gelten dessen Vorgaben (Global → Benutzer → Profil) "
                + "und Projekte, Skills und Memories liegen dort zentral; Änderungen kommen sofort an (GraphQL-Subscriptions).");
        help.setWrapText(true);
        help.getStyleClass().add("form-help");

        profile.setConverter(new StringConverter<>() {
            @Override
            public String toString(Me.ProfileInfo p) {
                return p == null ? "" : p.name();
            }

            @Override
            public Me.ProfileInfo fromString(String s) {
                return null;
            }
        });
        profile.setOnAction(e -> {
            Me.ProfileInfo p = profile.getValue();
            if (!updating && p != null) {
                background(() -> {
                    backend.activateProfile(p.id());
                    return "Profil „" + p.name() + "“ aktiv";
                }, msg -> msg);
            }
        });

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(8);
        grid.addRow(0, new Label("Backend"), mode);
        grid.addRow(1, new Label("Server-Adresse"), url);
        grid.add(new HBox(8, useServer, useEmbedded), 1, 2);
        grid.addRow(3, new Label("Status"), status);
        HBox userRow = new HBox(12, user, logout, changePassword);
        userRow.setAlignment(Pos.CENTER_LEFT);
        grid.addRow(4, new Label("Angemeldet"), userRow);
        grid.add(roles, 1, 5);
        grid.addRow(6, new Label("Profil"), profile);
        GridPane.setHgrow(url, Priority.ALWAYS);
        status.setWrapText(true);
        status.getStyleClass().add("status-text");

        Label projectsTitle = new Label("Projekte");
        projectsTitle.getStyleClass().add("section-title");
        Label projectsHelp = new Label("Ordne den Projekten ein Verzeichnis auf diesem Rechner zu – dann arbeiten Git, "
                + "Build und Code-Graph damit. Freigaben an andere vergibt die Web-UI des Team-Servers.");
        projectsHelp.setWrapText(true);
        projectsHelp.getStyleClass().add("form-help");
        newProject.setOnAction(e -> createProject());
        table();

        VBox top = new VBox(12, grid, help, graphSection(), projectsTitle, projectsHelp, newProject);
        top.setPadding(new Insets(0, 0, 12, 0));
        setTop(top);
        setCenter(table);

        backend.addListener(() -> Platform.runLater(this::refresh));
        refresh();
    }

    /** Graph-Datenbank für den Code-Graphen: eingebettet (Standard) oder ein externer ArcadeDB-Server. */
    private VBox graphSection() {
        Label title = new Label("Graph-Datenbank");
        title.getStyleClass().add("section-title");
        Label help = new Label("Das Backend startet für die Code-Graphen eine ArcadeDB – eingebettet in dieser App "
                + "(Standard) oder als Verbindung zu einem externen ArcadeDB-Server. Fehlt die Datenbank auf dem Server, "
                + "wird sie angelegt. Änderungen gelten sofort.");
        help.setWrapText(true);
        help.getStyleClass().add("form-help");

        graphHost.setPromptText("localhost");
        graphPort.setPrefColumnCount(6);
        graphDatabase.setPromptText("devtools");
        graphUser.setPromptText("root");
        HBox server = new HBox(8, graphHost, new Label("Port"), graphPort);
        server.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(graphHost, Priority.ALWAYS);
        graphStatus.setWrapText(true);
        graphStatus.getStyleClass().add("status-text");

        GridPane form = new GridPane();
        form.setHgap(12);
        form.setVgap(8);
        form.addRow(0, new Label("Datenbank"), graphMode);
        form.addRow(1, new Label("Server"), server);
        form.addRow(2, new Label("Datenbank-Name"), graphDatabase);
        form.addRow(3, new Label("Benutzer"), graphUser);
        form.addRow(4, new Label("Passwort"), graphPassword);
        form.add(graphApply, 1, 5);
        form.addRow(6, new Label("Status"), graphStatus);
        GridPane.setHgrow(server, Priority.ALWAYS);

        if (graphs == null) {
            form.setDisable(true);
            graphStatus.setText("Die Graph-Datenbank stellt der Team-Server bereit (devtools.graph.* auf dem Server).");
            return new VBox(8, title, help, form);
        }
        GraphDatabaseSettings saved = store.graph();
        GraphStorage.Settings current = graphs.settings();
        boolean remote = saved.configured() ? saved.remote() : current.mode() == GraphStorage.Mode.REMOTE;
        graphMode.setValue(remote ? GRAPH_REMOTE : GRAPH_EMBEDDED);
        graphHost.setText(saved.configured() ? saved.host() : blank(current.host()));
        graphPort.setText(String.valueOf(saved.configured() || current.port() <= 0 ? saved.port() : current.port()));
        graphDatabase.setText(saved.configured() ? saved.database() : blank(current.database()));
        graphUser.setText(saved.configured() ? saved.user() : blank(current.user()));
        graphPassword.setText(saved.configured() ? saved.password() : blank(current.password()));
        graphMode.setOnAction(e -> updateGraphFields());
        graphApply.getStyleClass().add("accent");
        graphApply.setOnAction(e -> applyGraph());
        updateGraphFields();
        graphs.addStatusListener(() -> Platform.runLater(() -> graphStatus.setText(graphs.status())));
        graphStatus.setText(graphs.status());
        return new VBox(8, title, help, form);
    }

    private static String blank(String s) {
        return s == null ? "" : s;
    }

    private void updateGraphFields() {
        boolean remote = GRAPH_REMOTE.equals(graphMode.getValue());
        for (javafx.scene.Node n : List.of(graphHost, graphPort, graphDatabase, graphUser, graphPassword)) {
            n.setDisable(!remote);
        }
    }

    /** Speichert die Einstellung und stellt die Graph-Storage im Hintergrund um. */
    private void applyGraph() {
        int port;
        try {
            port = graphPort.getText().isBlank() ? GraphDatabaseSettings.DEFAULT_PORT
                    : Integer.parseInt(graphPort.getText().strip());
        } catch (NumberFormatException e) {
            graphStatus.setText("Fehler: Der Port muss eine Zahl sein.");
            return;
        }
        GraphDatabaseSettings value = new GraphDatabaseSettings(GRAPH_REMOTE.equals(graphMode.getValue())
                ? GraphDatabaseSettings.REMOTE : GraphDatabaseSettings.EMBEDDED, graphHost.getText(), port,
                graphDatabase.getText(), graphUser.getText(), graphPassword.getText());
        graphApply.setDisable(true);
        graphStatus.setText("wird gestartet …");
        FxTasks.background(() -> {
            store.saveGraph(value);
            return graphs.configure(EmbeddedBackend.graphSettings(value));
        }, r -> {
            graphApply.setDisable(false);
            graphStatus.setText(r);
        }, e -> {
            graphApply.setDisable(false);
            graphStatus.setText("Fehler: " + e.getMessage());
        });
    }

    private void table() {
        TableColumn<ProjectInfo, String> name = new TableColumn<>("Name (in Tools)");
        name.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().toolName()));
        name.setPrefWidth(180);
        TableColumn<ProjectInfo, String> access = new TableColumn<>("Zugriff");
        access.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().writable() ? "lesen + schreiben"
                : "nur lesen"));
        access.setPrefWidth(120);
        TableColumn<ProjectInfo, String> dir = new TableColumn<>("Lokales Verzeichnis");
        dir.setCellValueFactory(c -> new SimpleStringProperty(backend.serverSettings().projectPaths()
                .getOrDefault(c.getValue().id(), "")));
        dir.setPrefWidth(360);
        TableColumn<ProjectInfo, ProjectInfo> actions = new TableColumn<>("");
        actions.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue()));
        actions.setCellFactory(col -> new TableCell<>() {
            private final Button choose = new Button("Verzeichnis wählen…");
            private final Button clear = new Button("Entfernen");
            private final HBox box = new HBox(6, choose, clear);

            {
                box.setAlignment(Pos.CENTER_LEFT);
                choose.setOnAction(e -> chooseDir(getItem()));
                clear.setOnAction(e -> backend.setProjectPath(getItem().id(), null));
            }

            @Override
            protected void updateItem(ProjectInfo item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty || item == null ? null : box);
            }
        });
        actions.setPrefWidth(240);
        table.getColumns().addAll(List.of(name, access, dir, actions));
        table.setPlaceholder(new Label("Keine Projekte."));
    }

    private void chooseDir(ProjectInfo p) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Verzeichnis für „" + p.toolName() + "“");
        backend.projectPath(p.id()).map(Path::toFile).filter(File::isDirectory).ifPresent(chooser::setInitialDirectory);
        File dir = chooser.showDialog(getScene() == null ? null : getScene().getWindow());
        if (dir != null) {
            backend.setProjectPath(p.id(), dir.getAbsolutePath());
        }
    }

    private void createProject() {
        TextInputDialog d = new TextInputDialog();
        d.setTitle("Neues Projekt");
        d.setHeaderText("Name des Projekts (danach Verzeichnis wählen)");
        if (getScene() != null) {
            d.initOwner(getScene().getWindow());
        }
        d.showAndWait().filter(n -> !n.isBlank()).ifPresent(n -> background(() -> {
            backend.createProject(n.strip(), null);
            return "Projekt „" + n.strip() + "“ angelegt";
        }, msg -> msg));
    }

    private void refresh() {
        updating = true;
        try {
            mode.setText(backend.embedded() ? "eingebettet (" + backend.url() + ")" : "Team-Server " + backend.url());
            String when = backend.lastSync().map(t -> " (zuletzt " + TIME.format(t) + ")").orElse("");
            status.setText(switch (backend.status()) {
                case SIGNED_OUT -> "Nicht angemeldet" + (backend.message().isEmpty() ? "" : " – " + backend.message());
                case CONNECTING -> "Verbinde …";
                case ONLINE -> "Verbunden" + when;
                case OFFLINE -> "Nicht erreichbar – letzter Stand gilt" + when + ". " + backend.message();
                case ERROR -> "Fehler: " + backend.message();
            });
            Me me = backend.me().orElse(null);
            user.setText(me == null ? "–" : me.label() + " (" + me.username() + ")"
                    + (me.email() == null ? "" : " <" + me.email() + ">"));
            roles.setText(me == null ? "" : "Rollen: "
                    + (me.roles().isEmpty() ? "keine" : String.join(", ", me.roles()))
                    + (me.admin() ? " – alle Rechte" : ""));
            logout.setDisable(me == null);
            changePassword.setDisable(me == null || backend.status() == BackendConnection.Status.OFFLINE);
            profile.getItems().setAll(me == null ? List.of() : me.profiles());
            profile.setDisable(me == null);
            if (me != null) {
                me.profiles().stream().filter(p -> p.id() == me.activeProfileId()).findFirst()
                        .ifPresent(profile::setValue);
            }
            newProject.setDisable(me == null || !me.grants().has(Permission.PROJECTS_CREATE));
            projects.setAll(backend.projects());
            table.refresh();
        } finally {
            updating = false;
        }
    }

    private void changePassword() {
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle("Passwort ändern");
        d.setHeaderText("Neues Passwort für " + backend.me().map(Me::username).orElse(""));
        if (getScene() != null) {
            d.initOwner(getScene().getWindow());
        }
        PasswordField current = new PasswordField();
        PasswordField next = new PasswordField();
        next.setPromptText("mindestens 8 Zeichen");
        PasswordField repeat = new PasswordField();
        GridPane form = new GridPane();
        form.setHgap(12);
        form.setVgap(8);
        form.addRow(0, new Label("Bisheriges Passwort"), current);
        form.addRow(1, new Label("Neues Passwort"), next);
        form.addRow(2, new Label("Wiederholen"), repeat);
        d.getDialogPane().setContent(form);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        d.showAndWait().filter(b -> b == ButtonType.OK).ifPresent(b -> {
            if (!next.getText().equals(repeat.getText())) {
                status.setText("Fehler: Die neuen Passwörter stimmen nicht überein.");
                return;
            }
            background(() -> {
                backend.changePassword(current.getText(), next.getText());
                return "Passwort geändert";
            }, msg -> msg);
        });
    }

    private void background(Supplier<String> action, java.util.function.UnaryOperator<String> success) {
        useServer.setDisable(true);
        status.setText("…");
        FxTasks.background(action, r -> {
            useServer.setDisable(false);
            refresh();
            if (r != null && !r.isEmpty()) {
                status.setText(success.apply(r));
            }
        }, e -> {
            useServer.setDisable(false);
            refresh();
            status.setText("Fehler: " + e.getMessage());
        });
    }
}
