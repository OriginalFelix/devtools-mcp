package systems.grebe.devtools.mcp.ui;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.TextInputControl;
import javafx.scene.image.ImageView;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import systems.grebe.devtools.mcp.remote.BackendConnection;
import systems.grebe.devtools.mcp.remote.EmbeddedAccounts;

/**
 * Anmeldefenster beim Start (und nach dem Abmelden): erstes Konto einrichten (eingebettetes Backend ohne Konto),
 * anmelden, ein vom Administrator gesetztes Passwort ändern. Ohne Anmeldung gibt es keine Tools; Schließen beendet die
 * App. Netzwerk und Datenbank laufen im Hintergrund.
 */
public final class LoginWindow {

    private final BackendConnection backend;
    private final Runnable onSignedIn;
    private final Runnable onQuit;
    private final Stage stage = new Stage();
    private final VBox page = new VBox(12);
    private final Label error = new Label();
    private final ProgressIndicator busy = new ProgressIndicator();
    private boolean done;

    private LoginWindow(BackendConnection backend, Runnable onSignedIn, Runnable onQuit) {
        this.backend = backend;
        this.onSignedIn = onSignedIn;
        this.onQuit = onQuit;
    }

    /**
     * Zeigt das Fenster.
     *
     * @param notice     Hinweis über dem Formular (z.B. „Anmeldung abgelaufen“), leer = keiner
     * @param onSignedIn nach erfolgreicher Anmeldung (FX-Thread)
     * @param onQuit     Fenster geschlossen bzw. „Beenden“ (FX-Thread)
     */
    public static void show(BackendConnection backend, String stylesheet, String notice, Runnable onSignedIn,
                            Runnable onQuit) {
        LoginWindow w = new LoginWindow(backend, onSignedIn, onQuit);
        w.open(stylesheet, notice);
    }

    private void open(String stylesheet, String notice) {
        ImageView icon = new ImageView(AppIcons.fxIcon(48));
        Label title = new Label("DevTools MCP");
        title.getStyleClass().add("app-title");
        Label where = new Label(backend.embedded() ? "Eingebettetes Backend auf diesem Rechner"
                : "Team-Server " + backend.url());
        where.getStyleClass().add("form-help");
        Hyperlink change = new Hyperlink("Backend ändern…");
        change.setOnAction(e -> changeBackend(backend.embedded() ? "" : backend.url()));
        Hyperlink discover = new Hyperlink("Im Netzwerk suchen…");
        discover.setOnAction(e -> DiscoveryDialog.search(stage, discover::setDisable, this::changeBackend));
        HBox backendLine = new HBox(6, where, change, discover);
        backendLine.setAlignment(Pos.BASELINE_LEFT);
        HBox head = new HBox(12, icon, new VBox(2, title, backendLine));
        head.setAlignment(Pos.CENTER_LEFT);

        error.getStyleClass().addAll("status-text", "error");
        error.setWrapText(true);
        error.setManaged(false);
        busy.setMaxSize(18, 18);
        busy.setVisible(false);

        VBox root = new VBox(16, head);
        if (notice != null && !notice.isBlank()) {
            Label n = new Label(notice);
            n.setWrapText(true);
            n.getStyleClass().add("status-text");
            root.getChildren().add(n);
        }
        root.getChildren().addAll(page, error);
        root.setPadding(new Insets(20, 24, 20, 24));
        root.setPrefWidth(460);

        if (backend.embeddedAccounts().map(EmbeddedAccounts::setupRequired).orElse(false)) {
            setupPage(backend.embeddedAccounts().orElseThrow());
        } else if (backend.passwordChangePending()) {
            passwordPage(null);
        } else {
            loginPage();
        }

        Scene scene = new Scene(root);
        if (stylesheet != null) {
            scene.getStylesheets().add(stylesheet);
        }
        stage.setScene(scene);
        stage.setTitle("DevTools MCP – Anmelden");
        stage.getIcons().add(AppIcons.fxIcon(64));
        stage.setResizable(false);
        stage.setOnCloseRequest(e -> {
            if (!done) {
                done = true;
                onQuit.run();
            }
        });
        stage.show();
        stage.toFront();
    }

