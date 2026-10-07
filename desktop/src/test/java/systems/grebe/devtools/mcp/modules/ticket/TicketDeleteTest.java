package systems.grebe.devtools.mcp.modules.ticket;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;
import systems.grebe.devtools.mcp.core.ModuleConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Löschen: eigener Schalter, „nur selbst angelegte“ (Standard) über das persistente Verzeichnis, Projektfreigabe,
 * sowie die Lösch-Requests je Provider.
 */
class TicketDeleteTest {

    @TempDir
    Path home;

    StubServer stub;
    TicketModule module;

    @BeforeEach
    void start() throws IOException {
        stub = new StubServer();
        module = new TicketModule(new TicketProviders(), new TicketOwnership(home.resolve("tickets-own.json")));
    }

    @AfterEach
    void stop() {
        stub.close();
    }

    private ModuleConfig config(Map<String, String> base, String... kv) {
        Map<String, String> v = new HashMap<>(base);
        for (int i = 0; i < kv.length; i += 2) {
            v.put(kv[i], kv[i + 1]);
        }
        return ModuleConfig.of(module.configSchema(), v);
    }

    private Map<String, String> jira() {
        return Map.of("jira.enabled", "true", "jira.baseUrl", stub.url(), "jira.token", "pat", "jira.defaultProject", "ABC",
                "allowDelete", "true", "allowCreate", "true", "allowComment", "true");
    }

    private void jiraRoutes() {
        stub.on("/rest/api/2/issue", "{\"key\":\"ABC-50\"}");
        stub.on("/rest/api/2/issue/ABC-50", r -> new StubServer.Reply(204, "", Map.of()));
        stub.on("/rest/api/2/issue/ABC-7", r -> new StubServer.Reply(204, "", Map.of()));
        stub.on("/rest/api/2/issue/ABC-7/comment", "{\"id\":\"900\"}");
        stub.on("/rest/api/2/issue/ABC-7/comment/900", r -> new StubServer.Reply(204, "", Map.of()));
        stub.on("/rest/api/2/issue/ABC-7/comment/111", r -> new StubServer.Reply(204, "", Map.of()));
    }

    private List<String> toolNames(ModuleConfig c) {
        return module.createTools(c).stream().map(ToolCallback::getToolDefinition).map(d -> d.name()).toList();
    }

    @Test
    void deleteToolsOnlyWithTheirSwitch() {
        assertThat(toolNames(config(jira(), "allowDelete", "false"))).doesNotContain("delete", "delete_comment");
        assertThat(toolNames(config(jira()))).contains("delete", "delete_comment");
        assertThat(module.configSchema()).filteredOn(f -> f.key().equals("deleteOnlyOwn"))
                .singleElement().satisfies(f -> assertThat(f.defaultValue()).isEqualTo("true"));
    }

    @Test
    void onlyOwnTicketsAndCommentsAreDeletedAndOwnershipSurvivesRestart() {
        jiraRoutes();
        TicketEnvironment env = module.environment(config(jira()));
        new TicketCreateTools(env).create("Wegwerf", null, null, null, null, null, null, null);
        new TicketCommentTools(env).comment("abc-7", "Notiz", null, null); // Kleinschreibung → kanonisch ABC-7
        assertThat(home.resolve("tickets-own.json")).exists();

        // „Neustart“: neues Modul liest dieselbe Datei
        TicketModule restarted = new TicketModule(new TicketProviders(), new TicketOwnership(home.resolve("tickets-own.json")));
        TicketDeleteTools del = new TicketDeleteTools(restarted.environment(config(jira())), true);

        int before = stub.requests.size();
        assertThatThrownBy(() -> del.delete("ABC-7", null, null))
                .hasMessageContaining("ABC-7 wurde nicht über ticket_create angelegt").hasMessageContaining("ticket_transition");
        assertThatThrownBy(() -> del.deleteComment("ABC-7", "111", null, null))
                .hasMessageContaining("Kommentar 111 an ABC-7 wurde nicht über ticket_comment angelegt");
        assertThat(stub.requests).hasSize(before); // abgelehnt, bevor Jira gefragt wurde

        assertThat(del.deleteComment("https://jira.example.com/browse/ABC-7", "900", null, null))
                .startsWith("ABC-7: Kommentar 900 gelöscht");
        assertThat(stub.last("/rest/api/2/issue/ABC-7/comment/900").method()).isEqualTo("DELETE");
        assertThat(del.delete("ABC-50", null, null)).startsWith("ABC-50: Ticket gelöscht");
        StubServer.Request r = stub.last("/rest/api/2/issue/ABC-50");
        assertThat(r.method()).isEqualTo("DELETE");
        assertThat(r.decodedQuery()).isEqualTo("deleteSubtasks=false");

        // gelöscht = ausgetragen: ein zweites Löschen ist wieder „fremd“
        assertThatThrownBy(() -> del.delete("ABC-50", null, null)).hasMessageContaining("nicht über ticket_create angelegt");
        assertThatThrownBy(() -> del.deleteComment("ABC-7", "900", null, null)).hasMessageContaining("nicht über ticket_comment");
    }

