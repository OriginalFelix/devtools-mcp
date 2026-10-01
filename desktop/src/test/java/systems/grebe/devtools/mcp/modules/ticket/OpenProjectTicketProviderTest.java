package systems.grebe.devtools.mcp.modules.ticket;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import systems.grebe.devtools.mcp.core.ModuleConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** OpenProject-Provider über die ticket_*-Tools gegen einen Stub mit Antworten im Format der API v3 (HAL+JSON). */
class OpenProjectTicketProviderTest {

    StubServer op;
    TicketModule module = new TicketModule(new TicketProviders());

    @BeforeEach
    void start() throws IOException {
        op = new StubServer();
        op.on("/api/v3/statuses", """
                {"_embedded":{"elements":[
                  {"id":1,"name":"New","isClosed":false,"_links":{"self":{"href":"/api/v3/statuses/1"}}},
                  {"id":7,"name":"In progress","isClosed":false,"_links":{"self":{"href":"/api/v3/statuses/7"}}},
                  {"id":12,"name":"Closed","isClosed":true,"_links":{"self":{"href":"/api/v3/statuses/12"}}}]}}""");
    }

    @AfterEach
    void stop() {
        op.close();
    }

    private TicketEnvironment env(String... kv) {
        Map<String, String> v = new HashMap<>(Map.of("openproject.enabled", "true", "openproject.baseUrl", op.url() + "/api/v3",
                "openproject.token", "k123", "openproject.defaultProject", "demo"));
        for (int i = 0; i < kv.length; i += 2) {
            v.put(kv[i], kv[i + 1]);
        }
        return new TicketEnvironment(module.providers(), ModuleConfig.of(module.configSchema(), v));
    }

    private TicketTools tools() {
        return new TicketTools(env());
    }

    private static final String WP_1 = """
            {"id":1,"subject":"Login schlägt fehl","updatedAt":"2026-09-28T10:00:00Z","lockVersion":3,
             "_embedded":{"project":{"identifier":"demo"}},
             "_links":{"status":{"href":"/api/v3/statuses/7","title":"In progress"},"type":{"href":"/api/v3/types/2","title":"Bug"},
              "priority":{"href":"/api/v3/priorities/9","title":"High"},"assignee":{"href":"/api/v3/users/5","title":"Felix Grebe"},
              "project":{"href":"/api/v3/projects/3","title":"Demo"}}}""";
    private static final String WP_2 = """
            {"id":2,"subject":"Doku","updatedAt":"2026-09-27T10:00:00Z",
             "_links":{"status":{"href":"/api/v3/statuses/1","title":"New"},"type":{"title":"Task"},"priority":{"title":"Normal"},
              "assignee":{"href":null}}}""";

    @Test
    void searchSendsFiltersUsesApiKeyAndPageOffsets() {
        op.on("/api/v3/projects/demo/work_packages", "{\"total\":5,\"count\":2,\"_embedded\":{\"elements\":[" + WP_1 + "," + WP_2 + "]}}");
        String out = tools().search(null, "login", null, "me", null, "[{\"type\":{\"operator\":\"=\",\"values\":[\"2\"]}}]",
                2, "2", null);

        assertThat(out).startsWith("Treffer: 5 (openproject)")
                .contains("- #1  [In progress]  @Felix Grebe  Login schlägt fehl  (Bug; High)")
                .contains("- #2  [New]  –  Doku  (Task; Normal)")
                .contains("ticket_search mit cursor=3");
        StubServer.Request r = op.last("/api/v3/projects/demo/work_packages");
        assertThat(r.decodedQuery()).contains("filters=[{\"status\":{\"operator\":\"o\",\"values\":[]}},"
                        + "{\"assignee\":{\"operator\":\"=\",\"values\":[\"me\"]}},{\"search\":{\"operator\":\"**\",\"values\":[\"login\"]}},"
                        + "{\"type\":{\"operator\":\"=\",\"values\":[\"2\"]}}]")
                .contains("sortBy=[[\"updatedAt\",\"desc\"]]", "pageSize=2", "offset=2");
        assertThat(r.headers().get("authorization")).isEqualTo("Basic "
                + Base64.getEncoder().encodeToString("apikey:k123".getBytes(StandardCharsets.UTF_8)));

        // alle Status: leere Filterliste mitschicken, sonst filtert die API auf offen
        tools().search(null, null, "all", null, null, null, null, null, null);
        assertThat(op.last("/api/v3/projects/demo/work_packages").decodedQuery()).contains("filters=[]", "offset=1");

        assertThatThrownBy(() -> tools().search(null, null, null, null, List.of("bug"), null, null, null, null))
                .hasMessageContaining("kennt keine Labels");
        assertThatThrownBy(() -> tools().search(null, null, null, null, null, "type = Bug", null, null, null))
                .hasMessageContaining("JSON-Array");
    }

