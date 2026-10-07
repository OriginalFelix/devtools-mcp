package systems.grebe.devtools.mcp.modules.skills;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import systems.grebe.devtools.mcp.core.ToolCallListener.ToolCall;
import systems.grebe.devtools.mcp.core.ToolRegistry;
import systems.grebe.devtools.mcp.modules.memories.MemoryBackend;
import systems.grebe.devtools.mcp.modules.memories.MemoryViews;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Hinweise auf registrierte Skills und frühere Aktionen – mit Attrappen statt Backend. */
class RecallHintsTest {

    static final Instant T = Instant.parse("2026-10-01T10:00:00Z");

    SkillBackend skills = mock(SkillBackend.class);
    MemoryBackend memories = mock(MemoryBackend.class);
    boolean skillsOn = true;
    boolean memoriesOn = true;
    RecallHints hints;

    static SkillViews.Summary skill(String name, String description, List<String> triggers) {
        return new SkillViews.Summary(name, description, null, List.of("review"), 1, 0, null, T, 0,
                SkillViews.Scope.OWN, null, null, triggers);
    }

    static MemoryViews.Entry memory(long id, String title, String reference) {
        return new MemoryViews.Entry(id, title, null, null, "ticket-review", reference, List.of(), null, T, T);
    }

    @BeforeEach
    void setUp() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory(Map.of("skills", skills,
                "memories", memories));
        hints = new RecallHints(beans.getBeanProvider(ToolRegistry.class), beans.getBeanProvider(SkillBackend.class),
                beans.getBeanProvider(MemoryBackend.class)) {
            @Override
            boolean active(String moduleId, String tool) {
                return moduleId.equals(SkillsModule.ID) ? skillsOn : memoriesOn;
            }
        };
        when(skills.overview()).thenReturn(List.of(
                skill("ticket-review", "Verwenden, wenn ein Ticket geprüft wird.", List.of("ticket_get", "pr_*")),
                skill("wildfly-heap", "Heap-Lecks finden.", List.of())));
        when(memories.references()).thenReturn(Set.of("abc-123", "#77"));
        when(memories.related(any(), any(), anyInt())).thenReturn(List.of(memory(12, "Ticket ABC-123 reviewt", "ABC-123")));
    }

    private String call(String session, String tool, String json) {
        return hints.afterSuccess(new ToolCall(tool.substring(0, tool.indexOf('_')), tool, json, session), "ok");
    }

    @Test
    void registeredSkillIsNamedOncePerSession() {
        String first = call("s1", "ticket_get", "{\"key\":\"XYZ-1\"}");
        assertThat(first).startsWith("ok\n\n---\n[DevTools] Registrierter Skill für ticket_get: ticket-review – "
                + "Verwenden, wenn ein Ticket geprüft wird. (per skills_view ladbar)");
        assertThat(call("s1", "pr_diff", "{}")).isEqualTo("ok"); // schon genannt
        assertThat(call("s2", "pr_diff", "{}")).contains("Registrierter Skill für pr_diff: ticket-review");
        assertThat(call("s1", "git_status", "{}")).isEqualTo("ok");
        verify(skills, times(1)).overview(); // Registrierungen aus dem Cache
    }

    @Test
    void loadedSkillIsNotNamedAgain() {
        call("s1", "skills_view", "{\"name\":\"ticket-review\"}");
        assertThat(call("s1", "ticket_get", "{}")).isEqualTo("ok");
    }

    @Test
    void knownReferenceInArgumentsNamesEarlierActions() {
        String r = call("s1", "git_grep", "{\"pattern\":\"Fix für ABC-123.\"}");
        assertThat(r).contains("[DevTools] Frühere Aktionen zu abc-123: #12 2026-10-01 Ticket ABC-123 reviewt "
                + "(per memories_view ladbar)");
        verify(memories).related(eq(List.of("abc-123")), isNull(), eq(3));
        assertThat(call("s1", "git_log", "{\"query\":\"abc-123\"}")).isEqualTo("ok"); // je Session einmal
        // ohne Treffer kein Backend-Aufruf außer dem einmaligen Laden der Bezüge
        call("s2", "git_log", "{\"query\":\"nichts\"}");
        verify(memories, times(1)).references();
        verify(memories, times(1)).related(any(), any(), anyInt());
    }

    @Test
    void numbersCountOnlyUnderIdLikeKeys() {
        when(memories.references()).thenReturn(Set.of("77"));
        assertThat(call("s1", "git_log", "{\"limit\":77}")).isEqualTo("ok");
        assertThat(call("s1", "pr_get", "{\"number\":77}")).contains("Frühere Aktionen zu 77");
    }

    @Test
    void skillViewNamesEarlierRunsAndListNamesMemories() {
        assertThat(call("s1", "skills_view", "{\"name\":\"ticket-review\"}"))
                .contains("[DevTools] Frühere Durchläufe von ticket-review: #12");
        verify(memories).related(isNull(), eq("ticket-review"), eq(3));
        when(memories.overview("review", null, null, 3)).thenReturn(List.of(memory(13, "Noch ein Review", null)));
        assertThat(call("s1", "skills_list", "{\"query\":\"review\"}"))
                .contains("Passende Memories (frühere Aktionen): #13");
        assertThat(call("s1", "skills_list", "{}")).isEqualTo("ok");
    }

    @Test
    void memorySearchNamesMatchingSkills() {
        assertThat(call("s1", "memories_search", "{\"query\":\"ticket geprüft\"}"))
                .contains("[DevTools] Passende Skills (Ablauf): ticket-review").doesNotContain("wildfly-heap");
    }

    @Test
    void switchedOffModulesGiveNoHints() {
        skillsOn = false;
        memoriesOn = false;
        assertThat(call("s1", "ticket_get", "{\"key\":\"ABC-123\"}")).isEqualTo("ok");
        verify(skills, never()).overview();
        verify(memories, never()).references();
    }

    @Test
    void ownWritesInvalidateTheCaches() {
        call("s1", "git_status", "{}");
        call("s1", "skills_create", "{\"name\":\"neu\"}");
        call("s1", "memories_save", "{}");
        call("s1", "git_status", "{}");
        verify(skills, times(2)).overview();
        verify(memories, times(2)).references();
    }

    @Test
    void tokensFromArguments() {
        var json = JsonMapper.builder().build();
        assertThat(RecallHints.tokens(json.readTree("{\"a\":\"Siehe #ABC-123, bitte\",\"limit\":5,\"id\":9}")))
                .contains("siehe #abc-123, bitte", "abc-123", "bitte", "9").doesNotContain("5");
        assertThat(RecallHints.normalize(" #PR-7. ")).isEqualTo("pr-7");
    }
}
