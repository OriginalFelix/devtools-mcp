package systems.grebe.devtools.mcp.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import systems.grebe.devtools.mcp.config.SettingsStore;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolModule;
import systems.grebe.devtools.mcp.core.ToolRegistry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Speichern im Detailbereich eines Moduls über die echte JavaFX-Oberfläche: Vor dem Speichern holt die App im
 * Hintergrund die Warnungen des Moduls ({@link ToolModule#saveWarnings}) und lässt sie bestätigen – das gilt für jedes
 * Modul, nicht nur die Fenstersteuerung. Übersprungen ohne JavaFX-Toolkit.
 */
@DirtiesContext
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"devtools.local-user.email=ui@example.com", "devtools.login.username=tester",
                "devtools.login.password=tester-passwort"})
class ModuleDetailPaneSaveTest {

    private static final String MODULE = "savetest";

    @TempDir
    static Path home;

    /** Warnungen, die das Testmodul beim nächsten Speichern meldet. */
    static final List<String> WARNINGS = new CopyOnWriteArrayList<>();
    /** Wenn gesetzt, wirft die Prüfung der Warnungen diese Exception. */
    static volatile RuntimeException failure;
    /** Wert des Feldes, den die Prüfung zuletzt gesehen hat – die ungespeicherten Eingaben. */
    static volatile String checked;

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        SettingsStore settingsStore() {
            return new SettingsStore(home);
        }

