package systems.grebe.devtools.mcp.ui;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Optional;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import systems.grebe.devtools.mcp.modules.window.WindowCandidates;
import systems.grebe.devtools.mcp.modules.window.WindowCandidates.Candidate;

/**
 * Fensterauswahl wie beim Teilen in Discord oder Teams: Übersicht aller Fenster als Kacheln mit Vorschau – ohne
 * Vorschau (minimiert, nicht erfassbar, noch nicht geladen) ein Platzhalter mit Prozessname und PID. Ein Klick zeigt das
 * Fenster groß, „Hinzufügen“ übernimmt es.
 */
final class WindowPickerDialog {

    private static final double TILE_W = 240;
    private static final double TILE_H = 150;
    private static final double PREVIEW_W = 820;
    private static final double PREVIEW_H = 440;

    private final Stage stage = new Stage();
    private final BorderPane root = new BorderPane();
    private final WindowCandidates candidates;
    private Node overview;
    private Candidate chosen;

    private WindowPickerDialog(Window owner, WindowCandidates candidates, String field) {
        this.candidates = candidates;
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("Fenster wählen – " + field);
        root.setPadding(new Insets(12));
        Scene scene = new Scene(root, 860, 600);
        if (owner != null && owner.getScene() != null) {
            scene.getStylesheets().setAll(owner.getScene().getStylesheets());
        }
        stage.setScene(scene);
    }

    /** Öffnet den Dialog und wartet; leer bei „Abbrechen“ oder Schließen. */
    static Optional<Candidate> choose(Window owner, WindowCandidates candidates, String field) {
        WindowPickerDialog d = new WindowPickerDialog(owner, candidates, field);
        d.overview = d.overview(candidates.list());
        d.showOverview();
        d.stage.showAndWait();
        return Optional.ofNullable(d.chosen);
    }

    private Node overview(List<Candidate> list) {
        FlowPane tiles = new FlowPane(12, 12);
        tiles.setPadding(new Insets(4));
        for (Candidate c : list) {
            StackPane image = placeholder(c, TILE_W, TILE_H);
            loadPreview(c, image, TILE_W, TILE_H);
            Label title = new Label(title(c));
            title.getStyleClass().add("window-title");
            title.setMaxWidth(TILE_W);
            Label process = new Label(c.processName() + " · PID " + c.pid());
            process.getStyleClass().add("form-help");
            process.setMaxWidth(TILE_W);
            VBox tile = new VBox(4, image, title, process);
            tile.getStyleClass().add("window-tile");
            tile.setPadding(new Insets(6));
            tile.setOnMouseClicked(e -> showPreview(c));
            tiles.getChildren().add(tile);
        }
        ScrollPane scroll = new ScrollPane(tiles);
        scroll.setFitToWidth(true);
        Label hint = new Label(list.isEmpty() ? "Keine wählbaren Fenster gefunden."
                : "Fenster anklicken, um es in der Vorschau anzusehen.");
        VBox box = new VBox(8, hint, scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return box;
    }

    private void showOverview() {
        Button cancel = new Button("Abbrechen");
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> stage.close());
        root.setCenter(overview);
        root.setBottom(buttons(cancel));
    }

    private void showPreview(Candidate c) {
        StackPane image = placeholder(c, PREVIEW_W, PREVIEW_H);
        loadPreview(c, image, PREVIEW_W, PREVIEW_H);
        Label title = new Label(title(c));
        title.getStyleClass().add("window-title");
        Label details = new Label(c.processName() + " · PID " + c.pid()
                + (c.executable() == null ? "" : "\n" + c.executable()));
        details.getStyleClass().add("form-help");
        details.setWrapText(true);

        Button back = new Button("Zurück");
        back.setCancelButton(true);
        back.setOnAction(e -> showOverview());
        Button add = new Button("Hinzufügen");
        add.getStyleClass().add("accent");
        add.setDefaultButton(true);
        add.setOnAction(e -> {
            chosen = c;
            stage.close();
        });
        root.setCenter(new VBox(8, image, title, details));
        root.setBottom(buttons(back, add));
    }

    private static HBox buttons(Button... buttons) {
        HBox bar = new HBox(8, buttons);
        bar.setAlignment(Pos.CENTER_RIGHT);
        bar.setPadding(new Insets(10, 0, 0, 0));
        return bar;
    }

    private static String title(Candidate c) {
        return c.window().title().isBlank() ? "(ohne Titel)" : c.window().title();
    }

    /** Platzhalter mit Prozessname und PID; die Vorschau ersetzt ihn, sobald sie geladen ist. */
    private static StackPane placeholder(Candidate c, double w, double h) {
        Label name = new Label(c.processName() + "\nPID " + c.pid());
        name.setWrapText(true);
        name.setTextAlignment(TextAlignment.CENTER);
        StackPane box = new StackPane(name);
        box.getStyleClass().add("window-placeholder");
        box.setMinSize(w, h);
        box.setPrefSize(w, h);
        box.setMaxSize(w, h);
        return box;
    }

    /** Lädt die Vorschau im Hintergrund, ohne das Fenster zu aktivieren; ohne Bild bleibt der Platzhalter. */
    private void loadPreview(Candidate c, StackPane target, double w, double h) {
        Thread.ofVirtual().start(() -> candidates.preview(c).ifPresent(img -> {
            Image fx = toFx(img);
            Platform.runLater(() -> {
                ImageView view = new ImageView(fx);
                view.setPreserveRatio(true);
                view.setSmooth(true);
                view.setFitWidth(w);
                view.setFitHeight(h);
                target.getChildren().setAll(view);
            });
        }));
    }

    /** AWT-Bild → JavaFX (ohne das Modul javafx-swing). */
    private static Image toFx(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        WritableImage fx = new WritableImage(w, h);
        fx.getPixelWriter().setPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), img.getRGB(0, 0, w, h, null, 0, w),
                0, w);
        return fx;
    }
}
