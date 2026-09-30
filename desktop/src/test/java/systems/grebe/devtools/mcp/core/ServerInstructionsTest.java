package systems.grebe.devtools.mcp.core;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import static org.assertj.core.api.Assertions.assertThat;

class ServerInstructionsTest {

    @Test
    void combinesPreambleBaseTextAndModuleSectionsInModuleOrder() {
        String text = new ServerInstructions(List.of(
                module("zeta", "Zeta", 100, "Z-Hinweis"),
                module("alpha", "Alpha", 100, "A-Hinweis"),
                module("first", "First", 10, "F-Hinweis"),
                module("silent", "Silent", 50, "  ")),
                "Zusatz aus Properties").build();

        assertThat(text).startsWith(ServerInstructions.PREAMBLE.strip())
                .contains("Zusatz aus Properties", "## First – Tools `first_*`\nF-Hinweis")
                .doesNotContain("Silent");
        assertThat(text.indexOf("Zusatz")).isLessThan(text.indexOf("## First"));
        assertThat(text.indexOf("## First")).isLessThan(text.indexOf("## Alpha"));
        assertThat(text.indexOf("## Alpha")).isLessThan(text.indexOf("## Zeta"));
    }

    @Test
    void failingModuleIsSkippedWithoutBreakingTheOthers() {
        ToolModule broken = new StubModule("broken", "Broken", 1, null) {
            @Override
            public String instructions() {
                throw new IllegalStateException("kaputt");
            }
        };
        String text = new ServerInstructions(List.of(broken, module("ok", "Ok", 2, "läuft")), "").build();

        assertThat(text).contains("## Ok – Tools `ok_*`").doesNotContain("Broken", "kaputt");
    }

    private static ToolModule module(String id, String name, int order, String instructions) {
        return new StubModule(id, name, order, instructions);
    }

    private static class StubModule implements ToolModule {
        private final String id;
        private final String name;
        private final int order;
        private final String instructions;

        StubModule(String id, String name, int order, String instructions) {
            this.id = id;
            this.name = name;
            this.order = order;
            this.instructions = instructions;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String displayName() {
            return name;
        }

        @Override
        public String description() {
            return "";
        }

        @Override
        public String instructions() {
            return instructions;
        }

        @Override
        public int order() {
            return order;
        }

        @Override
        public List<ToolCallback> createTools(ModuleConfig config) {
            return List.of();
        }
    }
}