    // ---------------------------------------------------------------- Seiten

    private void loginPage() {
        TextField username = new TextField(backend.lastUsername());
        username.setPromptText("Benutzername");
        PasswordField password = new PasswordField();
        password.setPromptText("Passwort");
        Button login = primary("Anmelden");
        login.setOnAction(e -> run(() -> backend.login(username.getText(), password.getText()), outcome -> {
            switch (outcome) {
                case SIGNED_IN, SIGNED_IN_OFFLINE -> finish();
                case PASSWORD_CHANGE_REQUIRED -> passwordPage(password.getText());
            }
        }, password));
        GridPane form = form();
        form.addRow(0, new Label("Benutzername"), username);
        form.addRow(1, new Label("Passwort"), password);
        page.getChildren().setAll(heading("Anmelden"), form, buttons(login));
        Platform.runLater(() -> (username.getText().isBlank() ? username : password).requestFocus());
    }

    private void setupPage(EmbeddedAccounts accounts) {
        boolean legacy = accounts.legacyAccount().isPresent();
        Label help = new Label(legacy
                ? "Bisher lief die App ohne Anmeldung (Benutzer „" + EmbeddedAccounts.LEGACY_USERNAME + "“). Lege "
                + "jetzt Benutzername und Passwort fest – Profile, Einstellungen, Projekte, Skills und Memories "
                + "bleiben erhalten."
                : "Erster Start: Lege das Konto des Administrators an. Weitere Benutzer und Rollen verwaltest du "
                + "danach im Tab „Benutzer“.");
        help.setWrapText(true);
        help.getStyleClass().add("form-help");
        TextField username = new TextField(EmbeddedAccounts.suggestedUsername());
        TextField displayName = new TextField();
        displayName.setPromptText("optional");
        TextField email = new TextField(accounts.suggestedEmail());
        email.setPromptText("Eigentümer deiner Skills und Memories");
        PasswordField password = new PasswordField();
        password.setPromptText("mindestens 8 Zeichen");
        PasswordField repeat = new PasswordField();
        Button create = primary("Konto einrichten");
        create.setOnAction(e -> {
            if (!password.getText().equals(repeat.getText())) {
                showError("Die Passwörter stimmen nicht überein.");
                return;
            }
            run(() -> {
                accounts.setup(username.getText(), displayName.getText(), email.getText(), password.getText());
                return backend.login(username.getText(), password.getText());
            }, outcome -> finish(), password, repeat);
        });
        GridPane form = form();
        form.addRow(0, new Label("Benutzername"), username);
        form.addRow(1, new Label("Anzeigename"), displayName);
        form.addRow(2, new Label("E-Mail"), email);
        form.addRow(3, new Label("Passwort"), password);
        form.addRow(4, new Label("Wiederholen"), repeat);
        page.getChildren().setAll(heading("Konto einrichten"), help, form, buttons(create));
        Platform.runLater(password::requestFocus);
    }

