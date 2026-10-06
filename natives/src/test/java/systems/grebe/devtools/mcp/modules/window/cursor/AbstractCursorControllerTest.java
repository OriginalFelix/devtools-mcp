package systems.grebe.devtools.mcp.modules.window.cursor;

import java.awt.Point;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AbstractCursorControllerTest {

    /** Protokolliert die nativen Schritte als Text. */
    static final class Recording extends AbstractCursorController<String> {
        final List<String> log = new ArrayList<>();
        private int next;

        @Override
        public String name() {
            return "Attrappe";
        }

        @Override
        protected String open(Point at, java.awt.Color color) {
            String c = "c" + ++next;
            log.add("open " + c + " " + at.x + "," + at.y);
            return c;
        }

        @Override
        protected void dispose(String cursor) {
            log.add("dispose " + cursor);
        }

        @Override
        protected void moveTo(String cursor, Point to, Set<MouseButton> held) {
            log.add("move " + cursor + " " + to.x + "," + to.y + " " + held);
        }

        @Override
        protected void button(String cursor, Point at, MouseButton button, boolean down, int clickCount,
                              Set<MouseButton> held) {
            log.add((down ? "down " : "up ") + cursor + " " + button + " #" + clickCount + " @" + at.x + "," + at.y
                    + " " + held);
        }

        @Override
        protected void wheel(String cursor, Point at, int notches, Set<MouseButton> held) {
            log.add("wheel " + cursor + " " + notches);
        }

        @Override
        protected boolean typeText(String cursor, Point at, String text) {
            log.add("type " + cursor + " " + text);
            return false;
        }

        @Override
        protected void key(String cursor, Point at, int keyCode, boolean down, Set<Integer> heldKeys) {
            log.add((down ? "keydown " : "keyup ") + cursor + " " + keyCode + " " + heldKeys);
        }
    }

    private final Recording controller = new Recording();

    @Test
    void createsMovesAndClicksAtCurrentPosition() {
        VirtualCursor c = controller.create(10, 20);
        controller.move(c, 30, 40);
        controller.click(c, MouseButton.LEFT, 2);

        assertThat(c.id()).isEqualTo(1);
        assertThat(controller.position(c)).isEqualTo(new Point(30, 40));
        assertThat(controller.log).containsExactly(
                "open c1 10,20",
                "move c1 30,40 []",
                "down c1 LEFT #1 @30,40 [LEFT]",
                "up c1 LEFT #1 @30,40 []",
                "down c1 LEFT #2 @30,40 [LEFT]",
                "up c1 LEFT #2 @30,40 []");
    }

    @Test
    void dragKeepsButtonHeldWhileMoving() {
        VirtualCursor c = controller.create(0, 0);
        controller.press(c, MouseButton.LEFT);
        controller.press(c, MouseButton.LEFT); // schon gehalten: nichts
        controller.move(c, 5, 5);
        controller.release(c, MouseButton.LEFT);
        controller.release(c, MouseButton.LEFT); // nicht gehalten: nichts

        assertThat(controller.pressed(c)).isEmpty();
        assertThat(controller.log).containsExactly(
                "open c1 0,0",
                "down c1 LEFT #1 @0,0 [LEFT]",
                "move c1 5,5 [LEFT]",
                "up c1 LEFT #1 @5,5 []");
    }

    @Test
    void destroyReleasesHeldButtonsFirst() {
        VirtualCursor c = controller.create(1, 2);
        controller.press(c, MouseButton.RIGHT);

        controller.destroy(c);
        controller.destroy(c); // mehrfach erlaubt

        assertThat(c.isOpen()).isFalse();
        assertThat(controller.cursors()).isEmpty();
        assertThat(controller.log).endsWith("up c1 RIGHT #1 @1,2 []", "dispose c1");
        assertThatThrownBy(() -> controller.move(c, 0, 0)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void closeDestroysAllCursors() {
        VirtualCursor a = controller.create(0, 0);
        VirtualCursor b = controller.create(0, 0);

        controller.close();

        assertThat(a.isOpen()).isFalse();
        assertThat(b.isOpen()).isFalse();
        assertThat(controller.log).contains("dispose c1", "dispose c2");
    }

    @Test
    void rejectsForeignCursorsAndInvalidInput() {
        VirtualCursor foreign = new Recording().create(0, 0);
        VirtualCursor c = controller.create(0, 0);

        assertThatThrownBy(() -> controller.move(foreign, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.click(c, MouseButton.LEFT, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.click(c, MouseButton.LEFT, 4)).isInstanceOf(IllegalArgumentException.class);
        controller.press(c, MouseButton.LEFT);
        assertThatThrownBy(() -> controller.click(c, MouseButton.LEFT)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void keyboardTracksHeldKeysAndReleasesThemOnDestroy() {
        VirtualCursor c = controller.create(0, 0);
        controller.type(c, "Hi");
        controller.keyPress(c, KeyEvent.VK_CONTROL);
        controller.keyPress(c, KeyEvent.VK_CONTROL); // schon gehalten: nichts
        controller.keyPress(c, KeyEvent.VK_SHIFT);
        controller.type(c, "");                      // leer: nichts

        assertThat(controller.pressedKeys(c)).containsExactly(KeyEvent.VK_CONTROL, KeyEvent.VK_SHIFT);
        controller.destroy(c);

        assertThat(controller.log).containsExactly(
                "open c1 0,0",
                "type c1 Hi",
                "keydown c1 17 [17]",
                "keydown c1 16 [17, 16]",
                "keyup c1 16 [17]",      // in umgekehrter Reihenfolge losgelassen
                "keyup c1 17 []",
                "dispose c1");
    }

    @Test
    void scrollSkipsZero() {
        VirtualCursor c = controller.create(0, 0);
        controller.scroll(c, 0);
        controller.scroll(c, -3);

        assertThat(controller.log).containsExactly("open c1 0,0", "wheel c1 -3");
    }
}