    @Test
    void getShowsFieldsStatusCategoryAndNewestComments() {
        op.on("/api/v3/work_packages/7", """
                {"id":7,"subject":"Export","updatedAt":"2026-09-02T08:00:00Z","createdAt":"2026-09-01T08:00:00Z",
                 "description":{"format":"markdown","raw":"## Ziel\\nCSV-Export"},"dueDate":"2026-10-01","percentageDone":40,
                 "_embedded":{"project":{"identifier":"demo"}},
                 "_links":{"status":{"href":"/api/v3/statuses/12","title":"Closed"},"type":{"title":"Feature"},
                  "author":{"title":"Bob"},"assignee":{"href":"/api/v3/users/6","title":"Anna"},"responsible":{"title":"Carla"},
                  "project":{"title":"Demo"},"parent":{"href":"/api/v3/work_packages/1","title":"Epic"},
                  "version":{"title":"1.4"},"category":{"href":null}}}""");
        op.on("/api/v3/work_packages/7/activities", """
                {"_embedded":{"elements":[
                  {"id":100,"comment":{"raw":"erster"},"createdAt":"c1","_links":{"user":{"title":"A"}}},
                  {"id":101,"comment":{"raw":""},"createdAt":"c2","_links":{"user":{"title":"B"}}},
                  {"id":102,"comment":{"raw":"dritter"},"createdAt":"c3","_links":{"user":{"title":"C"}}}]}}""");

        String out = tools().get(op.url() + "/projects/demo/work_packages/7/activity", null, 1, null);
        assertThat(out).startsWith("#7: Export")
                .contains("Status:     Closed [DONE]", "Typ:        Feature", "Zuständig:  Anna", "Autor:      Bob",
                        "Projekt:    Demo", "Parent:     #1 Epic", "Verantwortlich: Carla", "Version:    1.4",
                        "Fällig:     2026-10-01", "Fortschritt: 40 %", "URL:        " + op.url() + "/work_packages/7",
                        "## Beschreibung\n## Ziel\nCSV-Export", "## Kommentare (1 von 2, neueste)",
                        "### C, c3  (Kommentar 102)\ndritter")
                .doesNotContain("erster", "Kategorie");

        // status: ohne Aktivitäten
        op.on("/api/v3/work_packages/1", WP_1);
        assertThat(tools().status(List.of("#1"), null, null)).contains("- #1  [In progress]  @Felix Grebe");
        assertThat(op.requests).noneMatch(r -> r.path().equals("/api/v3/work_packages/1/activities"));
    }

    @Test
    void boardColumnsAreSavedQueriesInWidgetOrder() {
        op.on("/api/v3/projects/demo", "{\"id\":3,\"identifier\":\"demo\"}");
        op.on("/api/v3/grids", """
                {"_embedded":{"elements":[
                  {"id":4,"name":"Sprint-Board","options":{"type":"action","attribute":"status"},
                   "_links":{"scope":{"href":"/projects/demo/boards"}}},
                  {"id":5,"name":"Fremd","_links":{"scope":{"href":"/projects/other/boards"}}},
                  {"id":6,"name":"Meine Seite","_links":{"scope":{"href":"/my/page"}}}]}}""");
        op.on("/api/v3/grids/4", "{\"widgets\":[{\"startColumn\":2,\"options\":{\"queryId\":\"21\"}},"
                + "{\"startColumn\":1,\"options\":{\"queryId\":\"20\"}},{\"startColumn\":3,\"options\":{}}]}");
        op.on("/api/v3/queries/20", "{\"name\":\"New\",\"_embedded\":{\"results\":{\"total\":1,\"_embedded\":{\"elements\":["
                + WP_2 + "]}}}}");
        op.on("/api/v3/queries/21", "{\"name\":\"In progress\",\"_embedded\":{\"results\":{\"total\":3,\"_embedded\":{\"elements\":["
                + WP_1 + "]}}}}");
        op.on("/api/v3/users/me", "{\"name\":\"Felix Grebe\",\"_links\":{\"self\":{\"href\":\"/api/v3/users/5\"}}}");

        assertThat(tools().boards(null, null)).contains("1 Board(s) (openproject, demo)",
                "- 4  Sprint-Board  [Aktions-Board (status)]  " + op.url() + "/projects/demo/boards/4").doesNotContain("Fremd");

        String out = tools().board(null, null, null, null, null);
        assertThat(out).startsWith("Board Sprint-Board (openproject, ID 4) – Spalten = gespeicherte Abfragen des Boards")
                .contains("## New (1)\n- #2  [New]", "## In progress (3)\n- #1  [In progress]", "… weitere");
        assertThat(out.indexOf("## New")).isLessThan(out.indexOf("## In progress"));
        assertThat(op.last("/api/v3/queries/21").decodedQuery()).contains("pageSize=15", "offset=1");

        String mine = tools().board("sprint", null, "me", null, null);
        assertThat(mine).contains("## New (0)", "## In progress (1)\n- #1");
        assertThat(op.last("/api/v3/queries/21").decodedQuery()).contains("pageSize=100");
    }

