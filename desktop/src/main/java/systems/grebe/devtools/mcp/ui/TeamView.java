package systems.grebe.devtools.mcp.ui;

import java.io.File;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import javafx.application.Platform;
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
import systems.grebe.devtools.mcp.remote.TeamServer;

/**
 * Tab „Server“: Anbindung an einen Team-Server (Adresse + Desktop-Token), aktives Profil und die Server-Projekte mit
 * ihrem lokalen Verzeichnis. Netzwerkzugriffe laufen im Hintergrund.
 */
public class TeamView extends BorderPane {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final TeamServer team;
    private final TextField url = new TextField();
    private final PasswordField token = new PasswordField();
    private final Button connect = new Button("Verbinden");
    private final Button disconnect = new Button("Trennen");
    private final Button sync = new Button("Jetzt abgleichen");
    private final Label status = new Label();
    private final Label user = new Label();
    private final ComboBox<Me.ProfileInfo> profile = new ComboBox<>();
    private final ObservableList<ProjectInfo> projects = FXCollections.observableArrayList();
    private final TableView<ProjectInfo> table = new TableView<>(projects);
    private boolean updating;

    public TeamView(TeamServer team) {
        this.team = team;
        setPadding(new Insets(16));

        TeamSettings current = team.settingsOfConnection();
        url.setText(current.url());
        url.setPromptText("https://devtools.example.com");
        token.setText(current.token());
        token.setPromptText("Desktop-Token aus der Web-UI (Mein Konto)");
        HBox.setHgrow(url, Priority.ALWAYS);
        HBox.setHgrow(token, Priority.ALWAYS);
        connect.getStyleClass().add("accent");
        connect.setOnAction(e -> background(() -> team.connect(url.getText(), token.getText()), "Verbunden"));
        disconnect.setOnAction(e -> background(() -> {
            team.disconnect();
            return null;
        }, "Getrennt – es gelten wieder nur die lokalen Einstellungen"));
        sync.setOnAction(e -> background(() -> {
            team.sync();
            return null;
        }, "Abgeglichen"));

        Label help = new Label("Mit einem Team-Server gelten zusätzlich die Vorgaben deines aktiven Profils "
                + "(Global → Benutzer → Profil) und deine Projekte; Skills liegen dann zentral auf dem Server. Ohne "
                + "Server arbeitet die App mit den lokalen Einstellungen.");
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
                    team.activateProfile(p.id());
                    return null;
                }, "Profil „" + p.name() + "“ aktiv");
            }
        });

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(8);
        grid.addRow(0, new Label("Adresse"), url);
        grid.addRow(1, new Label("Token"), token);
        grid.add(new HBox(8, connect, disconnect, sync), 1, 2);
        grid.addRow(3, new Label("Status"), status);
        grid.addRow(4, new Label("Benutzer"), user);
        grid.addRow(5, new Label("Profil"), profile);
        GridPane.setHgrow(url, Priority.ALWAYS);
        status.setWrapText(true);
        status.getStyleClass().add("status-text");

        Label projectsTitle = new Label("Projekte");
        projectsTitle.getStyleClass().add("section-title");
        Label projectsHelp = new Label("Projekte verwaltest du in der Web-UI. Hier ordnest du ihnen ein Verzeichnis auf "
                + "diesem Rechner zu – dann arbeiten Git, Build und Code-Graph damit.");
        projectsHelp.setWrapText(true);
        projectsHelp.getStyleClass().add("form-help");
        table();

        VBox top = new VBox(12, grid, help, projectsTitle, projectsHelp);
        top.setPadding(new Insets(0, 0, 12, 0));
        setTop(top);
        setCenter(table);

        team.addListener(() -> Platform.runLater(this::refresh));
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
        dir.setCellValueFactory(c -> new SimpleStringProperty(team.settingsOfConnection().projectPaths()
                .getOrDefault(c.getValue().id(), "")));
        dir.setPrefWidth(360);
        TableColumn<ProjectInfo, ProjectInfo> actions = new TableColumn<>("");
        actions.setCellValueFactory(c -> new javafx.beans.property.SimpleObjectProperty<>(c.getValue()));
        actions.setCellFactory(col -> new TableCell<>() {
            private final Button choose = new Button("Verzeichnis wählen…");
            private final Button clear = new Button("Entfernen");
            private final HBox box = new HBox(6, choose, clear);

            {
                box.setAlignment(Pos.CENTER_LEFT);
                choose.setOnAction(e -> chooseDir(getItem()));
                clear.setOnAction(e -> team.setProjectPath(getItem().id(), null));
            }

            @Override
            protected void updateItem(ProjectInfo item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty || item == null ? null : box);
            }
        });
        actions.setPrefWidth(240);
        table.getColumns().addAll(List.of(name, access, dir, actions));
        table.setPlaceholder(new Label("Keine Projekte – oder kein Server verbunden."));
    }

    private void chooseDir(ProjectInfo p) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Verzeichnis für „" + p.toolName() + "“");
        team.projectPath(p.id()).map(Path::toFile).filter(File::isDirectory).ifPresent(chooser::setInitialDirectory);
        File dir = chooser.showDialog(getScene() == null ? null : getScene().getWindow());
        if (dir != null) {
            team.setProjectPath(p.id(), dir.getAbsolutePath());
        }
    }

    private void refresh() {
        updating = true;
        try {
            boolean configured = team.settingsOfConnection().configured();
            disconnect.setDisable(!configured);
            sync.setDisable(!configured);
            String when = team.lastSync().map(t -> " (zuletzt " + TIME.format(t) + ")").orElse("");
            status.setText(switch (team.status()) {
                case OFF -> "Nicht verbunden – lokale Einstellungen";
                case ONLINE -> "Verbunden" + when;
                case OFFLINE -> "Server nicht erreichbar – letzter Stand gilt" + when + ". " + team.message();
                case ERROR -> "Fehler: " + team.message();
            });
            Me me = team.me().orElse(null);
            user.setText(me == null ? "–" : me.username() + (me.email() == null ? "" : " <" + me.email() + ">")
                    + (me.admin() ? " (Administrator)" : ""));
            profile.getItems().setAll(me == null ? List.of() : me.profiles());
            profile.setDisable(me == null || !configured);
            if (me != null) {
                me.profiles().stream().filter(p -> p.id() == me.activeProfileId()).findFirst()
                        .ifPresent(profile::setValue);
            }
            projects.setAll(team.projects());
            table.refresh();
        } finally {
            updating = false;
        }
    }

    private <T> void background(Supplier<T> action, String success) {
        connect.setDisable(true);
        status.setText("…");
        CompletableFuture.supplyAsync(action).whenComplete((r, e) -> Platform.runLater(() -> {
            connect.setDisable(false);
            refresh();
            if (e != null) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                status.setText("Fehler: " + cause.getMessage());
            } else {
                status.setText(success + ". " + status.getText());
            }
        }));
    }
}
