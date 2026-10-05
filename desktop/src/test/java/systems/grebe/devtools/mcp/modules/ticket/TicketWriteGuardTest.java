package systems.grebe.devtools.mcp.modules.ticket;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import systems.grebe.devtools.mcp.core.ModuleConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Schreibende Ticket-Tools: je Schalter ein Tool, Projektfreigabe vor jedem Schreibzugriff, Kommentar beim
 * Statuswechsel nur mit Kommentar-Freigabe. Gegen einen Jira-Stub, weil die Prüfungen im Modul liegen.
 */
class TicketWriteGuardTest {

    StubServer jira;
    TicketModule module = new TicketModule(new TicketProviders());

    @BeforeEach
    void start() throws IOException {
        jira = new StubServer();
        jira.on("/rest/api/2/issue/ABC-1/transitions", r -> "POST".equals(r.method())
                ? new StubServer.Reply(204, "", Map.of())
                : StubServer.Reply.json("""
                        {"transitions":[{"id":"11","name":"Start","to":{"name":"In Arbeit","statusCategory":{"key":"indeterminate"}}},
                          {"id":"31","name":"Erledigt","to":{"name":"Done","statusCategory":{"key":"done"}}}]}"""));
        jira.on("/rest/api/2/issue/ABC-1/comment", r -> "GET".equals(r.method())
                ? StubServer.Reply.json("{\"comments\":[{\"id\":\"1002\",\"body\":\"fertig\\n\\n(via MCP)\"}]}")
                : StubServer.Reply.json("{\"id\":\"1001\"}"));
    }

    @AfterEach
    void stop() {
        jira.close();
    }

    private ModuleConfig config(Map<String, String> extra) {
        Map<String, String> v = new HashMap<>(Map.of("jira.enabled", "true", "jira.baseUrl", jira.url(),
                "jira.token", "pat", "jira.defaultProject", "ABC"));
        v.putAll(extra);
        return ModuleConfig.of(module.configSchema(), v);
    }

    private TicketEnvironment env(Map<String, String> extra) {
        return new TicketEnvironment(module.providers(), config(extra));
    }

    private List<String> toolNames(Map<String, String> extra) {
        return module.createTools(config(extra)).stream().map(ToolCallback::getToolDefinition).map(d -> d.name()).toList();
    }

    @Test
    void eachSwitchAddsExactlyItsTool() {
        List<String> read = List.of("providers", "boards", "board", "search", "get", "status", "links", "transitions", "worklogs");
        assertThat(toolNames(Map.of())).containsExactlyInAnyOrderElementsOf(read);
        Map<String, String> switches = Map.ofEntries(Map.entry("allowComment", "comment"),
                Map.entry("allowTransition", "transition"), Map.entry("allowAssign", "assign"),
                Map.entry("allowEdit", "update"), Map.entry("allowCreate", "create"), Map.entry("allowLogTime", "log_time"));
        switches.forEach((sw, tool) -> assertThat(toolNames(Map.of(sw, "true"))).as(sw)
                .hasSize(read.size() + 1).contains(tool));
        assertThat(toolNames(Map.ofEntries(switches.keySet().stream().map(k -> Map.entry(k, "true")).toArray(Map.Entry[]::new))))
                .hasSize(read.size() + switches.size());
    }

    @Test
    void transitionPicksByNameOrTargetAndCommentNeedsItsOwnSwitch() {
        TicketTransitionTools withoutComment = new TicketTransitionTools(env(Map.of()), false);
        assertThat(withoutComment.transition("ABC-1", "in arbeit", null, null, null))
                .startsWith("ABC-1: Status → In Arbeit ('Start')");
        assertThat(jira.last("/rest/api/2/issue/ABC-1/transitions").body()).isEqualTo("{\"transition\":{\"id\":\"11\"}}");

        int before = jira.requests.size();
        assertThatThrownBy(() -> withoutComment.transition("ABC-1", "Done", "fertig", null, null))
                .hasMessageContaining("Kommentieren ist in der DevTools-App abgeschaltet");
        // abgelehnt, BEVOR irgendetwas geschrieben wurde
        assertThat(jira.requests.subList(before, jira.requests.size())).noneMatch(r -> "POST".equals(r.method()));

        TicketTransitionTools withComment = new TicketTransitionTools(env(Map.of("commentSuffix", "(via MCP)")), true);
        assertThat(withComment.transition("ABC-1", "31", "fertig", null, null))
                .contains("Status → Done ('Erledigt') mit Kommentar 1002", "focusedCommentId=1002");
        // Jira: Kommentar im Übergang selbst, damit Validatoren mit Pflichtkommentar ihn sehen
        assertThat(jira.last("/rest/api/2/issue/ABC-1/transitions").body()).isEqualTo("{\"transition\":{\"id\":\"31\"},"
                + "\"update\":{\"comment\":[{\"add\":{\"body\":\"fertig\\n\\n(via MCP)\"}}]}}");
        assertThat(jira.requests).noneMatch(r -> r.path().endsWith("/comment") && "POST".equals(r.method()));
        assertThat(jira.last("/rest/api/2/issue/ABC-1/comment").decodedQuery()).isEqualTo("orderBy=-created&maxResults=1");

        assertThatThrownBy(() -> withoutComment.transition("ABC-1", "Review", null, null, null))
                .hasMessageContaining("nicht eindeutig oder nicht möglich").hasMessageContaining("'Start' → In Arbeit [11]");
    }