    @Test
    void ownershipIsPerInstanceAndSwitchOffAllowsForeignDeletes() {
        jiraRoutes();
        new TicketCreateTools(module.environment(config(jira()))).create("Wegwerf", null, null, null, null, null, null, null);

        // derselbe Schlüssel auf einer anderen Jira-Instanz gilt nicht als eigen
        try (StubServer other = new StubServer()) {
            ModuleConfig otherJira = config(jira(), "jira.baseUrl", other.url());
            assertThatThrownBy(() -> new TicketDeleteTools(module.environment(otherJira), true).delete("ABC-50", null, null))
                    .hasMessageContaining("nicht über ticket_create angelegt");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }

        TicketDeleteTools any = new TicketDeleteTools(module.environment(config(jira(), "deleteOnlyOwn", "false")), false);
        assertThat(any.delete("ABC-7", null, null)).startsWith("ABC-7: Ticket gelöscht");

        // Projektfreigabe gilt auch fürs Löschen
        TicketDeleteTools restricted = new TicketDeleteTools(module.environment(config(jira(), "writeProjects", "XYZ")), false);
        assertThatThrownBy(() -> restricted.delete("ABC-7", null, null)).hasMessageContaining("'ABC' (jira) ist nicht freigegeben");
    }

    @Test
    void jiraRefusesTicketsWithSubtasksWithHint() {
        stub.on("/rest/api/2/issue/ABC-8", r -> new StubServer.Reply(400,
                "{\"errorMessages\":[\"The issue has subtasks. Use deleteSubtasks=true.\"],\"errors\":{}}", Map.of()));
        TicketDeleteTools any = new TicketDeleteTools(module.environment(config(jira())), false);
        assertThatThrownBy(() -> any.delete("ABC-8", null, null)).hasMessageContaining("has subtasks")
                .hasMessageContaining("Unteraufgaben zuerst einzeln löschen");
    }

