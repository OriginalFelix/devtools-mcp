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

/** YouTrack-Provider über die ticket_*-Tools gegen einen Stub mit Antworten im Format der YouTrack-REST-API. */
class YouTrackTicketProviderTest {

    StubServer yt;
    TicketModule module = new TicketModule(new TicketProviders());

    @BeforeEach
    void start() throws IOException {
        yt = new StubServer();
    }

    @AfterEach
    void stop() {
        yt.close();
    }

    private Map<String, String> config(String... kv) {
        Map<String, String> v = new HashMap<>(Map.of("youtrack.enabled", "true", "youtrack.baseUrl", yt.url() + "/",
                "youtrack.token", "perm:abc", "youtrack.defaultProject", "ABC"));
        for (int i = 0; i < kv.length; i += 2) {
            v.put(kv[i], kv[i + 1]);
        }
        return v;
    }

    private TicketEnvironment env(String... kv) {
        return new TicketEnvironment(module.providers(), ModuleConfig.of(module.configSchema(), config(kv)));
    }

    private TicketTools tools() {
        return new TicketTools(env());
    }

    private static final String ISSUE_1 = """
            {"idReadable":"ABC-1","summary":"Login schlägt fehl","updated":1790000000000,"resolved":null,
             "tags":[{"name":"auth"}],"customFields":[
              {"name":"State","$type":"StateIssueCustomField","value":{"name":"In Progress","isResolved":false}},
              {"name":"Assignee","$type":"SingleUserIssueCustomField","value":{"login":"fgrebe","fullName":"Felix Grebe"}},
              {"name":"Priority","$type":"SingleEnumIssueCustomField","value":{"name":"Critical"}},
              {"name":"Type","$type":"SingleEnumIssueCustomField","value":{"name":"Bug"}}]}""";
    private static final String ISSUE_2 = """
            {"idReadable":"ABC-2","summary":"Doku","updated":1789000000000,"tags":[],"customFields":[
              {"name":"State","$type":"StateIssueCustomField","value":{"name":"Open","isResolved":false}},
              {"name":"Assignee","$type":"SingleUserIssueCustomField","value":null},
              {"name":"Type","$type":"SingleEnumIssueCustomField","value":{"name":"Task"}}]}""";
    private static final String ISSUE_3 = """
            {"idReadable":"ABC-3","summary":"Alt","updated":1788000000000,"resolved":1788000000000,"tags":[],"customFields":[
              {"name":"State","$type":"StateIssueCustomField","value":{"name":"Fixed","isResolved":true}}]}""";

    @Test
    void searchBuildsQueryUsesBearerAndSkipPaging() {
        yt.on("/api/issues", "[" + ISSUE_1 + "," + ISSUE_2 + "," + ISSUE_3 + "]");
        String out = tools().search(null, "login fehler", null, "me", List.of("auth"), "Priority: Critical sort by: priority",
                2, "4", null);

        assertThat(out).startsWith("2 Treffer (youtrack)")
                .contains("- ABC-1  [In Progress]  @Felix Grebe  Login schlägt fehl  (Bug; Critical; auth)")
                .contains("- ABC-2  [Open]  –  Doku  (Task)")
                .doesNotContain("ABC-3")
                .contains("ticket_search mit cursor=6");
        StubServer.Request r = yt.last("/api/issues");
        assertThat(r.decodedQuery()).contains("query=project: {ABC} #Unresolved for: me tag: {auth} login fehler "
                        + "(Priority: Critical) sort by: priority")
                .contains("$skip=4", "$top=3");
        assertThat(r.headers().get("authorization")).isEqualTo("Bearer perm:abc");

        yt.on("/api/issues", "[" + ISSUE_3 + "]");
        assertThat(tools().search(null, null, "closed", "none", null, null, null, null, null))
                .contains("- ABC-3  [Fixed]").doesNotContain("cursor=");
        assertThat(yt.last("/api/issues").decodedQuery())
                .contains("query=project: {ABC} #Resolved has: -Assignee sort by: updated desc");
    }

