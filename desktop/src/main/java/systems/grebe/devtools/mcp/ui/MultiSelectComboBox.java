package systems.grebe.devtools.mcp.ui;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

/**
 * Mehrfachauswahl im Stil einer ComboBox: Die gewählten Einträge stehen als Chips im Feld (× entfernt sie), ein Klick,
 * Leertaste oder Alt+↓ öffnet die Liste mit Häkchen. Die Liste bleibt beim Anklicken offen, damit sich mehrere Einträge
 * nacheinander umschalten lassen. Die Auswahl steht in der Reihenfolge der Einträge, nicht in der des Anklickens.
 */
public class MultiSelectComboBox<T> extends HBox {

    private static final PseudoClass SHOWING = PseudoClass.getPseudoClass("showing");

    private final List<T> items;
    private final Function<T, String> labels;
    private final ObservableList<T> selected = FXCollections.observableArrayList();
    private final Map<T, CheckBox> checks = new LinkedHashMap<>();
    private final FlowPane chips = new FlowPane(4, 4);
    private final Label prompt = new Label();
    private final ContextMenu popup = new ContextMenu();

    public MultiSelectComboBox(List<T> items, Function<T, String> labels) {
        this.items = List.copyOf(items);
        this.labels = labels;
        getStyleClass().add("multi-select");
        setAlignment(Pos.CENTER_LEFT);
        setFocusTraversable(true);
        setMaxWidth(Double.MAX_VALUE);

        prompt.getStyleClass().add("prompt");
        chips.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(chips, Priority.ALWAYS);
        Region arrow = new Region();
        arrow.getStyleClass().add("arrow");
        arrow.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        StackPane arrowButton = new StackPane(arrow);
        arrowButton.getStyleClass().add("arrow-button");
        getChildren().addAll(chips, arrowButton);

        for (T item : this.items) {
            // Häkchen nur zur Anzeige: umgeschaltet wird über die Aktion des Menüeintrags. Klicks löst der Eintrag nur
            // auf seinem Inhalt aus, daher fängt sie die ganze Zeile (nicht das Häkchen) auf.
            CheckBox check = new CheckBox(labels.apply(item));
            check.setMouseTransparent(true);
            check.setFocusTraversable(false);
            checks.put(item, check);
            StackPane row = new StackPane(check);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPickOnBounds(true);
            CustomMenuItem entry = new CustomMenuItem(row, false);
            entry.setOnAction(e -> toggle(item));
            popup.getItems().add(entry);
        }
        popup.getStyleClass().add("multi-select-popup");
        popup.showingProperty().addListener((obs, o, showing) -> pseudoClassStateChanged(SHOWING, showing));

        setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                requestFocus();
                togglePopup();
            }
        });
        addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.SPACE || e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.F4
                    || e.isAltDown() && e.getCode() == KeyCode.DOWN) {
                togglePopup();
                e.consume();
            } else if (e.getCode() == KeyCode.BACK_SPACE && !selected.isEmpty()) {
                selected.removeLast();
                e.consume();
            }
        });
        selected.addListener((ListChangeListener<T>) c -> refresh());
        refresh();
    }

    /** Gewählte Einträge (veränderbar, z.B. für Listener); Reihenfolge wie in den Einträgen. */
    public ObservableList<T> getSelected() {
        return selected;
    }

    public void setSelected(Collection<T> values) {
        selected.setAll(items.stream().filter(values::contains).toList());
    }

    public boolean isSelected(T item) {
        return selected.contains(item);
    }

    /** Text im leeren Feld, z.B. „keine“. */
    public void setPromptText(String text) {
        prompt.setText(text);
    }

    private void toggle(T item) {
        boolean on = !selected.contains(item);
        selected.setAll(items.stream().filter(i -> i.equals(item) ? on : selected.contains(i)).toList());
    }

    private void togglePopup() {
        if (popup.isShowing()) {
            popup.hide();
            return;
        }
        // Liste so breit wie das Feld
        checks.values().forEach(c -> ((Region) c.getParent()).setPrefWidth(Math.max(120, getWidth() - 32)));
        popup.show(this, Side.BOTTOM, 0, 1);
    }

    private void refresh() {
        checks.forEach((item, check) -> check.setSelected(selected.contains(item)));
        chips.getChildren().clear();
        if (selected.isEmpty()) {
            chips.getChildren().add(prompt);
        }
        for (T item : selected) {
            chips.getChildren().add(chip(item));
        }
    }

    private HBox chip(T item) {
        Label name = new Label(labels.apply(item));
        Region cross = new Region();
        cross.getStyleClass().add("chip-remove-icon");
        StackPane remove = new StackPane(cross);
        remove.getStyleClass().add("chip-remove");
        remove.setPickOnBounds(true);
        Tooltip.install(remove, new Tooltip(labels.apply(item) + " entfernen"));
        remove.setOnMouseClicked(e -> {
            selected.remove(item);
            e.consume(); // nicht zusätzlich die Liste öffnen
        });
        HBox chip = new HBox(2, name, remove);
        chip.getStyleClass().add("chip");
        chip.setAlignment(Pos.CENTER_LEFT);
        return chip;
    }
}
