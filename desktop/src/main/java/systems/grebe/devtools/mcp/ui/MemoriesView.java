package systems.grebe.devtools.mcp.ui;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
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
import javafx.util.Duration;
import systems.grebe.devtools.mcp.api.MediaTypes;
import systems.grebe.devtools.mcp.modules.memories.MemoryBackend;
import systems.grebe.devtools.mcp.modules.memories.MemoryViews;

/**
 * Memories des aktuellen Benutzers: links die neuesten bzw. – mit Suchtext – die besten Treffer (gesucht wird im
 * Backend wie bei {@code memories_search}), rechts der vollständige Eintrag. Aktualisiert sich selbst, sobald das LLM
 * eine Memory anlegt oder ergänzt.
 */
public class MemoriesView extends BorderPane {

    static final int LIMIT = 200;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM.yy HH:mm")
            .withZone(ZoneId.systemDefault());

    private final MemoryBackend service;
    private final TableView<MemoryViews.Entry> table = new TableView<>();
    private final TextField search = new TextField();
    private final Label countLabel = new Label();
    private final Button delete = new Button("Löschen…");
    private final StackPane detailHolder = new StackPane();
    private final Label placeholder = new Label("Memory auswählen");
    private final PauseTransition debounce = new PauseTransition(Duration.millis(300));

    private final Label title = new Label();
    private final Label meta = new Label();
    private final TextArea content = new TextArea();
    private final javafx.scene.control.ListView<MemoryViews.File> files = new javafx.scene.control.ListView<>();
    private final Button saveFile = new Button("Datei speichern unter…");
    private final HBox fileBox = new HBox(8);
    private final VBox detail;

    public MemoriesView(MemoryBackend service) {
        this.service = service;
        getStyleClass().add("memories-view");
        detail = buildDetail();
        placeholder.getStyleClass().add("form-help");
        detailHolder.getStyleClass().add("detail-holder");
        detailHolder.getChildren().setAll(placeholder);

        SplitPane split = new SplitPane(buildList(), detailHolder);
        split.setDividerPositions(0.5);
        SplitPane.setResizableWithParent(split.getItems().getFirst(), false);
        setTop(buildToolbar());
        setCenter(split);

        service.addChangeListener(() -> Platform.runLater(this::refresh));
        refresh();
    }

    // ------------------------------------------------------------------ Aufbau

