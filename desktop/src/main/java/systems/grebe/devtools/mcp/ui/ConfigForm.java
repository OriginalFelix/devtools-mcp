package systems.grebe.devtools.mcp.ui;

import java.io.File;
import java.util.ArrayList;
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
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.Window;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.ConfigGroup;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.modules.window.ProcessPatterns;
import systems.grebe.devtools.mcp.modules.window.WindowCandidates;

/**
 * Erzeugt aus einem {@link ConfigField}-Schema ein Formular. Neue Module brauchen dadurch keinen eigenen UI-Code.
 *
 * <p>Felder von {@link ConfigGroup Provider-Gruppen} erscheinen nicht untereinander: An der Stelle der ersten Gruppe
 * steht eine Mehrfachauswahl der aktiven Gruppen (sie setzt deren Schalter), darunter die Einstellungen einer aktiven
 * Gruppe mit einem Umschalter zwischen ihnen.
 */
public class ConfigForm {

    private static final double LABEL_WIDTH = 200;

    private final GridPane grid = new GridPane();
    private final Map<String, Supplier<String>> getters = new LinkedHashMap<>();
    private final List<ConfigField> schema;
    private Runnable onChange = () -> { };
    private MultiSelectComboBox<ConfigGroup> activeGroups;
    private final ToggleGroup groupSwitch = new ToggleGroup();

    public ConfigForm(List<ConfigField> schema, Map<String, String> values) {
        this.schema = schema;
        grid.getStyleClass().add("config-form");
        columns(grid);

        int row = 0;
        boolean groups = false;
        for (ConfigField f : schema) {
            if (f.group() == null) {
                grid.getChildren().addAll(row(f, f.label(), values, row++));
            } else if (!groups) {
                row = addGroups(values, row);
                groups = true;
            }
        }
        if (schema.isEmpty()) {
            grid.add(new Label("Dieses Modul benötigt keine Konfiguration."), 0, 0, 2, 1);
        }
    }

    private static void columns(GridPane grid) {
        grid.setHgap(12);
        grid.setVgap(10);
        ColumnConstraints labels = new ColumnConstraints();
        labels.setMinWidth(LABEL_WIDTH);
        labels.setPrefWidth(LABEL_WIDTH);
        labels.setMaxWidth(LABEL_WIDTH);
        labels.setHalignment(HPos.RIGHT);
        ColumnConstraints editors = new ColumnConstraints();
        editors.setHgrow(Priority.ALWAYS);
        editors.setFillWidth(true);
        grid.getColumnConstraints().addAll(labels, editors);
    }

    /** Beschriftung und Editor (mit Hilfetext) eines Feldes in der angegebenen Zeile, noch in keinem Raster. */
    private List<Node> row(ConfigField f, String text, Map<String, String> values, int row) {
        String value = values.getOrDefault(f.key(), f.defaultValue() == null ? "" : f.defaultValue());
        Label label = formLabel(text + (f.required() ? " *" : ""));
        VBox cell = new VBox(3, editor(f, value));
        if (f.help() != null) {
            Label help = new Label(f.help());
            help.getStyleClass().add("form-help");
            help.setWrapText(true);
            cell.getChildren().add(help);
        }
        GridPane.setConstraints(label, 0, row);
        GridPane.setConstraints(cell, 1, row);
        return List.of(label, cell);
    }

