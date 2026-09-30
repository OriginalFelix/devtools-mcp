package systems.grebe.devtools.mcp.ui;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.value.ChangeListener;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.ModuleConfig;

/**
 * Erzeugt aus einem {@link ConfigField}-Schema ein Formular. Neue Module brauchen dadurch keinen eigenen UI-Code.
 */
public class ConfigForm {

    private final GridPane grid = new GridPane();
    private final Map<String, Supplier<String>> getters = new LinkedHashMap<>();
    private final List<ConfigField> schema;
    private Runnable onChange = () -> { };

    public ConfigForm(List<ConfigField> schema, Map<String, String> values) {
        this.schema = schema;
        grid.getStyleClass().add("config-form");
        grid.setHgap(12);
        grid.setVgap(10);
        ColumnConstraints labels = new ColumnConstraints();
        labels.setMinWidth(200);
        labels.setPrefWidth(200);
        labels.setMaxWidth(200);
        labels.setHalignment(HPos.RIGHT);
        ColumnConstraints editors = new ColumnConstraints();
        editors.setHgrow(Priority.ALWAYS);
        editors.setFillWidth(true);
        grid.getColumnConstraints().addAll(labels, editors);

        int row = 0;
        for (ConfigField f : schema) {
            String value = values.getOrDefault(f.key(), f.defaultValue() == null ? "" : f.defaultValue());
            Label label = new Label(f.label() + (f.required() ? " *" : ""));
            label.getStyleClass().add("form-label");
            label.setWrapText(true);
            label.setTextAlignment(javafx.scene.text.TextAlignment.RIGHT);
            GridPane.setValignment(label, VPos.TOP);
            label.setPadding(new Insets(5, 0, 0, 0));
            Node editor = editor(f, value);
            VBox cell = new VBox(3, editor);
            if (f.help() != null) {
                Label help = new Label(f.help());
                help.getStyleClass().add("form-help");
                help.setWrapText(true);
                cell.getChildren().add(help);
            }
            grid.add(label, 0, row);
            grid.add(cell, 1, row);
            row++;
        }
        if (schema.isEmpty()) {
            grid.add(new Label("Dieses Modul benötigt keine Konfiguration."), 0, 0, 2, 1);
        }
    }

    public Node node() {
        return grid;
    }

    public void setOnChange(Runnable onChange) {
        this.onChange = onChange;
    }

    /** Aktuelle Formularwerte. */
    public Map<String, String> values() {
        Map<String, String> out = new LinkedHashMap<>();
        getters.forEach((k, g) -> out.put(k, g.get()));
        return out;
    }

    public List<String> validate() {
        return ModuleConfig.of(schema, values()).validate();
    }

    private Node editor(ConfigField f, String value) {
        ChangeListener<Object> changed = (obs, o, n) -> onChange.run();
        return switch (f.type()) {
            case SECRET -> {
                PasswordField pf = new PasswordField();
                pf.setText(value);
                pf.setPromptText("••••••");
                pf.textProperty().addListener(changed);
                getters.put(f.key(), pf::getText);
                yield pf;
            }
            case BOOLEAN -> {
                CheckBox cb = new CheckBox();
                cb.setSelected(Boolean.parseBoolean(value));
                cb.selectedProperty().addListener(changed);
                getters.put(f.key(), () -> String.valueOf(cb.isSelected()));
                yield cb;
            }
            case ENUM -> {
                ComboBox<String> combo = new ComboBox<>();
                combo.getItems().addAll(f.options());
                combo.setValue(value.isEmpty() ? null : value);
                combo.valueProperty().addListener(changed);
                getters.put(f.key(), () -> combo.getValue() == null ? "" : combo.getValue());
                yield combo;
            }
            case INT -> {
                TextField tf = new TextField(value);
                tf.setPrefColumnCount(8);
                tf.setMaxWidth(140);
                tf.setTextFormatter(new javafx.scene.control.TextFormatter<>(c ->
                        c.getControlNewText().matches("-?\\d*") ? c : null));
                tf.textProperty().addListener(changed);
                getters.put(f.key(), tf::getText);
                yield tf;
            }
            case DIRECTORY -> {
                TextField tf = new TextField(value);
                HBox.setHgrow(tf, Priority.ALWAYS);
                Button browse = new Button("…");
                browse.setTooltip(new Tooltip("Verzeichnis wählen"));
                browse.setOnAction(e -> chooseDirectory(tf.getScene().getWindow(), tf.getText())
                        .ifPresent(d -> tf.setText(d.getAbsolutePath())));
                tf.textProperty().addListener(changed);
                getters.put(f.key(), tf::getText);
                yield new HBox(6, tf, browse);
            }
            case DIRECTORY_LIST -> directoryList(f, value, changed);
            case RECORD_LIST -> recordList(f, value);
            case STRING_LIST -> {
                TextArea ta = new TextArea(value);
                ta.setPrefRowCount(Math.min(8, Math.max(3, (int) value.lines().count())));
                ta.setPromptText("Ein Eintrag pro Zeile");
                ta.textProperty().addListener(changed);
                getters.put(f.key(), ta::getText);
                yield ta;
            }
            default -> { // STRING, URL
                TextField tf = new TextField(value);
                if (f.defaultValue() != null) {
                    tf.setPromptText(f.defaultValue());
                }
                tf.textProperty().addListener(changed);
                getters.put(f.key(), tf::getText);
                yield tf;
            }
        };
    }

