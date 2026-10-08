package systems.grebe.devtools.mcp.ui;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.springframework.ai.tool.definition.ToolDefinition;
import systems.grebe.devtools.mcp.core.ConnectionTestResult;
import systems.grebe.devtools.mcp.core.ModuleAction;
import systems.grebe.devtools.mcp.core.ToolModule;
import systems.grebe.devtools.mcp.core.ToolRegistry;

/** Rechte Seite: Konfiguration und Tool-Auswahl eines Moduls. */
public class ModuleDetailPane extends ScrollPane {

    private final ToolRegistry registry;
    private final ToolModule module;
    private final VBox content = new VBox(16);
    private final VBox toolBox = new VBox(6);
    private final Label status = new Label();
    /** Welche Werte der Team-Server (bzw. seine Projekte) vorgibt – lokal geänderte Werte wirken dort nicht. */
    private final Label overlay = new Label();
    private final Button save = new Button("Speichern");
    private final Button revert = new Button("Verwerfen");
    private final ToggleButton enabled = new ToggleButton();
    private final List<ModuleActionPanel> actionPanels = new java.util.ArrayList<>();
    private ConfigForm form;
    private Map<String, String> savedValues;

    public ModuleDetailPane(ToolRegistry registry, ToolModule module) {
        this.registry = registry;
        this.module = module;
        getStyleClass().add("detail-scroll");
        setFitToWidth(true);
        content.setPadding(new Insets(20, 24, 24, 24));
        content.getStyleClass().add("detail");
        setContent(content);

        Label title = new Label(module.displayName());
        title.getStyleClass().add("detail-title");
        enabled.getStyleClass().add("switch");
        enabled.setOnAction(e -> {
            if (!apply(() -> registry.setModuleEnabled(module.id(), enabled.isSelected()))) {
                enabled.setSelected(!enabled.isSelected());
            }
            updateEnabledText();
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(12, title, spacer, enabled);
        header.setAlignment(Pos.CENTER_LEFT);

        Label description = new Label(module.description());
        description.setWrapText(true);
        description.getStyleClass().add("detail-description");

        Label configTitle = new Label("Konfiguration");
        configTitle.getStyleClass().add("section-title");

        save.getStyleClass().add("accent");
        save.setDefaultButton(true);
        save.setOnAction(e -> save());
        revert.setOnAction(e -> reload());
        Button test = new Button("Verbindung testen");
        test.setOnAction(e -> testConnection(test));
        status.getStyleClass().add("status-text");
        status.setWrapText(true);
        HBox actions = new HBox(8, save, revert, test);
        actions.setAlignment(Pos.CENTER_LEFT);

        Label toolsTitle = new Label("Tools");
        toolsTitle.getStyleClass().add("section-title");
        Label toolsHint = new Label("Einzelne Tools lassen sich abschalten, z.B. um die Tool-Liste des LLM schlank zu halten.");
        toolsHint.getStyleClass().add("form-help");
        toolsHint.setWrapText(true);

        VBox formHolder = new VBox();
        overlay.setWrapText(true);
        overlay.getStyleClass().add("form-help");
        content.getChildren().addAll(header, description, overlay, new Separator(), configTitle, formHolder, actions,
                status);
        if (!module.actions().isEmpty()) {
            Label actionsTitle = new Label("Aktionen");
            actionsTitle.getStyleClass().add("section-title");
            Label actionsHint = new Label("Laufen mit der gespeicherten Konfiguration, auch wenn das Modul inaktiv ist.");
            actionsHint.getStyleClass().add("form-help");
            actionsHint.setWrapText(true);
            content.getChildren().addAll(new Separator(), actionsTitle, actionsHint);
            for (ModuleAction a : module.actions()) {
                ModuleActionPanel panel = new ModuleActionPanel(registry, module.id(), a);
                actionPanels.add(panel);
                content.getChildren().add(panel);
            }
        }
        if (module.hasTools()) {
            content.getChildren().addAll(new Separator(), toolsTitle, toolsHint, toolBox);
        } else {
            enabled.setVisible(false);
            enabled.setManaged(false);
            configTitle.setText("Einstellungen (gelten für mehrere Module)");
        }

        this.formHolder = formHolder;
        reload();
    }

    private final VBox formHolder;

    /** Formular und Tool-Liste aus dem aktuellen Stand der Registry neu aufbauen. */
    public final void reload() {
        savedValues = registry.settings(module.id()).values();
        form = new ConfigForm(module.configSchema(), savedValues);
        form.setOnChange(this::updateDirty);
        formHolder.getChildren().setAll(form.node());
        status.setText("");
        status.getStyleClass().removeAll("ok", "error");
        refreshState();
        updateDirty();
    }

    /** Status/Tool-Liste aktualisieren, ohne das Formular zu verwerfen. */
    public void refreshState() {
        enabled.setSelected(registry.settings(module.id()).enabled());
        updateOverlay();
        updateEnabledText();
        rebuildToolList();
        actionPanels.forEach(ModuleActionPanel::refreshTargets); // z.B. nach Speichern neuer Projekte
    }

    private void updateOverlay() {
        boolean permitted = registry.modulePermitted(module.id());
        enabled.setDisable(!permitted);
        if (!permitted) {
            overlay.setText("Für dieses Modul fehlt dir das Recht – es bleibt aus. Rechte vergeben Rollen "
                    + "(Administrator: Tab „Benutzer“ bzw. Web-UI des Team-Servers).");
            return;
        }
        java.util.Set<String> locked = registry.lockedKeys(module.id());
        List<String> parts = new java.util.ArrayList<>();
        if (locked.contains("@enabled")) {
            parts.add("Modul an/aus");
        }
        module.configSchema().stream().filter(f -> locked.contains(f.key())).forEach(f -> parts.add(f.label()));
        if (locked.contains("@tools")) {
            parts.add("Tool-Auswahl");
        }
        overlay.setText("Änderungen gelten für " + registry.settingsTarget() + "."
                + (parts.isEmpty() ? "" : " Vom Administrator gesperrt (Änderungen werden abgelehnt): "
                + String.join(", ", parts)));
    }

    private void updateEnabledText() {
        enabled.setText(enabled.isSelected() ? "Aktiv" : "Inaktiv");
    }

    private void updateDirty() {
        boolean dirty = !Objects.equals(normalize(form.values()), normalize(savedValues));
        save.setDisable(!dirty);
        revert.setDisable(!dirty);
    }

    private Map<String, String> normalize(Map<String, String> values) {
        Map<String, String> out = new java.util.TreeMap<>();
        module.configSchema().forEach(f -> {
            String v = values.get(f.key());
            if (v == null || v.isBlank()) {
                v = f.defaultValue() == null ? "" : f.defaultValue();
            }
            out.put(f.key(), v.strip());
        });
        return out;
    }

    private void save() {
        List<String> errors = form.validate();
        if (!errors.isEmpty()) {
            Alert a = new Alert(Alert.AlertType.WARNING, String.join("\n", errors));
            a.setHeaderText("Bitte Eingaben prüfen");
            a.initOwner(getScene().getWindow());
            a.showAndWait();
            return;
        }
        // Warnungen (z.B. Freigaben heben Ausschlüsse auf) können eine Ordnersuche brauchen – im Hintergrund holen
        Map<String, String> values = form.values();
        save.setDisable(true);
        showStatus(null, "Prüfe Freigaben …");
        Task<List<String>> task = new Task<>() {
            @Override
            protected List<String> call() {
                return registry.saveWarnings(module.id(), values);
            }
        };
        task.setOnSucceeded(e -> {
            if (task.getValue().isEmpty() || confirm(task.getValue())) {
                store(values);
            } else {
                updateDirty();
                showStatus(null, "Nicht gespeichert.");
            }
        });
        task.setOnFailed(e -> {
            updateDirty();
            showStatus(false, String.valueOf(task.getException().getMessage()));
        });
        Thread.ofVirtual().start(task);
    }

    /** „Trotzdem speichern“ bestätigt die Warnungen. */
    private boolean confirm(List<String> warnings) {
        ButtonType anyway = new ButtonType("Trotzdem speichern", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Abbrechen", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert a = new Alert(Alert.AlertType.WARNING, String.join("\n", warnings), anyway, cancel);
        a.setHeaderText("Freigaben heben Ausschlüsse auf");
        a.initOwner(getScene().getWindow());
        a.getDialogPane().setMinWidth(760);
        return a.showAndWait().filter(anyway::equals).isPresent();
    }

    private void store(Map<String, String> values) {
        if (!apply(() -> registry.updateConfig(module.id(), values))) {
            updateDirty();
            return;
        }
        savedValues = registry.settings(module.id()).values();
        updateDirty();
        showStatus(true, "Gespeichert. Die Tools wurden neu registriert.");
    }

    /**
     * Speichert über die Registry; abgelehnte Änderungen (gesperrt, fehlendes Recht, Backend nicht erreichbar)
     * erscheinen als Meldung.
     *
     * @return ob gespeichert wurde
     */
    private boolean apply(Runnable change) {
        try {
            change.run();
            return true;
        } catch (IllegalArgumentException | IllegalStateException e) {
            showStatus(false, e.getMessage());
            return false;
        }
    }

    private void testConnection(Button button) {
        Map<String, String> values = form.values();
        button.setDisable(true);
        showStatus(null, "Prüfe …");
        Task<ConnectionTestResult> task = new Task<>() {
            @Override
            protected ConnectionTestResult call() {
                return registry.testConnection(module.id(), values);
            }
        };
        task.setOnSucceeded(e -> {
            button.setDisable(false);
            showStatus(task.getValue().success(), task.getValue().message());
        });
        task.setOnFailed(e -> {
            button.setDisable(false);
            showStatus(false, String.valueOf(task.getException().getMessage()));
        });
        Thread.ofVirtual().start(task);
    }

    private void showStatus(Boolean ok, String text) {
        status.getStyleClass().removeAll("ok", "error");
        if (ok != null) {
            status.getStyleClass().add(ok ? "ok" : "error");
        }
        status.setText(text);
    }

    private void rebuildToolList() {
        toolBox.getChildren().clear();
        registry.moduleError(module.id()).ifPresent(err -> {
            Label l = new Label("Fehler beim Erzeugen der Tools: " + err);
            l.getStyleClass().addAll("status-text", "error");
            toolBox.getChildren().add(l);
        });
        List<ToolDefinition> tools = registry.availableTools(module.id());
        boolean moduleOn = registry.settings(module.id()).enabled();
        for (ToolDefinition t : tools) {
            boolean permitted = registry.toolPermitted(module.id(), t.name());
            CheckBox cb = new CheckBox(permitted ? t.name() : t.name() + " (keine Berechtigung)");
            cb.getStyleClass().add("tool-name");
            cb.setSelected(permitted && !registry.settings(module.id()).disabledTools().contains(t.name()));
            cb.setDisable(!moduleOn || !permitted);
            cb.setOnAction(e -> {
                if (!apply(() -> registry.setToolEnabled(module.id(), t.name(), cb.isSelected()))) {
                    cb.setSelected(!cb.isSelected());
                }
            });
            Label desc = new Label(t.description());
            desc.setWrapText(true);
            desc.getStyleClass().add("tool-description");
            VBox row = new VBox(2, cb, desc);
            row.getStyleClass().add("tool-row");
            toolBox.getChildren().add(row);
        }
        if (tools.isEmpty()) {
            toolBox.getChildren().add(new Label("Keine Tools verfügbar."));
        }
    }

    public ToolModule module() {
        return module;
    }

    /** Aufruf aus beliebigem Thread. */
    public void refreshLater() {
        Platform.runLater(this::refreshState);
    }
}
