package systems.grebe.devtools.mcp.modules.ticket;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import systems.grebe.devtools.mcp.core.ModuleConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** GitLab-Provider gegen einen Stub der REST API v4 (Projekt vs. Gruppe, Issue-Boards, Notizen). */
class GitLabTicketProviderTest {

    StubServer gl;
    TicketModule module = new TicketModule(new TicketProviders());

    @BeforeEach
    void start() throws IOException {
        gl = new StubServer();
        gl.on("/api/v4/projects/grp%2Fapp", "{\"id\":1,\"path_with_namespace\":\"grp/app\"}");
        gl.on("/api/v4/groups/grp", "{\"id\":2,\"full_path\":\"grp\"}");
    }

    @AfterEach
    void stop() {
        gl.close();
    }

    private TicketTools tools(Map<String, String> extra) {
        Map<String, String> v = new HashMap<>(Map.of("gitlab.enabled", "true", "gitlab.baseUrl", gl.url() + "/",
                "gitlab.token", "glpat-x", "gitlab.defaultProject", "grp/app"));
        v.putAll(extra);
        return new TicketTools(new TicketEnvironment(module.providers(), ModuleConfig.of(module.configSchema(), v)));
    }

    private static String issue(int iid, String state, List<String> labels, String assignee) {
        return """
                {"iid":%d,"title":"Issue %d","state":"%s","labels":%s,"assignees":%s,"issue_type":"issue",
                 "severity":"UNKNOWN","updated_at":"2026-09-28T10:00:00Z","references":{"full":"grp/app#%d"},
                 "web_url":"https://gitlab.example.com/grp/app/-/issues/%d"}"""
                .formatted(iid, iid, state, labels.stream().map(l -> "\"" + l + "\"").toList(),
                        assignee == null ? "[]" : "[{\"username\":\"" + assignee + "\"}]", iid, iid);
    }

    @Test
    void searchMapsFiltersToIssueApiAndReadsPagingHeaders() {
        gl.on("/api/v4/projects/grp%2Fapp/issues", r -> new StubServer.Reply(200,
                "[" + issue(12, "opened", List.of("bug"), "felix") + "]", Map.of("X-Total", "41", "X-Next-Page", "2")));
        String out = tools(Map.of()).search(null, "login", null, "me", List.of("bug", "backend"),
                "milestone=16.0&weight=3", 1, null, null);
        assertThat(out).startsWith("Treffer: 41 (gitlab)")
                .contains("- grp/app#12  [opened]  @felix  Issue 12  (bug)", "cursor=2");
        StubServer.Request r = gl.last("/api/v4/projects/grp%2Fapp/issues");
        assertThat(r.decodedQuery()).contains("state=opened", "scope=assigned_to_me", "labels=bug,backend", "search=login",
                "milestone=16.0", "weight=3", "per_page=1", "order_by=updated_at");
        assertThat(r.headers().get("private-token")).isEqualTo("glpat-x");

        assertThatThrownBy(() -> tools(Map.of()).search(null, null, null, null, null, "milestone 16", null, null, null))
                .hasMessageContaining("name=wert");
        assertThatThrownBy(() -> tools(Map.of()).search(null, null, null, null, null, "private_token=x", null, null, null))
                .hasMessageContaining("nicht erlaubt");
    }

    @Test
    void groupPathIsDetectedAfterProjectLookupFails() {
        gl.on("/api/v4/groups/grp/issues", "[" + issue(3, "closed", List.of(), null) + "]");
        String out = tools(Map.of()).search("grp", null, "closed", "none", null, null, null, null, null);
        assertThat(out).contains("- grp/app#3  [closed]  –  Issue 3");
        assertThat(gl.last("/api/v4/groups/grp/issues").decodedQuery()).contains("state=closed", "assignee_id=None");
        assertThat(gl.requests).extracting(StubServer.Request::path).contains("/api/v4/projects/grp");

        assertThatThrownBy(() -> tools(Map.of()).search("nix/da", null, null, null, null, null, null, null, null))
                .hasMessageContaining("weder Projekt noch Gruppe");
        // 401 ist kein "gibt es nicht" – nicht zur Gruppe weiterprobieren, sondern Anmeldefehler melden
        gl.on("/api/v4/projects/geheim", r -> new StubServer.Reply(401, "{\"message\":\"401 Unauthorized\"}", Map.of()));
        assertThatThrownBy(() -> tools(Map.of()).search("geheim", null, null, null, null, null, null, null, null))
                .hasMessageContaining("nicht angemeldet (401)");
    }

