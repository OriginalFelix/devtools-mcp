package systems.grebe.devtools.mcp.core;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolBeansTest {

    @ToolHints(destructive = false)
    public static class Sample {
        private final String prefix;

        Sample(String prefix) {
            this.prefix = prefix;
        }

        @Tool(name = "greet", description = "Grüßt")
        public String greet(@ToolParam(description = "Name") String name, @ToolParam(required = false, description = "Anzahl") Integer times) {
            return prefix + name + (times == null ? "" : "x" + times);
        }

        @Tool(description = "Liest")
        @ToolHints(readOnly = true)
        public String read() {
            return prefix + "read";
        }

        public String notATool() {
            return "nein";
        }
    }

    public static class Duplicate {
        @Tool(name = "same", description = "a")
        public String a() {
            return "a";
        }

        @Tool(name = "same", description = "b")
        public String b() {
            return "b";
        }
    }

    public static class NoTools {
        public String x() {
            return "x";
        }
    }

    @Test
    void producesTheSameDefinitionsAsSpringAiAndKeepsTheHints() {
        List<ToolCallback> ours = ToolBeans.callbacks(new Sample("A:"));
        ToolCallback[] spring = ToolCallbacks.from(new Sample("A:"));

        assertThat(ours).hasSameSizeAs(spring);
        for (ToolCallback cb : spring) {
            ToolCallback mine = ours.stream().filter(o -> o.getToolDefinition().name().equals(cb.getToolDefinition().name()))
                    .findFirst().orElseThrow();
            assertThat(mine.getToolDefinition()).isEqualTo(cb.getToolDefinition());
            assertThat(mine.getToolMetadata()).isEqualTo(cb.getToolMetadata());
        }
        ToolCallback read = ours.stream().filter(o -> o.getToolDefinition().name().equals("read")).findFirst().orElseThrow();
        ToolCallback greet = ours.stream().filter(o -> o.getToolDefinition().name().equals("greet")).findFirst().orElseThrow();
        assertThat(ToolBeans.hints(read).readOnly()).isTrue();
        assertThat(ToolBeans.hints(greet).destructive()).isFalse(); // Klassen-Hinweis
    }

    @Test
    void eachBeanGetsItsOwnCallbacksFromTheSharedDefinitions() {
        List<ToolCallback> a = ToolBeans.callbacks(new Sample("A:"));
        List<ToolCallback> b = ToolBeans.callbacks(new Sample("B:"));

        ToolCallback greetA = a.stream().filter(o -> o.getToolDefinition().name().equals("greet")).findFirst().orElseThrow();
        ToolCallback greetB = b.stream().filter(o -> o.getToolDefinition().name().equals("greet")).findFirst().orElseThrow();

        assertThat(greetA.call("{\"name\":\"x\",\"times\":2}")).contains("A:x");
        assertThat(greetB.call("{\"name\":\"x\"}")).contains("B:x");
        assertThat(greetA.getToolDefinition()).isSameAs(greetB.getToolDefinition());
    }

    @Test
    void rejectsObjectsWithoutToolsAndDuplicateNames() {
        assertThatThrownBy(() -> ToolBeans.callbacks(new NoTools())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No @Tool annotated methods");
        assertThatThrownBy(() -> ToolBeans.callbacks(new Duplicate())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same");
    }

    @Test
    void cachingMakesRepeatedCallsCheaperThanSpringAi() {
        Sample bean = new Sample("A:");
        ToolBeans.callbacks(bean);
        long t0 = System.nanoTime();
        for (int i = 0; i < 200; i++) {
            ToolBeans.callbacks(bean);
        }
        long cached = System.nanoTime() - t0;
        long t1 = System.nanoTime();
        for (int i = 0; i < 200; i++) {
            ToolCallbacks.from(bean);
        }
        long spring = System.nanoTime() - t1;
        System.out.printf("ToolBeans.callbacks: %d ms, ToolCallbacks.from: %d ms (200 Aufrufe)%n", cached / 1_000_000,
                spring / 1_000_000);
        assertThat(cached).isLessThan(spring);
    }
}