    private static Label formLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("form-label");
        label.setWrapText(true);
        label.setTextAlignment(javafx.scene.text.TextAlignment.RIGHT);
        GridPane.setValignment(label, VPos.TOP);
        label.setPadding(new Insets(5, 0, 0, 0));
        return label;
    }

    /**
     * Alle Provider-Gruppen des Schemas: Zeile „Aktiv“ mit der Mehrfachauswahl, darunter ein Kasten mit dem Umschalter
     * zwischen den aktiven Gruppen und den Einstellungen der gewählten. Die Zeilen jeder Gruppe werden einmal erzeugt
     * und beim Umschalten nur getauscht – Eingaben bleiben so erhalten, auch die abgewählter Gruppen.
     *
     * @return nächste freie Zeile
     */
    private int addGroups(Map<String, String> values, int row) {
        Map<ConfigGroup, List<ConfigField>> fields = new LinkedHashMap<>();
        Map<ConfigGroup, Boolean> enabled = new LinkedHashMap<>();
        for (ConfigField f : schema) {
            if (f.group() == null) {
                continue;
            }
            List<ConfigField> own = fields.computeIfAbsent(f.group(), g -> new ArrayList<>());
            if (f.key().equals(f.group().enabledKey())) {
                String v = values.get(f.key());
                enabled.put(f.group(), Boolean.parseBoolean(v == null || v.isBlank() ? f.defaultValue() : v));
            } else {
                own.add(f);
            }
        }
        List<ConfigGroup> groups = List.copyOf(fields.keySet());
        activeGroups = new MultiSelectComboBox<>(groups, ConfigGroup::label);
        activeGroups.setPromptText("keine – auswählen, um die Einstellungen zu sehen");
        activeGroups.setSelected(groups.stream().filter(g -> enabled.getOrDefault(g, false)).toList());
        groups.forEach(g -> getters.put(g.enabledKey(), () -> String.valueOf(activeGroups.isSelected(g))));
        grid.add(formLabel("Aktiv"), 0, row);
        grid.add(activeGroups, 1, row);

        GridPane panel = new GridPane();
        panel.getStyleClass().add("group-panel");
        columns(panel);
        Map<ConfigGroup, List<Node>> pages = new LinkedHashMap<>();
        fields.forEach((g, own) -> {
            List<Node> nodes = new ArrayList<>();
            for (int i = 0; i < own.size(); i++) {
                nodes.addAll(row(own.get(i), g.shortLabel(own.get(i)), values, i + 1));
            }
            if (own.isEmpty()) {
                Label none = new Label("Keine weiteren Einstellungen.");
                none.getStyleClass().add("form-help");
                GridPane.setConstraints(none, 1, 1);
                nodes.add(none);
            }
            pages.put(g, nodes);
        });
        HBox switcher = new HBox();
        switcher.getStyleClass().add("segmented");
        panel.add(formLabel("Einstellungen für"), 0, 0);
        panel.add(switcher, 1, 0);
        groupSwitch.selectedToggleProperty().addListener((obs, o, n) -> {
            panel.getChildren().removeIf(node -> GridPane.getRowIndex(node) != null && GridPane.getRowIndex(node) > 0);
            if (n != null) {
                panel.getChildren().addAll(pages.get((ConfigGroup) n.getUserData()));
            }
        });

        List<ConfigGroup> before = new ArrayList<>();
        Runnable update = () -> {
            List<ConfigGroup> now = List.copyOf(activeGroups.getSelected());
            // eine neu aktivierte Gruppe gleich zeigen, sonst die bisherige, solange sie aktiv bleibt
            ConfigGroup current = shownGroup();
            ConfigGroup show = now.stream().filter(g -> !before.contains(g)).findFirst()
                    .orElse(current != null && now.contains(current) ? current : now.isEmpty() ? null : now.getFirst());
            before.clear();
            before.addAll(now);
            groupSwitch.getToggles().clear();
            switcher.getChildren().setAll(now.stream().map(this::switchButton).toList());
            if (!now.isEmpty()) {
                switcher.getChildren().getFirst().getStyleClass().add("first");
                switcher.getChildren().getLast().getStyleClass().add("last");
            }
            groupSwitch.getToggles().stream().filter(t -> t.getUserData().equals(show)).findFirst()
                    .ifPresent(t -> t.setSelected(true));
            panel.setVisible(!now.isEmpty());
            panel.setManaged(!now.isEmpty());
        };
        update.run();
        activeGroups.getSelected().addListener((javafx.collections.ListChangeListener<ConfigGroup>) c -> {
            update.run();
            onChange.run();
        });
        grid.add(panel, 0, row + 1, 2, 1);
        return row + 2;
    }

    private ToggleButton switchButton(ConfigGroup g) {
        ToggleButton b = new ToggleButton(g.label()) {
            @Override
            public void fire() {
                if (!isSelected()) { // erneutes Klicken lässt die gezeigte Gruppe gewählt
                    super.fire();
                }
            }
        };
        b.setUserData(g);
        b.setToggleGroup(groupSwitch);
        return b;
    }

    /** Gruppe, deren Einstellungen gerade zu sehen sind, sonst {@code null}. */
    private ConfigGroup shownGroup() {
        return groupSwitch.getSelectedToggle() == null ? null : (ConfigGroup) groupSwitch.getSelectedToggle().getUserData();
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
            case PROCESS_PATTERN -> {
                TextField tf = new TextField(value);
                HBox.setHgrow(tf, Priority.ALWAYS);
                Button pick = new Button("Fenster wählen…");
                WindowCandidates candidates = WindowCandidates.current();
                candidates.unsupportedReason().ifPresentOrElse(r -> {
                    pick.setDisable(true);
                    tf.setTooltip(new Tooltip("Fensterauswahl nicht verfügbar: " + r));
                }, () -> pick.setTooltip(new Tooltip("Programm über sein Fenster auswählen")));
                pick.setOnAction(e -> WindowPickerDialog.choose(tf.getScene().getWindow(), candidates, f.label())
                        .ifPresent(c -> tf.setText(ProcessPatterns.append(tf.getText(), c.processName()))));
                tf.textProperty().addListener(changed);
                getters.put(f.key(), tf::getText);
                yield new HBox(6, tf, pick);
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
