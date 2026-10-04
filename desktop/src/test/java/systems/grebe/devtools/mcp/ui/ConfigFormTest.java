package systems.grebe.devtools.mcp.ui;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import systems.grebe.devtools.mcp.core.ConfigGroup;
import systems.grebe.devtools.mcp.modules.ticket.TicketModule;
import systems.grebe.devtools.mcp.modules.ticket.TicketProviders;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Formular eines Moduls mit Providern (Tickets): Mehrfachauswahl der aktiven Systeme, Umschalter zwischen ihren
 * Einstellungen. Speichert Screenshots nach {@code build/ui-snapshots/}. Übersprungen ohne JavaFX-Toolkit.
 */
class ConfigFormTest {

    static boolean toolkit;

    final TicketModule module = new TicketModule(new TicketProviders());
    ConfigForm form;
    Scene scene;
    MultiSelectComboBox<ConfigGroup> active;

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
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void showForm() throws Exception {
        Assumptions.assumeTrue(toolkit, "kein JavaFX-Toolkit verfügbar");
        Map<String, String> saved = new HashMap<>(Map.of("jira.enabled", "true", "jira.baseUrl", "https://jira.example.com",
                "youtrack.enabled", "true", "github.token", "ghp_x"));
        onFx(() -> {
            form = new ConfigForm(module.configSchema(), saved);
            VBox root = new VBox(form.node());
            root.setStyle("-fx-padding: 20 24 24 24; -fx-background-color: white;");
            scene = new Scene(root, 900, 760);
            scene.getStylesheets().add(getClass().getResource("/ui/app.css").toExternalForm());
            Stage stage = new Stage();
            stage.setScene(scene);
            stage.show();
            active = (MultiSelectComboBox<ConfigGroup>) search(scene.getRoot(), MultiSelectComboBox.class, null);
        });
        assertThat(active).isNotNull();
    }

    @Test
    void showsOnlyTheSettingsOfTheChosenActiveProvider() throws Exception {
        onFx(() -> {
            assertThat(active.getSelected()).extracting(ConfigGroup::id).containsExactly("jira", "youtrack");
            assertThat(switchButtons()).containsExactly("Jira", "YouTrack");
            // erste aktive Gruppe gezeigt, ohne „Jira:“ vor den Beschriftungen; andere Systeme gar nicht
            assertThat(labels()).contains("Aktiv", "Einstellungen für", "Server-URL", "API-Token", "Standardprojekt",
                    "Kommentare je Ticket").noneMatch(t -> t.contains(": "));
            assertThat(texts()).contains("https://jira.example.com").doesNotContain("ghp_x");
        });
        snapshot(scene, "config-form-groups.png");

        onFx(() -> button("YouTrack").fire());
        onFx(() -> assertThat(texts()).doesNotContain("https://jira.example.com"));
        // erneuter Klick auf die gezeigte Gruppe lässt sie gewählt
        onFx(() -> button("YouTrack").fire());
        onFx(() -> assertThat(button("YouTrack").isSelected()).isTrue());
    }

    @Test
    void multiSelectSetsTheSwitchesAndKeepsInputsOfHiddenProviders() throws Exception {
        onFx(() -> {
            textFieldWith("https://jira.example.com").setText("https://jira.firma.de");
            active.setSelected(List.of(new ConfigGroup("jira", "Jira"), new ConfigGroup("github", "GitHub"),
                    new ConfigGroup("youtrack", "YouTrack")));
        });
        onFx(() -> {
            Map<String, String> v = form.values();
            assertThat(v).containsEntry("jira.enabled", "true").containsEntry("github.enabled", "true")
                    .containsEntry("youtrack.enabled", "true").containsEntry("gitlab.enabled", "false")
                    .containsEntry("openproject.enabled", "false");
            // neu aktivierte Gruppe wird gleich gezeigt, Reihenfolge wie in der Auswahl
            assertThat(switchButtons()).containsExactly("Jira", "GitHub", "YouTrack");
            assertThat(button("GitHub").isSelected()).isTrue();
            assertThat(texts()).contains("ghp_x");
        });

        // abgewählte Gruppe: Einstellungen verschwinden, Eingaben bleiben im Formular
        onFx(() -> active.getSelected().removeIf(g -> g.id().equals("jira")));
        onFx(() -> {
            assertThat(switchButtons()).containsExactly("GitHub", "YouTrack");
            assertThat(form.values()).containsEntry("jira.enabled", "false")
                    .containsEntry("jira.baseUrl", "https://jira.firma.de");
        });

        onFx(() -> active.getSelected().clear());
        onFx(() -> {
            assertThat(switchButtons()).isEmpty();
            GridPane panel = (GridPane) scene.getRoot().lookup(".group-panel");
            assertThat(panel.isVisible()).isFalse();
            assertThat(panel.isManaged()).isFalse();
        });
        snapshot(scene, "config-form-none.png");
    }

