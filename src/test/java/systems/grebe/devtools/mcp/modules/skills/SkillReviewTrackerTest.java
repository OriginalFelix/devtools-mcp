package systems.grebe.devtools.mcp.modules.skills;

import org.junit.jupiter.api.Test;
import systems.grebe.devtools.mcp.core.ToolCallListener.ToolCall;

import static org.assertj.core.api.Assertions.assertThat;

class SkillReviewTrackerTest {

    /** Tracker mit festem Intervall, ohne Registry. */
    static SkillReviewTracker tracker(int interval, boolean reviewAvailable) {
        return new SkillReviewTracker(null) {
            @Override
            int nudgeInterval() {
                return interval;
            }

            @Override
            boolean reviewAvailable() {
                return reviewAvailable;
            }
        };
    }

    private static String call(SkillReviewTracker t, String session, String tool, String input) {
        return t.afterSuccess(new ToolCall(tool.substring(0, tool.indexOf('_')), tool, input, session), "ok");
    }

    @Test
    void nudgesAfterIntervalAndStartsCountingAgain() {
        SkillReviewTracker t = tracker(3, true);
        assertThat(call(t, "a", "git_status", "{}")).isEqualTo("ok");
        assertThat(call(t, "a", "git_log", "{}")).isEqualTo("ok");
        assertThat(call(t, "a", "git_diff", "{}")).startsWith("ok\n\n---\n[DevTools-Skills] 3 Tool-Aufrufe")
                .contains("skills_review");
        // danach wieder von vorn, nicht bei jedem Aufruf
        assertThat(call(t, "a", "git_diff", "{}")).isEqualTo("ok");
        assertThat(call(t, "a", "git_diff", "{}")).isEqualTo("ok");
        assertThat(call(t, "a", "git_diff", "{}")).contains("[DevTools-Skills]");
    }

    @Test
    void countsPerSession() {
        SkillReviewTracker t = tracker(2, true);
        call(t, "a", "git_status", "{}");
        assertThat(call(t, "b", "git_status", "{}")).isEqualTo("ok");
        assertThat(call(t, "a", "git_status", "{}")).contains("[DevTools-Skills]");
        assertThat(call(t, "b", "git_status", "{}")).contains("[DevTools-Skills]");
    }

    @Test
    void skillMaintenanceResetsCounterAndIsRecorded() {
        SkillReviewTracker t = tracker(2, true);
        call(t, "a", "git_status", "{}");
        call(t, "a", "skills_view", "{\"name\":\"wildfly-heap-leak\"}");
        call(t, "a", "skills_patch", "{\"name\":\"wildfly-heap-leak\",\"old_string\":\"a\",\"new_string\":\"b\"}");
        // skills_view zählt als normaler Aufruf, skills_patch setzt zurück
        assertThat(call(t, "a", "git_status", "{}")).isEqualTo("ok");
        SkillReviewTracker.SessionState s = t.state("a");
        assertThat(s.viewed()).containsExactly("wildfly-heap-leak");
        assertThat(s.changed()).containsExactly("wildfly-heap-leak");
        assertThat(s.totalCalls()).isEqualTo(4);
        assertThat(t.state("unbekannt").totalCalls()).isZero();
    }

    @Test
    void noNudgeWhenDisabledOrReviewNotOffered() {
        SkillReviewTracker off = tracker(0, true);
        SkillReviewTracker unavailable = tracker(1, false);
        for (int i = 0; i < 5; i++) {
            assertThat(call(off, "a", "git_status", "{}")).isEqualTo("ok");
            assertThat(call(unavailable, "a", "git_status", "{}")).isEqualTo("ok");
        }
    }

    @Test
    void callsWithoutSessionShareOneCounter() {
        SkillReviewTracker t = tracker(2, true);
        call(t, null, "git_status", "{}");
        assertThat(call(t, "", "git_status", "{}")).contains("[DevTools-Skills]");
    }
}
