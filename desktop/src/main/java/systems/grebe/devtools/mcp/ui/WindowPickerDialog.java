package systems.grebe.devtools.mcp.ui;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Optional;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.FillRule;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.TextAlignment;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
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
        stage.setMinWidth(480);
        stage.setMinHeight(400);
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
            StackPane image = placeholder(c);
            image.setMinSize(TILE_W, TILE_H);
            image.setPrefSize(TILE_W, TILE_H);
            image.setMaxSize(TILE_W, TILE_H);
            loadPreview(c, image);
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
        // wächst und schrumpft mit dem Fenster: nimmt den Platz, den Titel und Angaben übrig lassen
        StackPane image = placeholder(c);
        image.setMinSize(0, 0);
        image.setPrefSize(1, 1);
        image.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        VBox.setVgrow(image, Priority.ALWAYS);
        loadPreview(c, image);
        Label title = new Label(title(c));
        title.getStyleClass().add("window-title");
        GridPane details = new GridPane();
        details.setHgap(8);
        details.setVgap(4);
        details.setMaxWidth(Double.MAX_VALUE);
        ColumnConstraints labels = new ColumnConstraints();
        labels.setMinWidth(Region.USE_PREF_SIZE);
        ColumnConstraints values = new ColumnConstraints();
        values.setHgrow(Priority.ALWAYS);
        values.setFillWidth(true);
        details.getColumnConstraints().setAll(labels, values);
        copyable(details, 0, "Name", c.processName());
        copyable(details, 1, "PID", String.valueOf(c.pid()));
        copyable(details, 2, "Pfad", c.executable() == null ? "(unbekannt)" : c.executable());

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

    /** Zwei überlappende Blätter – das übliche Kopier-Symbol. */
    private static final String COPY_ICON = "M5 1h8a2 2 0 0 1 2 2v8h-2V3H5z M1 5a2 2 0 0 1 2-2h7a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H3"
            + "a2 2 0 0 1-2-2z M3 5v9h7V5z";
    private static final String CHECK_ICON = "M1 8l2-2 4 4 7-7 2 2-9 9z";

    /**
     * Eine Zeile „Beschriftung · Wert“; der Wert ist markierbar, das Kopier-Symbol im Feld kopiert ihn ganz und zeigt kurz
     * einen Haken.
     */
    private static void copyable(GridPane grid, int row, String label, String value) {
        Label name = new Label(label);
        name.getStyleClass().add("form-label");
        TextField field = new TextField(value);
        field.setEditable(false);
        field.setFocusTraversable(false);
        field.setStyle("-fx-padding: 4 30 4 7;"); // Platz für das Symbol rechts im Feld

        SVGPath icon = new SVGPath();
        icon.setContent(COPY_ICON);
        icon.setFillRule(FillRule.EVEN_ODD); // vorderes Blatt hohl
        icon.getStyleClass().add("copy-icon");
        Button copy = new Button(null, icon);
        copy.getStyleClass().add("copy-button");
        copy.setFocusTraversable(false);
        copy.setTooltip(new Tooltip(label + " kopieren"));
        PauseTransition reset = new PauseTransition(Duration.seconds(1.5));
        reset.setOnFinished(e -> icon.setContent(COPY_ICON));
        copy.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(value);
            Clipboard.getSystemClipboard().setContent(content);
            icon.setContent(CHECK_ICON);
            reset.playFromStart();
        });
        StackPane.setAlignment(copy, Pos.CENTER_RIGHT);
        StackPane.setMargin(copy, new Insets(0, 4, 0, 0));
        StackPane cell = new StackPane(field, copy);
        GridPane.setHgrow(cell, Priority.ALWAYS);
        grid.addRow(row, name, cell);
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

    /** Platzhalter mit Prozessname und PID; die Vorschau ersetzt ihn, sobald sie geladen ist. Größe setzt der Aufrufer. */
    private static StackPane placeholder(Candidate c) {
        Label name = new Label(c.processName() + "\nPID " + c.pid());
        name.setWrapText(true);
        name.setTextAlignment(TextAlignment.CENTER);
        StackPane box = new StackPane(name);
        box.getStyleClass().add("window-placeholder");
        return box;
    }

    /** Lädt die Vorschau im Hintergrund, ohne das Fenster zu aktivieren; ohne Bild bleibt der Platzhalter. */
    private void loadPreview(Candidate c, StackPane target) {
        Thread.ofVirtual().start(() -> candidates.preview(c).ifPresent(img -> {
            Image fx = toFx(img);
            Platform.runLater(() -> {
                ImageView view = new ImageView(fx);
                view.setPreserveRatio(true);
                view.setSmooth(true);
                // passt sich der Größe des Platzhalters an (Seitenverhältnis bleibt)
                view.fitWidthProperty().bind(target.widthProperty());
                view.fitHeightProperty().bind(target.heightProperty());
                view.setManaged(false); // keine Rückwirkung der Bildgröße auf das Layout
                target.getChildren().setAll(view);
                target.widthProperty().addListener((o, a, b) -> center(view, target));
                target.heightProperty().addListener((o, a, b) -> center(view, target));
                view.layoutBoundsProperty().addListener((o, a, b) -> center(view, target));
                center(view, target);
            });
        }));
    }

    /** Nicht verwaltete Bilder legt das Layout nicht selbst ab – mittig in den Platzhalter setzen. */
    private static void center(ImageView view, StackPane target) {
        view.relocate((target.getWidth() - view.getLayoutBounds().getWidth()) / 2,
                (target.getHeight() - view.getLayoutBounds().getHeight()) / 2);
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
