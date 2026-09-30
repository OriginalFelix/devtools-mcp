package systems.grebe.devtools.mcp.ui;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import systems.grebe.devtools.mcp.config.SettingsStore;
import systems.grebe.devtools.mcp.core.ToolModule;
import systems.grebe.devtools.mcp.core.ToolRegistry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Echte JavaFX-Oberfläche: Im Modul „Code-Graph“ ein Projekt wählen, „Indizieren“ klicken, Ergebnis abwarten.
 * Speichert einen Screenshot nach {@code build/ui-snapshots/}. Übersprungen ohne JavaFX-Toolkit.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ModuleActionPanelTest {

    @TempDir
    static Path home;

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        SettingsStore settingsStore() {
            return new SettingsStore(home);
        }

        /** Fester Benutzer statt ~/.gitconfig des Entwicklers. */
        @Bean
        @Primary
        systems.grebe.devtools.mcp.modules.skills.SkillUser testSkillUser(SettingsStore settingsStore) {
            return systems.grebe.devtools.mcp.modules.skills.SkillTestContext.user(settingsStore, "ui@example.com");
        }
    }

    @Autowired
    ToolRegistry registry;

    @TempDir
    Path project;

    static boolean toolkit;

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

    @Test
    @SuppressWarnings("unchecked")
    void indexButtonBuildsGraphAndShowsResult() throws Exception {
        Assumptions.assumeTrue(toolkit, "kein JavaFX-Toolkit verfügbar");
        Files.writeString(project.resolve("build.gradle"), "");
        Path src = Files.createDirectories(project.resolve("src/main/java/demo"));
        Files.writeString(src.resolve("A.java"), "package demo;\nclass A { void a() { new B().b(); } }\n");
        Files.writeString(src.resolve("B.java"), "package demo;\nclass B { void b() { } }\n");

        registry.updateConfig("graph", Map.of("projects", project.toString(), "storage", "file"));
        ToolModule graph = registry.modules().stream().filter(m -> m.id().equals("graph")).findFirst().orElseThrow();

        AtomicReference<Scene> scene = new AtomicReference<>();
        AtomicReference<ModuleDetailPane> pane = new AtomicReference<>();
        onFx(() -> {
            ModuleDetailPane p = new ModuleDetailPane(registry, graph);
            Scene s = new Scene(p, 900, 900);
            s.getStylesheets().add(getClass().getResource("/ui/app.css").toExternalForm());
            Stage stage = new Stage();
            stage.setScene(s);
            stage.show();
            scene.set(s);
            pane.set(p);
        });

        // Die Aktionsleiste liegt unter dem Konfigurationsformular (dort gibt es weitere ComboBoxen, z.B. „Ablage“)
        ComboBox<String> target = (ComboBox<String>) find(find(pane.get(), ModuleActionPanel.class, null),
                ComboBox.class, null);
        Button index = (Button) find(pane.get(), Button.class, "Indizieren");
        CheckBox force = (CheckBox) find(pane.get(), CheckBox.class, "Komplett neu");
        String name = project.getFileName().toString();
        onFx(() -> { });
        assertThat(target.getItems()).containsExactly(name);
        assertThat(target.getValue()).isEqualTo(name); // einziges Projekt ist vorausgewählt
        assertThat(index.isDisabled()).isFalse();
        assertThat(force.isSelected()).isFalse();
        waitFor(() -> labels(pane.get()).stream().anyMatch(t -> t.endsWith("noch kein Graph")), "Zustand vor dem Lauf");

        onFx(index::fire);
        waitFor(() -> labels(pane.get()).stream().anyMatch(t -> t.startsWith("Graph gebaut")), "Ergebnis");
        // Projekt ohne Git: ein Graph ohne Branch
        assertThat(Files.exists(project.resolve("devtools-fileinfo.graph"))).isTrue();
        waitFor(() -> labels(pane.get()).stream().anyMatch(t -> t.contains("Graph vom") && t.contains("2 Dateien")),
                "Zustand nach dem Lauf");
        assertThat(index.isDisabled()).isFalse();

        snapshot(scene.get(), "graph-index-action.png");

        onFx(index::fire);
        waitFor(() -> labels(pane.get()).stream().anyMatch(t -> t.startsWith("Graph ist aktuell")), "zweiter Lauf");
    }

    // ------------------------------------------------------------------ Hilfen

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
        if (n instanceof javafx.scene.Parent p) {
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
        if (n instanceof javafx.scene.Parent p) {
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
