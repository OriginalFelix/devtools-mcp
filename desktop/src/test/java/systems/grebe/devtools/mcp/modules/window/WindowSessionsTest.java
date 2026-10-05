package systems.grebe.devtools.mcp.modules.window;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import systems.grebe.devtools.mcp.core.ToolSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WindowSessionsTest {

    private final AtomicLong now = new AtomicLong(1_000);
    private final WindowSessions sessions = new WindowSessions(new AiColors(), Duration.ofMinutes(30), now::get);
    private final ToolSession claude = new ToolSession("s1", "Claude Code");
    private final ToolSession codex = new ToolSession("s2", "Codex");

    private WindowSession in(ToolSession s) {
        return ToolSession.callIn(s, sessions::current);
    }

    private static WindowSession.Binding me() {
        return new WindowSession.Binding(ProcessHandle.current(), "java", true);
    }

    @Test
    void eachAiHasItsOwnSessionAndColor() {
        WindowSession a = in(claude);
        WindowSession b = in(codex);

        assertThat(a).isNotSameAs(b).isSameAs(in(claude));
        assertThat(a.color()).isEqualTo(AiColors.color(0));
        assertThat(b.color()).isEqualTo(AiColors.color(180));
        assertThat(a.client()).isEqualTo("Claude Code");
        assertThat(in(ToolSession.LOCAL).client()).isEqualTo("KI");
    }

    @Test
    void aProcessBelongsToOneAiOnly() {
        in(claude).bind(me());

        assertThatThrownBy(() -> in(codex).bind(me())).hasMessage("java wird gerade von Claude Code gesteuert.");

        in(claude).bind(me()); // dieselbe KI darf neu binden
        in(claude).unbind();
        in(codex).bind(me()); // frei geworden
        assertThat(in(codex).current()).isNotNull();
    }

    @Test
    void idleSessionsAreClosedAndTheirColorIsFreed() {
        in(claude).bind(me());
        in(codex);
        now.addAndGet(Duration.ofMinutes(20).toMillis());
        in(codex); // Codex bleibt aktiv
        now.addAndGet(Duration.ofMinutes(11).toMillis());

        sessions.reap();

        assertThat(sessions.size()).isEqualTo(1);
        WindowSession again = in(new ToolSession("s3", null));
        assertThat(again.color()).isEqualTo(AiColors.color(0)); // Rot ist wieder frei
        in(codex).bind(me()); // die Bindung von Claude ist aufgehoben
    }
}