    private HBox buildToolbar() {
        search.setPromptText("Suchen (Titel, Inhalt, Ticket, Projekt, Skill, Tags)");
        search.setPrefWidth(360);
        debounce.setOnFinished(e -> refresh());
        search.textProperty().addListener((o, a, b) -> debounce.playFromStart());
        Button refresh = new Button("Aktualisieren");
        refresh.setOnAction(e -> refresh());
        delete.setOnAction(e -> selected().ifPresent(this::confirmDelete));
        delete.setDisable(true);
        table.getSelectionModel().selectedItemProperty().addListener((o, a, m) -> delete.setDisable(m == null));
        countLabel.getStyleClass().add("form-help");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        for (javafx.scene.control.Control c : List.of(refresh, delete, countLabel)) {
            c.setMinWidth(Region.USE_PREF_SIZE);
        }
        HBox bar = new HBox(8, search, spacer, countLabel, refresh, delete);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(10, 12, 8, 12));
        return bar;
    }

    private TableView<MemoryViews.Entry> buildList() {
        table.setPlaceholder(new Label("Noch keine Memories. Das LLM hält abgeschlossene Aktionen mit "
                + "memories_save fest."));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getColumns().add(col("Nr.", 48, m -> "#" + m.id()));
        table.getColumns().add(col("Zeit", 100, m -> TIME.format(m.createdAt())));
        table.getColumns().add(col("Typ", 70, m -> MemoryViews.Type.orDefault(m.type()).label()));
        table.getColumns().add(col("Projekt", 100, m -> orEmpty(m.project())));
        table.getColumns().add(col("Skill", 110, m -> orEmpty(m.skill())));
        table.getColumns().add(col("Bezug", 90, m -> orEmpty(m.reference())));
        table.getColumns().add(col("Titel", 300, MemoryViews.Entry::title));
        table.getColumns().forEach(c -> c.setReorderable(false));
        table.getSelectionModel().selectedItemProperty().addListener((o, a, m) -> showDetails(m));
        return table;
    }

    private VBox buildDetail() {
        title.getStyleClass().add("detail-title");
        title.setWrapText(true);
        meta.getStyleClass().add("form-help");
        meta.setWrapText(true);
        meta.setMinHeight(Region.USE_PREF_SIZE);
        content.setEditable(false);
        content.setWrapText(true);
        content.getStyleClass().add("mono");
        VBox.setVgrow(content, Priority.ALWAYS);
        files.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(MemoryViews.File f, boolean empty) {
                super.updateItem(f, empty);
                setText(empty || f == null ? null
                        : f.path() + "  (" + f.mediaType() + ", " + MediaTypes.size(f.size()) + ")");
            }
        });
        files.setPrefHeight(90);
        files.getSelectionModel().selectedItemProperty().addListener((o, a, f) -> saveFile.setDisable(f == null));
        saveFile.setOnAction(e -> saveSelectedFile());
        HBox.setHgrow(files, Priority.ALWAYS);
        fileBox.getChildren().setAll(files, saveFile);
        fileBox.managedProperty().bind(fileBox.visibleProperty());
        VBox box = new VBox(6, title, meta, content, fileBox);
        box.setPadding(new Insets(14, 16, 12, 16));
        return box;
    }

    // ------------------------------------------------------------------ Verhalten

    /** Lädt die Liste neu (Hintergrund-Thread) und behält die Auswahl. */
    public void refresh() {
        String query = search.getText();
        background(() -> service.overview(query, null, null, LIMIT), list -> {
            Long keep = selected().map(MemoryViews.Entry::id).orElse(null);
            table.getItems().setAll(list);
            boolean filtered = query != null && !query.isBlank();
            countLabel.setText(list.size() + (list.size() == 1 ? " Memory" : " Memories")
                    + (filtered ? " gefunden" : list.size() >= LIMIT ? " (neueste)" : ""));
            Optional<MemoryViews.Entry> again = keep == null ? Optional.empty()
                    : list.stream().filter(m -> m.id() == keep).findFirst();
            again.ifPresentOrElse(m -> {
                table.getSelectionModel().select(m);
                showDetails(m);
            }, () -> showDetails(null));
        });
    }

    private void showDetails(MemoryViews.Entry m) {
        if (m == null) {
            detailHolder.getChildren().setAll(placeholder);
            return;
        }
        title.setText("#" + m.id() + "  " + m.title());
        meta.setText(metaLine(m));
        content.setText(m.content());
        content.positionCaret(0);
        files.getItems().setAll(m.files());
        saveFile.setDisable(true);
        fileBox.setVisible(!m.files().isEmpty());
        detailHolder.getChildren().setAll(detail);
    }

    private void saveSelectedFile() {
        MemoryViews.File f = files.getSelectionModel().getSelectedItem();
        Optional<MemoryViews.Entry> m = selected();
        if (f == null || m.isEmpty()) {
            return;
        }
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle("Angehängte Datei speichern");
        chooser.setInitialFileName(f.path().substring(f.path().lastIndexOf('/') + 1));
        java.io.File target = chooser.showSaveDialog(getScene() == null ? null : getScene().getWindow());
        if (target != null) {
            long id = m.get().id();
            background(() -> service.exportFile(id, f.path(), target.toPath()), msg -> { });
        }
    }

    static String metaLine(MemoryViews.Entry m) {
        List<String> parts = new ArrayList<>();
        if (m.ephemeral()) {
            parts.add(m.type().label());
        }
        if (m.project() != null) {
            parts.add("Projekt: " + m.project());
        }
        if (m.skill() != null) {
            parts.add("Skill: " + m.skill());
        }
        if (m.reference() != null) {
            parts.add("Bezug: " + m.reference());
        }
        if (!m.tags().isEmpty()) {
            parts.add("Tags: " + String.join(", ", m.tags()));
        }
        parts.add("angelegt " + TIME.format(m.createdAt()));
        if (!m.updatedAt().equals(m.createdAt())) {
            parts.add("geändert " + TIME.format(m.updatedAt()));
        }
        return String.join("  ·  ", parts);
    }

    private void confirmDelete(MemoryViews.Entry m) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, "Memory #" + m.id() + " „" + m.title()
                + "“ endgültig löschen?", ButtonType.CANCEL, ButtonType.OK);
        a.setHeaderText(null);
        if (getScene() != null) {
            a.initOwner(getScene().getWindow());
        }
        a.showAndWait().filter(b -> b == ButtonType.OK)
                .ifPresent(b -> background(() -> service.delete(m.id(), false), msg -> refresh()));
    }

    private Optional<MemoryViews.Entry> selected() {
        return Optional.ofNullable(table.getSelectionModel().getSelectedItem());
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

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private static TableColumn<MemoryViews.Entry, String> col(String title, double width,
                                                              Function<MemoryViews.Entry, String> f) {
        TableColumn<MemoryViews.Entry, String> c = new TableColumn<>(title);
        c.setPrefWidth(width);
        c.setCellValueFactory(cd -> new ReadOnlyStringWrapper(f.apply(cd.getValue())));
        return c;
    }
}
