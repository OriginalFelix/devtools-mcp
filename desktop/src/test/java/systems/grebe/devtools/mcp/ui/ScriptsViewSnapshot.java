package systems.grebe.devtools.mcp.ui;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javax.imageio.ImageIO;
import org.springframework.context.ConfigurableApplicationContext;
import systems.grebe.devtools.mcp.DevToolsMcpApplication;
import systems.grebe.devtools.mcp.modules.scripts.ScriptBackend;
import systems.grebe.devtools.mcp.modules.scripts.ScriptManager;
import systems.grebe.devtools.mcp.modules.scripts.ScriptTemplates;
import systems.grebe.devtools.mcp.modules.scripts.ScriptViews;
import systems.grebe.devtools.mcp.remote.BackendConnection;

/**
 * Manueller Sichttest (kein JUnit): startet die App ohne Fenster in einem frischen Ordner, legt ein gültiges und ein
 * fehlerhaftes Skript an und speichert Screenshots des Skripte-Tabs (Argumente: PNG-Datei, optional Skriptname).
 */
public final class ScriptsViewSnapshot {

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        String select = args.length > 1 ? args[1] : "demo";
        System.setProperty("devtools.mcp.home", Files.createTempDirectory("scripts-snapshot").toString());
        ConfigurableApplicationContext ctx = DevToolsMcpApplication.startSpring(new String[] {"--server.port=0",
                "--devtools.local-user.email=snapshot@example.com"});
        ScriptManager scripts = ctx.getBean(ScriptManager.class);
        String groovy = ScriptTemplates.GROOVY.replace(ScriptTemplates.PLACEHOLDER_DESCRIPTION,
                "Begrüßungen für den Sichttest");
        scripts.save("demo", null, groovy, "erste Fassung", null);
        scripts.save("demo", null, groovy.replace("Hallo", "Moin"), "Gruß geändert", 1);
        scripts.save("javademo", ScriptViews.Language.JAVA, ScriptTemplates.JAVA.replace(
                ScriptTemplates.PLACEHOLDER_DESCRIPTION, "Java-Begrüßungen"), null, null);
        ctx.getBean(ScriptBackend.class).save("kaputt", "Absichtlich fehlerhaft",
                "module { description 'x' }\ntool('a') {\n  descripton 'Tippfehler'\n  run { 1 }\n}", null, null);
        scripts.reload();
        CountDownLatch done = new CountDownLatch(1);
        Platform.startup(() -> {
            ScriptsView view = new ScriptsView(scripts, ctx.getBean(BackendConnection.class)::me);
            Stage stage = new Stage();
            Scene scene = new Scene(view, 1180, 640);
            scene.getStylesheets().add(ScriptsViewSnapshot.class.getResource("/ui/app.css").toExternalForm());
            stage.setScene(scene);
            stage.show();
            new Thread(() -> {
                String[] names = {select, "kaputt", "javademo"};
                for (int i = 0; i < names.length; i++) {
                    String n = names[i];
                    int index = i;
                    Platform.runLater(() -> view.selectForTest(n));
                    sleep(1200);
                    Platform.runLater(() -> {
                        try {
                            ImageIO.write(toAwt(scene.snapshot(null)), "png",
                                    new File(out.toString().replace(".png", "-" + index + ".png")));
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
