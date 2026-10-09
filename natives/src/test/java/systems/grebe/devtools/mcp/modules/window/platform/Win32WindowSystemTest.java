package systems.grebe.devtools.mcp.modules.window.platform;

import java.awt.Rectangle;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Echte user32-Aufrufe gegen ein Swing-Fenster in einem eigenen Prozess. Ohne interaktiven Desktop (z.B. als Dienst)
 * erscheint kein Fenster – dann wird übersprungen. Aktiviert wird bewusst nichts, um den Nutzer nicht zu stören.
 */
@EnabledOnOs(OS.WINDOWS)
class Win32WindowSystemTest {

    static final String TITLE = WindowFixture.TITLE;
    private static Process fixture;
    private static NativeWindow window;
    private static double scale;

    @BeforeAll
    static void start() throws Exception {
        String javaExe = ProcessHandle.current().info().command().orElseThrow();
        // nur das Testklassen-Verzeichnis: der volle Klassenpfad ist für die Windows-Kommandozeile zu lang
        String classes = java.nio.file.Path.of(WindowFixture.class.getProtectionDomain().getCodeSource().getLocation()
                .toURI()).toString();
        fixture = new ProcessBuilder(javaExe, "-Djava.awt.headless=false", "-cp", classes, WindowFixture.class.getName()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        String line = new java.io.BufferedReader(new java.io.InputStreamReader(fixture.getInputStream())).readLine();
        assumeTrue(line != null && line.startsWith("SCALE "), "Fixture ohne Bildschirm gestartet: " + line);
        scale = Double.parseDouble(line.substring(6));
        WindowSystem ws = new Win32WindowSystem();
        for (int i = 0; i < 100 && window == null; i++) {
            Thread.sleep(100);
            Optional<NativeWindow> w = ws.windows().stream().filter(n -> n.title().equals(TITLE)).findFirst();
            window = w.orElse(null);
        }
        assumeTrue(window != null, "Kein interaktiver Desktop – Fenster der Fixture erschien nicht");
    }

    @AfterAll
    static void stop() {
        if (fixture != null) {
            fixture.destroyForcibly();
        }
    }

    @Test
    void findsWindowWithProcessAndThread() {
        assertThat(window.pid()).isEqualTo(fixture.pid());
        assertThat(window.threadId()).isNotNull().isPositive();
        assertThat(window.minimized()).isFalse();
    }

    @Test
    void boundsArePhysicalPixelsWithoutGraphicsEnvironment() {
        // Der Test-JVM läuft headless, ScreenMapper rechnet dann nicht um: erwartet werden physische Pixel (Fenster
        // auf dem Hauptmonitor, Ursprung 0,0). Swing-Grenzen enthalten die unsichtbaren Ränder von Windows 10/11, die
        // DWM-Grenzen nicht – daher die Toleranz.
        Rectangle b = window.bounds();
        int border = (int) Math.ceil(12 * scale);
        assertThat(b.x).isBetween((int) (200 * scale) - 2, (int) (200 * scale) + border);
        assertThat(b.y).isBetween((int) (150 * scale) - 2, (int) (150 * scale) + border);
        assertThat(b.width).isBetween((int) (400 * scale) - 2 * border, (int) (400 * scale) + 2);
        assertThat(b.height).isBetween((int) (300 * scale) - 2 * border, (int) (300 * scale) + 2);
    }

    @Test
    void lookupByIdAndForeground() {
        WindowSystem ws = new Win32WindowSystem();
        assertThat(ws.window(window.id())).get().extracting(NativeWindow::title).isEqualTo(TITLE);
        assertThat(ws.window(0x7FFFFFF0L)).isEmpty();
        assertThat(ws.foreground()).isPresent();
        List<NativeWindow> all = ws.windows();
        assertThat(all).extracting(NativeWindow::id).doesNotHaveDuplicates();
    }
}
