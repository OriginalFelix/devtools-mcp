package systems.grebe.devtools.mcp.modules.window.cursor.windows;

import java.awt.Point;
import java.awt.event.KeyEvent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import systems.grebe.devtools.mcp.modules.window.cursor.CursorController;
import systems.grebe.devtools.mcp.modules.window.cursor.MouseButton;
import systems.grebe.devtools.mcp.modules.window.cursor.VirtualCursor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Java/Swing-Fenster im Hintergrund ({@link AwtFixture}, eigener Prozess): AWT liest die Position von Maus-Nachrichten
 * aus {@code GetMessagePos} statt aus Client-Koordinaten, gehaltene Maustasten aus dem Tastaturzustand seines Threads
 * und verwirft Tasten, solange es keines seiner Fenster für aktiv hält. Der eigene Zeiger muss trotzdem klicken und
 * tippen können – ohne das Fenster wirklich zu aktivieren.
 *
 * <p>Die meisten Tests geben das Fenster der Fixture als Fenster unter dem Zeiger vor: dann laufen sie auch, wenn etwas
 * darüber liegt (z.B. bei gesperrtem Bildschirm). Nur {@link #findsTheSwingWindowUnderThePointer} sucht wirklich.
 */
@EnabledOnOs(OS.WINDOWS)
class AwtInputTest {

    private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    private CursorController controller;
    private HWND window;
    private Process fixture;
    private Point field;
    private Point tab;

    @BeforeEach
    void start() throws Exception {
        String javaExe = ProcessHandle.current().info().command().orElseThrow();
        // nur das Testklassen-Verzeichnis: der volle Klassenpfad ist für die Windows-Kommandozeile zu lang
        String classes = java.nio.file.Path.of(AwtFixture.class.getProtectionDomain().getCodeSource().getLocation()
                .toURI()).toString();
        fixture = new ProcessBuilder(javaExe, "-Djava.awt.headless=false", "-cp", classes, AwtFixture.class.getName())
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        Thread.ofPlatform().daemon().start(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(fixture.getInputStream(),
                    StandardCharsets.UTF_8))) {
                for (String l; (l = r.readLine()) != null; ) {
                    lines.add(l);
                    System.out.println("fixture> " + l); // im Testbericht, falls etwas fehlt
                }
            } catch (java.io.IOException e) {
                // Fixture beendet
            }
        });
        String ready = await("READY ");
        assumeTrue(ready != null, "Kein interaktiver Desktop – Fenster der Fixture erschien nicht");
        String[] p = ready.split(" ");
        field = new Point(Integer.parseInt(p[1]), Integer.parseInt(p[2]));
        tab = new Point(Integer.parseInt(p[3]), Integer.parseInt(p[4]));
        User32Ext.INSTANCE.SetThreadDpiAwarenessContext(User32Ext.PER_MONITOR_AWARE_V2);
        window = User32.INSTANCE.FindWindow("SunAwtFrame", AwtFixture.TITLE);
        assertThat(window).as("Fenster der Fixture").isNotNull();
        HWND target = window;
        controller = new WindowsCursorController(at -> target);
    }

    @AfterEach
    void stop() {
        if (controller != null) {
            controller.close();
        }
        if (fixture != null) {
            fixture.destroyForcibly();
        }
    }

    /** Nächste Zeile der Fixture, die mit {@code prefix} beginnt; {@code null} nach Zeitablauf. */
    private String await(String prefix) throws InterruptedException {
        long end = System.currentTimeMillis() + 15_000;
        for (String l; (l = lines.poll(Math.max(1, end - System.currentTimeMillis()), TimeUnit.MILLISECONDS)) != null; ) {
            if (l.startsWith(prefix)) {
                return l;
            }
        }
        return null;
    }

    @Test
    void clicksAndTypesIntoASwingWindowInTheBackground() throws Exception {
        VirtualCursor cursor = controller.create(field.x, field.y);

        controller.click(cursor, MouseButton.LEFT);
        controller.type(cursor, "E777-C");
        assertThat(await("TEXT E777-C")).isEqualTo("TEXT E777-C");

        controller.keyPress(cursor, KeyEvent.VK_ENTER);
        controller.keyRelease(cursor, KeyEvent.VK_ENTER);
        assertThat(await("ENTER ")).isEqualTo("ENTER E777-C");

        controller.move(cursor, tab.x, tab.y);
        controller.click(cursor, MouseButton.LEFT);
        assertThat(await("TAB ")).isEqualTo("TAB 1");

        // Strg+Bild ab wechselt den Reiter, wenn der Fokus in der Reiterleiste steht. Den holt sich Swing erst beim
        // Klick auf den schon gewählten Reiter (auch mit echter Maus), beim Wechsel selbst nicht.
        Thread.sleep(600); // kein Doppelklick
        controller.click(cursor, MouseButton.LEFT);
        controller.keyPress(cursor, KeyEvent.VK_CONTROL);
        controller.keyPress(cursor, KeyEvent.VK_PAGE_DOWN);
        controller.keyRelease(cursor, KeyEvent.VK_PAGE_DOWN);
        controller.keyRelease(cursor, KeyEvent.VK_CONTROL);
        assertThat(await("TAB ")).isEqualTo("TAB 2");

        assertThat(User32.INSTANCE.GetForegroundWindow()).as("nie wirklich aktiviert").isNotEqualTo(window);
    }

    /** Wer nur für die KI aktiv war, ist es nach dem Zeiger nicht mehr – der Java-Fokus bleibt nicht hängen. */
    @Test
    void javaFocusEndsWithTheCursor() throws Exception {
        VirtualCursor cursor = controller.create(field.x, field.y);
        controller.click(cursor, MouseButton.LEFT);
        assertThat(await("FOCUSED")).isEqualTo("FOCUSED");

        controller.destroy(cursor);

        assertThat(await("UNFOCUSED")).isEqualTo("UNFOCUSED");
    }

    /** Ohne vorgegebenes Fenster: der Zeiger findet das Swing-Fenster selbst – nur, wenn nichts darüber liegt. */
    @Test
    void findsTheSwingWindowUnderThePointer() throws Exception {
        Win32Input input = new Win32Input();
        for (Point p : List.of(tab, field)) {
            assumeTrue(input.pids(input.hit(p)).contains(fixture.pid()), "Fenster der Fixture verdeckt (z.B. gesperrter "
                    + "Bildschirm) – über die Suche unter dem Zeiger nicht prüfbar");
        }
        CursorController real = new WindowsCursorController();
        try {
            VirtualCursor cursor = real.create(tab.x, tab.y);
            real.click(cursor, MouseButton.LEFT);
            assertThat(await("TAB ")).isEqualTo("TAB 1");
        } finally {
            real.close();
        }
    }
}