    @Test
    void githubDeletesViaGraphqlRefusesPullRequestsAndChecksCommentBelongsToIssue() {
        Map<String, String> gh = Map.of("github.enabled", "true", "github.baseUrl", stub.url(), "github.token", "ghp",
                "github.defaultProject", "Octo/App", "allowDelete", "true", "allowComment", "true", "allowCreate", "true");
        stub.on("/repos/octo/app/issues", "{\"number\":40,\"html_url\":\"u40\"}");
        stub.on("/repos/Octo/App/issues", "{\"number\":40,\"html_url\":\"u40\"}");
        stub.on("/repos/Octo/App/issues/40", "{\"number\":40,\"node_id\":\"I_kw40\"}");
        stub.on("/repos/Octo/App/issues/41", "{\"number\":41,\"node_id\":\"PR_41\",\"pull_request\":{}}");
        stub.on("/repos/Octo/App/issues/40/comments", "{\"id\":555,\"html_url\":\"c555\"}");
        stub.on("/repos/Octo/App/issues/comments/555", r -> "DELETE".equals(r.method())
                ? new StubServer.Reply(204, "", Map.of())
                : StubServer.Reply.json("{\"id\":555,\"issue_url\":\"" + stub.url() + "/repos/Octo/App/issues/40\"}"));
        stub.on("/repos/Octo/App/issues/comments/556", "{\"id\":556,\"issue_url\":\"" + stub.url() + "/repos/Octo/App/issues/99\"}");
        stub.on("/graphql", "{\"data\":{\"deleteIssue\":{\"repository\":{\"nameWithOwner\":\"Octo/App\"}}}}");
        // GitHub ignoriert Groß-/Kleinschreibung von Owner/Repo – der Stub nicht, daher dieselben Routen klein
        for (String path : List.of("/issues/40", "/issues/comments/555")) {
            stub.on("/repos/octo/app" + path, r -> "DELETE".equals(r.method())
                    ? new StubServer.Reply(204, "", Map.of())
                    : StubServer.Reply.json(path.contains("comments")
                            ? "{\"id\":555,\"issue_url\":\"" + stub.url() + "/repos/Octo/App/issues/40\"}"
                            : "{\"number\":40,\"node_id\":\"I_kw40\"}"));
        }
        stub.on("/repos/OCTO/app/issues/40", "{\"number\":40,\"node_id\":\"I_kw40\"}");

        TicketEnvironment env = module.environment(config(gh));
        new TicketCreateTools(env).create("Wegwerf", null, null, null, null, null, null, null);
        new TicketCommentTools(env).comment("#40", "x", null, null);
        TicketDeleteTools own = new TicketDeleteTools(env, true);

        // Groß-/Kleinschreibung des Repos spielt für „eigen“ keine Rolle
        assertThat(own.deleteComment("octo/app#40", "555", null, null)).startsWith("octo/app#40: Kommentar 555 gelöscht");
        assertThat(own.delete("https://github.com/OCTO/app/issues/40", null, null)).startsWith("octo/app#40: Issue gelöscht");
        assertThat(stub.last("/graphql").body()).contains("deleteIssue", "\"id\":\"I_kw40\"");

        TicketDeleteTools any = new TicketDeleteTools(env, false);
        assertThatThrownBy(() -> any.delete("#41", null, null)).hasMessageContaining("Pull Request");
        assertThatThrownBy(() -> any.deleteComment("#40", "556", null, null)).hasMessageContaining("gehört nicht zu");
        assertThat(stub.requests).noneMatch(r -> r.path().endsWith("/556") && "DELETE".equals(r.method()));
    }

    @Test
    void gitlabDeletesNoteAndIssueAndExplainsMissingRole() {
        Map<String, String> gl = Map.of("gitlab.enabled", "true", "gitlab.baseUrl", stub.url(), "gitlab.token", "glpat",
                "gitlab.defaultProject", "grp/app", "allowDelete", "true");
        stub.on("/api/v4/projects/grp%2Fapp/issues/12/notes/77", r -> new StubServer.Reply(204, "", Map.of()));
        stub.on("/api/v4/projects/grp%2Fapp/issues/12", r -> new StubServer.Reply(204, "", Map.of()));
        stub.on("/api/v4/projects/grp%2Fapp/issues/13", r -> new StubServer.Reply(403, "{\"message\":\"403 Forbidden\"}", Map.of()));
        TicketDeleteTools any = new TicketDeleteTools(module.environment(config(gl)), false);

        assertThat(any.deleteComment("#12", "77", null, null)).startsWith("grp/app#12: Kommentar 77 gelöscht");
        assertThat(stub.last("/api/v4/projects/grp%2Fapp/issues/12/notes/77").method()).isEqualTo("DELETE");
        assertThat(any.delete("grp/app#12", null, null)).startsWith("grp/app#12: Issue gelöscht");
        assertThatThrownBy(() -> any.delete("#13", null, null)).hasMessageContaining("Owner/Planner");
        assertThatThrownBy(() -> any.deleteComment("#12", "abc", null, null)).hasMessageContaining("ist eine Zahl");
    }

    @Test
    void getShowsCommentIdsForDeletion() {
        stub.on("/rest/api/2/issue/ABC-7", """
                {"key":"ABC-7","fields":{"summary":"S","status":{"name":"Offen"},
                 "comment":{"total":1,"comments":[{"id":"900","author":{"displayName":"A"},"created":"c1","body":"hallo"}]}}}""");
        assertThat(new TicketTools(module.environment(config(jira()))).get("ABC-7", null, 5, null))
                .contains("### A, c1  (Kommentar 900)\nhallo");
        assertThat(Files.exists(home.resolve("tickets-own.json"))).isFalse(); // Lesen schreibt nichts
    }
}
