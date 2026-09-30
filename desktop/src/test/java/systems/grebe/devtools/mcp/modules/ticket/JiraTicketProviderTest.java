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

/** Jira-Provider über die ticket_*-Tools gegen einen Stub mit Antworten im Format von Jira REST v2 / Agile 1.0. */
class JiraTicketProviderTest {

    StubServer jira;
    TicketModule module = new TicketModule(new TicketProviders());

    @BeforeEach
    void start() throws IOException {
        jira = new StubServer();
    }

    @AfterEach
    void stop() {
        jira.close();
    }

    private TicketTools tools(Map<String, String> extra) {
        Map<String, String> v = new HashMap<>(Map.of("jira.enabled", "true", "jira.baseUrl", jira.url(),
                "jira.token", "pat-123", "jira.defaultProject", "ABC"));
        v.putAll(extra);
        return new TicketTools(new TicketEnvironment(module.providers(), ModuleConfig.of(module.configSchema(), v)));
    }

    private static final String ISSUE_1 = """
            {"key":"ABC-1","fields":{"summary":"Login schlägt fehl","status":{"id":"3","name":"In Arbeit",
             "statusCategory":{"key":"indeterminate"}},"assignee":{"displayName":"Felix Grebe","name":"fgrebe"},
             "issuetype":{"name":"Bug"},"priority":{"name":"High"},"labels":["auth"],"updated":"2026-09-28T10:00:00.000+0200"}}""";
    private static final String ISSUE_2 = """
            {"key":"ABC-2","fields":{"summary":"Doku","status":{"id":"1","name":"Offen","statusCategory":{"key":"new"}},
             "assignee":null,"issuetype":{"name":"Task"},"priority":{"name":"Low"},"labels":[],"updated":"2026-09-27T10:00:00.000+0200"}}""";

    @Test
    void dataCenterSearchBuildsJqlUsesBearerAndOffsetPaging() {
        jira.on("/rest/api/2/search", "{\"startAt\":0,\"maxResults\":2,\"total\":5,\"issues\":[" + ISSUE_1 + "," + ISSUE_2 + "]}");
        String out = tools(Map.of()).search(null, "login fehler", null, "me", List.of("auth"),
                "sprint in openSprints() order by priority DESC", 2, null, null);

        assertThat(out).startsWith("Treffer: 5 (jira)")
                .contains("- ABC-1  [In Arbeit]  @Felix Grebe  Login schlägt fehl  (Bug; High; auth)")
                .contains("- ABC-2  [Offen]  –  Doku  (Task; Low)")
                .contains("ticket_search mit cursor=2");
        StubServer.Request r = jira.last("/rest/api/2/search");
        assertThat(r.decodedQuery()).contains("jql=project = \"ABC\" AND statusCategory != Done AND assignee = currentUser() "
                + "AND labels = \"auth\" AND text ~ \"login fehler\" AND (sprint in openSprints()) order by priority DESC")
                .contains("maxResults=2").contains("startAt=0");
        assertThat(r.headers().get("authorization")).isEqualTo("Bearer pat-123");
    }

    @Test
    void cloudSearchUsesEnhancedEndpointWithTokenPagingAndBasicAuth() {
        jira.on("/rest/api/2/search/jql", "{\"issues\":[" + ISSUE_1 + "],\"nextPageToken\":\"tok-2\",\"isLast\":false}");
        TicketTools t = tools(Map.of("jira.deployment", "cloud", "jira.user", "felix@example.com"));

        String out = t.search(null, null, "closed", "none", null, null, null, "tok-1", null);
        assertThat(out).startsWith("1 Treffer (jira)").contains("cursor=tok-2");
        StubServer.Request r = jira.last("/rest/api/2/search/jql");
        assertThat(r.decodedQuery()).contains("statusCategory = Done AND assignee is EMPTY ORDER BY updated DESC")
                .contains("nextPageToken=tok-1");
        assertThat(r.headers().get("authorization")).startsWith("Basic ");
        assertThat(jira.requests).noneMatch(x -> x.path().equals("/rest/api/2/search"));
    }

    @Test
    void getReturnsDescriptionFieldsAndNewestComments() {
        jira.on("/rest/api/2/issue/ABC-7", """
                {"key":"ABC-7","fields":{"summary":"Export","status":{"id":"5","name":"Erledigt","statusCategory":{"key":"done"}},
                 "assignee":{"displayName":"Anna"},"reporter":{"displayName":"Bob"},"created":"2026-09-01T08:00:00.000+0200",
                 "issuetype":{"name":"Story"},"priority":{"name":"Medium"},"labels":["export"],"updated":"2026-09-02T08:00:00.000+0200",
                 "description":"h2. Ziel\\nCSV-Export für Kunden.","parent":{"key":"ABC-1","fields":{"summary":"Epic"}},
                 "resolution":{"name":"Fixed"},"fixVersions":[{"name":"1.4"}],"components":[],
                 "comment":{"total":3,"comments":[{"author":{"displayName":"A"},"created":"c1","body":"erster"},
                   {"author":{"displayName":"B"},"created":"c2","body":"zweiter"},
                   {"author":{"displayName":"C"},"created":"c3","body":"dritter"}]}}}""");

        String out = tools(Map.of()).get("https://jira.example.com/browse/abc-7?focusedCommentId=1", null, 2, null);
        assertThat(out).startsWith("ABC-7: Export")
                .contains("Status:     Erledigt [DONE]", "Zuständig:  Anna", "Autor:      Bob", "Parent:     ABC-1 Epic",
                        "Resolution: Fixed", "Fix-Versionen: 1.4", "URL:        " + jira.url() + "/browse/ABC-7",
                        "## Beschreibung\nh2. Ziel\nCSV-Export für Kunden.", "## Kommentare (2 von 3, neueste)",
                        "### B, c2\nzweiter", "### C, c3\ndritter")
                .doesNotContain("erster");
        assertThat(jira.last("/rest/api/2/issue/ABC-7").decodedQuery()).contains("comment");

        // status: ohne Kommentare laden, ein unbekanntes Ticket bricht die übrigen nicht ab
        jira.on("/rest/api/2/issue/ABC-1", ISSUE_1);
        String status = tools(Map.of()).status(List.of("ABC-1", "ABC-404"), null, null);
        assertThat(status).contains("- ABC-1  [In Arbeit]  @Felix Grebe  Login schlägt fehl")
                .contains("- ABC-404  FEHLER: Jira: nicht gefunden (404)");
        assertThat(jira.last("/rest/api/2/issue/ABC-1").decodedQuery()).doesNotContain("comment");
    }

