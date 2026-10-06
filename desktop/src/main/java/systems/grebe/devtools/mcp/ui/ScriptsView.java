package systems.grebe.devtools.mcp.ui;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import systems.grebe.devtools.mcp.api.Me;
import systems.grebe.devtools.mcp.api.Permission;
import systems.grebe.devtools.mcp.modules.scripts.ScriptManager;
import systems.grebe.devtools.mcp.modules.scripts.ScriptTemplates;
import systems.grebe.devtools.mcp.modules.scripts.ScriptViews;
import systems.grebe.devtools.mcp.modules.scripts.ScriptsModule;
import systems.grebe.devtools.mcp.modules.scripts.assist.ScriptAssist;
import systems.grebe.devtools.mcp.modules.scripts.assist.ToolInfo;
import systems.grebe.devtools.mcp.ui.code.CodeEditor;

/**
 * Groovy-Skripte bearbeiten: links die Skripte mit Herkunft und Zustand (aktiv, deaktiviert, Fehler), rechts Editor,
 * Historie und Referenz. Skripte sind Groovy, Java oder Gherkin (Auswahl neben dem Namen). Der Editor hebt die Syntax
 * hervor und vervollständigt wie IntelliJ ({@link CodeEditor}, {@link ScriptAssist}). Speichern prüft das Skript, legt
 * es im Backend ab und lädt es sofort als Modul – die Tools stehen den Clients ohne Neustart zur Verfügung. Ändert
 * jemand anderes (das LLM, eine andere Desktop-App) ein Skript, aktualisiert sich die Liste; ungespeicherte Änderungen
 * im Editor bleiben dabei erhalten.
 */
public class ScriptsView extends BorderPane {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM.yy HH:mm")
            .withZone(ZoneId.systemDefault());

    private final ScriptManager scripts;
    private final Supplier<Optional<Me>> account;
    private final TableView<ScriptManager.Status> table = new TableView<>();
    private final TextField name = new TextField();
    private final ComboBox<ScriptViews.Language> language = new ComboBox<>();
    private final Label scopeHint = new Label();
    private final Label status = new Label();
    private final CodeEditor editor;
    private final TableView<ScriptViews.Revision> revisions = new TableView<>();
    private final CodeEditor revisionContent = new CodeEditor(null);
    private final Tab historyTab = new Tab("Historie");
    private final Button save = new Button("Speichern");
    private final Button delete = new Button("Löschen…");
    private final Button publish = new Button("Als Vorlage veröffentlichen…");
    private final Button unpublish = new Button("Vorlage zurückziehen…");
    private final Label countLabel = new Label();

    /** Stand im Editor beim Laden – Grundlage für „ungespeichert“ und {@code expectedRevision}. */
    private String loadedName;
    private String loadedContent = "";
    private Integer loadedRevision;
    private boolean selecting;
    /** Während des Speicherns lädt {@link #refresh} den Editor nicht nach – das übernimmt das Speichern selbst. */
    private boolean saving;

    public ScriptsView(ScriptManager scripts, Supplier<Optional<Me>> account) {
        this.scripts = scripts;
        this.account = account;
        this.editor = new CodeEditor(new ScriptAssist(() -> scripts.activeTools().stream().map(ToolInfo::of).toList()));
        getStyleClass().add("skills-view");
        SplitPane split = new SplitPane(buildList(), buildEditor());
        split.setDividerPositions(0.3);
        SplitPane.setResizableWithParent(split.getItems().getFirst(), false);
        setTop(buildToolbar());
        setCenter(split);
        newScript();
        scripts.addChangeListener(() -> Platform.runLater(this::refresh));
        refresh();
    }

    // ------------------------------------------------------------------ Aufbau

