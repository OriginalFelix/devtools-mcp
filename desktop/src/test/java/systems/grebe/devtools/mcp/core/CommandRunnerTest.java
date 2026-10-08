package systems.grebe.devtools.mcp.core;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Startfehler und Abbruch sind verschieden - Aufrufer melden einen Abbruch nicht als "nicht installiert". */
class CommandRunnerTest {

    private static List<String> sleeper() {
        return System.getProperty("os.name", "").startsWith("Windows")
                ? List.of("ping", "-n", "30", "127.0.0.1") : List.of("sleep", "30");
    }

    @Test
    void missingProgramIsNotStartable() {
        assertThatThrownBy(() -> CommandRunner.run(List.of("kein-solches-programm-xyz"), Duration.ofSeconds(5),
                StandardCharsets.UTF_8)).isInstanceOf(CommandRunner.NotStartable.class)
                .hasMessageContaining("nicht startbar");
    }

    @Test
    void interruptIsAnAbortAndNotANotStartable() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Boolean> interruptedAfter = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                CommandRunner.run(sleeper(), Duration.ofSeconds(60), StandardCharsets.UTF_8);
            } catch (RuntimeException e) {
                failure.set(e);
                interruptedAfter.set(Thread.currentThread().isInterrupted());
            }
        }, "runner-test");
        t.start();
        Thread.sleep(500);
        long start = System.nanoTime();
        t.interrupt();
        t.join(10_000);

        assertThat(t.isAlive()).isFalse();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(8));
        assertThat(failure.get()).isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(CommandRunner.NotStartable.class).hasMessage("Abgebrochen");
        assertThat(interruptedAfter.get()).isTrue();
    }
}
