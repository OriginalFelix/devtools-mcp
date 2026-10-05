package systems.grebe.devtools.mcp.ui.code;

import java.util.List;
import java.util.function.Consumer;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Popup;
import systems.grebe.devtools.mcp.modules.scripts.assist.CamelMatcher;
import systems.grebe.devtools.mcp.modules.scripts.assist.Completion;
import systems.grebe.devtools.mcp.modules.scripts.assist.CompletionResult.Ranked;

/**
 * Lookup-Liste der Vervollständigung wie in IntelliJ: Symbol je Art, Name mit fett markierten Treffern, Parameter grau
 * dahinter, Typ rechts; rechts daneben die Erklärung des gewählten Eintrags. Nimmt keinen Fokus – die Tasten steuert der
 * Editor ({@link CodeEditor}).
 */
final class CompletionPopup {

    private static final int ROW = 24;
    private static final int MAX_ROWS = 12;

    private final Popup popup = new Popup();
    private final ListView<Ranked> list = new ListView<>();
    private final Label doc = new Label();
    private final ScrollPane docPane = new ScrollPane(doc);

    CompletionPopup(Consumer<Completion> onAccept) {
        list.getStyleClass().add("completion-list");
        list.setFocusTraversable(false);
        list.setFixedCellSize(ROW);
        list.setPrefWidth(520);
        list.setCellFactory(v -> new Cell());
        list.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && selected() != null) {
                onAccept.accept(selected());
            }
        });
        list.getSelectionModel().selectedItemProperty().addListener((o, a, r) -> showDoc(r));
        doc.getStyleClass().add("completion-doc");
        doc.setWrapText(true);
        doc.setMaxWidth(340);
        docPane.getStyleClass().add("completion-doc-pane");
        docPane.setFitToWidth(true);
        docPane.setPrefWidth(360);
        docPane.setFocusTraversable(false);
        HBox box = new HBox(list, docPane);
        box.getStyleClass().addAll("root", "completion-popup"); // eigene Szene: Modena-Variablen hängen an .root
        box.getStylesheets().add(CompletionPopup.class.getResource("/ui/app.css").toExternalForm());
        popup.getContent().add(box);
        popup.setAutoFix(true);
        popup.setAutoHide(true);
        popup.setHideOnEscape(false);
    }

    void setOnHidden(Runnable action) {
        popup.setOnHidden(e -> action.run());
    }

    /**
     * Fenster, CSS und Zellen einmal vorab aufbauen: unsichtbar außerhalb des Bildschirms öffnen und gleich wieder
     * schließen – das erste echte Öffnen kostet sonst spürbar Zeit (gemessen ~0,7 s).
     */
    void prepare(Node owner) {
        if (owner.getScene() == null || owner.getScene().getWindow() == null
                || !owner.getScene().getWindow().isShowing() || popup.isShowing()) {
            return;
        }
        list.getItems().setAll(new Ranked(Completion.of(Completion.Kind.METHOD, "prepare").withTail("()")
                .withDetail("void"), new CamelMatcher.Match(CamelMatcher.EXACT_PREFIX, 0, new int[0])));
        popup.setAutoFix(false);
        popup.setOpacity(0);
        popup.show(owner, -10_000, -10_000);
        javafx.application.Platform.runLater(() -> {
            popup.hide();
            popup.setOpacity(1);
            popup.setAutoFix(true);
            list.getItems().clear();
        });
    }

    /** Das Fenster der Liste (für Sichttests). */
    Popup window() {
        return popup;
    }

    boolean isShowing() {
        return popup.isShowing();
    }

    /** Zeigt bzw. aktualisiert die Liste; der erste Eintrag ist gewählt. Position nur beim ersten Anzeigen. */
    void show(Node owner, List<Ranked> items, double screenX, double screenY) {
        list.getItems().setAll(items);
        list.getSelectionModel().select(0);
        list.scrollTo(0);
        list.setPrefHeight(Math.min(items.size(), MAX_ROWS) * ROW + 4);
        docPane.setPrefHeight(list.getPrefHeight());
        showDoc(items.isEmpty() ? null : items.getFirst());
        if (!popup.isShowing()) {
            popup.show(owner, screenX, screenY);
        }
    }

    void hide() {
        popup.hide();
    }

    Completion selected() {
        Ranked r = list.getSelectionModel().getSelectedItem();
        return r == null ? null : r.item();
    }

    /** Auswahl verschieben; über die Enden hinaus springt sie wie in IntelliJ zum anderen Ende. */
    void move(int delta) {
        int size = list.getItems().size();
        if (size == 0) {
            return;
        }
        int current = list.getSelectionModel().getSelectedIndex();
        int next;
        if (Math.abs(delta) == 1) {
            next = Math.floorMod(current + delta, size);
        } else {
            next = Math.max(0, Math.min(size - 1, current + delta));
        }
        list.getSelectionModel().select(next);
        list.scrollTo(Math.max(0, next - MAX_ROWS / 2));
    }

    static int page() {
        return MAX_ROWS - 1;
    }

    private void showDoc(Ranked r) {
        String text = r == null ? null : r.item().doc();
        boolean visible = text != null && !text.isBlank();
        doc.setText(visible ? text : "");
        docPane.setVisible(visible);
        docPane.setManaged(visible);
        popup.sizeToScene();
    }

    /** Zeile der Liste: Symbol, Name mit Treffern, Zusatz, Typ. */
    private static final class Cell extends ListCell<Ranked> {
        private final Label icon = new Label();
        private final TextFlow name = new TextFlow();
        private final Label detail = new Label();
        private final HBox box;

        Cell() {
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            detail.getStyleClass().add("completion-detail");
            detail.setMinWidth(Region.USE_PREF_SIZE);
            box = new HBox(6, icon, name, spacer, detail);
            box.setAlignment(Pos.CENTER_LEFT);
            setText(null);
            setPrefWidth(0); // Zellen so breit wie die Liste – keine waagerechte Scrollleiste, Typ bleibt sichtbar
        }

        @Override
        protected void updateItem(Ranked r, boolean empty) {
            super.updateItem(r, empty);
            if (empty || r == null) {
                setGraphic(null);
                return;
            }
            Completion c = r.item();
            icon.setText(iconText(c.kind()));
            icon.getStyleClass().setAll("completion-icon", "icon-" + c.kind().name().toLowerCase());
            name.getChildren().setAll(label(c, r.match().ranges()));
            if (c.tail() != null) {
                Text tail = new Text(c.tail().length() > 60 ? c.tail().substring(0, 59) + "…" : c.tail());
                tail.getStyleClass().add("completion-tail");
                name.getChildren().add(tail);
            }
            detail.setText(c.detail() == null ? "" : c.detail());
            setGraphic(box);
        }

        private static List<Text> label(Completion c, int[] ranges) {
            String label = c.label();
            String base = c.kind() == Completion.Kind.KEYWORD ? "completion-keyword" : "completion-label";
            java.util.ArrayList<Text> out = new java.util.ArrayList<>();
            int pos = 0;
            for (int i = 0; i + 1 < ranges.length; i += 2) {
                int from = Math.min(ranges[i], label.length());
                int to = Math.min(ranges[i + 1], label.length());
                if (from > pos) {
                    out.add(text(label.substring(pos, from), base));
                }
                if (to > from) {
                    out.add(text(label.substring(from, to), base, "completion-match"));
                }
                pos = Math.max(pos, to);
            }
            if (pos < label.length()) {
                out.add(text(label.substring(pos), base));
            }
            return out;
        }

        private static Text text(String s, String... styles) {
            Text t = new Text(s);
            t.getStyleClass().addAll(styles);
            return t;
        }

        private static String iconText(Completion.Kind kind) {
            return switch (kind) {
                case METHOD -> "m";
                case FIELD -> "f";
                case PROPERTY -> "p";
                case CONSTANT -> "c";
                case VARIABLE -> "v";
                case PARAMETER -> "p";
                case CLASS -> "C";
                case INTERFACE -> "I";
                case ENUM -> "E";
                case ANNOTATION, TAG -> "@";
                case PACKAGE -> "▪";
                case SNIPPET -> "t";
                case STEP -> "s";
                case TOOL -> "T";
                case KEYWORD -> "k";
            };
        }
    }
}
