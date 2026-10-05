package systems.grebe.devtools.mcp.ui;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
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
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import systems.grebe.devtools.mcp.api.Me;
import systems.grebe.devtools.mcp.modules.skills.SkillBackend;
import systems.grebe.devtools.mcp.modules.skills.SkillViews;

/**
 * Übersicht der Skills des aktuellen Benutzers und der globalen Vorlagen: links Liste mit Suche und Filtern, rechts
 * Inhalt, Zusatzdateien und Änderungshistorie. Aktualisiert sich selbst, sobald das LLM einen Skill anlegt oder ändert.
 * Mit dem Admin-Schalter lassen sich eigene Skills als Vorlage veröffentlichen und Vorlagen zurückziehen.
 */
public class SkillsView extends BorderPane {

    static final String ALL_CATEGORIES = "Alle Kategorien";
    static final String ALL_SCOPES = "Eigene und global";
    static final String ONLY_OWN = "Nur eigene";
    static final String ONLY_GLOBAL = "Nur globale Vorlagen";
    static final String NO_CATEGORY = "(ohne Kategorie)";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM.yy HH:mm")
            .withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yy")
            .withZone(ZoneId.systemDefault());

    private final SkillBackend service;
    /** Angemeldetes Konto am Backend (Eigentümer der Skills, Administrator für Vorlagen). */
    private final Supplier<Optional<Me>> account;
    private final ComboBox<String> scopeFilter = new ComboBox<>();
    private final Label userLabel = new Label();
    private final Label readOnlyHint = new Label();
    private final Button publish = new Button("Als Vorlage veröffentlichen…");
    private final Button unpublish = new Button("Vorlage zurückziehen…");
    private final Button delete = new Button("Löschen…");
    private final ObservableList<SkillViews.Summary> items = FXCollections.observableArrayList();
    private final FilteredList<SkillViews.Summary> filtered = new FilteredList<>(items);
    private final TableView<SkillViews.Summary> table = new TableView<>(filtered);
    private final TextField search = new TextField();
    private final ComboBox<String> category = new ComboBox<>();
    private final Label countLabel = new Label();
    private final StackPane detailHolder = new StackPane();
    private final Label placeholder = new Label("Skill auswählen");

    // Detail
    private final Label title = new Label();
    private final Label description = new Label();
    private final Label meta = new Label();
    private final TextArea content = monoArea();
    private final ListView<SkillViews.File> fileList = new ListView<>();
    private final TextArea fileContent = monoArea();
    private final TableView<SkillViews.Revision> revisionTable = new TableView<>();
    private final TextArea revisionContent = monoArea();
    private final Tab filesTab = new Tab("Dateien");
    private final Tab historyTab = new Tab("Historie");
    private final VBox detail;

    public SkillsView(SkillBackend service, Supplier<Optional<Me>> account) {
        this.service = service;
        this.account = account;
        getStyleClass().add("skills-view");
        detail = buildDetail();
        placeholder.getStyleClass().add("form-help");
        detailHolder.getStyleClass().add("detail-holder");
        detailHolder.getChildren().setAll(placeholder);

        SplitPane split = new SplitPane(buildList(), detailHolder);
        split.setDividerPositions(0.42);
        SplitPane.setResizableWithParent(split.getItems().getFirst(), false);
        setTop(buildToolbar());
        setCenter(split);

        service.addChangeListener(() -> Platform.runLater(this::refresh));
        refresh();
    }

    // ------------------------------------------------------------------ Aufbau