    private Node directoryList(ConfigField f, String value, ChangeListener<Object> changed) {
        ListView<String> list = new ListView<>();
        list.getItems().addAll(ModuleConfig.splitLines(value));
        list.setPrefHeight(130);
        list.setPlaceholder(new Label("Noch keine Verzeichnisse – über „Hinzufügen…“ auswählen"));
        list.getItems().addListener((javafx.collections.ListChangeListener<String>) c -> onChange.run());
        Button add = new Button("Hinzufügen…");
        add.setOnAction(e -> chooseDirectory(list.getScene().getWindow(),
                list.getItems().isEmpty() ? null : list.getItems().getLast())
                .ifPresent(d -> {
                    if (!list.getItems().contains(d.getAbsolutePath())) {
                        list.getItems().add(d.getAbsolutePath());
                    }
                }));
        Button remove = new Button("Entfernen");
        remove.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
        remove.setOnAction(e -> list.getItems().remove(list.getSelectionModel().getSelectedItem()));
        TextField manual = new TextField();
        manual.setPromptText("oder Pfad eingeben und Enter");
        HBox.setHgrow(manual, Priority.ALWAYS);
        manual.setOnAction(e -> {
            String p = manual.getText().trim();
            if (!p.isEmpty() && !list.getItems().contains(p)) {
                list.getItems().add(p);
            }
            manual.clear();
        });
        getters.put(f.key(), () -> String.join("\n", list.getItems()));
        return new VBox(6, list, new HBox(6, add, remove, manual));
    }

    /**
     * Tabelle der Datensätze (geheime Spalten ausgeblendet) mit Hinzufügen/Bearbeiten/Entfernen. Bearbeitet wird in
     * einem Dialog, dessen Formular wieder ein {@link ConfigForm} aus den Spalten ist.
     */
    private Node recordList(ConfigField f, String value) {
        TableView<Map<String, String>> table = new TableView<>();
        List<Map<String, String>> initial;
        try {
            initial = ModuleConfig.parseRecords(value, f.columns());
        } catch (IllegalArgumentException e) {
            initial = List.of();
        }
        table.getItems().addAll(initial);
        for (ConfigField c : f.columns()) {
            if (c.secret()) {
                continue;
            }
            TableColumn<Map<String, String>, String> col = new TableColumn<>(c.label());
            col.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().getOrDefault(c.key(), "")));
            table.getColumns().add(col);
        }
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPrefHeight(160);
        table.setPlaceholder(new Label("Noch keine Einträge – über „Hinzufügen…“ anlegen"));
        table.getItems().addListener((javafx.collections.ListChangeListener<Map<String, String>>) c -> onChange.run());

        Button add = new Button("Hinzufügen…");
        add.setOnAction(e -> editRecord(table, f, Map.of()).ifPresent(r -> table.getItems().add(r)));
        Button edit = new Button("Bearbeiten…");
        edit.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        Runnable editSelected = () -> {
            int i = table.getSelectionModel().getSelectedIndex();
            if (i >= 0) {
                editRecord(table, f, table.getItems().get(i)).ifPresent(r -> table.getItems().set(i, r));
            }
        };
        edit.setOnAction(e -> editSelected.run());
        table.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                editSelected.run();
            }
        });
        Button remove = new Button("Entfernen");
        remove.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        remove.setOnAction(e -> table.getItems().remove(table.getSelectionModel().getSelectedIndex()));
        getters.put(f.key(), () -> ModuleConfig.formatRecords(table.getItems()));
        return new VBox(6, table, new HBox(6, add, edit, remove));
    }

    private static java.util.Optional<Map<String, String>> editRecord(Node owner, ConfigField f, Map<String, String> values) {
        ConfigForm form = new ConfigForm(f.columns(), values);
        Dialog<Map<String, String>> dialog = new Dialog<>();
        dialog.initOwner(owner.getScene().getWindow());
        dialog.setTitle(f.label() + (values.isEmpty() ? " – neuer Eintrag" : " – bearbeiten"));
        dialog.setResizable(true);
        dialog.getDialogPane().getStylesheets().addAll(owner.getScene().getStylesheets());
        ScrollPane scroll = new ScrollPane(form.node());
        scroll.setFitToWidth(true);
        scroll.setPrefSize(620, Math.min(520, 60 + 52 * f.columns().size()));
        dialog.getDialogPane().setContent(scroll);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        Label errors = new Label();
        errors.setStyle("-fx-text-fill: #c53030; -fx-padding: 8 12 0 12;");
        errors.setWrapText(true);
        dialog.getDialogPane().setHeader(errors);
        errors.setVisible(false);
        errors.setManaged(false);
        // OK nur mit gültigen Werten: Fehler anzeigen statt schließen
        dialog.getDialogPane().lookupButton(ButtonType.OK).addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            List<String> problems = form.validate();
            if (!problems.isEmpty()) {
                errors.setText(String.join("\n", problems));
                errors.setVisible(true);
                errors.setManaged(true);
                e.consume();
            }
        });
        dialog.setResultConverter(b -> b == ButtonType.OK ? Map.copyOf(form.values()) : null);
        return dialog.showAndWait();
    }

    private static java.util.Optional<File> chooseDirectory(Window owner, String initial) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Verzeichnis wählen");
        if (initial != null && !initial.isBlank()) {
            File dir = new File(initial);
            File start = dir.isDirectory() ? dir : dir.getParentFile();
            if (start != null && start.isDirectory()) {
                chooser.setInitialDirectory(start);
            }
        }
        return java.util.Optional.ofNullable(chooser.showDialog(owner));
    }
}