        @Bean
        ToolModule saveTestModule() {
            return new ToolModule() {
                @Override
                public String id() {
                    return MODULE;
                }

                @Override
                public String displayName() {
                    return "Speichertest";
                }

                @Override
                public String description() {
                    return "Modul nur für diesen Test";
                }

                @Override
                public List<ConfigField> configSchema() {
                    return List.of(ConfigField.of("name", "Name", FieldType.STRING));
                }

                @Override
                public List<ToolCallback> createTools(ModuleConfig config) {
                    return List.of();
                }

                @Override
                public List<String> saveWarnings(ModuleConfig config) {
                    checked = config.getString("name", "");
                    if (failure != null) {
                        throw failure;
                    }
                    return List.copyOf(WARNINGS);
                }
            };
        }
    }

    @Autowired
    ToolRegistry registry;

    static boolean toolkit;

    private Stage stage;

    @BeforeAll
    static void startToolkit() throws Exception {
        CountDownLatch up = new CountDownLatch(1);
        try {
            Platform.startup(up::countDown);
            toolkit = up.await(10, TimeUnit.SECONDS);
        } catch (IllegalStateException alreadyRunning) {
            toolkit = true;
        } catch (UnsupportedOperationException | Error noDisplay) {
            toolkit = false;
        }
        if (toolkit) {
            Platform.setImplicitExit(false);
        }
    }

    @BeforeEach
    void reset() {
        Assumptions.assumeTrue(toolkit, "kein JavaFX-Toolkit verfügbar");
        WARNINGS.clear();
        failure = null;
        checked = null;
        registry.updateConfig(MODULE, Map.of("name", "alt"));
    }

    @AfterEach
    void close() throws Exception {
        if (stage != null) {
            onFx(stage::close);
        }
    }

    @Test
    void savesWithoutWarningsAndChecksTheUnsavedValues() throws Exception {
        ModuleDetailPane pane = show(MODULE);
        Button save = (Button) find(pane, Button.class, "Speichern");
        TextField name = (TextField) editor(pane, "Name");

        onFx(() -> name.setText("neu"));
        assertThat(save.isDisabled()).isFalse();
        onFx(save::fire);

        waitFor(() -> labels(pane).stream().anyMatch(t -> t.startsWith("Gespeichert.")), "Gespeichert");
        assertThat(checked).isEqualTo("neu");
        assertThat(registry.settings(MODULE).values()).containsEntry("name", "neu");
        assertThat(save.isDisabled()).isTrue();
        assertThat(dialog()).isNull();
    }

    @Test
    void cancellingTheWarningKeepsTheOldValueAndTheChange() throws Exception {
        WARNINGS.add("Freigabe hebt den Ausschluss von foo auf");
        ModuleDetailPane pane = show(MODULE);
        Button save = (Button) find(pane, Button.class, "Speichern");
        TextField name = (TextField) editor(pane, "Name");

        onFx(() -> name.setText("neu"));
        onFx(save::fire);
        waitFor(() -> dialog() != null, "Warnung");
        DialogPane warning = dialog();
        assertThat(warning.getHeaderText()).isEqualTo("Freigaben heben Ausschlüsse auf");
        assertThat(warning.getContentText()).contains("Freigabe hebt den Ausschluss von foo auf");
        click(warning, "Abbrechen");

        waitFor(() -> labels(pane).contains("Nicht gespeichert."), "Nicht gespeichert");
        assertThat(registry.settings(MODULE).values()).containsEntry("name", "alt");
        assertThat(name.getText()).isEqualTo("neu");
        assertThat(save.isDisabled()).isFalse(); // Eingabe ist noch ungespeichert
    }

    @Test
    void confirmingTheWarningSaves() throws Exception {
        WARNINGS.add("Freigabe hebt den Ausschluss von foo auf");
        ModuleDetailPane pane = show(MODULE);
        Button save = (Button) find(pane, Button.class, "Speichern");
        TextField name = (TextField) editor(pane, "Name");

        onFx(() -> name.setText("neu"));
        onFx(save::fire);
        waitFor(() -> dialog() != null, "Warnung");
        click(dialog(), "Trotzdem speichern");

        waitFor(() -> labels(pane).stream().anyMatch(t -> t.startsWith("Gespeichert.")), "Gespeichert");
        assertThat(registry.settings(MODULE).values()).containsEntry("name", "neu");
        assertThat(save.isDisabled()).isTrue();
    }

    @Test
    void failingCheckShowsTheMessageAndSavesNothing() throws Exception {
        failure = new IllegalStateException("Ordner nicht lesbar");
        ModuleDetailPane pane = show(MODULE);
        Button save = (Button) find(pane, Button.class, "Speichern");
        TextField name = (TextField) editor(pane, "Name");

        onFx(() -> name.setText("neu"));
        onFx(save::fire);

        waitFor(() -> labels(pane).contains("Ordner nicht lesbar"), "Fehlermeldung");
        assertThat(registry.settings(MODULE).values()).containsEntry("name", "alt");
        assertThat(save.isDisabled()).isFalse();
        assertThat(dialog()).isNull();
    }

    /** Ein eingebautes Modul außerhalb der Fenstersteuerung: Git speichert wie vorher, ohne Rückfrage. */
    @Test
    void gitModuleSavesWithoutConfirmation() throws Exception {
        boolean before = Boolean.parseBoolean(registry.settings("git").values().getOrDefault("allowWrite", "true"));
        ModuleDetailPane pane = show("git");
        Button save = (Button) find(pane, Button.class, "Speichern");
        CheckBox allowWrite = (CheckBox) editor(pane, "Schreibende Operationen erlauben");
        try {
            onFx(() -> allowWrite.setSelected(!before));
            onFx(save::fire);

            waitFor(() -> labels(pane).stream().anyMatch(t -> t.startsWith("Gespeichert.")), "Gespeichert");
            assertThat(registry.settings("git").values()).containsEntry("allowWrite", String.valueOf(!before));
            assertThat(dialog()).isNull();
        } finally {
            registry.updateConfig("git", Map.of("allowWrite", String.valueOf(before)));
        }
    }

    // ------------------------------------------------------------------ Hilfen

    private ModuleDetailPane show(String moduleId) throws Exception {
        ToolModule module = registry.modules().stream().filter(m -> m.id().equals(moduleId)).findFirst().orElseThrow();
        AtomicReference<ModuleDetailPane> pane = new AtomicReference<>();
        onFx(() -> {
            ModuleDetailPane p = new ModuleDetailPane(registry, module);
            Scene s = new Scene(p, 900, 700);
            s.getStylesheets().add(getClass().getResource("/ui/app.css").toExternalForm());
            stage = new Stage();
            stage.setScene(s);
            stage.show(); // die Warnung braucht ein Besitzerfenster
            pane.set(p);
        });
        return pane.get();
    }

    /** Der Editor eines Formularfeldes: rechts neben seiner Beschriftung im Raster des Formulars. */
    private static Node editor(Node root, String label) throws Exception {
        Label l = (Label) find(root, Label.class, label);
        AtomicReference<Node> hit = new AtomicReference<>();
        onFx(() -> {
            GridPane grid = (GridPane) l.getParent();
            Integer row = GridPane.getRowIndex(l);
            grid.getChildren().stream()
                    .filter(n -> Objects.equals(GridPane.getRowIndex(n), row) && Integer.valueOf(1).equals(
                            GridPane.getColumnIndex(n)))
                    .findFirst().map(n -> ((VBox) n).getChildren().getFirst()).ifPresent(hit::set);
        });
        assertThat(hit.get()).as("Editor von " + label).isNotNull();
        return hit.get();
    }

    /** Der offene Dialog (Alert) oder {@code null}. */
    private static DialogPane dialog() {
        AtomicReference<DialogPane> d = new AtomicReference<>();
        onFxQuiet(() -> Window.getWindows().stream().filter(Window::isShowing).map(Window::getScene)
                .filter(Objects::nonNull).map(Scene::getRoot).filter(DialogPane.class::isInstance)
                .map(DialogPane.class::cast).findFirst().ifPresent(d::set));
        return d.get();
    }

    private static void click(DialogPane dialog, String text) throws Exception {
        onFx(() -> {
            ButtonType type = dialog.getButtonTypes().stream().filter(b -> b.getText().equals(text)).findFirst()
                    .orElseThrow(() -> new AssertionError("Knopf " + text));
            ((Button) dialog.lookupButton(type)).fire();
        });
    }

    private static List<String> labels(Node root) {
        List<String> out = new java.util.ArrayList<>();
        onFxQuiet(() -> collect(root, out));
        return out;
    }

    private static void collect(Node n, List<String> out) {
        if (n instanceof Label l && l.getText() != null) {
            out.add(l.getText());
        }
        if (n instanceof javafx.scene.control.ScrollPane sp && sp.getContent() != null) {
            collect(sp.getContent(), out);
        }
        if (n instanceof Parent p) {
            p.getChildrenUnmodifiable().forEach(c -> collect(c, out));
        }
    }

    private static Node find(Node root, Class<?> type, String text) throws Exception {
        AtomicReference<Node> hit = new AtomicReference<>();
        onFx(() -> hit.set(search(root, type, text)));
        assertThat(hit.get()).as(type.getSimpleName() + " " + text).isNotNull();
        return hit.get();
    }

    private static Node search(Node n, Class<?> type, String text) {
        if (type.isInstance(n) && (text == null
                || (n instanceof javafx.scene.control.Labeled l && text.equals(l.getText())))) {
            return n;
        }
        List<Node> children = new java.util.ArrayList<>();
        if (n instanceof javafx.scene.control.ScrollPane sp && sp.getContent() != null) {
            children.add(sp.getContent());
        }
        if (n instanceof Parent p) {
            children.addAll(p.getChildrenUnmodifiable());
        }
        for (Node c : children) {
            Node r = search(c, type, text);
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    private static void waitFor(java.util.function.BooleanSupplier cond, String what) throws Exception {
        long end = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < end) {
            if (cond.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Timeout beim Warten auf " + what);
    }

    private static void onFx(Runnable r) throws Exception {
        CountDownLatch l = new CountDownLatch(1);
        AtomicReference<Throwable> err = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                r.run();
            } catch (Throwable t) {
                err.set(t);
            } finally {
                l.countDown();
            }
        });
        assertThat(l.await(10, TimeUnit.SECONDS)).isTrue();
        if (err.get() != null) {
            throw new AssertionError(err.get());
        }
    }

    private static void onFxQuiet(Runnable r) {
        try {
            onFx(r);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
