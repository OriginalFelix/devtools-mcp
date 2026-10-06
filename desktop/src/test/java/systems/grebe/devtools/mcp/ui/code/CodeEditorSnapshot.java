package systems.grebe.devtools.mcp.ui.code;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.stage.Popup;
import javafx.stage.Stage;
import javax.imageio.ImageIO;
import systems.grebe.devtools.mcp.modules.scripts.ScriptTemplates;
import systems.grebe.devtools.mcp.modules.scripts.ScriptViews.Language;
import systems.grebe.devtools.mcp.modules.scripts.assist.ScriptAssist;
import systems.grebe.devtools.mcp.modules.scripts.assist.ToolInfo;

/**
 * Manueller Sichttest: zeigt den Editor mit Groovy, Java und Gherkin, öffnet jeweils die Vervollständigung und speichert
 * Bildschirmfotos samt Liste (Argument: Ordner für die PNGs). Läuft als {@code main} oder über
 * {@link CodeEditorSnapshotTest}, wenn es den Ordner {@code build/code-editor-snapshot} gibt.
 */
public final class CodeEditorSnapshot {

    private static final String GROOVY = """
            // devtools: compileStatic
            module {
                description 'Begrüßungen'
                setting 'baseUrl', 'Basis-URL', URL, required: true
            }

            tool('hello') {
                description 'Begrüßt jemanden'
                param 'who', String, 'Wen begrüßen'
                param 'count', Integer, 'Wie oft', required: false
                readOnly true
                execute { args, cfg ->
                    progress "Begrüße ${args.who} …"
                    def names = ['a', 'b'].collect { it.toUpperCase() }
                    args.
                }
            }
            """;

    private static final ScriptAssist ASSIST = new ScriptAssist(() -> List.of(
            new ToolInfo("git_status", "Status des Arbeitsverzeichnisses. Zeigt geänderte Dateien.", List.of(
                    new ToolInfo.Param("repository", "string", "Repository-Name", true))),
            new ToolInfo("build_test", "Führt die Tests eines Projekts aus.", List.of())));

    public static void main(String[] args) throws Exception {
        boolean ok = run(Path.of(args.length > 0 ? args[0] : "."));
        Platform.exit();
        System.exit(ok ? 0 : 1);
    }