    private HBox buildToolbar() {
        Button create = new Button("Neu");
        create.setOnAction(e -> {
            if (confirmDiscard()) {
                table.getSelectionModel().clearSelection();
                newScript();
            }
        });
        Button check = new Button("Prüfen");
        check.setOnAction(e -> background(() -> scripts.check(name.getText(), language.getValue(), editor.getText()), this::ok));
        save.getStyleClass().add("accent");
        save.setOnAction(e -> saveScript());
        delete.setOnAction(e -> confirm("Skript „" + loadedName + "“ samt Historie löschen? Seine Tools verschwinden "
                + "sofort.", () -> scripts.delete(loadedName), msg -> {
            newScript();
            ok(msg);
        }));
        publish.setOnAction(e -> confirm("Skript „" + loadedName + "“ als globale Vorlage veröffentlichen? Es läuft "
                + "danach in den Desktop-Apps aller Benutzer.", () -> scripts.publish(loadedName), this::ok));
        unpublish.setOnAction(e -> confirm("Globale Vorlage „" + loadedName + "“ zurückziehen?",
                () -> scripts.unpublish(loadedName), this::ok));
        Button refresh = new Button("Aktualisieren");
        refresh.setOnAction(e -> background(() -> {
            scripts.reload();
            return "";
        }, x -> refresh()));
        countLabel.getStyleClass().add("form-help");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(8, create, check, save, delete, publish, unpublish, spacer, countLabel, refresh);
        for (Control c : List.of(create, check, save, delete, publish, unpublish, refresh, countLabel)) {
            c.setMinWidth(Region.USE_PREF_SIZE);
        }
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(10, 12, 8, 12));
        return bar;
    }

    private TableView<ScriptManager.Status> buildList() {
        table.setPlaceholder(new Label("Noch keine Skripte – „Neu“ legt eines an."));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getColumns().add(col("Name", 110, ScriptManager.Status::name));
        table.getColumns().add(col("Sprache", 60, s -> s.summary().language().label()));
        table.getColumns().add(col("Herkunft", 70, s -> scopeLabel(s.summary())));
        table.getColumns().add(col("Rev.", 40, s -> String.valueOf(s.summary().revision())));
        table.getColumns().add(col("Zustand", 140, ScriptsView::stateLabel));
        table.getColumns().forEach(c -> c.setReorderable(false));
        table.setRowFactory(tv -> {
            var row = new javafx.scene.control.TableRow<ScriptManager.Status>();
            row.itemProperty().addListener((o, a, s) -> row.setTooltip(s == null ? null
                    : new javafx.scene.control.Tooltip(s.summary().description()
                    + (s.error() == null ? "" : "\n\n" + s.error()))));
            return row;
        });
        table.getSelectionModel().selectedItemProperty().addListener((o, previous, s) -> {
            if (selecting || s == null || s.name().equals(loadedName)) {
                return;
            }
            if (!confirmDiscard()) {
                selectQuietly(previous);
                return;
            }
            load(s.name(), null);
        });
        return table;
    }

    private VBox buildEditor() {
        name.setPromptText("name (Modul-ID, z.B. jira)");
        name.setPrefColumnCount(18);
        Label nameLabel = new Label("Name");
        nameLabel.getStyleClass().add("form-label");
        scopeHint.getStyleClass().add("form-help");
        language.getItems().setAll(ScriptViews.Language.values());
        language.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(ScriptViews.Language l) {
                return l == null ? "" : l.label();
            }

            @Override
            public ScriptViews.Language fromString(String s) {
                return null;
            }
        });
        language.setValue(ScriptViews.Language.GROOVY);
        // Neues Skript, Vorlage noch unverändert: Vorlage der gewählten Sprache zeigen
        language.valueProperty().addListener((o, a, l) -> {
            editor.setLanguage(l);
            if (l != null && loadedName == null && editor.getText().equals(ScriptTemplates.of(a))) {
                loadedContent = ScriptTemplates.of(l);
                editor.load(loadedContent);
            }
        });
        HBox head = new HBox(8, nameLabel, name, language, scopeHint);
        head.setAlignment(Pos.CENTER_LEFT);

        status.getStyleClass().add("status-text");
        status.setWrapText(true);
        status.setMinHeight(Region.USE_PREF_SIZE);

        TextArea reference = monoArea();
        reference.setText(ScriptsModule.REFERENCE);

        revisions.setPlaceholder(new Label("Keine Historie"));
        revisions.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        revisions.getColumns().add(revCol("Rev.", 45, r -> String.valueOf(r.revision())));
        revisions.getColumns().add(revCol("Zeit", 110, r -> TIME.format(r.changedAt())));
        revisions.getColumns().add(revCol("Aktion", 80, ScriptViews.Revision::action));
        revisions.getColumns().add(revCol("Von", 150, r -> r.changedBy() == null ? "" : r.changedBy()));
        revisions.getColumns().add(revCol("Notiz", 250, r -> r.note() == null ? "" : r.note()));
        revisions.getSelectionModel().selectedItemProperty().addListener((o, a, r) ->
                revisionContent.load(r == null ? "" : r.content()));
        revisionContent.setEditable(false);
        Button restore = new Button("In den Editor übernehmen");
        restore.setOnAction(e -> Optional.ofNullable(revisions.getSelectionModel().getSelectedItem())
                .ifPresent(r -> editor.setText(r.content())));
        VBox revisionBox = new VBox(6, revisionContent, restore);
        VBox.setVgrow(revisionContent, Priority.ALWAYS);
        SplitPane history = new SplitPane(revisions, revisionBox);
        history.setOrientation(Orientation.VERTICAL);
        history.setDividerPositions(0.4);
        historyTab.setContent(history);

        TabPane tabs = new TabPane(new Tab("Quelltext", editor), historyTab, new Tab("Referenz", reference));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        VBox.setVgrow(tabs, Priority.ALWAYS);
        VBox box = new VBox(8, head, status, tabs);
        box.setPadding(new Insets(12, 16, 12, 16));
        return box;
    }

    // ------------------------------------------------------------------ Verhalten

    /** Beim Öffnen des Tabs: Vervollständigung schon vorbereiten, bevor der Benutzer tippt. */
    public void prepareEditor() {
        editor.warmUp();
    }

    /** Liste neu aus dem {@link ScriptManager} (kein Backend-Zugriff); der Editor bleibt unberührt. */
    public void refresh() {
        List<ScriptManager.Status> all = scripts.statuses();
        selecting = true;
        try {
            table.getItems().setAll(all);
            all.stream().filter(s -> s.name().equals(loadedName)).findFirst()
                    .ifPresent(s -> table.getSelectionModel().select(s));
        } finally {
            selecting = false;
        }
        countLabel.setText(all.size() + " Skript(e)");
        Optional<ScriptManager.Status> current = all.stream().filter(s -> s.name().equals(loadedName)).findFirst();
        if (current.isPresent() && !saving && !dirty() && !Integer.valueOf(current.get().summary().revision()).equals(loadedRevision)) {
            load(loadedName, null); // woanders geändert, hier nichts Ungespeichertes
        } else {
            current.ifPresent(this::showState);
        }
        updateActions(current.map(ScriptManager.Status::summary).orElse(null));
    }

    private void newScript() {
        loadedName = null;
        loadedRevision = null;
        language.setValue(ScriptViews.Language.GROOVY);
        loadedContent = ScriptTemplates.GROOVY;
        name.setText("");
        name.setEditable(true);
        editor.setLanguage(ScriptViews.Language.GROOVY);
        editor.load(ScriptTemplates.GROOVY);
        scopeHint.setText("neues Skript");
        revisions.getItems().clear();
        revisionContent.load("");
        historyTab.setText("Historie");
        setStatus("Name eintragen, Quelltext anpassen, „Speichern“. Groovy, Java oder Gherkin – die Referenz steht im dritten Reiter.", null);
        updateActions(null);
    }

    /** Lädt ein Skript in den Editor; {@code message} ersetzt danach die Zustandsanzeige (z.B. „gespeichert“). */
    private void load(String scriptName, String message) {
        background(() -> scripts.details(scriptName), d -> d.ifPresentOrElse(details -> {
            ScriptViews.Summary s = details.summary();
            loadedName = s.name();
            loadedRevision = s.revision();
            loadedContent = details.content();
            name.setText(s.name());
            language.setValue(s.language());
            editor.setLanguage(s.language());
            editor.load(details.content());
            revisionContent.setLanguage(s.language());
            scopeHint.setText(s.global()
                    ? "globale Vorlage – Speichern legt ein eigenes Skript an, das sie verdeckt"
                    : "eigenes Skript · Revision " + s.revision() + " · geändert " + TIME.format(s.updatedAt()));
            revisions.getItems().setAll(details.revisions());
            revisionContent.load("");
            historyTab.setText("Historie (" + details.revisions().size() + ")");
            if (message != null) {
                ok(message);
            } else {
                scripts.status(s.name()).ifPresentOrElse(this::showState, () -> setStatus("", null));
            }
            updateActions(s);
        }, this::newScript));
    }

    private void saveScript() {
        String n = name.getText() == null ? "" : name.getText().strip();
        String content = editor.getText();
        Integer expected = n.equals(loadedName) ? loadedRevision : null;
        saving = true;
        background(() -> scripts.save(n, language.getValue(), content, null, expected), msg -> {
            saving = false;
            loadedName = n;
            loadedContent = content;
            // save() hat schon neu geladen: Revision übernehmen, damit refresh() nicht ein zweites Mal lädt
            loadedRevision = scripts.status(n).map(s -> s.summary().revision()).orElse(null);
            refresh();
            load(n, msg);
        }, () -> saving = false);
    }

    private void showState(ScriptManager.Status s) {
        if (s.cached()) {
            setStatus("Letzter gespeicherter Stand – der Team-Server ist gerade nicht erreichbar. Tools: "
                    + String.join(", ", s.activeTools()), null);
        } else if (s.error() != null) {
            setStatus("Fehler: " + s.error(), "error");
        } else if (!s.enabled()) {
            setStatus("Modul deaktiviert (Schalter im Tab „Module“) – Tools: " + String.join(", ", s.tools()), null);
        } else {
            setStatus("Aktiv – Tools: " + String.join(", ", s.activeTools()), "ok");
        }
    }

    private void updateActions(ScriptViews.Summary s) {
        boolean admin = account.get().map(m -> m.grants().has(Permission.TEMPLATES_PUBLISH)).orElse(false);
        delete.setDisable(s == null || s.global());
        publish.setVisible(admin);
        publish.setManaged(admin);
        unpublish.setVisible(admin);
        unpublish.setManaged(admin);
        publish.setDisable(s == null || s.global());
        unpublish.setDisable(s == null || !s.global());
    }

    boolean dirty() {
        return !editor.getText().equals(loadedContent)
                || loadedName == null && name.getText() != null && !name.getText().isBlank();
    }

    private boolean confirmDiscard() {
        if (!dirty()) {
            return true;
        }
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, "Ungespeicherte Änderungen verwerfen?", ButtonType.CANCEL,
                ButtonType.OK);
        a.setHeaderText(null);
        if (getScene() != null) {
            a.initOwner(getScene().getWindow());
        }
        return a.showAndWait().filter(b -> b == ButtonType.OK).isPresent();
    }

    private void selectQuietly(ScriptManager.Status s) {
        selecting = true;
        try {
            if (s == null) {
                table.getSelectionModel().clearSelection();
            } else {
                table.getSelectionModel().select(s);
            }
        } finally {
            selecting = false;
        }
    }

    private void confirm(String question, Supplier<String> action, Consumer<String> done) {
        if (loadedName == null) {
            return;
        }
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, question, ButtonType.CANCEL, ButtonType.OK);
        a.setHeaderText(null);
        if (getScene() != null) {
            a.initOwner(getScene().getWindow());
        }
        a.showAndWait().filter(b -> b == ButtonType.OK).ifPresent(b -> background(action, done));
    }

    private void ok(String msg) {
        setStatus(msg, "ok");
    }

    private void setStatus(String text, String style) {
        status.setText(text);
        status.getStyleClass().removeAll("ok", "error");
        if (style != null) {
            status.getStyleClass().add(style);
        }
        if ("error".equals(style)) {
            editor.markErrors(text); // „Zeile 3, Spalte 5: …“ → Zeile im Editor markieren
        }
    }

    /** Backend und Übersetzen außerhalb des FX-Threads; Fehler erscheinen als Status unter dem Namen. */
    private <T> void background(Supplier<T> work, Consumer<T> onFx) {
        background(work, onFx, () -> { });
    }

    private <T> void background(Supplier<T> work, Consumer<T> onFx, Runnable onError) {
        Thread.ofVirtual().start(() -> {
            try {
                T result = work.get();
                Platform.runLater(() -> onFx.accept(result));
            } catch (RuntimeException e) {
                Platform.runLater(() -> {
                    onError.run();
                    setStatus(e.getMessage(), "error");
                });
            }
        });
    }

    /** Wählt ein Skript per Name aus (für Sichttests). */
    void selectForTest(String scriptName) {
        table.getItems().stream().filter(s -> s.name().equals(scriptName)).findFirst()
                .ifPresent(s -> table.getSelectionModel().select(s));
    }

    // ------------------------------------------------------------------ Anzeige

    static String scopeLabel(ScriptViews.Summary s) {
        return s.global() ? "global" : "eigen";
    }

    static String stateLabel(ScriptManager.Status s) {
        String state = s.error() != null ? "Fehler" : !s.enabled() ? "deaktiviert"
                : s.activeTools().size() + " Tool(s) aktiv";
        return s.cached() ? state + " · offline" : state;
    }

    private static TextArea monoArea() {
        TextArea a = new TextArea();
        a.setEditable(false);
        a.setWrapText(true);
        a.getStyleClass().add("mono");
        return a;
    }

    private static TableColumn<ScriptManager.Status, String> col(String title, double width,
                                                                 Function<ScriptManager.Status, String> f) {
        TableColumn<ScriptManager.Status, String> c = new TableColumn<>(title);
        c.setPrefWidth(width);
        c.setCellValueFactory(cd -> new ReadOnlyStringWrapper(f.apply(cd.getValue())));
        return c;
    }

    private static TableColumn<ScriptViews.Revision, String> revCol(String title, double width,
                                                                    Function<ScriptViews.Revision, String> f) {
        TableColumn<ScriptViews.Revision, String> c = new TableColumn<>(title);
        c.setPrefWidth(width);
        c.setCellValueFactory(cd -> new ReadOnlyStringWrapper(f.apply(cd.getValue())));
        return c;
    }
}
