package systems.grebe.devtools.mcp.ui;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableView;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javax.imageio.ImageIO;
import systems.grebe.devtools.mcp.config.SettingsStore;
import systems.grebe.devtools.mcp.plugin.PluginManager;
import systems.grebe.devtools.mcp.plugin.TestPlugins;
import systems.grebe.devtools.mcp.plugin.store.MavenPluginResolver;
import systems.grebe.devtools.mcp.plugin.store.PluginRepository;
import systems.grebe.devtools.mcp.plugin.store.PluginStore;

/**
 * Manueller Sichttest (kein JUnit): baut ein Beispiel-Repository mit Katalog und zwei installierte Plugins (eins davon
 * fehlerhaft), öffnet den Plugins-Tab und speichert je Unter-Tab einen Screenshot (Argument: PNG-Datei).
 */
public final class PluginsViewSnapshot {

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        Path out = Path.of(args[0]);
        Path work = Files.createTempDirectory("plugins-snapshot");
        Path home = work.resolve("home");
        Path repo = work.resolve("repo");
        TestPlugins.deploy(repo, "com.acme.devtools", "jira-plugin", "1.2.0",
                TestPlugins.echoPlugin("jira", "1.2.0", "jira", "", null).build(work.resolve("jira.jar")));
        TestPlugins.deploy(repo, "com.acme.devtools", "jira-plugin", "1.3.0",
                TestPlugins.echoPlugin("jira", "1.3.0", "jira", "", null).build(work.resolve("jira13.jar")));
        TestPlugins.deploy(repo, "com.acme.devtools", "confluence-plugin", "0.9.0",
                TestPlugins.echoPlugin("confluence", "0.9.0", "confluence", "", null).build(work.resolve("c.jar")));
        TestPlugins.deploy(repo, "com.acme.devtools", "catalog", "1", "yml", TestPlugins.textFile(work, "c.yml", """
                plugins:
                  - coordinates: com.acme.devtools:jira-plugin
                    name: Jira
                    description: Tickets lesen, kommentieren und verlinken
                    tags: [ticket]
                  - coordinates: com.acme.devtools:confluence-plugin
                    name: Confluence
                    description: Seiten suchen und lesen
                """), null);

        SettingsStore settings = new SettingsStore(home);
        settings.savePlugins(settings.plugins().withRepositories(List.of(PluginRepository.central(),
                new PluginRepository("team", "Team-Nexus", repo.toUri().toString(), "felix", "geheim", false,
                        "com.acme.devtools:catalog", true))));
        MavenPluginResolver resolver = new MavenPluginResolver(home.resolve("plugins/.repository"),
                () -> settings.plugins().repositories());
        PluginManager manager = new PluginManager(PluginManager.defaultDirectory(settings), settings, Set::of,
                () -> null, resolver);
        PluginStore store = new PluginStore(settings, manager, resolver);
        store.install("com.acme.devtools:jira-plugin:1.2.0");
        TestPlugins.jar().pluginYml("name: broken\nversion: 0.1\nmain: com.acme.Missing\ndepend: [jira]\n")
                .build(manager.directory().resolve("broken.jar"));
        manager.reload();

        CountDownLatch done = new CountDownLatch(1);
        Platform.startup(() -> {
            PluginsView view = new PluginsView(manager, store);
            Stage stage = new Stage();
            Scene scene = new Scene(view, 1180, 640);
            scene.getStylesheets().add(PluginsViewSnapshot.class.getResource("/ui/app.css").toExternalForm());
            stage.setScene(scene);
            stage.show();
            new Thread(() -> {
                sleep(600);
                Platform.runLater(() -> {
                    for (var n : view.lookupAll(".table-view")) {
                        TableView<?> t = (TableView<?>) n;
                        if (!t.getItems().isEmpty() && t.getItems().getFirst() instanceof PluginManager.PluginInfo) {
                            t.getSelectionModel().select(1); // jira
                        }
                    }
                    view.lookupAll(".button").stream().map(Button.class::cast)
                            .filter(b -> b.getText().equals("Nach Updates suchen")).findFirst().ifPresent(Button::fire);
                });
                sleep(2500);
                shot(scene, out, 0);
                Platform.runLater(() -> {
                    ((TabPane) view.lookupAll(".tab-pane").iterator().next()).getSelectionModel().select(1);
                    view.lookupAll(".button").stream().map(Button.class::cast)
                            .filter(b -> b.getText().equals("Katalog laden")).findFirst().ifPresent(Button::fire);
                });
                sleep(2500);
                Platform.runLater(() -> {
                    for (var n : view.lookupAll(".table-view")) {
                        TableView<?> t = (TableView<?>) n;
                        if (t.isVisible() && t.getScene() != null && !t.getItems().isEmpty()
                                && t.getItems().getFirst() instanceof systems.grebe.devtools.mcp.plugin.store.PluginCatalog.Entry) {
                            t.getSelectionModel().select(0);
                        }
                    }
                });
                sleep(2000);
                shot(scene, out, 1);
                Platform.runLater(() ->
                        ((TabPane) view.lookupAll(".tab-pane").iterator().next()).getSelectionModel().select(2));
                sleep(600);
                shot(scene, out, 2);
                Platform.runLater(done::countDown);
            }).start();
        });
        done.await(60, TimeUnit.SECONDS);
        manager.close();
        resolver.close();
        Platform.exit();
        System.exit(0);
    }

    private static void shot(Scene scene, Path out, int i) {
        CountDownLatch l = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                ImageIO.write(toAwt(scene.snapshot(null)), "png",
                        new File(out.toString().replace(".png", "-" + i + ".png")));
            } catch (Exception e) {
                e.printStackTrace();
            }
            l.countDown();
        });
        try {
            l.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        sleep(200);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static java.awt.image.BufferedImage toAwt(WritableImage img) {
        int w = (int) img.getWidth();
        int h = (int) img.getHeight();
        java.awt.image.BufferedImage b = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        PixelReader r = img.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                b.setRGB(x, y, r.getArgb(x, y));
            }
        }
        return b;
    }
}
