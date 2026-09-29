package systems.grebe.devtools.mcp.ui;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import systems.grebe.devtools.mcp.core.ModuleAction;
import systems.grebe.devtools.mcp.core.ToolRegistry;

/**
 * Bedienelement für eine {@link ModuleAction}: Zielauswahl, Optionen, Start/Abbrechen, Fortschritt, Ergebnis.
 * Die Aktion läuft in einem virtuellen Thread; „Abbrechen“ unterbricht ihn.
 */
final class ModuleActionPanel extends VBox {

    private final ToolRegistry registry;
    private final String moduleId;
    private final ModuleAction action;
    private final ComboBox<String> target = new ComboBox<>();
    private final Label targetInfo = new Label();
    private final Button start;
    private final Button cancel = new Button("Abbrechen");
    private final ProgressBar progress = new ProgressBar(0);
    private final Label progressText = new Label();
    private final Label result = new Label();
    private final java.util.Map<String, CheckBox> flags = new java.util.LinkedHashMap<>();
    private Task<ModuleAction.ActionResult> running;

    ModuleActionPanel(ToolRegistry registry, String moduleId, ModuleAction action) {
        super(8);
        this.registry = registry;
        this.moduleId = moduleId;
        this.action = action;
        getStyleClass().add("module-action");

        Label description = new Label(action.description());
        description.setWrapText(true);
        description.getStyleClass().add("form-help");

        start = new Button(action.label());
        start.getStyleClass().add("accent");
        start.setOnAction(e -> start());
        cancel.setOnAction(e -> cancel());
        cancel.setDisable(true);

        target.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(target, Priority.ALWAYS);
        target.valueProperty().addListener((obs, old, v) -> updateTargetInfo());

        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getChildren().add(target);
        for (ModuleAction.Flag f : action.flags()) {
            CheckBox cb = new CheckBox(f.label());
            flags.put(f.key(), cb);
            row.getChildren().add(cb);
        }
        row.getChildren().addAll(start, cancel);

        targetInfo.getStyleClass().add("form-help");
        targetInfo.setWrapText(true);
        progress.setMaxWidth(Double.MAX_VALUE);
        progress.setVisible(false);
        progress.setManaged(false);
        progressText.getStyleClass().add("status-text");
        result.getStyleClass().add("status-text");
        result.setWrapText(true);

        getChildren().addAll(description, row, targetInfo, progress, progressText, result);
        refreshTargets();
    }

    /** Ziele aus der gespeicherten Konfiguration neu laden; Auswahl bleibt erhalten, wenn möglich. */
    void refreshTargets() {
        if (running != null) {
            return; // Auswahl während des Laufs nicht verändern
        }
        List<String> targets = registry.actionTargets(moduleId, action.id());
        boolean hasTargets = !targets.isEmpty();
        String selected = target.getValue();
        if (!Objects.equals(target.getItems(), targets)) {
            target.getItems().setAll(targets);
        }
        if (selected != null && targets.contains(selected)) {
            target.setValue(selected);
        } else if (targets.size() == 1) {
            target.setValue(targets.getFirst());
        } else if (!targets.contains(selected)) {
            target.setValue(null);
        }
        target.setPromptText(hasTargets ? "Projekt wählen"
                : "Keine Projekte – in der Konfiguration eintragen und speichern");
        updateButtons();
        updateTargetInfo();
    }

    private void updateTargetInfo() {
        String t = target.getValue();
        if (t == null) {
            targetInfo.setText("");
            updateButtons();
            return;
        }
        // Kopf der Graph-Datei lesen o.Ä. – kurz, aber nicht im UI-Thread
        Thread.ofVirtual().start(() -> {
            String info = registry.describeActionTarget(moduleId, action.id(), t);
            Platform.runLater(() -> {
                if (Objects.equals(t, target.getValue())) {
                    targetInfo.setText(info == null ? "" : info);
                }
            });
        });
        updateButtons();
    }

    private void updateButtons() {
        boolean busy = running != null;
        start.setDisable(busy || target.getValue() == null);
        cancel.setDisable(!busy);
        target.setDisable(busy || target.getItems().isEmpty());
        flags.values().forEach(cb -> cb.setDisable(busy));
    }

    private void start() {
        String t = target.getValue();
        Set<String> selectedFlags = new LinkedHashSet<>();
        flags.forEach((k, cb) -> {
            if (cb.isSelected()) {
                selectedFlags.add(k);
            }
        });
        result.setText("");
        result.getStyleClass().removeAll("ok", "error");
        progress.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        progress.setVisible(true);
        progress.setManaged(true);
        progressText.setText("Starte …");

        Task<ModuleAction.ActionResult> task = new Task<>() {
            @Override
            protected ModuleAction.ActionResult call() {
                return registry.runAction(moduleId, action.id(), t, selectedFlags, (message, fraction) -> {
                    updateMessage(message);
                    if (fraction < 0) {
                        updateProgress(-1, 1);
                    } else {
                        updateProgress(Math.min(1, fraction), 1);
                    }
                });
            }
        };
        progress.progressProperty().bind(task.progressProperty());
        progressText.textProperty().bind(task.messageProperty());
        task.setOnSucceeded(e -> finish(task.getValue()));
        task.setOnFailed(e -> finish(ModuleAction.ActionResult.failed(String.valueOf(task.getException().getMessage()))));
        task.setOnCancelled(e -> finish(ModuleAction.ActionResult.failed("Abgebrochen.")));
        running = task;
        updateButtons();
        Thread.ofVirtual().name("action-" + moduleId + "-" + action.id()).start(task);
    }

    private void cancel() {
        if (running != null) {
            progressText.textProperty().unbind();
            progressText.setText("Breche ab …");
            running.cancel(true);
        }
    }

    private void finish(ModuleAction.ActionResult r) {
        progress.progressProperty().unbind();
        progressText.textProperty().unbind();
        progress.setVisible(false);
        progress.setManaged(false);
        progressText.setText("");
        running = null;
        result.getStyleClass().removeAll("ok", "error");
        result.getStyleClass().add(r.success() ? "ok" : "error");
        result.setText(r.message());
        updateButtons();
        updateTargetInfo();
    }

    boolean isRunning() {
        return running != null;
    }
}
