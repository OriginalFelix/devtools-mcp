package systems.grebe.devtools.mcp.core;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import static org.assertj.core.api.Assertions.assertThat;

class ToolSessionTest {

    @Test
    void currentIsLocalOutsideOfACall() {
        assertThat(ToolSession.current()).isEqualTo(ToolSession.LOCAL);
    }

    @Test
    void callInSetsAndRestoresTheSession() {
        ToolSession s1 = new ToolSession("s1", "Claude Code");
        ToolSession s2 = new ToolSession("s2", null);

        String seen = ToolSession.callIn(s1, () -> ToolSession.current().id()
                + ToolSession.callIn(s2, () -> "/" + ToolSession.current().id())
                + "/" + ToolSession.current().id());

        assertThat(seen).isEqualTo("s1/s2/s1");
        assertThat(ToolSession.current()).isEqualTo(ToolSession.LOCAL);
    }

    /** Skripte rufen Tools ohne MCP-Kontext auf – sie handeln für die KI, die das Skript aufgerufen hat. */
    @Test
    void withoutMcpExchangeTheRunningSessionIsKept() {
        ToolSession s1 = new ToolSession("s1", "Claude Code");

        assertThat(ToolSession.callIn(s1, () -> ToolSession.of(null))).isEqualTo(s1);
        assertThat(ToolSession.callIn(s1, () -> ToolSession.of(new ToolContext(Map.of())))).isEqualTo(s1);
    }

    @Test
    void withoutMcpExchangeTheSessionIsLocal() {
        assertThat(ToolSession.of(null)).isEqualTo(ToolSession.LOCAL);
        assertThat(ToolSession.of(new ToolContext(Map.of()))).isEqualTo(ToolSession.LOCAL);
    }
}
