package systems.grebe.devtools.mcp.modules.window;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import systems.grebe.devtools.mcp.modules.window.cursor.AbstractCursorController;
import systems.grebe.devtools.mcp.modules.window.cursor.MouseButton;
import systems.grebe.devtools.mcp.modules.window.platform.NativeWindow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Maus über den eigenen Zeiger, Tastatur weiter über das umhüllte Gerät; Klicks nur, wenn nichts Fremdes darüber liegt. */
class VirtualCursorInputDeviceTest {

    private static final long APP = 42;
    private static final long OTHER = 7;

    /** Protokolliert die nativen Schritte. */
    static final class Cursors extends AbstractCursorController<String> {
        final List<String> log = new ArrayList<>();

        @Override
        public String name() {
            return "Attrappe";
        }

        @Override
        protected String open(Point at) {
            log.add("create " + at.x + "," + at.y);
            return "c";
        }

        @Override
        protected void dispose(String cursor) {
            log.add("destroy");
        }

        @Override
        protected void moveTo(String cursor, Point to, Set<MouseButton> held) {
            log.add("move " + to.x + "," + to.y);
        }

        @Override
        protected void button(String cursor, Point at, MouseButton button, boolean down, int clickCount,
                              Set<MouseButton> held) {
            log.add((down ? "down " : "up ") + button + " #" + clickCount);
        }

        @Override
        protected void wheel(String cursor, Point at, int notches, Set<MouseButton> held) {
            log.add("wheel " + notches);
        }

        @Override
        protected void typeText(String cursor, Point at, String text) {
            log.add("type " + text);
        }

        @Override
        protected void key(String cursor, Point at, int keyCode, boolean down, Set<Integer> heldKeys) {
            log.add((down ? "keydown " : "keyup ") + keyCode);
        }
    }

    private final FakeDesktop desktop = new FakeDesktop();
    private final Cursors cursors = new Cursors();
    private final NativeWindow app = new NativeWindow(0x1A2B, APP, 1L, "Rechner", new Rectangle(0, 0, 400, 400), false);
    private VirtualCursorInputDevice device;

    @BeforeEach
    void setUp() {
        desktop.windows.add(app);
        // doppelte Auflösung wie bei 200 % Skalierung unter Windows
        device = device(true, true);
        device.target(app, Set.of(APP));
    }

    @Test
    void mouseGoesToOwnPointerInNativeCoordinates() {
        device.move(10, 20);
        device.move(30, 40);
        device.click(InputEvent.BUTTON1_DOWN_MASK, 2);
        device.press(InputEvent.BUTTON3_DOWN_MASK);
        device.release(InputEvent.BUTTON3_DOWN_MASK);
        device.wheel(3);

        assertThat(device.independentPointer()).isTrue();
        assertThat(device.pointer()).isEqualTo(new Point(30, 40));
        assertThat(cursors.log).containsExactly("create 20,40", "move 60,80", "down LEFT #1", "up LEFT #1",
                "down LEFT #2", "up LEFT #2", "down RIGHT #1", "up RIGHT #1", "wheel 3");
        assertThat(desktop.events).isEmpty(); // die echte Maus blieb unberührt
    }

    @Test
    void keyboardIsTheOwnPointersKeyboard() {
        device.keyPress(65);   // noch kein Zeiger: er entsteht in der Mitte des Zielfensters (200,200 → nativ 400,400)
        device.keyRelease(65);
        device.typeChar('ü');

        assertThat(device.independentKeyboard()).isTrue();
        assertThat(device.typesDirectly()).isTrue();
        assertThat(cursors.log).containsExactly("create 400,400", "keydown 65", "keyup 65", "type ü");
        assertThat(desktop.events).isEmpty(); // die echte Tastatur blieb unberührt
    }

    @Test
    void keyboardWithoutClickRefusesWhenAForeignWindowCoversTheCenter() {
        desktop.windows.addFirst(new NativeWindow(0x99, OTHER, 2L, "Fremd", new Rectangle(150, 150, 100, 100), false));

        assertThatThrownBy(() -> device.typeChar('x')).hasMessageContaining("„Fremd“");
    }