    @Test
    void getShowsCustomFieldsParentAndNewestComments() {
        yt.on("/api/issues/ABC-7", """
                {"idReadable":"ABC-7","summary":"Export","updated":1790000000000,"created":1789000000000,
                 "resolved":1790000000000,"description":"## Ziel\\nCSV-Export","commentsCount":3,
                 "reporter":{"login":"bob","fullName":"Bob"},"parent":{"issues":[{"idReadable":"ABC-1","summary":"Epic"}]},
                 "tags":[{"name":"export"}],"customFields":[
                  {"name":"State","$type":"StateIssueCustomField","value":{"name":"Fixed","isResolved":true}},
                  {"name":"Assignee","$type":"MultiUserIssueCustomField","value":[{"login":"anna","fullName":"Anna"},
                    {"login":"tom"}]},
                  {"name":"Fix versions","$type":"MultiVersionIssueCustomField","value":[{"name":"1.4"},{"name":"1.5"}]},
                  {"name":"Due Date","$type":"DateIssueCustomField","value":1791000000000},
                  {"name":"Estimation","$type":"PeriodIssueCustomField","value":{"presentation":"2d"}},
                  {"name":"Sprint","$type":"MultiVersionIssueCustomField","value":[]}]}""");
        yt.on("/api/issues/ABC-7/comments", """
                [{"id":"4-11","text":"zweiter","created":1789500000000,"author":{"login":"b","fullName":"B"}},
                 {"id":"4-12","text":"dritter","created":1789600000000,"author":{"login":"c"}}]""");

        String out = tools().get("https://yt.example.com/issue/abc-7/export-csv", null, 2, null);
        assertThat(out).startsWith("ABC-7: Export")
                .contains("Status:     Fixed [DONE]", "Zuständig:  Anna, tom", "Autor:      Bob", "Labels:     export",
                        "Parent:     ABC-1 Epic", "Fix versions: 1.4, 1.5", "Due Date:   2026-10-03", "Estimation: 2d",
                        "URL:        " + yt.url() + "/issue/ABC-7", "## Beschreibung\n## Ziel\nCSV-Export",
                        "## Kommentare (2 von 3, neueste)", "### B, 2026-09-", "(Kommentar 4-11)\nzweiter", "### c,")
                .doesNotContain("Sprint:");
        assertThat(yt.last("/api/issues/ABC-7/comments").decodedQuery()).contains("$skip=1", "$top=2");

        // status: ohne Kommentare
        yt.on("/api/issues/ABC-1", ISSUE_1);
        assertThat(tools().status(List.of("ABC-1"), null, null)).contains("- ABC-1  [In Progress]  @Felix Grebe");
        assertThat(yt.requests).noneMatch(r -> r.path().equals("/api/issues/ABC-1/comments"));
    }

    @Test
    void boardGroupsCurrentSprintByColumnFieldAndFiltersAssignee() {
        yt.on("/api/agiles", """
                [{"id":"108-1","name":"ABC Board","projects":[{"shortName":"ABC","name":"Alpha"}],
                  "sprintsSettings":{"disableSprints":false},"currentSprint":{"id":"109-5","name":"Sprint 5"}},
                 {"id":"108-2","name":"Fremd","projects":[{"shortName":"XYZ"}]}]""");
        yt.on("/api/agiles/108-1", """
                {"id":"108-1","name":"ABC Board","projects":[{"shortName":"ABC"}],"sprintsSettings":{"disableSprints":false},
                 "currentSprint":{"id":"109-5","name":"Sprint 5"},
                 "columnSettings":{"field":{"name":"State"},"columns":[
                   {"presentation":"Offen","fieldValues":[{"name":"Open"}]},
                   {"presentation":"In Arbeit","fieldValues":[{"name":"In Progress"},{"name":"Review"}]},
                   {"presentation":"Fertig","fieldValues":[{"name":"Fixed"}]}]}}""");
        yt.on("/api/agiles/108-1/sprints/109-5", "{\"name\":\"Sprint 5\",\"start\":1790000000000,\"finish\":1791000000000,"
                + "\"issues\":[" + ISSUE_1 + "," + ISSUE_2 + "," + ISSUE_3 + "]}");
        yt.on("/api/users/me", "{\"login\":\"fgrebe\",\"fullName\":\"Felix Grebe\"}");

        assertThat(tools().boards(null, null)).contains("1 Board(s) (youtrack, ABC)", "- 108-1  ABC Board  [scrum]")
                .doesNotContain("Fremd");
        String out = tools().board(null, null, null, null, null);
        assertThat(out).startsWith("Board ABC Board (youtrack, ID 108-1) – Sprint Sprint 5 (2026-09-21 – 2026-10-03)")
                .contains("## Offen (1)\n- ABC-2", "## In Arbeit (1)\n- ABC-1", "## Fertig (1)\n- ABC-3");

        String mine = tools().board("ABC Board", null, "me", null, null);
        assertThat(mine).contains("## In Arbeit (1)\n- ABC-1", "## Offen (0)", "## Fertig (0)");
    }