    private VBox buildToolbar() {
        search.setPromptText("Suchen (Name, Beschreibung, Tags, Inhalt)");
        search.setPrefWidth(300);
        scopeFilter.setMinWidth(Region.USE_PREF_SIZE);
        category.setMinWidth(Region.USE_PREF_SIZE);
        search.textProperty().addListener((o, a, b) -> applyFilter());
        category.getItems().setAll(ALL_CATEGORIES);
        category.getSelectionModel().selectFirst();
        category.valueProperty().addListener((o, a, b) -> applyFilter());
        scopeFilter.getItems().setAll(ALL_SCOPES, ONLY_OWN, ONLY_GLOBAL);
        scopeFilter.getSelectionModel().selectFirst();
        scopeFilter.valueProperty().addListener((o, a, b) -> applyFilter());
        Button refresh = new Button("Aktualisieren");
        refresh.setOnAction(e -> refresh());
        delete.setOnAction(e -> selected().ifPresent(this::confirmDelete));
        publish.setOnAction(e -> selected().ifPresent(this::confirmPublish));
        unpublish.setOnAction(e -> selected().ifPresent(this::confirmUnpublish));
        table.getSelectionModel().selectedItemProperty().addListener((o, a, s) -> updateActions(s));
        updateActions(null);
        countLabel.getStyleClass().add("form-help");
        userLabel.getStyleClass().add("form-help");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox filters = new HBox(8, search, category, scopeFilter, spacer, countLabel, refresh);
        filters.setAlignment(Pos.CENTER_LEFT);
        Region spacer2 = new Region();
        HBox.setHgrow(spacer2, Priority.ALWAYS);
        HBox actions = new HBox(8, userLabel, spacer2, publish, unpublish, delete);
        actions.setAlignment(Pos.CENTER_LEFT);
        for (javafx.scene.control.Control c : List.of(publish, unpublish, delete, refresh, userLabel, countLabel)) {
            c.setMinWidth(Region.USE_PREF_SIZE);
        }
        VBox bar = new VBox(6, filters, actions);
        bar.setPadding(new Insets(10, 12, 8, 12));
        return bar;
    }

    private TableView<SkillViews.Summary> buildList() {
        table.setPlaceholder(new Label("Noch keine Skills. Das LLM legt sie nach gelösten Aufgaben mit "
                + "skills_create an."));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getColumns().add(col("Name", 170, SkillViews.Summary::name));
        table.getColumns().add(col("Herkunft", 80, SkillsView::scopeLabel));
        table.getColumns().add(col("Kategorie", 150, s -> s.category() == null ? "" : s.category()));
        table.getColumns().add(numberCol("Rev.", 42, SkillViews.Summary::revision));
        table.getColumns().add(numberCol("Genutzt", 58, s -> s.useCount()));
        table.getColumns().add(col("Geändert", 72, s -> DAY.format(s.updatedAt())));
        table.getColumns().forEach(c -> c.setReorderable(false));
        table.setRowFactory(tv -> {
            var row = new javafx.scene.control.TableRow<SkillViews.Summary>();
            row.itemProperty().addListener((o, a, s) -> row.setTooltip(s == null ? null
                    : new javafx.scene.control.Tooltip(s.description())));
            return row;
        });
        table.getSelectionModel().selectedItemProperty().addListener((o, a, s) -> showDetails(s));
        return table;
    }