    @Test
    void invalidInputOfAnInactiveProviderDoesNotBlockSaving() throws Exception {
        onFx(() -> {
            textFieldWith("https://jira.example.com").setText("kein-url");
            assertThat(form.validate()).containsExactly("'Jira: Server-URL' muss eine http(s)-URL sein.");
            active.getSelected().removeIf(g -> g.id().equals("jira"));
            assertThat(form.validate()).isEmpty();
        });
    }

    @Test
    void popupListsAllProvidersWithChecksAndStaysOpenWhileToggling() throws Exception {
        onFx(() -> active.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.SPACE, false, false, false, false)));
        AtomicReference<ContextMenu> popup = new AtomicReference<>();
        onFx(() -> Window.getWindows().stream().filter(w -> w instanceof ContextMenu && w.isShowing())
                .map(ContextMenu.class::cast).findFirst().ifPresent(popup::set));
        assertThat(popup.get()).isNotNull();
        onFx(() -> {
            assertThat(popup.get().getItems()).hasSize(5);
            // Klick wie mit der Maus: der Eintrag reagiert auf MOUSE_CLICKED auf seinem Inhalt
            Node gitlab = ((CustomMenuItem) popup.get().getItems().get(2)).getContent();
            gitlab.fireEvent(new MouseEvent(MouseEvent.MOUSE_CLICKED, 5, 5, 5, 5, MouseButton.PRIMARY, 1,
                    false, false, false, false, false, false, false, false, false, true, null));
        });
        onFx(() -> {
            assertThat(popup.get().isShowing()).isTrue();
            assertThat(active.getSelected()).extracting(ConfigGroup::id).containsExactly("jira", "gitlab", "youtrack");
        });
        snapshot(popup.get().getScene(), "config-form-popup.png");
        onFx(() -> popup.get().hide());
    }

    @Test
    void chipCrossDeactivatesTheProviderWithoutOpeningTheList() throws Exception {
        onFx(() -> {
            Node cross = scene.getRoot().lookupAll(".chip-remove").iterator().next(); // Jira
            cross.fireEvent(new MouseEvent(MouseEvent.MOUSE_CLICKED, 3, 3, 3, 3, MouseButton.PRIMARY, 1,
                    false, false, false, false, false, false, false, false, false, true, null));
        });
        onFx(() -> {
            assertThat(active.getSelected()).extracting(ConfigGroup::id).containsExactly("youtrack");
            assertThat(form.values()).containsEntry("jira.enabled", "false");
            assertThat(Window.getWindows()).noneMatch(w -> w instanceof ContextMenu && w.isShowing());
        });
    }

    // ------------------------------------------------------------------ Hilfen

    private List<String> switchButtons() {
        List<String> out = new ArrayList<>();
        scene.getRoot().lookupAll(".segmented > .toggle-button").forEach(n -> out.add(((ToggleButton) n).getText()));
        return out;
    }

    private ToggleButton button(String text) {
        return (ToggleButton) search(scene.getRoot(), ToggleButton.class, text);
    }

    private List<String> labels() {
        List<String> out = new ArrayList<>();
        scene.getRoot().lookupAll(".form-label").forEach(n -> out.add(((Label) n).getText()));
        return out;
    }

    private List<String> texts() {
        List<String> out = new ArrayList<>();
        collect(scene.getRoot(), TextField.class, n -> out.add(((TextField) n).getText()));
        return out;
    }

    private TextField textFieldWith(String text) {
        List<TextField> hits = new ArrayList<>();
        collect(scene.getRoot(), TextField.class, n -> {
            if (text.equals(((TextField) n).getText())) {
                hits.add((TextField) n);
            }
        });
        assertThat(hits).hasSize(1);
        return hits.getFirst();
    }

    private static void collect(Node n, Class<?> type, java.util.function.Consumer<Node> sink) {
        if (type.isInstance(n)) {
            sink.accept(n);
        }
        if (n instanceof Parent p) {
            p.getChildrenUnmodifiable().forEach(c -> collect(c, type, sink));
        }
    }

    private static Node search(Node n, Class<?> type, String text) {
        if (type.isInstance(n) && (text == null
                || n instanceof javafx.scene.control.Labeled l && text.equals(l.getText()))) {
            return n;
        }
        if (n instanceof Parent p) {
            for (Node c : p.getChildrenUnmodifiable()) {
                Node r = search(c, type, text);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
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

    private static void snapshot(Scene scene, String file) throws Exception {
        Path dir = Files.createDirectories(Path.of("build/ui-snapshots"));
        AtomicReference<WritableImage> img = new AtomicReference<>();
        onFx(() -> img.set(scene.snapshot(null)));
        WritableImage i = img.get();
        java.awt.image.BufferedImage b = new java.awt.image.BufferedImage((int) i.getWidth(), (int) i.getHeight(),
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        PixelReader r = i.getPixelReader();
        for (int y = 0; y < b.getHeight(); y++) {
            for (int x = 0; x < b.getWidth(); x++) {
                b.setRGB(x, y, r.getArgb(x, y));
            }
        }
        ImageIO.write(b, "png", new File(dir.resolve(file).toString()));
    }
}