    @Test
    void linksUseDirectionalNames() {
        yt.on("/api/issues/ABC-2/links", """
                [{"direction":"INWARD","linkType":{"name":"Subtask","sourceToTarget":"parent for","targetToSource":"subtask of"},
                  "issues":[""" + ISSUE_1 + """
                ]},
                 {"direction":"BOTH","linkType":{"name":"Relates","sourceToTarget":"relates to","targetToSource":""},
                  "issues":[""" + ISSUE_3 + "]}]");
        assertThat(tools().links("ABC-2", null, null))
                .contains("- subtask of: ABC-1  [In Progress]  Login schlägt fehl  " + yt.url() + "/issue/ABC-1",
                        "- relates to: ABC-3  [Fixed]  Alt");
    }

    @Test
    void writesUseCommandsForStateAndTagsAndCustomFieldForAssignee() {
        yt.on("/api/issues/ABC-1", r -> {
            if (r.method().equals("POST")) {
                return StubServer.Reply.json("{\"idReadable\":\"ABC-1\"}");
            }
            if (r.decodedQuery().contains("projectCustomField")) {
                return StubServer.Reply.json("""
                        {"customFields":[{"name":"State","$type":"StateIssueCustomField","value":{"name":"Open"},
                          "projectCustomField":{"bundle":{"values":[{"name":"Open"},{"name":"In Progress"},
                            {"name":"Fixed","isResolved":true},{"name":"Obsolete","archived":true}]}}}]}""");
            }
            return StubServer.Reply.json("""
                    {"tags":[{"name":"alt"},{"name":"bleibt"}],"customFields":[
                      {"name":"State","$type":"StateIssueCustomField"},{"name":"Assignee","$type":"SingleUserIssueCustomField"}]}""");
        });
        yt.on("/api/commands", "{}");
        yt.on("/api/users", "[{\"id\":\"1-7\",\"login\":\"anna\",\"fullName\":\"Anna Admin\"},{\"id\":\"1-8\",\"login\":\"annab\"}]");
        yt.on("/api/issues/ABC-1/comments", "{\"id\":\"4-99\"}");
        TicketEnvironment env = env("allowTransition", "true");

        assertThat(tools().transitions("ABC-1", null, null))
                .contains("[State:In Progress]  State → In Progress  → In Progress (IN_PROGRESS)", "[State:Fixed]", "(DONE)")
                .doesNotContain("Obsolete", "[State:Open]");
        assertThat(new TicketTransitionTools(env, false).transition("ABC-1", "fixed", null, null, null))
                .startsWith("ABC-1: Status → Fixed");
        assertThat(yt.last("/api/commands").body())
                .isEqualTo("{\"query\":\"State {Fixed}\",\"issues\":[{\"idReadable\":\"ABC-1\"}]}");

        assertThat(new TicketAssignTools(env).assign("ABC-1", List.of("anna"), null, null))
                .startsWith("ABC-1: zugewiesen an Anna Admin");
        assertThat(yt.last("/api/issues/ABC-1").body()).isEqualTo("{\"customFields\":[{\"name\":\"Assignee\","
                + "\"$type\":\"SingleUserIssueCustomField\",\"value\":{\"id\":\"1-7\"}}]}");
        assertThatThrownBy(() -> new TicketAssignTools(env).assign("ABC-1", List.of("a", "b"), null, null))
                .hasMessageContaining("genau einen");
        new TicketAssignTools(env).assign("ABC-1", List.of("none"), null, null);
        assertThat(yt.last("/api/issues/ABC-1").body()).contains("\"value\":null");

        int before = yt.requests.size();
        assertThat(new TicketEditTools(env).update("ABC-1", "Neu", null, List.of("bleibt", "neu tag"), null, null))
                .contains("geändert: Titel, Labels [bleibt, neu tag]");
        List<String> writes = yt.requests.subList(before, yt.requests.size()).stream()
                .filter(r -> r.method().equals("POST")).map(StubServer.Request::body).toList();
        assertThat(writes).containsExactly("{\"summary\":\"Neu\"}",
                "{\"query\":\"untag {alt}\",\"issues\":[{\"idReadable\":\"ABC-1\"}]}",
                "{\"query\":\"tag {neu tag}\",\"issues\":[{\"idReadable\":\"ABC-1\"}]}");

        assertThat(new TicketCommentTools(env).comment("abc-1", "Hallo", null, null))
                .contains("ABC-1: Kommentar 4-99 hinzugefügt", "/issue/ABC-1#focus=Comments-4-99.0-0");
        assertThat(yt.last("/api/issues/ABC-1/comments").body()).isEqualTo("{\"text\":\"Hallo\"}");
    }

