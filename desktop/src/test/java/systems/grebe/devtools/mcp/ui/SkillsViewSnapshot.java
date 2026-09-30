package systems.grebe.devtools.mcp.ui;

import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javax.imageio.ImageIO;
import systems.grebe.devtools.mcp.modules.skills.SkillService;
import systems.grebe.devtools.mcp.modules.skills.SkillTestContext;
import systems.grebe.devtools.mcp.modules.skills.SkillsPersistenceConfig;

/**
 * Manueller Sichttest (kein JUnit): öffnet die Skill-Übersicht gegen einen vorhandenen Einstellungsordner, wählt einen
 * Skill aus und speichert einen Screenshot (Argumente: Einstellungsordner, PNG-Datei, Skill-Name).
 */
public final class SkillsViewSnapshot {

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        Path home = Path.of(args[0]);
        Path out = Path.of(args[1]);
        String select = args.length > 2 ? args[2] : null;
        var ctx = SkillTestContext.start(home);
        CountDownLatch done = new CountDownLatch(1);
        Platform.startup(() -> {
            SkillsView view = new SkillsView(ctx.getBean(SkillService.class),
                    ctx.getBean(SkillsPersistenceConfig.Status.class),
                    ctx.getBean(systems.grebe.devtools.mcp.modules.skills.SkillUser.class));
            Stage stage = new Stage();
            Scene scene = new Scene(view, 1180, 640);
            scene.getStylesheets().add(SkillsViewSnapshot.class.getResource("/ui/app.css").toExternalForm());
            stage.setScene(scene);
            stage.show();
            // Laden läuft im Hintergrund – kurz warten, dann auswählen und fotografieren
            new Thread(() -> {
                sleep(1500);
                Platform.runLater(() -> view.selectForTest(select));
                sleep(1200);
                for (int tab = 0; tab < 3; tab++) {
                    int t = tab;
                    Platform.runLater(() -> view.selectDetailTabForTest(t));
                    sleep(400);
                    Platform.runLater(() -> {
                        try {
                            String name = out.toString().replace(".png", "-" + t + ".png");
                            ImageIO.write(toAwt(scene.snapshot(null)), "png", new File(name));
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    });
                    sleep(300);
                }
                Platform.runLater(done::countDown);
            }).start();
        });
        boolean ok = done.await(30, TimeUnit.SECONDS);
        ctx.close();
        Platform.exit();
        System.out.println(ok ? "Screenshot: " + out : "Timeout");
        System.exit(ok ? 0 : 1);
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

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
