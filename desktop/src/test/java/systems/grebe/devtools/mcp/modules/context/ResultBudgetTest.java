package systems.grebe.devtools.mcp.modules.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import systems.grebe.devtools.mcp.core.ContextSettings;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolCallListener.ToolCall;

class ResultBudgetTest {

    private final ResultStore store = new ResultStore();
    private final ResultBudget budget = new ResultBudget(null, store);

    private static ContextSettings settings(Map<String, String> values) {
        return ContextSettings.of(true, ModuleConfig.of(List.of(), values));
    }

    private static String lines(int n) {
        return IntStream.rangeClosed(1, n).mapToObj(i -> "Zeile " + i + " mit etwas Text")
                .collect(Collectors.joining("\n"));
    }

    @Test
    void shortResultsPassUnchanged() {
        ToolCall call = new ToolCall("git", "git_status", "{}", "s1", true);
        assertThat(budget.apply(settings(Map.of()), call, "sauber")).isEqualTo("sauber");
        assertThat(store.size()).isZero();
    }

    @Test
    void longResultsAreCutInTheMiddleAndStoredUnderAHandle() {
        String text = lines(5_000);
        ToolCall call = new ToolCall("container", "container_logs", "{\"c\":\"db\"}", "s1", false);
        String out = budget.apply(settings(Map.of(ContextSettings.MAX_CHARS, "2000")), call, text);

        assertThat(out.length()).isLessThanOrEqualTo(2_000);
        assertThat(out).startsWith("Zeile 1 mit").endsWith("Zeile 5000 mit etwas Text")
                .contains("context_slice(handle=\"r1\"").contains("ausgelassen");
        assertThat(store.get("r1")).get().extracting(ResultStore.Stored::text).isEqualTo(text);
    }

    @Test
    void exemptToolsAreNeverCut() {
        String text = lines(2_000);
        ToolCall call = new ToolCall("skills", "skills_view", "{}", "s1", true);
        String out = budget.apply(settings(Map.of(ContextSettings.MAX_CHARS, "1000")), call, text);
        assertThat(out).isEqualTo(text);
    }

    @Test
    void identicalReadOnlyResultInSameSessionBecomesAReference() {
        String text = lines(200);
        ContextSettings ctx = settings(Map.of());
        ToolCall call = new ToolCall("git", "git_log", "{\"max\":200}", "s1", true);

        assertThat(budget.apply(ctx, call, text)).isEqualTo(text);
        String second = budget.apply(ctx, call, text);
        assertThat(second).startsWith("[DevTools] Ergebnis unverändert").contains("Handle r1");

        ToolCall otherSession = new ToolCall("git", "git_log", "{\"max\":200}", "s2", true);
        assertThat(budget.apply(ctx, otherSession, text)).isEqualTo(text);
        ToolCall writing = new ToolCall("git", "git_log", "{\"max\":200}", "s1", false);
        assertThat(budget.apply(ctx, writing, text)).isEqualTo(text);
    }

    @Test
    void changedResultIsSentAgain() {
        ContextSettings ctx = settings(Map.of());
        ToolCall call = new ToolCall("git", "git_status", "{}", "s1", true);
        budget.apply(ctx, call, lines(100));
        String changed = lines(101);
        assertThat(budget.apply(ctx, call, changed)).isEqualTo(changed);
    }

    @Test
    void dedupeCanBeSwitchedOff() {
        ContextSettings ctx = settings(Map.of(ContextSettings.DEDUPE, "false"));
        ToolCall call = new ToolCall("git", "git_log", "{}", "s1", true);
        String text = lines(200);
        budget.apply(ctx, call, text);
        assertThat(budget.apply(ctx, call, text)).isEqualTo(text);
    }

    @Test
    void logsAreCompactedButVerbatimToolsKeepTheirText() {
        String log = "a\nretry\nretry\nretry\nb   ";
        ContextSettings ctx = settings(Map.of());
        assertThat(budget.apply(ctx, new ToolCall("container", "container_logs", "{}", null, true), log))
                .isEqualTo("a\nretry\n… (vorige Zeile 3× in Folge)\nb   ");
        assertThat(budget.apply(ctx, new ToolCall("git", "git_diff", "{}", null, true), log)).isEqualTo(log);
        assertThat(budget.apply(ctx, new ToolCall("ssh", "ssh_read_file", "{}", null, true), log)).isEqualTo(log);
    }

    @Test
    void disabledModuleChangesNothing() {
        String text = "\u001B[31m" + lines(5_000);
        ToolCall call = new ToolCall("container", "container_logs", "{}", "s1", true);
        assertThat(budget.apply(ContextSettings.OFF, call, text)).isSameAs(text);
    }
}