    @Test
    void writeProjectsRestrictByKeyNotByProjectParameter() {
        TicketEnvironment env = env(Map.of("writeProjects", "jira:ABC\ngithub:octo/*\nXYZ"));
        TicketCommentTools tools = new TicketCommentTools(env);

        assertThat(tools.comment("ABC-1", "hallo", null, null)).startsWith("ABC-1: Kommentar 1001");
        // Projekt im Parameter freigegeben, Schlüssel aber aus einem anderen Projekt → abgelehnt
        int before = jira.requests.size();
        assertThatThrownBy(() -> tools.comment("DEF-9", "hallo", "ABC", null))
                .hasMessageContaining("'DEF' (jira) ist nicht freigegeben").hasMessageContaining("Module → Tickets");
        assertThat(jira.requests).hasSize(before); // kein Aufruf an Jira

        TicketEnvironment.Entry jiraEntry = env.resolve("jira", null);
        assertThat(env.writeAllowed("jira", "xyz")).isTrue();          // ohne System-Präfix: für alle Systeme
        assertThat(env.writeAllowed("jira", "octo/app")).isFalse();    // github:-Eintrag gilt nicht für Jira
        assertThat(env.writeAllowed("github", "octo/app")).isTrue();   // Präfix mit *
        assertThat(env.writeAllowed("github", "octopus/app")).isFalse();
        assertThat(env.checkWrite(jiraEntry, null, null, "Anlegen")).isEqualTo("ABC"); // Standardprojekt beim Anlegen

        TicketEnvironment open = env(Map.of());
        assertThat(open.checkWrite(open.resolve("jira", null), "DEF-9", null, "Kommentieren")).isEqualTo("DEF");
        assertThatThrownBy(() -> new TicketCommentTools(open).comment("ABC-1", "  ", null, null))
                .hasMessageContaining("Leerer Kommentar");
    }

    @Test
    void readOnlyToolsListLinksAndTransitions() {
        jira.on("/rest/api/2/issue/ABC-1", """
                {"key":"ABC-1","fields":{"parent":{"key":"ABC-0","fields":{"summary":"Epic","status":{"name":"Offen"}}},
                 "subtasks":[{"key":"ABC-2","fields":{"summary":"Teil","status":{"name":"Done"}}}],
                 "issuelinks":[{"type":{"inward":"is blocked by","outward":"blocks"},
                   "inwardIssue":{"key":"XYZ-7","fields":{"summary":"API","status":{"name":"In Arbeit"}}}}]}}""");
        jira.on("/rest/api/2/search", "{\"issues\":[{\"key\":\"ABC-2\",\"fields\":{\"summary\":\"Teil\",\"status\":{\"name\":\"Done\"}}},"
                + "{\"key\":\"ABC-5\",\"fields\":{\"summary\":\"Kind\",\"status\":{\"name\":\"Offen\"}}}]}");
        TicketTools t = new TicketTools(env(Map.of()));

        assertThat(t.links("ABC-1", null, null)).contains("4 Verknüpfung(en) von ABC-1",
                "- Parent: ABC-0  [Offen]  Epic", "- Unteraufgabe: ABC-2  [Done]  Teil",
                "- is blocked by: XYZ-7  [In Arbeit]  API", "- Kind (Epic): ABC-5  [Offen]  Kind");
        assertThat(jira.last("/rest/api/2/search").decodedQuery()).contains("jql=\"Epic Link\" = ABC-1");

        assertThat(t.transitions("ABC-1", null, null)).contains("- [11]  Start  → In Arbeit (IN_PROGRESS)  – Workflow",
                "- [31]  Erledigt  → Done (DONE)");
    }
}
