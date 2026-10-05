package systems.grebe.devtools.mcp.modules.skills;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import systems.grebe.devtools.mcp.core.ToolCallListener.ToolCall;

import static org.assertj.core.api.Assertions.assertThat;

class SkillReviewTrackerTest {

    /** Tracker mit festem Intervall, fester Bibliotheksgröße und steuerbarer Uhr, ohne Registry. */
    static class TestTracker extends SkillReviewTracker {
        final int interval;
        final boolean reviewAvailable;
        int librarySize = 0;
        Instant clock = Instant.parse("2026-01-01T08:00:00Z");

        TestTracker(int interval, boolean reviewAvailable) {
            super(null, null);
            this.interval = interval;
            this.reviewAvailable = reviewAvailable;
        }

        @Override
        int nudgeInterval() {
            return interval;
        }

        @Override
        boolean reviewAvailable() {
            return reviewAvailable;
        }

        @Override
        int librarySize() {
            return librarySize;
        }

        @Override
        Instant now() {
            return clock;
        }
    }

    static TestTracker tracker(int interval, boolean reviewAvailable) {
        return new TestTracker(interval, reviewAvailable);
    }

    private static String call(SkillReviewTracker t, String session, String tool, String input) {
        return t.afterSuccess(new ToolCall(tool.substring(0, tool.indexOf('_')), tool, input, session), "ok");
    }

    @Test
    void nudgesAfterIntervalAndStartsCountingAgain() {
        SkillReviewTracker t = tracker(3, true);
        call(t, "a", "git_status", "{}"); // erster Aufruf: Bibliothekshinweis, zählt aber mit
        assertThat(call(t, "a", "git_log", "{}")).isEqualTo("ok");
        assertThat(call(t, "a", "git_diff", "{}")).startsWith("ok\n\n---\n[DevTools-Skills] 3 Tool-Aufrufe")
                .contains("skills_patch", "skills_create", "skills_review");
        // danach wieder von vorn, nicht bei jedem Aufruf
        assertThat(call(t, "a", "git_diff", "{}")).isEqualTo("ok");
        assertThat(call(t, "a", "git_diff", "{}")).isEqualTo("ok");
        assertThat(call(t, "a", "git_diff", "{}")).contains("[DevTools-Skills]");
    }

    @Test
    void firstCallOfSessionPointsToLibrary() {
        TestTracker t = tracker(5, true);
        assertThat(call(t, "a", "git_status", "{}")).startsWith("ok\n\n---\n[DevTools-Skills] Skill-Bibliothek")
                .contains("noch leer", "skills_create", "skills_patch");
        assertThat(call(t, "a", "git_status", "{}")).isEqualTo("ok");
        t.librarySize = 3;
        assertThat(call(t, "b", "git_status", "{}")).contains("3 Skill(s). Abläufe je Aufgabentyp findet skills_list");
    }

    @Test
    void firstCallAfterLongPauseCountsAsNewTask() {
        TestTracker t = tracker(50, true);
        call(t, "a", "git_status", "{}");
        t.clock = t.clock.plus(Duration.ofMinutes(10));
        assertThat(call(t, "a", "git_status", "{}")).isEqualTo("ok");
        t.clock = t.clock.plus(SkillReviewTracker.IDLE_RESTART).plusSeconds(1);
        assertThat(call(t, "a", "git_status", "{}")).contains("Skill-Bibliothek");
        assertThat(call(t, "a", "git_status", "{}")).isEqualTo("ok");
    }

    @Test
    void noLibraryHintWhenSessionStartsWithSkillTools() {
        SkillReviewTracker t = tracker(5, true);
        assertThat(call(t, "a", "skills_list", "{}")).isEqualTo("ok");
        assertThat(call(t, "a", "git_status", "{}")).isEqualTo("ok");
    }

    @Test
    void countsPerSession() {
        SkillReviewTracker t = tracker(2, true);
        call(t, "a", "git_status", "{}");
        assertThat(call(t, "b", "git_status", "{}")).doesNotContain("Tool-Aufrufe");
        assertThat(call(t, "a", "git_status", "{}")).contains("2 Tool-Aufrufe");
        assertThat(call(t, "b", "git_status", "{}")).contains("2 Tool-Aufrufe");
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
    void noHintsWhenDisabledOrReviewNotOffered() {
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
        assertThat(call(t, "", "git_status", "{}")).contains("2 Tool-Aufrufe");
    }

    @Test
    void hintsAreStatementsNotCommands() {
        // Clients wie Hermes markieren MCP-Ergebnisse als nicht vertrauenswürdig und lassen darin enthaltene
        // Aufforderungen ignorieren – die Hinweise beschreiben deshalb nur den Zustand.
        for (String hint : new String[] {SkillReviewTracker.nudge(5), SkillReviewTracker.intro(0),
                SkillReviewTracker.intro(-1)}) {
            assertThat(hint).isNotBlank().doesNotContainIgnoringCase("jetzt").doesNotContain("aufrufen")
                    .doesNotContainIgnoringCase("ignore").doesNotContain("system:");
        }
    }
}