    @Test
    void linksShowParentChildrenAndRelationsFromThisSide() {
        op.on("/api/v3/work_packages/2", """
                {"id":2,"_links":{"parent":{"href":"/api/v3/work_packages/1","title":"Login schlägt fehl"},
                 "children":[{"href":"/api/v3/work_packages/9","title":"Teil"}]}}""");
        op.on("/api/v3/work_packages/2/relations", """
                {"_embedded":{"elements":[
                  {"type":"blocks","reverseType":"blocked","_links":{"from":{"href":"/api/v3/work_packages/2","title":"Doku"},
                   "to":{"href":"/api/v3/work_packages/3","title":"Release"}}},
                  {"type":"precedes","reverseType":"follows","_links":{"from":{"href":"/api/v3/work_packages/4","title":"Design"},
                   "to":{"href":"/api/v3/work_packages/2","title":"Doku"}}}]}}""");

        assertThat(tools().links("#2", null, null)).contains("- Parent: #1  [-]  Login schlägt fehl",
                "- Unteraufgabe: #9  [-]  Teil", "- blocks: #3  [-]  Release", "- follows: #4  [-]  Design  " + op.url()
                        + "/work_packages/4");
    }

    @Test
    void writesPatchWithLockVersionAndRespectWriteProjects() {
        op.on("/api/v3/work_packages/1", r -> r.method().equals("PATCH")
                ? StubServer.Reply.json("{\"id\":1,\"_links\":{\"status\":{\"title\":\"Closed\"},\"assignee\":{\"title\":\"Anna\"}}}")
                : StubServer.Reply.json(WP_1));
        op.on("/api/v3/work_packages/1/form", """
                {"_embedded":{"schema":{"status":{"_embedded":{"allowedValues":[
                  {"id":7,"name":"In progress","isClosed":false,"_links":{"self":{"href":"/api/v3/statuses/7"}}},
                  {"id":12,"name":"Closed","isClosed":true,"_links":{"self":{"href":"/api/v3/statuses/12"}}}]}}}}}""");
        op.on("/api/v3/work_packages/1/available_assignees", """
                {"_embedded":{"elements":[{"name":"Anna Admin","login":"anna","_links":{"self":{"href":"/api/v3/users/8"}}},
                  {"name":"Annabelle","_links":{"self":{"href":"/api/v3/users/9"}}}]}}""");
        op.on("/api/v3/work_packages/1/activities", "{\"id\":77}");
        TicketEnvironment env = env();

        assertThat(tools().transitions("#1", null, null)).contains("[status:12]  Status → Closed  → Closed (DONE)  – Workflow")
                .doesNotContain("[status:7]");
        assertThat(op.last("/api/v3/work_packages/1/form").body()).isEqualTo("{\"lockVersion\":3}");
        assertThat(new TicketTransitionTools(env, false).transition("1", "closed", null, null, null)).startsWith("#1: Status → Closed");
        assertThat(op.last("/api/v3/work_packages/1").body())
                .isEqualTo("{\"lockVersion\":3,\"_links\":{\"status\":{\"href\":\"/api/v3/statuses/12\"}}}");

        assertThat(new TicketAssignTools(env).assign("#1", List.of("anna"), null, null)).startsWith("#1: zugewiesen an Anna");
        assertThat(op.last("/api/v3/work_packages/1").body())
                .isEqualTo("{\"lockVersion\":3,\"_links\":{\"assignee\":{\"href\":\"/api/v3/users/8\"}}}");
        assertThatThrownBy(() -> new TicketAssignTools(env).assign("#1", List.of("ann"), null, null))
                .hasMessageContaining("mehrdeutig").hasMessageContaining("Annabelle");
        new TicketAssignTools(env).assign("#1", List.of(), null, null);
        assertThat(op.last("/api/v3/work_packages/1").body()).contains("\"assignee\":{\"href\":null}");

        assertThat(new TicketEditTools(env).update("#1", "Neu", "Text", null, null, null)).contains("geändert: Titel, Beschreibung");
        assertThat(op.last("/api/v3/work_packages/1").body())
                .isEqualTo("{\"lockVersion\":3,\"subject\":\"Neu\",\"description\":{\"raw\":\"Text\"}}");
        assertThatThrownBy(() -> new TicketEditTools(env).update("#1", null, null, List.of("x"), null, null))
                .hasMessageContaining("kennt keine Labels");

        assertThat(new TicketCommentTools(env).comment("#1", "Hallo", null, null)).startsWith("#1: Kommentar 77 hinzugefügt");
        assertThat(op.last("/api/v3/work_packages/1/activities").body()).isEqualTo("{\"comment\":{\"raw\":\"Hallo\"}}");

        // Projekt kommt aus dem Arbeitspaket, nicht aus dem Parameter oder einer URL
        TicketEnvironment restricted = env("writeProjects", "openproject:other");
        assertThatThrownBy(() -> new TicketCommentTools(restricted).comment(op.url() + "/projects/other/work_packages/1",
                "x", "other", null)).hasMessageContaining("in 'demo' (openproject) ist nicht freigegeben");
        assertThat(new TicketCommentTools(env("writeProjects", "openproject:demo")).comment("#1", "y", null, null))
                .startsWith("#1: Kommentar");
    }