    /** @param current bisheriges Passwort, wenn schon bekannt (gerade angemeldet), sonst {@code null} = abfragen */
    private void passwordPage(String current) {
        Label help = new Label("Dein Passwort wurde vom Administrator gesetzt – bitte wähle ein eigenes.");
        help.setWrapText(true);
        help.getStyleClass().add("form-help");
        PasswordField old = new PasswordField();
        PasswordField next = new PasswordField();
        next.setPromptText("mindestens 8 Zeichen");
        PasswordField repeat = new PasswordField();
        Button change = primary("Passwort ändern");
        change.setOnAction(e -> {
            if (!next.getText().equals(repeat.getText())) {
                showError("Die neuen Passwörter stimmen nicht überein.");
                return;
            }
            String before = current != null ? current : old.getText();
            run(() -> {
                backend.changePassword(before, next.getText());
                return Boolean.TRUE;
            }, ok -> finish(), next, repeat);
        });
        GridPane form = form();
        int row = 0;
        if (current == null) {
            form.addRow(row++, new Label("Bisheriges Passwort"), old);
        }
        form.addRow(row++, new Label("Neues Passwort"), next);
        form.addRow(row, new Label("Wiederholen"), repeat);
        page.getChildren().setAll(heading("Passwort ändern"), help, form, buttons(change));
        hideError();
        Platform.runLater((current == null ? old : next)::requestFocus);
    }

    // ---------------------------------------------------------------- Ablauf

    /** Führt {@code action} im Hintergrund aus; Fehler erscheinen im Fenster, {@code clear} wird dann geleert. */
    private <T> void run(Supplier<T> action, Consumer<T> success, TextInputControl... clear) {
        page.setDisable(true);
        busy.setVisible(true);
        hideError();
        CompletableFuture.supplyAsync(action).whenComplete((r, e) -> Platform.runLater(() -> {
            page.setDisable(false);
            busy.setVisible(false);
            if (e != null) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                showError(cause instanceof BackendConnection.UnreachableException
                        ? cause.getMessage() + " – läuft der Server? Sonst „Backend ändern…“."
                        : String.valueOf(cause.getMessage()));
                for (TextInputControl c : clear) {
                    c.clear();
                }
                if (clear.length > 0) {
                    clear[0].requestFocus();
                }
            } else {
                success.accept(r);
            }
        }));
    }

    private void finish() {
        if (done) {
            return;
        }
        done = true;
        onSignedIn.run(); // erst das Hauptfenster, dann schließen – sonst beendet JavaFX ohne Tray die App
        stage.close();
    }

    /** Team-Server eintragen oder zurück zum eingebetteten Backend – wirksam nach einem Neustart. */
    private void changeBackend(String initial) {
        TextInputDialog d = new TextInputDialog(initial);
        d.initOwner(stage);
        d.setTitle("Backend ändern");
        d.setHeaderText("Adresse des Team-Servers, z.B. https://devtools.example.com\n"
                + "Leer = eingebettetes Backend auf diesem Rechner. Gilt nach einem Neustart der App.");
        d.showAndWait().ifPresent(url -> run(() -> {
            backend.configureServer(url);
            return url.isBlank() ? "Eingebettetes Backend eingestellt." : "Team-Server " + url.strip()
                    + " eingetragen.";
        }, msg -> {
            Alert a = new Alert(Alert.AlertType.INFORMATION, msg + " Die App wird jetzt beendet – bitte neu starten.");
            a.initOwner(stage);
            a.setHeaderText("Backend geändert");
            a.showAndWait();
            done = true;
            stage.close();
            onQuit.run();
        }));
    }

    // ---------------------------------------------------------------- Bausteine

    private Button primary(String text) {
        Button b = new Button(text);
        b.getStyleClass().add("accent");
        b.setDefaultButton(true);
        return b;
    }

    private Node buttons(Button primary) {
        Button quit = new Button("Beenden");
        quit.setCancelButton(true);
        quit.setOnAction(e -> {
            done = true;
            stage.close();
            onQuit.run();
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox box = new HBox(8, busy, spacer, quit, primary);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private static Label heading(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("section-title");
        return l;
    }

    private static GridPane form() {
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(8);
        ColumnConstraints field = new ColumnConstraints();
        field.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(new ColumnConstraints(), field);
        return grid;
    }

    private void showError(String text) {
        error.setText(text);
        error.setManaged(true);
        error.setVisible(true);
        stage.sizeToScene();
    }

    private void hideError() {
        error.setText("");
        error.setManaged(false);
        error.setVisible(false);
    }
}
