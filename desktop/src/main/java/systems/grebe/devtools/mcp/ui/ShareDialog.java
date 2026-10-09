package systems.grebe.devtools.mcp.ui;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import systems.grebe.devtools.mcp.modules.shares.ShareViews;

/**
 * Freigaben eines eigenen Skills bzw. einer eigenen Memory: oben die bestehenden (entfernen), unten ein Ziel wählen –
 * Benutzer, Rolle oder alle – und hinzufügen. Jede Änderung wirkt sofort im Backend.
 */
final class ShareDialog extends Dialog<Void> {

    private final ListView<ShareViews.Share> current = new ListView<>();
    private final ComboBox<ShareViews.Candidate> target = new ComboBox<>();
    private final Label status = new Label();
    private final Supplier<List<ShareViews.Share>> load;
    private final BiFunction<ShareViews.Request, Boolean, String> apply;

    /**
     * @param what  z.B. „Skill „heap-leak““
     * @param apply freigeben bzw. zurücknehmen ({@code true}); liefert die Meldung des Backends
     */
    ShareDialog(Window owner, String what, Supplier<List<ShareViews.Candidate>> candidates,
                Supplier<List<ShareViews.Share>> load, BiFunction<ShareViews.Request, Boolean, String> apply) {
        this.load = load;
        this.apply = apply;
        setTitle("Teilen");
        setHeaderText(what + " teilen");
        if (owner != null) {
            initOwner(owner);
        }
        getDialogPane().getButtonTypes().setAll(ButtonType.CLOSE);

        current.setPrefHeight(160);
        current.setPlaceholder(new Label("Noch nicht geteilt."));
        current.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(ShareViews.Share s, boolean empty) {
                super.updateItem(s, empty);
                setText(empty || s == null ? null : label(s));
            }
        });
        Button remove = new Button("Entfernen");
        remove.disableProperty().bind(current.getSelectionModel().selectedItemProperty().isNull());
        remove.setOnAction(e -> {
            ShareViews.Share s = current.getSelectionModel().getSelectedItem();
            if (s != null) {
                change(request(s.target(), s.name()), true);
            }
        });

        target.setPromptText("Benutzer, Rolle oder alle");
        target.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(target, Priority.ALWAYS);
        target.setCellFactory(lv -> new CandidateCell());
        target.setButtonCell(new CandidateCell());
        Button add = new Button("Hinzufügen");
        add.disableProperty().bind(target.valueProperty().isNull());
        add.setOnAction(e -> {
            ShareViews.Candidate c = target.getValue();
            if (c != null) {
                change(request(c.target(), c.name()), false);
            }
        });
        HBox addRow = new HBox(8, target, add);
        addRow.setAlignment(Pos.CENTER_LEFT);
        status.getStyleClass().add("form-help");
        status.setWrapText(true);
        Label help = new Label("Empfänger sehen immer den aktuellen Stand, schreibgeschützt; ändern sie einen "
                + "geteilten Skill, bekommen sie eine eigene Kopie.");
        help.getStyleClass().add("form-help");
        help.setWrapText(true);
        HBox removeRow = new HBox(remove);
        removeRow.setAlignment(Pos.CENTER_RIGHT);
        VBox box = new VBox(8, new Label("Geteilt mit"), current, removeRow, new Label("Teilen mit"), addRow, help,
                status);
        box.setPadding(new Insets(10));
        box.setPrefWidth(460);
        getDialogPane().setContent(box);

        background(candidates, list -> target.getItems().setAll(list));
        reload();
    }

    static String label(ShareViews.Share s) {
        return switch (s.target()) {
            case USER -> "Benutzer " + s.name();
            case ROLE -> "Rolle " + s.name();
            case ALL -> "Alle Benutzer";
        };
    }

    static ShareViews.Request request(ShareViews.Target target, String name) {
        return switch (target) {
            case USER -> new ShareViews.Request(List.of(name), null, false);
            case ROLE -> new ShareViews.Request(null, List.of(name), false);
            case ALL -> new ShareViews.Request(null, null, true);
        };
    }

    private void change(ShareViews.Request request, boolean revoke) {
        background(() -> apply.apply(request, revoke), msg -> {
            status.setText(msg);
            reload();
        });
    }

    private void reload() {
        background(load, list -> current.getItems().setAll(list));
    }

    /** Backend-Zugriff außerhalb des FX-Threads, Ergebnis bzw. Fehler im FX-Thread. */
    private <T> void background(Supplier<T> work, Consumer<T> onFx) {
        Thread.ofVirtual().start(() -> {
            try {
                T result = work.get();
                Platform.runLater(() -> onFx.accept(result));
            } catch (RuntimeException e) {
                Platform.runLater(() -> status.setText(e.getMessage()));
            }
        });
    }

    private static final class CandidateCell extends ListCell<ShareViews.Candidate> {
        @Override
        protected void updateItem(ShareViews.Candidate c, boolean empty) {
            super.updateItem(c, empty);
            setText(empty || c == null ? null : switch (c.target()) {
                case USER -> c.label() + " – " + c.name();
                case ROLE -> "Rolle " + c.label();
                case ALL -> "Alle Benutzer";
            });
        }
    }
}
