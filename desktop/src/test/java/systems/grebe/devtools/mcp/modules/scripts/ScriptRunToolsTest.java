package systems.grebe.devtools.mcp.modules.scripts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** {@code scripts_run}: Groovy ruft Tools auf, nur das Ergebnis kommt zurück. */
class ScriptRunToolsTest {

    private final List<String> calls = new ArrayList<>();

    private final ToolCaller caller = new ToolCaller() {
        @Override
        public String call(String name, Map<String, Object> args) {
            calls.add(name + args);
            return switch (name) {
                case "git_status" -> "Branch: main\n M a.txt\n M b.txt\n?? c.txt";
                case "demo_json" -> "{\"items\":[1,2,3]}";
                default -> throw new IllegalArgumentException("Tool '" + name + "' gibt es nicht");
            };
        }

        @Override
        public boolean isActive(String name) {
            return name.equals("git_status");
        }
    };

    private final ScriptRunTools tools = new ScriptRunTools(caller, () -> Duration.ofSeconds(5));

    @Test
    void callsToolsByMethodNameAndReturnsOnlyTheResult() {
        String out = tools.run("tools.git_status(repository: 'x').readLines().count { it.startsWith(' M') }");
        assertThat(out).isEqualTo("2");
        assertThat(calls).containsExactly("git_status{repository=x}");
    }

    @Test
    void printedOutputComesBeforeReturnValueAndObjectsAreCompactJson() {
        String out = tools.run("""
                println 'Start'
                def data = tools.json(tools.call('demo_json', [:]))
                [summe: data.items.sum(), aktiv: tools.active('git_status')]""");
        assertThat(out).isEqualTo("Start\n{\"summe\":6,\"aktiv\":true}");
    }

    @Test
    void toolErrorsSurfaceWithMessage() {
        assertThatThrownBy(() -> tools.run("tools.nope_tool()")).hasMessageContaining("nope_tool");
    }

    @Test
    void positionalArgumentsAreRejectedWithHint() {
        assertThatThrownBy(() -> tools.run("tools.git_status('x')")).hasMessageContaining("benannt");
    }

    @Test
    void compileErrorsAreReported() {
        assertThatThrownBy(() -> tools.run("def x = ")).hasMessageContaining("nicht übersetzen");
    }

    @Test
    void endlessLoopIsStoppedByTimeout() {
        ScriptRunTools quick = new ScriptRunTools(caller, () -> Duration.ofMillis(300));
        assertThatThrownBy(() -> quick.run("while (true) { }")).hasMessageContaining("Zeitlimit");
    }

    @Test
    void nothingReturnedSaysSo() {
        assertThat(tools.run("def x = 1; null")).isEqualTo("(keine Ausgabe)");
    }
}
