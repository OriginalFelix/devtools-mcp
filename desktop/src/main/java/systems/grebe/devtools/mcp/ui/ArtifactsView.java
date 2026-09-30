package systems.grebe.devtools.mcp.ui;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Supplier;

import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import systems.grebe.devtools.mcp.modules.java.ArtifactStore;
import systems.grebe.devtools.mcp.modules.java.JavaEnvironment;

/**
 * Liste der Diagnose-Artefakte (JFR, Heap-Dumps, Flame Graphs, Snapshots) mit Öffnen im Browser,
 * in VisualVM oder im Dateimanager.
 */
public class ArtifactsView extends BorderPane {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM. HH:mm:ss").withZone(ZoneId.systemDefault());

    public interface VisualVmOpener {
        String open(Path file);
    }

    private final Supplier<JavaEnvironment> env;
    private final ObservableList<Path> items = FXCollections.observableArrayList();
    private final Label dirLabel = new Label();
    private final TableView<Path> table = new TableView<>(items);

    public ArtifactsView(Supplier<JavaEnvironment> env, HostServices host, VisualVmOpener visualVm) {
        this.env = env;
        table.setPlaceholder(new Label("Noch keine Artefakte. Sie entstehen durch jfr_record, asprof_profile, jvm_heap_dump, …"));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getColumns().add(col("Zeit", 130, p -> TIME.format(Instant.ofEpochMilli(modified(p)))));
        table.getColumns().add(col("Art", 110, ArtifactsView::kind));
        table.getColumns().add(col("Größe", 90, p -> ArtifactStore.humanSize(size(p))));
        table.getColumns().add(col("Datei", 500, p -> p.getFileName().toString()));
        table.getColumns().forEach(c -> c.setReorderable(false));
        // feste schmale Spalten, der Rest geht an den Dateinamen
        for (int i = 0; i < 3; i++) {
            TableColumn<Path, ?> c = table.getColumns().get(i);
            c.setMinWidth(c.getPrefWidth());
            c.setMaxWidth(c.getPrefWidth() + 40);
        }

        Button open = new Button("Öffnen");
        open.getStyleClass().add("accent");
        open.setOnAction(e -> selected().ifPresent(p -> openDefault(p, host)));
        Button vvm = new Button("In VisualVM öffnen");
        vvm.setOnAction(e -> selected().ifPresent(p -> runAsync(() -> visualVm.open(p))));
        Button folder = new Button("Ordner öffnen");
        folder.setOnAction(e -> openFolder());
        Button delete = new Button("Löschen");
        delete.setOnAction(e -> selected().ifPresent(this::delete));
        Button refresh = new Button("Aktualisieren");
        refresh.setOnAction(e -> refresh());
        var sel = table.getSelectionModel().selectedItemProperty();
        open.disableProperty().bind(sel.isNull());
        delete.disableProperty().bind(sel.isNull());
        vvm.disableProperty().bind(javafx.beans.binding.Bindings.createBooleanBinding(
                () -> sel.get() == null || !visualVmCompatible(sel.get()), sel));
        table.setRowFactory(tv -> {
            var row = new javafx.scene.control.TableRow<Path>();
            row.setOnMouseClicked(ev -> {
                if (ev.getClickCount() == 2 && !row.isEmpty()) {
                    openDefault(row.getItem(), host);
                }
            });
            return row;
        });

        dirLabel.getStyleClass().add("form-help");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(8, open, vvm, folder, delete, spacer, dirLabel, refresh);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(10, 12, 10, 12));
        setTop(bar);
        setCenter(table);

        ArtifactStore.addListener(() -> Platform.runLater(this::refresh));
        refresh();
    }

    public void refresh() {
        try {
            ArtifactStore s = env.get().artifacts();
            dirLabel.setText(s.dir().toString());
            Path sel = table.getSelectionModel().getSelectedItem();
            items.setAll(s.list());
            if (sel != null && items.contains(sel)) {
                table.getSelectionModel().select(sel);
            }
        } catch (RuntimeException e) {
            dirLabel.setText("Ablageordner nicht verfügbar: " + e.getMessage());
        }
    }

    private java.util.Optional<Path> selected() {
        return java.util.Optional.ofNullable(table.getSelectionModel().getSelectedItem());
    }

    private void openDefault(Path p, HostServices host) {
        String n = p.getFileName().toString();
        if (n.endsWith(".html")) {
            host.showDocument(p.toUri().toString());
            return;
        }
        runAsync(() -> {
            try {
                Desktop.getDesktop().open(p.toFile());
                return null;
            } catch (IOException | UnsupportedOperationException ex) {
                return "Keine Anwendung für " + n + " registriert – „In VisualVM öffnen“ verwenden.";
            }
        });
    }

    private void openFolder() {
        runAsync(() -> {
            try {
                Path dir = env.get().artifacts().dir();
                Files.createDirectories(dir);
                Desktop.getDesktop().open(dir.toFile());
                return null;
            } catch (IOException | UnsupportedOperationException ex) {
                return ex.getMessage();
            }
        });
    }

    private void delete(Path p) {
        try {
            env.get().artifacts().delete(p);
        } catch (IOException e) {
            error(e.getMessage());
        }
    }

    /** Führt eine Aktion im Hintergrund aus; eine zurückgegebene Meldung (außer Erfolgsmeldung) wird angezeigt. */
    private void runAsync(Supplier<String> action) {
        Thread.ofVirtual().start(() -> {
            try {
                String msg = action.get();
                if (msg != null && !msg.startsWith("VisualVM gestartet")) {
                    Platform.runLater(() -> error(msg));
                }
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

    static boolean visualVmCompatible(Path p) {
        String n = p.getFileName().toString();
        return n.endsWith(".hprof") || n.endsWith(".jfr") || n.endsWith(".nps") || n.endsWith(".tdump");
    }

    private static String kind(Path p) {
        String n = p.getFileName().toString();
        if (n.endsWith("-flamegraph.html")) {
            return "Flame Graph";
        }
        if (n.endsWith(".jfr")) {
            return n.contains("asprof") ? "async-profiler" : "JFR";
        }
        if (n.endsWith(".hprof")) {
            return "Heap-Dump";
        }
        if (n.endsWith(".nps")) {
            return "VisualVM-Snapshot";
        }
        int dot = n.lastIndexOf('.');
        return dot < 0 ? "" : n.substring(dot + 1);
    }

    private static TableColumn<Path, String> col(String title, double width, java.util.function.Function<Path, String> f) {
        TableColumn<Path, String> c = new TableColumn<>(title);
        c.setPrefWidth(width);
        c.setCellValueFactory(cd -> new ReadOnlyStringWrapper(f.apply(cd.getValue())));
        return c;
    }

    private static long modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    private static long size(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0;
        }
    }
}