    @Test
    void createResolvesProjectIdSetsTypeTagsAndAssigneeAndDeleteOnlyOwn() {
        yt.on("/api/admin/projects", "[{\"id\":\"0-1\",\"shortName\":\"XYZ\"},{\"id\":\"0-2\",\"shortName\":\"ABC\",\"name\":\"Alpha\"}]");
        yt.on("/api/issues", "{\"idReadable\":\"ABC-50\"}");
        yt.on("/api/commands", "{}");
        yt.on("/api/users/me", "{\"id\":\"1-1\",\"login\":\"fgrebe\",\"fullName\":\"Felix Grebe\"}");
        yt.on("/api/issues/ABC-50", r -> r.method().equals("DELETE") ? new StubServer.Reply(200, "", Map.of())
                : r.method().equals("POST") ? StubServer.Reply.json("{}")
                : StubServer.Reply.json("{\"customFields\":[{\"name\":\"Assignee\",\"$type\":\"SingleUserIssueCustomField\"}]}"));
        TicketEnvironment env = env();

        assertThat(new TicketCreateTools(env).create("Neu", "Text", null, "Bug", List.of("x"), List.of("me"), null))
                .startsWith("ABC-50: angelegt (Bug), Tags [x], zugewiesen an Felix Grebe");
        assertThat(yt.last("/api/issues").body()).isEqualTo("{\"project\":{\"id\":\"0-2\"},\"summary\":\"Neu\","
                + "\"description\":\"Text\",\"customFields\":[{\"name\":\"Type\",\"$type\":\"SingleEnumIssueCustomField\","
                + "\"value\":{\"name\":\"Bug\"}}]}");
        assertThat(yt.last("/api/commands").body()).contains("tag {x}", "ABC-50");

        assertThatThrownBy(() -> new TicketCreateTools(env).create("Neu", null, "NOPE", null, null, null, null))
                .hasMessageContaining("Projekt 'NOPE' nicht gefunden").hasMessageContaining("XYZ, ABC");

        TicketDeleteTools del = new TicketDeleteTools(env, true);
        assertThatThrownBy(() -> del.delete("ABC-49", null, null)).hasMessageContaining("nicht über ticket_create");
        assertThat(del.delete(yt.url() + "/issue/ABC-50", null, null)).isEqualTo("ABC-50: Issue gelöscht");
        assertThat(yt.last("/api/issues/ABC-50").method()).isEqualTo("DELETE");
    }

    @Test
    void errorsNameTheNextStep() {
        yt.on("/api/issues", r -> new StubServer.Reply(400,
                "{\"error\":\"bad_request\",\"error_description\":\"Unknown field: Prio\"}", Map.of()));
        assertThatThrownBy(() -> tools().search(null, null, null, null, null, "Prio: 1", null, null, null))
                .hasMessageContaining("YouTrack-Fehler 400").hasMessageContaining("Unknown field: Prio");
        yt.on("/api/users/me", r -> new StubServer.Reply(401, "", Map.of()));
        assertThat(tools().providers()).contains("youtrack (YouTrack): nicht erreichbar – YouTrack: nicht angemeldet (401)");
        assertThatThrownBy(() -> tools().get("kein schlüssel", null, null, null)).hasMessageContaining("keine Issue-ID");
        TicketTools unconfigured = new TicketTools(new TicketEnvironment(module.providers(),
                ModuleConfig.of(module.configSchema(), Map.of("youtrack.enabled", "true"))));
        assertThatThrownBy(() -> unconfigured.get("ABC-1", null, null, null)).hasMessageContaining("keine Server-URL");
    }
}
