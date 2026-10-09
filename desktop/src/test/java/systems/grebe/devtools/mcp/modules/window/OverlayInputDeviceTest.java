package systems.grebe.devtools.mcp.modules.window;

import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.util.Set;

import org.junit.jupiter.api.Test;

import systems.grebe.devtools.mcp.modules.window.platform.NativeWindow;
import systems.grebe.devtools.mcp.modules.window.platform.ScreenMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** Die Anzeige hängt sich nur an – Eingaben und Kontrolle gehen unverändert an das umhüllte Gerät. */
class OverlayInputDeviceTest {

    @Test
    void delegatesEverythingAndNeverNeedsTheNativePointerHeadless() {
        FakeDesktop desktop = new FakeDesktop();
        NativeWindow w = new NativeWindow(0x1A2B, 42L, 7L, "Editor", new Rectangle(0, 0, 100, 100), false);
        desktop.windows.add(w);
        ControlOverlay overlay = new ControlOverlay(() -> {
            throw new AssertionError("ohne Bildschirm kein nativer Zeiger");
        }, ScreenMapper.Mode.IDENTITY);
        OverlayInputDevice device = new OverlayInputDevice(desktop, overlay, desktop, true);

        device.target(w, Set.of(42L));
        device.move(10, 20);
        device.press(InputEvent.BUTTON1_DOWN_MASK);
        device.release(InputEvent.BUTTON1_DOWN_MASK);
        device.release();

        assertThat(desktop.events).containsExactly("move 10,20", "press " + InputEvent.BUTTON1_DOWN_MASK,
                "release " + InputEvent.BUTTON1_DOWN_MASK);
        assertThat(desktop.control).containsExactly("target 1a2b", "release");
        assertThat(overlay.visible()).isFalse(); // headless: es wird nichts angezeigt
    }
}
