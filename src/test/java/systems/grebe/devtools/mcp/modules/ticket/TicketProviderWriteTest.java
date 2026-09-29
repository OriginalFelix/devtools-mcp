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

/** Schreibende Aufrufe je Provider: Methode, Pfad und Body so, wie die jeweilige API sie erwartet. */
class TicketProviderWriteTest {

    StubServer stub;
    TicketModule module = new TicketModule(new TicketProviders());

    @BeforeEach
    void start() throws IOException {
        stub = new StubServer();
    }

    @AfterEach
    void stop() {
        stub.close();
    }

    private TicketEnvironment env(Map<String, String> v) {
        return new TicketEnvironment(module.providers(), ModuleConfig.of(module.configSchema(), v));
    }

    private static Map<String, String> with(Map<String, String> base, String... kv) {
        Map<String, String> m = new HashMap<>(base);
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    // ------------------------------------------------------------------ Jira

    private Map<String, String> jira() {
        return Map.of("jira.enabled", "true", "jira.baseUrl", stub.url(), "jira.token", "pat", "jira.defaultProject", "ABC");
    }

    @Test
    void jiraDataCenterAssignUpdateCreate() {
        stub.on("/rest/api/2/user/assignable/search", """
                [{"name":"fgrebe","displayName":"Felix Grebe","emailAddress":"felix@example.com"},
                 {"name":"fmueller","displayName":"Frank Müller"}]""");
        stub.on("/rest/api/2/issue/ABC-1/assignee", r -> new StubServer.Reply(204, "", Map.of()));
        stub.on("/rest/api/2/issue/ABC-1", r -> new StubServer.Reply(204, "", Map.of()));
        stub.on("/rest/api/2/issue", "{\"id\":\"1\",\"key\":\"ABC-9\"}");
        TicketEnvironment env = env(jira());

        assertThat(new TicketAssignTools(env).assign("ABC-1", List.of("felix@example.com"), null, null))
                .startsWith("ABC-1: zugewiesen an Felix Grebe");
        assertThat(stub.last("/rest/api/2/issue/ABC-1/assignee").body()).isEqualTo("{\"name\":\"fgrebe\"}");
        assertThat(stub.last("/rest/api/2/user/assignable/search").decodedQuery()).contains("issueKey=ABC-1",
                "username=felix@example.com");
        assertThatThrownBy(() -> new TicketAssignTools(env).assign("ABC-1", List.of("f"), null, null))
                .hasMessageContaining("mehrdeutig").hasMessageContaining("Frank Müller (fmueller)");
        assertThatThrownBy(() -> new TicketAssignTools(env).assign("ABC-1", List.of("a", "b"), null, null))
                .hasMessageContaining("genau einen Zuständigen");
        new TicketAssignTools(env).assign("ABC-1", List.of(), null, null);
        assertThat(stub.last("/rest/api/2/issue/ABC-1/assignee").body()).isEqualTo("{\"name\":null}");

        assertThat(new TicketEditTools(env).update("ABC-1", "Neu", null, List.of("needs review"), null, null))
                .contains("geändert: Titel, Labels [needs review]");
        assertThat(stub.last("/rest/api/2/issue/ABC-1").method()).isEqualTo("PUT");
        assertThat(stub.last("/rest/api/2/issue/ABC-1").body())
                .isEqualTo("{\"fields\":{\"summary\":\"Neu\",\"labels\":[\"needs_review\"]}}");
        assertThatThrownBy(() -> new TicketEditTools(env).update("ABC-1", " ", null, null, null, null))
                .hasMessageContaining("Nichts zu ändern");

        assertThat(new TicketCreateTools(env).create("Neues Ticket", "h2. Ziel", null, "Bug", List.of("x"), null, null))
                .isEqualTo("ABC-9: angelegt (Bug)\n" + stub.url() + "/browse/ABC-9");
        assertThat(stub.last("/rest/api/2/issue").body()).isEqualTo("{\"fields\":{\"project\":{\"key\":\"ABC\"},"
                + "\"summary\":\"Neues Ticket\",\"description\":\"h2. Ziel\",\"issuetype\":{\"name\":\"Bug\"},\"labels\":[\"x\"]}}");
    }

    @Test
    void jiraCloudAssignsByAccountIdAndCreateReportsValidTypes() {
        Map<String, String> cloud = with(jira(), "jira.deployment", "cloud", "jira.user", "me@example.com");
        stub.on("/rest/api/2/myself", "{\"accountId\":\"5b10ac\",\"displayName\":\"Ich\"}");
        stub.on("/rest/api/2/issue/ABC-1/assignee", r -> new StubServer.Reply(204, "", Map.of()));
        stub.on("/rest/api/2/issue", r -> new StubServer.Reply(400,
                "{\"errorMessages\":[],\"errors\":{\"issuetype\":\"valid issue type is required\"}}", Map.of()));
        stub.on("/rest/api/2/project/ABC", "{\"issueTypes\":[{\"name\":\"Task\"},{\"name\":\"Story\"}]}");
        TicketEnvironment env = env(cloud);

        new TicketAssignTools(env).assign("ABC-1", List.of("me"), null, null);
        assertThat(stub.last("/rest/api/2/issue/ABC-1/assignee").body()).isEqualTo("{\"accountId\":\"5b10ac\"}");
        assertThatThrownBy(() -> new TicketCreateTools(env).create("T", null, null, "Bugg", null, null, null))
                .hasMessageContaining("valid issue type").hasMessageContaining("gültige Typen: Task, Story");
    }

    // ------------------------------------------------------------------ GitHub

    private Map<String, String> github() {
        return Map.of("github.enabled", "true", "github.baseUrl", stub.url(), "github.token", "ghp",
                "github.defaultProject", "octo/app");
    }

    private String ghIssue(String state, String reason, String... assignees) {
        return """
                {"number":12,"title":"T","state":"%s","state_reason":%s,"repository_url":"%s/repos/octo/app",
                 "html_url":"https://github.com/octo/app/issues/12","assignees":[%s],"labels":[]}"""
                .formatted(state, reason == null ? "null" : "\"" + reason + "\"", stub.url(),
                        String.join(",", java.util.Arrays.stream(assignees).map(a -> "{\"login\":\"" + a + "\"}").toList()));
    }

    @Test
    void githubCloseWithReasonMoveProjectColumnAssignCreate() {
        stub.on("/repos/octo/app/issues/12", r -> StubServer.Reply.json("PATCH".equals(r.method())
                ? (r.body().contains("\"state\"") ? ghIssue("closed", "not_planned") : ghIssue("open", null, "felix"))
                : ghIssue("open", null)));
        stub.on("/graphql", r -> StubServer.Reply.json(r.body().contains("mutation")
                ? "{\"data\":{\"updateProjectV2ItemFieldValue\":{\"projectV2Item\":{\"id\":\"PVTI_1\"}}}}"
                : """
                {"data":{"repository":{"issueOrPullRequest":{"projectItems":{"nodes":[{"id":"PVTI_1",
                  "project":{"id":"PVT_1","title":"Roadmap","number":3,"field":{"id":"F_1","options":[
                    {"id":"o1","name":"Todo"},{"id":"o2","name":"Doing"}]}},"fieldValueByName":{"name":"Todo"}}]}}}}}"""));
        stub.on("/user", "{\"login\":\"felix\"}");
        stub.on("/repos/octo/app/issues", "{\"number\":40,\"html_url\":\"https://github.com/octo/app/issues/40\"}");
        TicketEnvironment env = env(github());

        String transitions = new TicketTools(env).transitions("#12", null, null);
        assertThat(transitions).contains("[close:not_planned]  Schließen (nicht geplant)",
                "[project:PVT_1:PVTI_1:F_1:o2]  Project Roadmap: Status → Doing  → Doing  – Project Roadmap")
                .doesNotContain("→ Todo"); // aktueller Wert ist kein Wechsel

        TicketTransitionTools tt = new TicketTransitionTools(env, false);
        assertThat(tt.transition("#12", "nicht geplant", null, null, null)).startsWith("octo/app#12: Status → closed (not_planned)");
        assertThat(stub.requests).filteredOn(r -> "PATCH".equals(r.method())).last()
                .extracting(StubServer.Request::body).isEqualTo("{\"state\":\"closed\",\"state_reason\":\"not_planned\"}");

        assertThat(tt.transition("#12", "Doing", null, null, null)).contains("Project Roadmap: Status → Doing");
        assertThat(stub.last("/graphql").body()).contains("updateProjectV2ItemFieldValue", "\"option\":\"o2\"",
                "\"item\":\"PVTI_1\"", "\"field\":\"F_1\"");

        assertThat(new TicketAssignTools(env).assign("#12", List.of("me", "ghost"), null, null))
                .contains("zugewiesen an felix", "ignoriert (kein Zugriff aufs Repository?): ghost");
        assertThat(stub.requests).filteredOn(r -> "PATCH".equals(r.method())).last()
                .extracting(StubServer.Request::body).isEqualTo("{\"assignees\":[\"felix\",\"ghost\"]}");

        assertThat(new TicketCreateTools(env).create("Neu", "Text", null, "Bug", List.of("bug"), List.of("me"), null))
                .startsWith("octo/app#40: angelegt");
        assertThat(stub.last("/repos/octo/app/issues").body()).isEqualTo(
                "{\"title\":\"Neu\",\"body\":\"Text\",\"labels\":[\"bug\"],\"assignees\":[\"felix\"],\"type\":\"Bug\"}");

        TicketEnvironment anonymous = env(with(github(), "github.token", ""));
        assertThatThrownBy(() -> new TicketCommentTools(anonymous).comment("#12", "x", null, null))
                .hasMessageContaining("braucht ein Token");
    }

    // ------------------------------------------------------------------ GitLab

    @Test
    void gitlabMoveBetweenBoardListsSwapsLabelsAndUnassignSendsZero() {
        Map<String, String> v = Map.of("gitlab.enabled", "true", "gitlab.baseUrl", stub.url(), "gitlab.token", "glpat",
                "gitlab.defaultProject", "grp/app");
        stub.on("/api/v4/projects/grp%2Fapp/issues/12", r -> StubServer.Reply.json("PUT".equals(r.method())
                ? "{\"iid\":12,\"state\":\"opened\",\"labels\":[\"bug\",\"Review\"],\"assignees\":[],\"web_url\":\"w\"}"
                : "{\"iid\":12,\"state\":\"opened\",\"labels\":[\"bug\",\"Doing\"]}"));
        stub.on("/api/v4/projects/grp%2Fapp/boards", """
                [{"id":5,"name":"Dev","lists":[{"label":{"name":"Doing"}},{"label":{"name":"Review"}},{"assignee":{"username":"x"}}]}]""");
        stub.on("/api/v4/projects/grp%2Fapp/issues/12/notes", "{\"id\":77}");
        TicketEnvironment env = env(v);

        assertThat(new TicketTools(env).transitions("#12", null, null))
                .contains("[close]  Schließen", "[label:5:Review]  Board Dev: nach 'Review' verschieben")
                .doesNotContain("label:5:Doing"); // steht schon dort
        assertThat(new TicketTransitionTools(env, false).transition("#12", "review", null, null, null))
                .contains("Labels [bug, Review]");
        assertThat(stub.last("/api/v4/projects/grp%2Fapp/issues/12").body())
                .isEqualTo("{\"add_labels\":\"Review\",\"remove_labels\":\"Doing\"}");

        new TicketTransitionTools(env, false).transition("#12", "close", null, null, null);
        assertThat(stub.last("/api/v4/projects/grp%2Fapp/issues/12").body()).isEqualTo("{\"state_event\":\"close\"}");

        new TicketAssignTools(env).assign("#12", List.of("none"), null, null);
        assertThat(stub.last("/api/v4/projects/grp%2Fapp/issues/12").body()).isEqualTo("{\"assignee_ids\":[0]}");

        assertThat(new TicketCommentTools(env).comment("grp/app#12", "**ok**", null, null))
                .contains("Kommentar 77 hinzugefügt", "/grp/app/-/issues/12#note_77");
        assertThat(stub.last("/api/v4/projects/grp%2Fapp/issues/12/notes").body()).isEqualTo("{\"body\":\"**ok**\"}");
    }
}