    @Test
    void createResolvesTypeAndDeleteRefusesParents() {
        op.on("/api/v3/projects/demo/types", """
                {"_embedded":{"elements":[{"name":"Task","_links":{"self":{"href":"/api/v3/types/1"}}},
                  {"name":"Bug","_links":{"self":{"href":"/api/v3/types/2"}}}]}}""");
        op.on("/api/v3/projects/demo/work_packages", "{\"id\":60,\"_links\":{\"type\":{\"title\":\"Bug\"}},"
                + "\"_embedded\":{\"project\":{\"identifier\":\"demo\"}}}");
        op.on("/api/v3/work_packages/60", r -> r.method().equals("DELETE") ? new StubServer.Reply(204, "", Map.of())
                : StubServer.Reply.json("{\"id\":60,\"_links\":{\"children\":[]}}"));
        op.on("/api/v3/work_packages/2", "{\"id\":2,\"_embedded\":{\"project\":{\"identifier\":\"demo\"}},"
                + "\"_links\":{\"children\":[{\"href\":\"/api/v3/work_packages/9\"}]}}");
        TicketEnvironment env = env();

        assertThat(new TicketCreateTools(env).create("Neu", "Text", null, "bug", null, null, null))
                .startsWith("#60: angelegt (Bug)\n" + op.url() + "/work_packages/60");
        assertThat(op.last("/api/v3/projects/demo/work_packages").body())
                .isEqualTo("{\"subject\":\"Neu\",\"description\":{\"raw\":\"Text\"},\"_links\":{\"type\":{\"href\":\"/api/v3/types/2\"}}}");
        assertThatThrownBy(() -> new TicketCreateTools(env).create("Neu", null, null, "Epic", null, null, null))
                .hasMessageContaining("Typ 'Epic'").hasMessageContaining("Gültig: Task, Bug");
        assertThatThrownBy(() -> new TicketCreateTools(env).create("Neu", null, null, null, List.of("x"), null, null))
                .hasMessageContaining("kennt keine Labels");

        TicketDeleteTools own = new TicketDeleteTools(env, true);
        assertThatThrownBy(() -> own.delete("#2", null, null)).hasMessageContaining("nicht über ticket_create");
        assertThat(own.delete("60", null, null)).isEqualTo("#60: Arbeitspaket gelöscht");
        assertThat(op.last("/api/v3/work_packages/60").method()).isEqualTo("DELETE");

        assertThatThrownBy(() -> new TicketDeleteTools(env, false).delete("#2", null, null))
                .hasMessageContaining("1 Unteraufgabe(n)");
        assertThat(op.requests).noneMatch(r -> r.method().equals("DELETE") && r.path().equals("/api/v3/work_packages/2"));
    }

    @Test
    void errorsNameTheNextStep() {
        op.on("/api/v3", r -> new StubServer.Reply(401, "{\"_type\":\"Error\",\"message\":\"You did not provide the correct "
                + "credentials.\"}", Map.of()));
        assertThat(tools().providers()).contains("openproject (OpenProject): nicht erreichbar – OpenProject: nicht angemeldet "
                + "(401)").contains("correct credentials");
        assertThatThrownBy(() -> tools().get("abc", null, null, null)).hasMessageContaining("ist kein Arbeitspaket");
        TicketTools unconfigured = new TicketTools(new TicketEnvironment(module.providers(),
                ModuleConfig.of(module.configSchema(), Map.of("openproject.enabled", "true"))));
        assertThatThrownBy(() -> unconfigured.get("#1", null, null, null)).hasMessageContaining("keine Server-URL");
    }
}
