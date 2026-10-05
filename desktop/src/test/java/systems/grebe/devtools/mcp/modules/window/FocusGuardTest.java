package systems.grebe.devtools.mcp.modules.window;

import java.awt.Rectangle;
import java.time.Duration;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import systems.grebe.devtools.mcp.modules.window.platform.NativeWindow;

import static org.assertj.core.api.Assertions.assertThat;

/** Das gestartete Programm darf dem Nutzer den Fokus nicht wegnehmen – außer er klickt es selbst an. */
class FocusGuardTest {

    private static final long USER = 0x10;
    private static final long OTHER_USER_WINDOW = 0x20;
    private static final long LAUNCHED = 0x30;
    private static final long LAUNCHED_PID = 4711;

    private final FakeDesktop desktop = new FakeDesktop();
    private final AtomicLong clock = new AtomicLong();
    private final AtomicBoolean clicking = new AtomicBoolean();
    private FocusGuard guard;
    private Runnable onTick = () -> { };

    @BeforeEach
    void setUp() {
        desktop.windows.add(new NativeWindow(USER, 1, 1L, "Editor des Nutzers", new Rectangle(0, 0, 100, 100), false));
        desktop.windows.add(new NativeWindow(OTHER_USER_WINDOW, 2, 2L, "Browser", new Rectangle(0, 0, 100, 100), false));
        desktop.windows.add(new NativeWindow(LAUNCHED, LAUNCHED_PID, 3L, "Word", new Rectangle(0, 0, 100, 100), false));
        desktop.foreground = USER;
        guard = new FocusGuard(desktop, pid -> pid == LAUNCHED_PID, clicking::get, clock::get, millis -> {
            clock.addAndGet(millis);
            onTick.run();
        });
    }

    @Test
    void givesForegroundBackWhenTheProgramStealsIt() {
        AtomicLong ticks = new AtomicLong();
        onTick = () -> {
            if (ticks.incrementAndGet() == 3) {
                desktop.foreground = LAUNCHED; // Word holt sich beim Hochfahren den Vordergrund
            }
        };

        int restored = guard.guard(OptionalLong.of(USER), Duration.ofSeconds(1));

        assertThat(restored).isEqualTo(1);
        assertThat(desktop.foreground).isEqualTo(USER);
        assertThat(desktop.events).containsExactly("activate 10");
    }

    @Test
    void userClickingTheWindowTakesItOver() {
        onTick = () -> {
            desktop.foreground = LAUNCHED;
            clicking.set(true);
        };

        int restored = guard.guard(OptionalLong.of(USER), Duration.ofSeconds(1));

        assertThat(restored).isEqualTo(-1);
        assertThat(desktop.foreground).isEqualTo(LAUNCHED);
        assertThat(desktop.events).isEmpty();
    }

    @Test
    void followsTheUserToAnotherWindow() {
        AtomicLong ticks = new AtomicLong();
        onTick = () -> {
            long t = ticks.incrementAndGet();
            if (t == 2) {
                desktop.foreground = OTHER_USER_WINDOW; // der Nutzer wechselt selbst
            } else if (t == 5) {
                desktop.foreground = LAUNCHED;
            }
        };

        guard.guard(OptionalLong.of(USER), Duration.ofSeconds(1));

        assertThat(desktop.foreground).isEqualTo(OTHER_USER_WINDOW); // dorthin zurück, nicht zum alten Fenster
        assertThat(desktop.events).containsExactly("activate 20");
    }
}