    @Test
    void scrumBoardShowsActiveSprintGroupedByColumnStatuses() {
        jira.on("/rest/agile/1.0/board", """
                {"isLast":true,"values":[{"id":42,"name":"ABC Scrum","type":"scrum","location":{"projectKey":"ABC"}}]}""");
        jira.on("/rest/agile/1.0/board/42/configuration", """
                {"columnConfig":{"columns":[{"name":"To Do","statuses":[{"id":"1"}]},
                 {"name":"In Progress","statuses":[{"id":"3"},{"id":"4"}]},{"name":"Done","statuses":[{"id":"5"}]}]}}""");
        jira.on("/rest/agile/1.0/board/42/sprint", """
                {"values":[{"id":7,"name":"Sprint 7","state":"active","startDate":"2026-09-22T08:00:00.000Z","endDate":"2026-10-06T08:00:00.000Z"}]}""");
        jira.on("/rest/agile/1.0/board/42/sprint/7/issue", "{\"startAt\":0,\"total\":2,\"issues\":[" + ISSUE_1 + "," + ISSUE_2 + "]}");

        String out = tools(Map.of()).board(null, null, "me", null, null);
        assertThat(out).startsWith("Board ABC Scrum (jira, ID 42) – Sprint Sprint 7 (2026-09-22 – 2026-10-06) – nur assignee=me")
                .contains("## To Do (1)\n- ABC-2  [Offen]", "## In Progress (1)\n- ABC-1  [In Arbeit]", "## Done (0)");
        assertThat(jira.last("/rest/agile/1.0/board").decodedQuery()).contains("projectKeyOrId=ABC");
        assertThat(jira.last("/rest/agile/1.0/board/42/sprint/7/issue").decodedQuery())
                .contains("jql=assignee = currentUser()");
    }

    @Test
    void kanbanBoardByIdLimitsToOpenAndRecentlyDone() {
        jira.on("/rest/agile/1.0/board/9", "{\"id\":9,\"name\":\"Support\",\"type\":\"kanban\",\"location\":{\"projectKey\":\"SUP\"}}");
        jira.on("/rest/agile/1.0/board/9/configuration", "{\"columnConfig\":{\"columns\":[{\"name\":\"Neu\",\"statuses\":[{\"id\":\"1\"}]}]}}");
        jira.on("/rest/agile/1.0/board/9/issue", "{\"startAt\":0,\"total\":2,\"issues\":[" + ISSUE_1 + "," + ISSUE_2 + "]}");

        String out = tools(Map.of()).board("9", null, null, 1, null);
        assertThat(out).contains("Board Support (jira, ID 9) – offen sowie in den letzten 14 Tagen erledigt",
                "## Neu (1)", "## (keiner Spalte zugeordnet) (1)");
        assertThat(jira.last("/rest/agile/1.0/board/9/issue").decodedQuery())
                .contains("jql=(statusCategory != Done OR resolutiondate >= -14d)");
        assertThat(jira.requests).noneMatch(r -> r.path().equals("/rest/agile/1.0/board")); // ID direkt, keine Liste
    }

    @Test
    void errorsNameTheNextStep() {
        jira.on("/rest/api/2/search", r -> new StubServer.Reply(400,
                "{\"errorMessages\":[\"Field 'sprnt' does not exist\"],\"errors\":{}}", Map.of()));
        assertThatThrownBy(() -> tools(Map.of()).search(null, null, null, null, null, "sprnt = 1", null, null, null))
                .hasMessageContaining("Jira-Fehler 400").hasMessageContaining("Field 'sprnt' does not exist");
        jira.on("/rest/api/2/serverInfo", r -> new StubServer.Reply(401, "", Map.of()));
        assertThat(tools(Map.of()).providers()).contains("jira (Jira): nicht erreichbar – Jira: nicht angemeldet (401)");
        assertThatThrownBy(() -> tools(Map.of()).get("kein schlüssel", null, null, null))
                .hasMessageContaining("kein Ticket-Schlüssel");
        TicketTools unconfigured = new TicketTools(new TicketEnvironment(module.providers(),
                ModuleConfig.of(module.configSchema(), Map.of("jira.enabled", "true"))));
        assertThatThrownBy(() -> unconfigured.get("ABC-1", null, null, null)).hasMessageContaining("keine Server-URL");
    }
}
