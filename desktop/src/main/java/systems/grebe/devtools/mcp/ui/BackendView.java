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
import javafx.scene.control.ComboBox;
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
import systems.grebe.devtools.mcp.api.ProjectInfo;
import systems.grebe.devtools.mcp.config.TeamSettings;
import systems.grebe.devtools.mcp.remote.BackendConnection;

/**
 * Tab „Backend“: eingebettet oder Team-Server (Adresse + Desktop-Token, wirksam nach Neustart), Verbindungsstatus,
 * aktives Profil und die Projekte mit ihrem lokalen Verzeichnis. Netzwerkzugriffe laufen im Hintergrund.
 */
public class BackendView extends BorderPane {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final BackendConnection backend;
    private final TextField url = new TextField();
    private final PasswordField token = new PasswordField();
    private final Button useServer = new Button("Server eintragen");
    private final Button useEmbedded = new Button("Eingebettet verwenden");
    private final Label mode = new Label();
    private final Label status = new Label();
    private final Label user = new Label();
    private final ComboBox<Me.ProfileInfo> profile = new ComboBox<>();
    private final Button newProject = new Button("Neues Projekt…");
    private final ObservableList<ProjectInfo> projects = FXCollections.observableArrayList();
    private final TableView<ProjectInfo> table = new TableView<>(projects);
    private boolean updating;

    public BackendView(BackendConnection backend) {
        this.backend = backend;
        setPadding(new Insets(16));

        TeamSettings current = backend.serverSettings();
        url.setText(current.url());
        url.setPromptText("https://devtools.example.com – leer = eingebettetes Backend");
        token.setText(current.token());
        token.setPromptText("Desktop-Token aus der Web-UI (Mein Konto)");
        HBox.setHgrow(url, Priority.ALWAYS);
        useServer.getStyleClass().add("accent");
        useServer.setOnAction(e -> background(() -> backend.configureServer(url.getText(), token.getText())
                        .map(me -> "Server eingetragen (angemeldet als " + me.username() + ")").orElse(""),
                msg -> msg + " – wirksam nach einem Neustart der App."));
        useEmbedded.setOnAction(e -> background(() -> {
            backend.configureServer("", "");
            return "Eingebettetes Backend eingestellt";
        }, msg -> msg + " – wirksam nach einem Neustart der App."));

        Label help = new Label("Ohne Server läuft das Backend (Benutzer, Profile, Einstellungen, Projekte, Skills) "
                + "eingebettet in dieser App. Mit einem Team-Server gelten dessen Vorgaben (Global → Benutzer → Profil) "
                + "und Projekte, Skills liegen dort zentral; Änderungen kommen sofort an (GraphQL-Subscriptions).");
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
        grid.addRow(2, new Label("Desktop-Token"), token);
        grid.add(new HBox(8, useServer, useEmbedded), 1, 3);
        grid.addRow(4, new Label("Status"), status);
        grid.addRow(5, new Label("Benutzer"), user);
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

        VBox top = new VBox(12, grid, help, projectsTitle, projectsHelp, newProject);
        top.setPadding(new Insets(0, 0, 12, 0));
        setTop(top);
        setCenter(table);

        backend.addListener(() -> Platform.runLater(this::refresh));
        refresh();
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
                case CONNECTING -> "Verbinde …";
                case ONLINE -> "Verbunden" + when;
                case OFFLINE -> "Nicht erreichbar – letzter Stand gilt" + when + ". " + backend.message();
                case ERROR -> "Fehler: " + backend.message();
            });
            Me me = backend.me().orElse(null);
            user.setText(me == null ? "–" : me.username() + (me.email() == null ? "" : " <" + me.email() + ">")
                    + (me.admin() ? " (Administrator)" : ""));
            profile.getItems().setAll(me == null ? List.of() : me.profiles());
            profile.setDisable(me == null);
            if (me != null) {
                me.profiles().stream().filter(p -> p.id() == me.activeProfileId()).findFirst()
                        .ifPresent(profile::setValue);
            }
            newProject.setDisable(me == null);
            projects.setAll(backend.projects());
            table.refresh();
        } finally {
            updating = false;
        }
    }

    private void background(Supplier<String> action, java.util.function.UnaryOperator<String> success) {
        useServer.setDisable(true);
        status.setText("…");
        CompletableFuture.supplyAsync(action).whenComplete((r, e) -> Platform.runLater(() -> {
            useServer.setDisable(false);
            refresh();
            if (e != null) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                status.setText("Fehler: " + cause.getMessage());
            } else if (r != null && !r.isEmpty()) {
                status.setText(success.apply(r));
            }
        }));
    }
}