    private VBox buildDetail() {
        title.getStyleClass().add("detail-title");
        description.getStyleClass().add("detail-description");
        description.setWrapText(true);
        meta.getStyleClass().add("form-help");
        meta.setWrapText(true);
        meta.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        readOnlyHint.getStyleClass().add("skill-readonly");
        readOnlyHint.setWrapText(true);
        readOnlyHint.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        readOnlyHint.managedProperty().bind(readOnlyHint.visibleProperty());

        fileList.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(SkillViews.File f, boolean empty) {
                super.updateItem(f, empty);
                setText(empty || f == null ? null : f.path());
            }
        });
        fileList.getSelectionModel().selectedItemProperty().addListener((o, a, f) ->
                fileContent.setText(f == null ? "" : f.content()));
        fileList.setPrefWidth(220);
        SplitPane files = new SplitPane(fileList, fileContent);
        files.setDividerPositions(0.3);
        filesTab.setContent(files);

        revisionTable.setPlaceholder(new Label("Keine Historie"));
        revisionTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        revisionTable.getColumns().add(revCol("Rev.", 45, r -> String.valueOf(r.revision())));
        revisionTable.getColumns().add(revCol("Zeit", 110, r -> TIME.format(r.changedAt())));
        revisionTable.getColumns().add(revCol("Aktion", 90, SkillViews.Revision::action));
        revisionTable.getColumns().add(revCol("Von", 150, r -> r.changedBy() == null ? "" : r.changedBy()));
        revisionTable.getColumns().add(revCol("Notiz", 300, r -> r.note() == null ? "" : r.note()));
        revisionTable.getSelectionModel().selectedItemProperty().addListener((o, a, r) ->
                revisionContent.setText(r == null ? "" : "description: " + r.description() + "\n\n" + r.content()));
        SplitPane history = new SplitPane(revisionTable, revisionContent);
        history.setOrientation(Orientation.VERTICAL);
        history.setDividerPositions(0.4);
        historyTab.setContent(history);

        TabPane tabs = new TabPane(new Tab("Inhalt", content), filesTab, historyTab);
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        VBox.setVgrow(tabs, Priority.ALWAYS);

        VBox box = new VBox(6, title, description, meta, readOnlyHint, tabs);
        box.setPadding(new Insets(14, 16, 12, 16));
        return box;
    }

    // ------------------------------------------------------------------ Verhalten

    /** Lädt die Übersicht neu (Hintergrund-Thread) und behält Auswahl und Filter. */
    public void refresh() {
        userLabel.setText(account.get().map(me -> me.email() == null
                        ? "Konto " + me.username() + " ohne E-Mail – Skills brauchen eine E-Mail im Konto"
                        : "Benutzer: " + me.email() + " (Konto " + me.username() + ")")
                .orElse("Backend noch nicht verbunden"));
        background(service::overview, list -> {
            String keep = selected().map(SkillViews.Summary::name).orElse(null);
            items.setAll(list);
            String current = category.getValue();
            category.getItems().setAll(ALL_CATEGORIES);
            category.getItems().addAll(categories(list));
            category.setValue(current != null && category.getItems().contains(current) ? current : ALL_CATEGORIES);
            applyFilter();
            if (keep != null) {
                filtered.stream().filter(s -> s.name().equals(keep)).findFirst()
                        .ifPresentOrElse(s -> {
                            table.getSelectionModel().select(s);
                            showDetails(s);
                        }, () -> showDetails(null));
            }
        });
    }

    void applyFilter() {
        String q = search.getText() == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
        String cat = category.getValue();
        String scope = scopeFilter.getValue();
        filtered.setPredicate(s -> matchesCategory(s, cat) && matchesScope(s, scope) && matchesQuery(s, q));
        countLabel.setText(filtered.size() == items.size() ? items.size() + " Skill(s)"
                : filtered.size() + " von " + items.size() + " Skill(s)");
    }

    static boolean matchesCategory(SkillViews.Summary s, String cat) {
        if (cat == null || cat.equals(ALL_CATEGORIES)) {
            return true;
        }
        return cat.equals(NO_CATEGORY) ? s.category() == null : cat.equals(s.category());
    }

    static boolean matchesScope(SkillViews.Summary s, String scope) {
        if (scope == null || scope.equals(ALL_SCOPES)) {
            return true;
        }
        return scope.equals(ONLY_GLOBAL) == s.global();
    }

    static String scopeLabel(SkillViews.Summary s) {
        return switch (s.scope()) {
            case GLOBAL -> "global";
            case COPY -> s.templateUpdated() ? "Kopie ⟳" : "Kopie";
            case OWN -> "eigen";
        };
    }

    /** Hinweis im Detailbereich; {@code null} = keiner. */
    static String scopeHint(SkillViews.Summary s) {
        return switch (s.scope()) {
            case GLOBAL -> "Globale Vorlage – schreibgeschützt. Ändert das LLM sie, entsteht automatisch eine "
                    + "persönliche Kopie, die ab dann statt der Vorlage gilt.";
            case COPY -> s.currentTemplateRevision() == null
                    ? "Persönliche Kopie einer inzwischen zurückgezogenen Vorlage (Revision " + s.templateRevision() + ")."
                    : s.templateUpdated()
                    ? "Persönliche Kopie der Vorlage Revision " + s.templateRevision() + " – die Vorlage ist inzwischen "
                    + "bei Revision " + s.currentTemplateRevision() + ". Löschen der Kopie zeigt wieder die Vorlage."
                    : "Persönliche Kopie der globalen Vorlage (Revision " + s.templateRevision() + "); sie verdeckt die "
                    + "Vorlage. Löschen der Kopie zeigt wieder die Vorlage.";
            case OWN -> null;
        };
    }

    private void updateActions(SkillViews.Summary s) {
        boolean admin = account.get().map(Me::admin).orElse(false);
        delete.setDisable(s == null || s.global());
        publish.setVisible(admin);
        publish.setManaged(admin);
        unpublish.setVisible(admin);
        unpublish.setManaged(admin);
        publish.setDisable(s == null || s.global());
        unpublish.setDisable(s == null || !s.global());
    }

    /** Filtert lokal über Name, Beschreibung und Tags; die Volltextsuche im Inhalt bietet skills_list. */
    static boolean matchesQuery(SkillViews.Summary s, String q) {
        if (q.isEmpty()) {
            return true;
        }
        return s.name().toLowerCase(Locale.ROOT).contains(q)
                || s.description().toLowerCase(Locale.ROOT).contains(q)
                || s.tags().stream().anyMatch(t -> t.contains(q));
    }

    private void showDetails(SkillViews.Summary s) {
        if (s == null) {
            detailHolder.getChildren().setAll(placeholder);
            return;
        }
        background(() -> service.details(s.name()), d -> {
            if (d.isEmpty()) {
                detailHolder.getChildren().setAll(placeholder);
                return;
            }
            SkillViews.Details det = d.get();
            SkillViews.Summary sum = det.summary();
            title.setText(sum.name());
            description.setText(sum.description());
            meta.setText(metaLine(det));
            String hint = scopeHint(sum);
            readOnlyHint.setText(hint == null ? "" : hint);
            readOnlyHint.setVisible(hint != null);
            updateActions(sum);
            content.setText(det.content());
            content.positionCaret(0);
            fileList.getItems().setAll(det.files());
            fileContent.clear();
            if (!det.files().isEmpty()) {
                fileList.getSelectionModel().selectFirst();
            }
            filesTab.setText("Dateien (" + det.files().size() + ")");
            revisionTable.getItems().setAll(det.revisions());
            revisionContent.clear();
            historyTab.setText("Historie (" + det.revisions().size() + ")");
            detailHolder.getChildren().setAll(detail);
        });
    }

    static String metaLine(SkillViews.Details d) {
        SkillViews.Summary s = d.summary();
        StringBuilder sb = new StringBuilder();
        sb.append(s.category() == null ? NO_CATEGORY : s.category());
        if (!s.tags().isEmpty()) {
            sb.append("  ·  Tags: ").append(String.join(", ", s.tags()));
        }
        if (!s.triggers().isEmpty()) {
            sb.append("  ·  Registriert für: ").append(String.join(", ", s.triggers()));
        }
        sb.append("  ·  Revision ").append(s.revision())
                .append("  ·  angelegt ").append(TIME.format(d.createdAt()))
                .append("  ·  geändert ").append(TIME.format(s.updatedAt()))
                .append("  ·  ").append(s.useCount()).append("× geladen");
        if (s.lastUsedAt() != null) {
            sb.append(", zuletzt ").append(TIME.format(s.lastUsedAt()));
        }
        return sb.toString();
    }

    private void confirmDelete(SkillViews.Summary s) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, "Skill „" + s.name() + "“ samt Zusatzdateien und "
                + "Historie endgültig löschen?", ButtonType.CANCEL, ButtonType.OK);
        a.setHeaderText(null);
        if (getScene() != null) {
            a.initOwner(getScene().getWindow());
        }
        a.showAndWait().filter(b -> b == ButtonType.OK)
                .ifPresent(b -> background(() -> service.delete(s.name()), msg -> refresh()));
    }

    private void confirmPublish(SkillViews.Summary s) {
        confirm("Skill „" + s.name() + "“ als globale Vorlage für alle Benutzer veröffentlichen? Eine bestehende "
                + "Vorlage gleichen Namens wird mit deinem Stand aktualisiert.", () -> service.publish(s.name()));
    }

    private void confirmUnpublish(SkillViews.Summary s) {
        confirm("Globale Vorlage „" + s.name() + "“ zurückziehen? Persönliche Kopien der Benutzer bleiben erhalten.",
                () -> service.unpublish(s.name()));
    }

    private void confirm(String question, Supplier<String> action) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, question, ButtonType.CANCEL, ButtonType.OK);
        a.setHeaderText(null);
        if (getScene() != null) {
            a.initOwner(getScene().getWindow());
        }
        a.showAndWait().filter(b -> b == ButtonType.OK).ifPresent(b -> background(action, msg -> refresh()));
    }

    private Optional<SkillViews.Summary> selected() {
        return Optional.ofNullable(table.getSelectionModel().getSelectedItem());
    }

    /** Wählt im Detailbereich Inhalt (0), Dateien (1) oder Historie (2) und darin den ersten Eintrag (für Sichttests). */
    void selectDetailTabForTest(int index) {
        if (detail != null && detail.getChildren().getLast() instanceof TabPane tabs) {
            tabs.getSelectionModel().select(index);
            if (index == 2 && !revisionTable.getItems().isEmpty()) {
                revisionTable.getSelectionModel().selectFirst();
            }
        }
    }

    /** Wählt einen Skill per Name aus (für Sichttests). */
    void selectForTest(String name) {
        filtered.stream().filter(s -> s.name().equals(name)).findFirst().ifPresent(s -> {
            table.getSelectionModel().select(s);
            table.scrollTo(s);
        });
    }

    /** Datenbankzugriff außerhalb des FX-Threads, Ergebnis im FX-Thread. */
    private <T> void background(Supplier<T> work, Consumer<T> onFx) {
        Thread.ofVirtual().start(() -> {
            try {
                T result = work.get();
                Platform.runLater(() -> onFx.accept(result));
            } catch (RuntimeException e) {
                Platform.runLater(() -> error(e.getMessage()));
            }
        });
    }

    private void error(String msg) {
        Alert a = new Alert(Alert.AlertType.WARNING, msg);
        a.setHeaderText(null);
        if (getScene() != null) {
            a.initOwner(getScene().getWindow());
        }
        a.show();
    }

    // ------------------------------------------------------------------ Hilfen

    private static TextArea monoArea() {
        TextArea a = new TextArea();
        a.setEditable(false);
        a.setWrapText(true);
        a.getStyleClass().add("mono");
        return a;
    }

    private static TableColumn<SkillViews.Summary, String> col(String title, double width,
                                                               Function<SkillViews.Summary, String> f) {
        TableColumn<SkillViews.Summary, String> c = new TableColumn<>(title);
        c.setPrefWidth(width);
        c.setCellValueFactory(cd -> new ReadOnlyStringWrapper(f.apply(cd.getValue())));
        return c;
    }

    private static TableColumn<SkillViews.Summary, Number> numberCol(String title, double width,
                                                                     Function<SkillViews.Summary, Number> f) {
        TableColumn<SkillViews.Summary, Number> c = new TableColumn<>(title);
        c.setPrefWidth(width);
        c.setMaxWidth(width + 20);
        c.setStyle("-fx-alignment: CENTER-RIGHT;");
        c.setCellValueFactory(cd -> new ReadOnlyObjectWrapper<>(f.apply(cd.getValue())));
        return c;
    }

    private static TableColumn<SkillViews.Revision, String> revCol(String title, double width,
                                                                   Function<SkillViews.Revision, String> f) {
        TableColumn<SkillViews.Revision, String> c = new TableColumn<>(title);
        c.setPrefWidth(width);
        c.setCellValueFactory(cd -> new ReadOnlyStringWrapper(f.apply(cd.getValue())));
        return c;
    }

    static List<String> categories(List<SkillViews.Summary> list) {
        TreeSet<String> cats = new TreeSet<>();
        list.forEach(s -> cats.add(s.category() == null ? NO_CATEGORY : s.category()));
        return List.copyOf(cats);
    }
}