    @Test
    void anotherTargetWindowStartsAFreshPointer() {
        device.move(10, 10);
        device.click(InputEvent.BUTTON1_DOWN_MASK, 1);
        NativeWindow other = new NativeWindow(0x3C4D, APP, 1L, "Dialog", new Rectangle(500, 0, 100, 100), false);
        desktop.windows.add(other);

        device.target(other, Set.of(APP));
        device.typeChar('a');

        assertThat(cursors.log).containsSubsequence("create 20,20", "destroy", "create 1100,100", "type a");
    }

    @Test
    void refusesToClickThroughAForeignWindowOnTop() {
        desktop.windows.addFirst(new NativeWindow(0x99, OTHER, 2L, "Fremd", new Rectangle(0, 0, 50, 50), false));
        device.move(10, 10);

        assertThatThrownBy(() -> device.click(InputEvent.BUTTON1_DOWN_MASK, 1)).hasMessageContaining("„Fremd“");
        device.move(100, 100); // dort liegt nur das eigene Fenster
        device.click(InputEvent.BUTTON1_DOWN_MASK, 1);
        assertThat(cursors.log).containsSubsequence("down LEFT #1", "up LEFT #1");
    }

    private VirtualCursorInputDevice device(boolean ownPointer, boolean ownKeyboard) {
        return new VirtualCursorInputDevice(desktop, () -> cursors, p -> new Point(p.x * 2, p.y * 2), desktop,
                ownPointer, ownKeyboard);
    }

    @Test
    void ownKeyboardWithRealMouse() {
        VirtualCursorInputDevice d = device(false, true);
        d.target(app, Set.of(APP));

        d.move(10, 20);
        d.click(InputEvent.BUTTON1_DOWN_MASK, 1);
        d.keyPress(65);
        d.typeChar('x');

        assertThat(d.independentPointer()).isFalse();
        assertThat(d.independentKeyboard()).isTrue();
        // Maus über das echte Gerät, Tastatur über den eigenen Zeiger in der Mitte des Fensters
        assertThat(desktop.events).containsExactly("move 10,20", "press " + InputEvent.BUTTON1_DOWN_MASK,
                "release " + InputEvent.BUTTON1_DOWN_MASK);
        assertThat(cursors.log).containsExactly("create 400,400", "keydown 65", "type x");
        assertThat(d.pointer()).isEqualTo(desktop.pointer); // Not-Aus beobachtet die echte Maus
    }

    @Test
    void ownMouseWithRealKeyboard() {
        VirtualCursorInputDevice d = device(true, false);
        d.target(app, Set.of(APP));

        d.move(10, 20);
        d.click(InputEvent.BUTTON1_DOWN_MASK, 1);
        d.keyPress(65);
        d.keyRelease(65);

        assertThat(d.independentPointer()).isTrue();
        assertThat(d.independentKeyboard()).isFalse();
        assertThat(d.typesDirectly()).isFalse();
        assertThat(cursors.log).containsExactly("create 20,40", "down LEFT #1", "up LEFT #1");
        assertThat(desktop.events).containsExactly("down A", "up A");
    }

    @Test
    void pointerDisappearsByItselfWhenIdle() throws Exception {
        VirtualCursorInputDevice d = new VirtualCursorInputDevice(desktop, () -> cursors, p -> p, desktop, true, true,
                java.time.Duration.ofMillis(100));
        d.target(app, Set.of(APP));
        d.move(10, 10);

        for (int i = 0; i < 50 && !cursors.log.contains("destroy"); i++) {
            Thread.sleep(20);
        }

        assertThat(cursors.log).containsExactly("create 10,10", "destroy");
        assertThat(d.pointer()).isNull();
    }

    @Test
    void releaseDestroysThePointer() {
        device.move(1, 1);
        device.release();

        assertThat(cursors.log).endsWith("destroy");
        assertThat(cursors.cursors()).isEmpty();
        assertThat(device.pointer()).isNull();
        assertThat(desktop.control).endsWith("release");
    }
}
