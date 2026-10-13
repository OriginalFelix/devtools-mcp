package systems.grebe.devtools.mcp.modules.window.cursor.windows;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ForegroundKeeperTest {

    private static final long JAVA = 10;
    private static final long JAVA_DIALOG = 11;
    private static final long USER = 20;
    private static final long OTHER = 30;
    private static final Map<Long, Long> PIDS = Map.of(JAVA, 1L, JAVA_DIALOG, 1L, USER, 2L, OTHER, 3L);

    private long foreground = USER;
    private long now;
    private boolean clicking;
    private final List<Long> activated = new ArrayList<>();
    private final ForegroundKeeper keeper = new ForegroundKeeper(() -> foreground, PIDS::get, id -> {
        activated.add(id);
        foreground = id;
    }, () -> clicking, () -> now, null);

    @Test
    void givesTheForegroundBackWhenTheJavaProgramTakesIt() {
        keeper.watch(JAVA);
        foreground = JAVA_DIALOG; // AWT hat sich nach vorn geholt (auch ein anderes Fenster desselben Programms)

        keeper.poll();

        assertThat(activated).containsExactly(USER);
        assertThat(keeper.takeRestored()).isTrue();
        assertThat(keeper.takeRestored()).isFalse();
    }

    @Test
    void leavesTheProgramToTheUserWhenHeClicksIt() {
        keeper.watch(JAVA);
        foreground = JAVA;
        clicking = true;

        keeper.poll();
        clicking = false;
        keeper.poll();

        assertThat(activated).isEmpty();
    }

    @Test
    void followsTheUserIntoAnotherWindow() {
        keeper.watch(JAVA);
        foreground = OTHER;
        keeper.poll();
        foreground = JAVA;

        keeper.poll();

        assertThat(activated).containsExactly(OTHER);
    }

    @Test
    void watchesOnlyForAWhile() {
        keeper.watch(JAVA);
        now += ForegroundKeeper.WATCH_MILLIS;
        foreground = JAVA;

        keeper.poll();

        assertThat(activated).isEmpty();
    }

    @Test
    void nothingToGiveBackWhenTheUserWorksInTheJavaProgramItself() {
        foreground = JAVA_DIALOG;
        keeper.watch(JAVA);
        foreground = JAVA;

        keeper.poll();

        assertThat(activated).isEmpty();
    }
}