    /** Fotografiert die Beispiele nach {@code out}; {@code false} bei Zeitüberschreitung. */
    static boolean run(Path out) throws InterruptedException {
        ScriptAssist assist = ASSIST;
        CountDownLatch done = new CountDownLatch(1);
        Platform.startup(() -> {
            CodeEditor editor = new CodeEditor(assist);
            Stage stage = new Stage();
            Scene scene = new Scene(editor, 900, 520);
            scene.getStylesheets().add(CodeEditorSnapshot.class.getResource("/ui/app.css").toExternalForm());
            stage.setScene(scene);
            stage.setX(40);
            stage.setY(40);
            stage.show();
            String gherkin = ScriptTemplates.GHERKIN.replace("Und ich gebe", "Wenn \n    Und ich gebe");
            String java = ScriptTemplates.JAVA.replace("config.getString(\"greeting\", \"Hallo\")", "config.");
            Thread warmUp = editor.warmUp(); // wie beim Öffnen des Tabs
            long w0 = System.nanoTime();
            new Thread(() -> {
                try {
                    warmUp.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                System.out.printf("Vorwärmen: %d ms%n", (System.nanoTime() - w0) / 1_000_000);
                step(editor, stage, Language.GROOVY, GROOVY, GROOVY.indexOf("args.\n") + 5, out.resolve("editor-groovy.png"));
                step(editor, stage, Language.JAVA, java, java.indexOf("config.") + 7, out.resolve("editor-java.png"));
                step(editor, stage, Language.GHERKIN, gherkin, gherkin.indexOf("Wenn \n") + 5,
                        out.resolve("editor-gherkin.png"));
                fx(() -> {
                    editor.setLanguage(Language.GROOVY);
                    editor.load(GROOVY.replace("description 'Begrüßt jemanden'", "descripton 'Begrüßt jemanden'"));
                    editor.markErrors("Zeile 8, Spalte 5: Unbekannte Methode descripton");
                });
                sleep(500);
                fx(() -> stage.getScene().getRoot().lookupAll(".lineno").stream()
                        .map(n -> (javafx.scene.control.Label) n).filter(l -> l.getText().strip().equals("8"))
                        .forEach(l -> System.out.println("Zeilennummer 8: " + l.getStyleClass() + " " + l.getTextFill())));
                capture(stage, out.resolve("editor-error.png"));
                Platform.runLater(done::countDown);
            }).start();
        });
        boolean ok = done.await(60, TimeUnit.SECONDS);
        System.out.println(ok ? "Bilder in " + out.toAbsolutePath() : "Timeout");
        return ok;
    }

    private static void step(CodeEditor editor, Stage stage, Language language, String text, int caret, Path png) {
        long c0 = System.nanoTime();
        int items = ASSIST.complete(language, text, caret).items().size();
        System.out.printf("%s: Rechnen %d ms (%d Vorschläge)%n", language, (System.nanoTime() - c0) / 1_000_000, items);
        fx(() -> {
            editor.setLanguage(language);
            editor.load(text);
            editor.completeAt(caret);
        });
        long t0 = System.nanoTime();
        boolean[] showing = new boolean[1];
        while (!showing[0] && System.nanoTime() - t0 < 15_000_000_000L) {
            sleep(100);
            fx(() -> showing[0] = editor.popup().isShowing());
        }
        System.out.printf("%s: Liste %s nach %d ms; Stil bei 'module'/'tool': %s%n", language,
                showing[0] ? "offen" : "NICHT offen", (System.nanoTime() - t0) / 1_000_000,
                text.contains("module") ? styleOf(editor, text.indexOf("module")) : "-");
        sleep(300);
        capture(stage, png);
    }

    /** Szene und – falls offen – die Liste darüber; Fenster anderer Programme stören so nicht. */
    private static void capture(Stage stage, Path png) {
        fx(() -> {
            Scene scene = stage.getScene();
            WritableImage base = scene.snapshot(null);
            CodeEditor editor = (CodeEditor) scene.getRoot();
            Popup popup = editor.popup().window();
            WritableImage list = popup.isShowing() ? popup.getScene().snapshot(null) : null;
            double dx = list == null ? 0 : popup.getX() - stage.getX() - scene.getX();
            double dy = list == null ? 0 : popup.getY() - stage.getY() - scene.getY();
            int w = (int) Math.max(base.getWidth(), list == null ? 0 : dx + list.getWidth());
            int h = (int) Math.max(base.getHeight(), list == null ? 0 : dy + list.getHeight());
            BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            draw(out, base, 0, 0);
            if (list != null) {
                draw(out, list, (int) dx, (int) dy);
            }
            try {
                ImageIO.write(out, "png", png.toFile());
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        sleep(300);
    }

    private static void draw(BufferedImage out, WritableImage img, int ox, int oy) {
        PixelReader r = img.getPixelReader();
        for (int y = 0; y < (int) img.getHeight(); y++) {
            for (int x = 0; x < (int) img.getWidth(); x++) {
                if (ox + x >= 0 && oy + y >= 0 && ox + x < out.getWidth() && oy + y < out.getHeight()) {
                    out.setRGB(ox + x, oy + y, r.getArgb(x, y));
                }
            }
        }
    }

    private static String styleOf(CodeEditor editor, int pos) {
        String[] out = new String[1];
        fx(() -> out[0] = String.valueOf(editor.styleAt(pos)));
        return out[0];
    }

    private static void fx(Runnable r) {
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                r.run();
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