    @Test
    void getShowsWorkItemFieldsAndSkipsSystemNotes() {
        gl.on("/api/v4/projects/grp%2Fapp/issues/12", """
                {"iid":12,"title":"Export","state":"opened","labels":["feature"],"assignees":[{"username":"felix"}],
                 "author":{"username":"anna"},"created_at":"2026-09-01T00:00:00Z","updated_at":"2026-09-02T00:00:00Z",
                 "description":"Beschreibung **fett**","milestone":{"title":"16.0"},"weight":3,"due_date":"2026-10-01",
                 "task_completion_status":{"count":4,"completed_count":1},"merge_requests_count":2,"user_notes_count":2,
                 "references":{"full":"grp/app#12"},"web_url":"https://gitlab.example.com/grp/app/-/issues/12"}""");
        gl.on("/api/v4/projects/grp%2Fapp/issues/12/notes", """
                [{"system":false,"author":{"username":"b"},"created_at":"n3","body":"neu"},
                 {"system":true,"author":{"username":"bot"},"created_at":"n2","body":"added ~feature label"},
                 {"system":false,"author":{"username":"a"},"created_at":"n1","body":"alt"}]""");

        String out = tools(Map.of()).get("https://gitlab.example.com/grp/app/-/issues/12", null, 5, null);
        assertThat(out).startsWith("grp/app#12: Export")
                .contains("Zuständig:  felix", "Milestone:  16.0", "Gewicht:    3", "Fällig:     2026-10-01",
                        "Aufgaben:   1/4 erledigt", "Merge Requests: 2", "## Beschreibung\nBeschreibung **fett**",
                        "## Kommentare (2 von 2)", "### a, n1\nalt", "### b, n3\nneu")
                .doesNotContain("added ~feature label");
        assertThat(out.indexOf("alt")).isLessThan(out.indexOf("neu"));
        assertThat(gl.last("/api/v4/projects/grp%2Fapp/issues/12/notes").decodedQuery()).contains("sort=desc");
    }

    @Test
    void boardColumnsFollowOpenLabelListsAndClosed() {
        gl.on("/api/v4/projects/grp%2Fapp/boards", """
                [{"id":5,"name":"Entwicklung","hide_backlog_list":false,"hide_closed_list":false,"labels":[],
                  "lists":[{"id":2,"position":1,"label":{"name":"Review"}},{"id":1,"position":0,"label":{"name":"Doing"}}]}]""");
        gl.on("/api/v4/projects/grp%2Fapp/issues", r -> {
            String q = r.decodedQuery();
            if (q.contains("state=closed")) {
                return new StubServer.Reply(200, "[" + issue(9, "closed", List.of(), null) + "]", Map.of("X-Total", "1"));
            }
            if (q.contains("labels=Doing")) {
                return new StubServer.Reply(200, "[" + issue(2, "opened", List.of("Doing"), "felix") + "]", Map.of("X-Total", "7"));
            }
            if (q.contains("labels=Review")) {
                return new StubServer.Reply(200, "[]", Map.of("X-Total", "0"));
            }
            // Open: alle offenen, Listen-Labels werden clientseitig ausgefiltert
            return StubServer.Reply.json("[" + issue(1, "opened", List.of("bug"), null) + ","
                    + issue(2, "opened", List.of("Doing"), "felix") + "]");
        });

        String out = tools(Map.of()).board(null, null, null, 5, null);
        assertThat(out).startsWith("Board Entwicklung (gitlab, ID 5)")
                .contains("## Open (1)\n- grp/app#1  [opened]  –  Issue 1",
                        "## Doing (7)\n- grp/app#2  [opened]  @felix  Issue 2\n  … weitere",
                        "## Review (0)", "## Closed (14 Tage) (1)\n- grp/app#9");
        // Listen in Board-Reihenfolge (position), nicht in API-Reihenfolge
        assertThat(out.indexOf("## Doing")).isLessThan(out.indexOf("## Review"));
        assertThat(gl.requests).filteredOn(r -> r.decodedQuery().contains("state=closed")).singleElement()
                .satisfies(r -> assertThat(r.decodedQuery()).contains("updated_after="));
    }
}
