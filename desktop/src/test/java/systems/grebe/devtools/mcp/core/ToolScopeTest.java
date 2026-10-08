package systems.grebe.devtools.mcp.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolScopeTest {

    private static ToolScope scope(String id) {
        return new ToolScope(id, "u-" + id, id, id + "@example.test", null, false);
    }

    @Test
    void callInBindsTheScopeOnlyForTheCallAndNestsProperly() {
        ToolScope outer = scope("outer");
        ToolScope inner = scope("inner");

        assertThat(ToolScope.current()).isSameAs(ToolScope.LOCAL);
        String seen = ToolScope.callIn(outer, () -> {
            assertThat(ToolScope.current()).isSameAs(outer);
            assertThat(ToolScope.callIn(inner, ToolScope::current)).isSameAs(inner);
            return ToolScope.current().toString();
        });

        assertThat(seen).isEqualTo("outer");
        assertThat(ToolScope.current()).isSameAs(ToolScope.LOCAL);
    }

    @Test
    void exceptionsPassThroughAndTheScopeIsUnboundAfterwards() {
        assertThatThrownBy(() -> ToolScope.callIn(scope("x"), () -> {
            throw new IllegalStateException("kaputt");
        })).isInstanceOf(IllegalStateException.class).hasMessage("kaputt");

        assertThat(ToolScope.current()).isSameAs(ToolScope.LOCAL);
    }

    @Test
    void progressIsActiveOnlyInsideCallWith() {
        assertThat(ToolProgress.active()).isFalse();
        boolean inside = ToolProgress.callWith((message, count) -> { }, ToolProgress::active);
        assertThat(inside).isTrue();
        assertThat(ToolProgress.active()).isFalse();
    }
}
