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

        assertThat(new TicketEditTools(env).update("ABC-1", "Neu", null, List.of("needs review"), null, null, null))
                .contains("geändert: Titel, Labels [needs review]");
        assertThat(stub.last("/rest/api/2/issue/ABC-1").method()).isEqualTo("PUT");
        assertThat(stub.last("/rest/api/2/issue/ABC-1").body())
                .isEqualTo("{\"fields\":{\"summary\":\"Neu\",\"labels\":[\"needs_review\"]}}");
        assertThatThrownBy(() -> new TicketEditTools(env).update("ABC-1", " ", null, null, null, null, null))
                .hasMessageContaining("Nichts zu ändern");

        assertThat(new TicketCreateTools(env).create("Neues Ticket", "h2. Ziel", null, "Bug", List.of("x"), null, null, null))
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
        assertThatThrownBy(() -> new TicketCreateTools(env).create("T", null, null, "Bugg", null, null, null, null))
                .hasMessageContaining("valid issue type").hasMessageContaining("gültige Typen: Task, Story");

        stub.on("/rest/api/2/issue/ABC-1/editmeta", EDITMETA);
        stub.on("/rest/api/2/issue/ABC-1", r -> new StubServer.Reply(204, "", Map.of()));
        new TicketEditTools(env).update("ABC-1", null, null, null, Map.of("Tester", "me"), null, null);
        assertThat(stub.last("/rest/api/2/issue/ABC-1").body()).isEqualTo("{\"fields\":{\"customfield_10368\":{\"accountId\":\"5b10ac\"}}}");
    }

    private static final String EDITMETA = """
            {"fields":{
             "customfield_10368":{"name":"Tester","schema":{"type":"user","custom":"com.atlassian.jira.plugin.system.customfieldtypes:userpicker"}},
             "fixVersions":{"name":"Lösungsversionen","schema":{"type":"array","items":"version","system":"fixVersions"},
               "allowedValues":[{"id":"1","name":"43.1"},{"id":"2","name":"42.6"}]},
             "customfield_10016":{"name":"Story Points","schema":{"type":"number"}},
             "customfield_10500":{"name":"Umgebung","schema":{"type":"option"},"allowedValues":[{"value":"Produktion"},{"value":"Test"}]},
             "customfield_10600":{"name":"Notiz","schema":{"type":"string"}},
             "customfield_10601":{"name":"Notiz","schema":{"type":"string"}},
             "customfield_10700":{"name":"Kaskade","schema":{"type":"option-with-child"}}}}""";

    @Test
    void jiraUpdateFieldsResolvesNamesAndConvertsByFieldType() {
        stub.on("/rest/api/2/issue/ABC-1/editmeta", EDITMETA);
        stub.on("/rest/api/2/issue/ABC-1", r -> new StubServer.Reply(204, "", Map.of()));
        stub.on("/rest/api/2/user/assignable/search", "[{\"name\":\"fgrebe\",\"displayName\":\"Felix Grebe\",\"emailAddress\":\"felix@example.com\"}]");
        TicketEditTools tools = new TicketEditTools(env(jira()));

        Map<String, String> fields = new java.util.LinkedHashMap<>();
        fields.put("Tester", "");
        fields.put("lösungsversionen", "43.1, 42.6");
        fields.put("customfield_10016", "5,5");
        fields.put("Umgebung", "test");
        assertThat(tools.update("ABC-1", null, null, null, fields, null, null))
                .startsWith("ABC-1: Felder geändert: Tester geleert, Lösungsversionen = 43.1, 42.6, Story Points = 5,5, Umgebung = test");
        assertThat(stub.last("/rest/api/2/issue/ABC-1").method()).isEqualTo("PUT");
        assertThat(stub.last("/rest/api/2/issue/ABC-1").body()).isEqualTo("{\"fields\":{\"customfield_10368\":null,"
                + "\"fixVersions\":[{\"name\":\"43.1\"},{\"name\":\"42.6\"}],\"customfield_10016\":5.5,"
                + "\"customfield_10500\":{\"value\":\"Test\"}}}");

        // Data Center: Benutzer über die zuweisbaren Benutzer; Titel und Felder in einem Aufruf
        assertThat(tools.update("ABC-1", "Neu", null, null, Map.of("Tester", "felix@example.com"), null, null))
                .contains("geändert: Titel", "Felder geändert: Tester = felix@example.com");
        assertThat(stub.last("/rest/api/2/issue/ABC-1").body()).isEqualTo("{\"fields\":{\"customfield_10368\":{\"name\":\"fgrebe\"}}}");
        // Rohes JSON für Feldtypen ohne eigene Umwandlung
        tools.update("ABC-1", null, null, null, Map.of("Kaskade", "{\"value\":\"a\",\"child\":{\"value\":\"b\"}}"), null, null);
        assertThat(stub.last("/rest/api/2/issue/ABC-1").body())
                .isEqualTo("{\"fields\":{\"customfield_10700\":{\"value\":\"a\",\"child\":{\"value\":\"b\"}}}}");

        int before = stub.requests.size();
        assertThatThrownBy(() -> tools.update("ABC-1", null, null, null, Map.of("Bearbeiter", "x"), null, null))
                .hasMessageContaining("Feld 'Bearbeiter' ist für ABC-1 unbekannt oder nicht bearbeitbar")
                .hasMessageContaining("Tester (customfield_10368)");
        assertThatThrownBy(() -> tools.update("ABC-1", null, null, null, Map.of("Notiz", "x"), null, null))
                .hasMessageContaining("mehrdeutig").hasMessageContaining("Notiz (customfield_10601)");
        assertThatThrownBy(() -> tools.update("ABC-1", null, null, null, Map.of("Umgebung", "Staging"), null, null))
                .hasMessageContaining("'Staging' ist für Umgebung nicht erlaubt – erlaubt: Produktion, Test");
        assertThatThrownBy(() -> tools.update("ABC-1", null, null, null, Map.of("Story Points", "viel"), null, null))
                .hasMessageContaining("Story Points erwartet eine Zahl");
        assertThatThrownBy(() -> tools.update("ABC-1", null, null, null, Map.of("Kaskade", "a"), null, null))
                .hasMessageContaining("Feldtyp 'option-with-child'").hasMessageContaining("als JSON angeben");
        // abgelehnt, bevor geschrieben wurde
        assertThat(stub.requests.subList(before, stub.requests.size())).noneMatch(r -> "PUT".equals(r.method()));
        assertThatThrownBy(() -> tools.update("ABC-1", null, null, null, Map.of(), null, null))
                .hasMessageContaining("title, description, labels oder fields");
    }

    private static final String CREATEMETA = """
            {"startAt":0,"maxResults":100,"total":5,"fields":[
             {"fieldId":"summary","name":"Zusammenfassung","required":true,"schema":{"type":"string","system":"summary"}},
             {"fieldId":"components","name":"Komponenten","required":true,"schema":{"type":"array","items":"component","system":"components"},
              "allowedValues":[{"id":"1","name":"GDPdU"},{"id":"2","name":"Transform"}]},
             {"fieldId":"priority","name":"Priorität","required":false,"hasDefaultValue":true,"schema":{"type":"priority","system":"priority"},
              "allowedValues":[{"id":"2","name":"High"},{"id":"3","name":"Medium"},{"id":"4","name":"Low"}]},
             {"fieldId":"customfield_10900","name":"Dringlichkeit","required":false,"schema":{"type":"option"},
              "allowedValues":[{"value":"High"},{"value":"Low"}]},
             {"fieldId":"customfield_10368","name":"Tester","required":false,"schema":{"type":"user"}}]}""";

    @Test
    void jiraCreateSetsFieldsOfTheCreateScreenInTheSameCall() {
        Map<String, String> cloud = with(jira(), "jira.deployment", "cloud", "jira.user", "me@example.com");
        stub.on("/rest/api/2/project/ABC", "{\"issueTypes\":[{\"id\":\"10001\",\"name\":\"Task\"},{\"id\":\"10004\",\"name\":\"Bug\"}]}");
        stub.on("/rest/api/2/issue/createmeta/ABC/issuetypes/10004", CREATEMETA);
        stub.on("/rest/api/2/myself", "{\"accountId\":\"5b10ac\",\"displayName\":\"Ich\"}");
        stub.on("/rest/api/2/issue", "{\"id\":\"1\",\"key\":\"ABC-10\"}");
        TicketCreateTools tools = new TicketCreateTools(env(cloud));

        Map<String, String> fields = new java.util.LinkedHashMap<>();
        fields.put("komponenten", "transform");
        fields.put("priority", "low");
        fields.put("Dringlichkeit", "Low");
        fields.put("Tester", "me");
        fields.put("Sprint", " "); // leer: beim Anlegen nichts zu setzen, auch kein unbekanntes Feld
        assertThat(tools.create("Endlosschleife", "Text", null, "Bug", null, null, fields, null))
                .startsWith("ABC-10: angelegt (Bug), Felder: Komponenten = transform, Priorität = low, Dringlichkeit = Low, "
                        + "Tester = me\n");
        // Pflichtfelder im Create selbst, nicht danach – sonst lehnt Jira schon das Anlegen ab
        assertThat(stub.requests).filteredOn(r -> r.path().startsWith("/rest/api/2/issue/ABC-10")).isEmpty();
        assertThat(stub.last("/rest/api/2/issue").body()).isEqualTo("{\"fields\":{\"project\":{\"key\":\"ABC\"},"
                + "\"summary\":\"Endlosschleife\",\"description\":\"Text\",\"issuetype\":{\"name\":\"Bug\"},"
                + "\"components\":[{\"name\":\"Transform\"}],\"priority\":{\"name\":\"Low\"},"
                + "\"customfield_10900\":{\"value\":\"Low\"},\"customfield_10368\":{\"accountId\":\"5b10ac\"}}}");

        int before = stub.requests.size();
        assertThatThrownBy(() -> tools.create("T", null, null, "Bug", null, null, Map.of("Sprint", "1"), null))
                .hasMessageContaining("Feld 'Sprint' gibt es beim Anlegen von Bug in ABC nicht. Möglich: "
                        + "Zusammenfassung (summary), Komponenten (components)");
        assertThatThrownBy(() -> tools.create("T", null, null, "Bug", null, null, Map.of("Komponenten", "Kern"), null))
                .hasMessageContaining("'Kern' ist für Komponenten nicht erlaubt – erlaubt: GDPdU, Transform");
        assertThatThrownBy(() -> tools.create("T", null, null, "Epic", null, null, Map.of("Komponenten", "Transform"), null))
                .hasMessageContaining("Issue-Typ 'Epic' gibt es in ABC nicht – gültige Typen: Task, Bug");
        // abgelehnt, bevor angelegt wurde
        assertThat(stub.requests.subList(before, stub.requests.size())).noneMatch(r -> "POST".equals(r.method()));
    }

    @Test
    void jiraCreateNamesTheMissingRequiredFieldsAndFindsUsersInTheProject() {
        stub.on("/rest/api/2/project/ABC", "{\"issueTypes\":[{\"id\":\"10004\",\"name\":\"Bug\"}]}");
        // Data Center liefert die Felder unter 'values'
        stub.on("/rest/api/2/issue/createmeta/ABC/issuetypes/10004", """
                {"startAt":0,"maxResults":100,"total":3,"isLast":true,"values":[
                 {"fieldId":"summary","name":"Summary","required":true,"schema":{"type":"string"}},
                 {"fieldId":"components","name":"Komponenten","required":true,"schema":{"type":"array","items":"component"}},
                 {"fieldId":"customfield_10368","name":"Tester","required":false,"schema":{"type":"user"}}]}""");
        stub.on("/rest/api/2/issue", r -> r.body().contains("components") ? StubServer.Reply.json("{\"id\":\"2\",\"key\":\"ABC-11\"}")
                : new StubServer.Reply(400, "{\"errorMessages\":[],\"errors\":{\"components\":\"Komponenten ist erforderlich.\"}}", Map.of()));
        stub.on("/rest/api/2/user/assignable/search", "[{\"name\":\"fgrebe\",\"displayName\":\"Felix Grebe\"}]");
        TicketCreateTools tools = new TicketCreateTools(env(jira()));

        assertThatThrownBy(() -> tools.create("T", null, null, "Bug", null, null, null, null))
                .hasMessageContaining("Komponenten ist erforderlich")
                .hasMessageContaining("Pflichtfelder für Bug in ABC, per 'fields' angeben: Komponenten (components)");

        Map<String, String> fields = new java.util.LinkedHashMap<>();
        fields.put("Komponenten", "Transform");
        fields.put("Tester", "fgrebe");
        assertThat(tools.create("T", null, null, "Bug", null, null, fields, null))
                .startsWith("ABC-11: angelegt (Bug), Felder: Komponenten = Transform, Tester = fgrebe");
        // vor dem Anlegen gibt es kein Ticket – gesucht wird unter den im Projekt zuweisbaren Benutzern
        assertThat(stub.last("/rest/api/2/user/assignable/search").decodedQuery()).contains("project=ABC", "username=fgrebe");
        assertThat(stub.last("/rest/api/2/issue").body()).contains("\"components\":[{\"name\":\"Transform\"}],"
                + "\"customfield_10368\":{\"name\":\"fgrebe\"}");
    }

    @Test
    void jiraCreateReadsTheOldCreatemetaOnDataCentersBefore84() {
        stub.on("/rest/api/2/project/ABC", "{\"issueTypes\":[{\"id\":\"10004\",\"name\":\"Bug\"}]}");
        stub.on("/rest/api/2/issue/createmeta", """
                {"projects":[{"key":"ABC","issuetypes":[{"id":"10004","name":"Bug","fields":{
                 "components":{"name":"Komponenten","required":true,"schema":{"type":"array","items":"component"},
                   "allowedValues":[{"name":"Transform"}]}}}]}]}""");
        stub.on("/rest/api/2/issue", "{\"id\":\"3\",\"key\":\"ABC-12\"}");

        assertThat(new TicketCreateTools(env(jira())).create("T", null, null, "Bug", null, null,
                Map.of("components", "transform"), null)).startsWith("ABC-12: angelegt (Bug), Felder: Komponenten = transform");
        assertThat(stub.last("/rest/api/2/issue/createmeta").decodedQuery())
                .contains("projectKeys=ABC", "issuetypeIds=10004", "expand=projects.issuetypes.fields");
        assertThat(stub.last("/rest/api/2/issue").body()).contains("\"components\":[{\"name\":\"Transform\"}]");
    }

    @Test
    void createWithFieldsSetsThemAfterwardsOrReportsWhatTheSystemCannotSet() {
        stub.on("/repos/octo/app/issues", "{\"number\":41,\"html_url\":\"https://github.com/octo/app/issues/41\"}");

        assertThat(new TicketCreateTools(env(github())).create("Neu", null, null, null, null, null, Map.of("Tester", "x"), null))
                .startsWith("octo/app#41: angelegt")
                .contains("; Felder nicht gesetzt: Felder ändern wird vom Ticket-System 'github' nicht unterstützt");
    }

    @Test
    void jiraTransitionWithCommentFallsBackWhenTheTransitionTakesNoComment() {
        stub.on("/rest/api/2/issue/ABC-1/transitions", r -> !"POST".equals(r.method())
                ? StubServer.Reply.json("{\"transitions\":[{\"id\":\"31\",\"name\":\"Erledigt\",\"to\":{\"name\":\"Done\"}}]}")
                : r.body().contains("update") ? new StubServer.Reply(400, "{\"errorMessages\":[],\"errors\":{\"comment\":"
                        + "\"Field 'comment' cannot be set. It is not on the appropriate screen, or unknown.\"}}", Map.of())
                : new StubServer.Reply(204, "", Map.of()));
        stub.on("/rest/api/2/issue/ABC-1/comment", "{\"id\":\"1003\"}");

        assertThat(new TicketTransitionTools(env(jira()), true).transition("ABC-1", "Done", "fertig", null, null))
                .contains("Status → Done ('Erledigt'); Kommentar 1003 hinzugefügt");
        assertThat(stub.requests).filteredOn(r -> "POST".equals(r.method()) && r.path().endsWith("/transitions"))
                .extracting(StubServer.Request::body).containsExactly(
                        "{\"transition\":{\"id\":\"31\"},\"update\":{\"comment\":[{\"add\":{\"body\":\"fertig\"}}]}}",
                        "{\"transition\":{\"id\":\"31\"}}");
        assertThat(stub.last("/rest/api/2/issue/ABC-1/comment").body()).isEqualTo("{\"body\":\"fertig\"}");
    }

    @Test
    void updateFieldsIsReportedAsUnsupportedWhereTheSystemHasNoSuchFields() {
        assertThatThrownBy(() -> new TicketEditTools(env(github())).update("#12", null, null, null, Map.of("Tester", ""), null, null))
                .hasMessageContaining("Felder ändern wird vom Ticket-System 'github' nicht unterstützt");
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

        assertThat(new TicketCreateTools(env).create("Neu", "Text", null, "Bug", List.of("bug"), List.of("me"), null, null))
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
