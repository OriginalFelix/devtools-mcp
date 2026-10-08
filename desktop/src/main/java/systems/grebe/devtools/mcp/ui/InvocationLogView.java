package systems.grebe.devtools.mcp.ui;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import systems.grebe.devtools.mcp.core.TokenStats;
import systems.grebe.devtools.mcp.core.ToolInvocation;
import systems.grebe.devtools.mcp.core.ToolInvocationLog;

/** Live-Protokoll der Tool-Aufrufe. */
public class InvocationLogView extends BorderPane {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final int MAX_ROWS = 500;

    private final ObservableList<ToolInvocation> items = FXCollections.observableArrayList();

    public InvocationLogView(ToolInvocationLog log) {
        getStyleClass().add("log-view");
        items.setAll(log.snapshot());
        FilteredList<ToolInvocation> filtered = new FilteredList<>(items);

        TableView<ToolInvocation> table = new TableView<>(filtered);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("Noch keine Tool-Aufrufe. Verbinde einen MCP-Client (siehe „Client verbinden“)."));
        table.getColumns().add(column("Zeit", 80, i -> TIME.format(i.timestamp())));
        TableColumn<ToolInvocation, String> status = column("", 36, i -> i.success() ? "✔" : "✖");
        status.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                getStyleClass().removeAll("ok", "error");
                if (!empty && getTableRow() != null && getTableRow().getItem() != null) {
                    getStyleClass().add(getTableRow().getItem().success() ? "ok" : "error");
                }
            }
        });
        table.getColumns().add(status);
        status.setMinWidth(36);
        status.setMaxWidth(36);
        table.getColumns().forEach(c -> c.setReorderable(false));
        table.getColumns().add(column("Tool", 200, ToolInvocation::toolName));
        table.getColumns().add(column("Dauer", 80, i -> i.duration().toMillis() + " ms"));
        table.getColumns().add(column("≈Tokens", 110, InvocationLogView::tokens));
        table.getColumns().add(column("Argumente", 500, i -> oneLine(i.arguments())));
        table.getColumns().getFirst().setMaxWidth(90);
        table.getColumns().get(3).setMaxWidth(100);
        table.getColumns().get(4).setMaxWidth(140);

        TextArea args = detailArea();
        TextArea result = detailArea();
        table.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
            args.setText(n == null ? "" : n.arguments());
            result.setText(n == null ? "" : n.result());
        });

        TextField filter = new TextField();
        filter.setPromptText("Filter (Toolname) …");
        filter.textProperty().addListener((obs, o, n) -> filtered.setPredicate(
                n == null || n.isBlank() ? null : i -> i.toolName().toLowerCase().contains(n.toLowerCase())));
        Button clear = new Button("Leeren");
        clear.setOnAction(e -> log.clear());
        Button copy = new Button("Ergebnis kopieren");
        copy.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        copy.setOnAction(e -> {
            ClipboardContent cc = new ClipboardContent();
            cc.putString(result.getText());
            Clipboard.getSystemClipboard().setContent(cc);
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(8, filter, spacer, copy, clear);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(10, 12, 10, 12));
        HBox.setHgrow(filter, Priority.SOMETIMES);

        TableView<TokenStats.Entry> stats = statsTable();
        Label total = new Label();
        total.getStyleClass().add("section-title");
        Runnable refreshStats = () -> {
            stats.getItems().setAll(log.stats().snapshot());
            TokenStats.Entry t = log.stats().total();
            total.setText("Tokens je Tool – ≈" + fmt(t.sentTokens()) + " gesendet, ≈" + fmt(t.savedTokens())
                    + " gespart");
        };
        refreshStats.run();
        VBox statsBox = new VBox(4, total, stats);
        statsBox.setPadding(new Insets(6, 8, 8, 8));
        VBox.setVgrow(stats, Priority.ALWAYS);

        SplitPane details = new SplitPane(labeled("Argumente", args), labeled("Ergebnis", result), statsBox);
        details.setDividerPositions(0.3, 0.68);
        SplitPane main = new SplitPane(table, details);
        main.setOrientation(Orientation.VERTICAL);
        main.setDividerPositions(0.5);

        setTop(toolbar);
        setCenter(main);

        log.addListener(inv -> Platform.runLater(() -> {
            items.addFirst(inv);
            if (items.size() > MAX_ROWS) {
                items.remove(MAX_ROWS, items.size());
            }
            refreshStats.run();
        }));
        log.addClearListener(() -> Platform.runLater(() -> {
            items.clear();
            refreshStats.run();
        }));
    }

    /** Summen je Tool, teuerste zuerst – woran sich Kürzen und Aufräumen lohnen. */
    private static TableView<TokenStats.Entry> statsTable() {
        TableView<TokenStats.Entry> t = new TableView<>();
        t.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        t.setPlaceholder(new Label("Noch keine Aufrufe."));
        t.getColumns().add(statColumn("Tool", 160, TokenStats.Entry::tool));
        t.getColumns().add(statColumn("Aufrufe", 60, e -> fmt(e.calls())));
        t.getColumns().add(statColumn("≈Tokens", 80, e -> fmt(e.sentTokens())));
        t.getColumns().add(statColumn("Ø", 60, e -> fmt(e.sentTokens() / Math.max(1, e.calls()))));
        t.getColumns().add(statColumn("gespart", 80, e -> fmt(e.savedTokens())));
        return t;
    }

    private static TableColumn<TokenStats.Entry, String> statColumn(String title, double width,
                                                                   java.util.function.Function<TokenStats.Entry, String> value) {
        TableColumn<TokenStats.Entry, String> c = new TableColumn<>(title);
        c.setPrefWidth(width);
        c.setCellValueFactory(cd -> new ReadOnlyStringWrapper(value.apply(cd.getValue())));
        c.setSortable(false);
        c.setReorderable(false);
        return c;
    }

    /** Gesendete Tokens, bei gekürzten Ergebnissen mit der ursprünglichen Größe. */
    private static String tokens(ToolInvocation i) {
        int sent = i.resultTokens();
        int raw = TokenStats.estimate(i.rawChars());
        return raw > sent + 10 ? fmt(sent) + " (von " + fmt(raw) + ")" : fmt(sent);
    }

    private static String fmt(long n) {
        return String.format(java.util.Locale.GERMANY, "%,d", n);
    }

    private static TableColumn<ToolInvocation, String> column(String title, double width,
                                                            java.util.function.Function<ToolInvocation, String> value) {
        TableColumn<ToolInvocation, String> c = new TableColumn<>(title);
        c.setPrefWidth(width);
        c.setCellValueFactory(cd -> new ReadOnlyStringWrapper(value.apply(cd.getValue())));
        c.setSortable(false);
        return c;
    }

    private static TextArea detailArea() {
        TextArea ta = new TextArea();
        ta.setEditable(false);
        ta.getStyleClass().add("mono");
        return ta;
    }

    private static VBox labeled(String title, TextArea area) {
        Label l = new Label(title);
        l.getStyleClass().add("section-title");
        VBox box = new VBox(4, l, area);
        box.setPadding(new Insets(6, 8, 8, 8));
        VBox.setVgrow(area, Priority.ALWAYS);
        return box;
    }

    private static String oneLine(String s) {
        if (s == null) {
            return "";
        }
        String flat = s.replaceAll("\\s+", " ");
        return flat.length() > 200 ? flat.substring(0, 200) + "…" : flat;
    }
}
