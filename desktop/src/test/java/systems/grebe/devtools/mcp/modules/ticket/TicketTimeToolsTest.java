package systems.grebe.devtools.mcp.modules.ticket;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.modules.ticket.spi.TicketSystem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Zeiten buchen und lesen: Eingaben (Dauer, Datum) und die Aufrufe je Provider. */
class TicketTimeToolsTest {

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

    private TicketEnvironment env(String... kv) {
        Map<String, String> v = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            v.put(kv[i], kv[i + 1]);
        }
        return new TicketEnvironment(module.providers(), ModuleConfig.of(module.configSchema(), v));
    }

    // ------------------------------------------------------------------ Eingaben

    @Test
    void parsesCommonDurationFormats() {
        Duration ninety = Duration.ofMinutes(90);
        for (String s : new String[] {"1h 30m", "1h30m", "1h30", "90m", "90 min", "1,5h", "1.5 h", "1:30", "PT1H30M",
                "1 Stunde 30 Minuten", "1 hour and 30 minutes", "1,5 Std"}) {
            assertThat(TicketTimeTools.parseDuration(s)).as(s).isEqualTo(ninety);
        }
        assertThat(TicketTimeTools.parseDuration("0.33h")).isEqualTo(Duration.ofMinutes(20));
        assertThat(TicketTimeTools.parseDuration("8h")).isEqualTo(Duration.ofHours(8));
    }

    @Test
    void rejectsAmbiguousOrImplausibleDurations() {
        assertThatThrownBy(() -> TicketTimeTools.parseDuration("90")).hasMessageContaining("ohne Einheit");
        assertThatThrownBy(() -> TicketTimeTools.parseDuration("1h foo")).hasMessageContaining("nicht verstanden");
        assertThatThrownBy(() -> TicketTimeTools.parseDuration("2d")).hasMessageContaining("Tage und Wochen");
        assertThatThrownBy(() -> TicketTimeTools.parseDuration("25h")).hasMessageContaining("je Tag einzeln");
        assertThatThrownBy(() -> TicketTimeTools.parseDuration("0m")).hasMessageContaining("kleiner als eine Minute");
        assertThatThrownBy(() -> TicketTimeTools.parseDuration(" ")).hasMessageContaining("Keine Dauer");
    }

    @Test
    void parsesDatesAndRejectsFuture() {
        LocalDate today = LocalDate.of(2026, 10, 5);
        assertThat(TicketTimeTools.parseDate(null, today)).isEqualTo(today);
        assertThat(TicketTimeTools.parseDate("gestern", today)).isEqualTo(LocalDate.of(2026, 10, 4));
        assertThat(TicketTimeTools.parseDate("2026-10-01", today)).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(TicketTimeTools.parseDate("1.10.2026", today)).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThatThrownBy(() -> TicketTimeTools.parseDate("2026-10-06", today)).hasMessageContaining("Zukunft");
        assertThatThrownBy(() -> TicketTimeTools.parseDate("Montag", today)).hasMessageContaining("nicht verstanden");
    }

    @Test
    void formatsDurations() {
        assertThat(TicketSystem.formatDuration(Duration.ofMinutes(45))).isEqualTo("45m");
        assertThat(TicketSystem.formatDuration(Duration.ofMinutes(120))).isEqualTo("2h");
        assertThat(TicketSystem.formatDuration(Duration.ofMinutes(95))).isEqualTo("1h 35m");
    }

    // ------------------------------------------------------------------ Provider

    @Test
    void jiraPostsWorklogAndListsThem() {
        stub.on("/rest/api/2/issue/ABC-1/worklog", r -> StubServer.Reply.json("POST".equals(r.method())
                ? "{\"id\":\"10001\"}"
                : """
                {"startAt":0,"total":2,"worklogs":[
                  {"id":"1","author":{"displayName":"Felix"},"started":"2026-10-01T09:00:00.000+0200","timeSpentSeconds":5400,"comment":"Analyse"},
                  {"id":"2","author":{"displayName":"Anna"},"started":"2026-10-02T10:00:00.000+0200","timeSpentSeconds":1800}]}"""));
        TicketEnvironment env = env("jira.enabled", "true", "jira.baseUrl", stub.url(), "jira.token", "pat");

        assertThat(new TicketTimeTools(env).logTime("ABC-1", "1h 30m", "2026-10-01", "Analyse", null, null, null))
                .isEqualTo("ABC-1: 1h 30m gebucht am 2026-10-01 (Buchung 10001)\n" + stub.url()
                        + "/browse/ABC-1?focusedWorklogId=10001");
        String body = stub.last("/rest/api/2/issue/ABC-1/worklog").body();
        assertThat(body).contains("\"timeSpentSeconds\":5400", "\"started\":\"2026-10-01T09:00:00.000", "\"comment\":\"Analyse\"");
        assertThat(body).matches(".*\"started\":\"2026-10-01T09:00:00\\.000[+-]\\d{4}\".*"); // Jira: Offset ohne Doppelpunkt
        assertThatThrownBy(() -> new TicketTimeTools(env).logTime("ABC-1", "1h", null, null, "Development", null, null))
                .hasMessageContaining("keine Tätigkeitsart");

        assertThat(new TicketTools(env).worklogs("ABC-1", null, null)).isEqualTo("""
                2 Buchung(en) auf ABC-1, gesamt 2h:
                - 2026-10-01  1h 30m  Felix  Analyse  (Buchung 1)
                - 2026-10-02  30m  Anna  (Buchung 2)""");
    }

    @Test
    void writeProjectsRestrictLogTimeBeforeAnythingIsSent() {
        TicketEnvironment env = env("jira.enabled", "true", "jira.baseUrl", stub.url(), "jira.token", "pat",
                "writeProjects", "XYZ");
        assertThatThrownBy(() -> new TicketTimeTools(env).logTime("ABC-1", "1h", null, null, null, null, null))
                .hasMessageContaining("nicht freigegeben");
        assertThat(stub.requests).isEmpty();
    }

    @Test
    void gitlabCreatesTimelogViaGraphQlWithDate() {
        stub.on("/api/v4/projects/grp%2Fapp/issues/12", "{\"id\":4711,\"iid\":12,\"web_url\":\"w\"}");
        stub.on("/api/graphql", r -> StubServer.Reply.json(r.body().contains("timelogCreate")
                ? "{\"data\":{\"timelogCreate\":{\"timelog\":{\"id\":\"gid://gitlab/Timelog/99\"},\"errors\":[]}}}"
                : """
                {"data":{"project":{"issue":{"timelogs":{"nodes":[
                  {"id":"gid://gitlab/Timelog/99","timeSpent":2700,"spentAt":"2026-10-01T07:00:00Z","summary":"Review",
                   "user":{"username":"fg","name":"Felix"}}],"pageInfo":{"hasNextPage":false}}}}}}"""));
        TicketEnvironment env = env("gitlab.enabled", "true", "gitlab.baseUrl", stub.url(), "gitlab.token", "glpat",
                "gitlab.defaultProject", "grp/app");

        assertThat(new TicketTimeTools(env).logTime("#12", "45m", "2026-10-01", "Review", null, null, null))
                .isEqualTo("grp/app#12: 45m gebucht am 2026-10-01 (Buchung 99)\nw");
        assertThat(stub.last("/api/graphql").body()).contains("\"issuableId\":\"gid://gitlab/Issue/4711\"",
                "\"timeSpent\":\"45m\"", "\"spentAt\":\"2026-10-01T09:00:00", "\"summary\":\"Review\"");

        assertThat(new TicketTools(env).worklogs("#12", null, null))
                .isEqualTo("1 Buchung(en) auf #12, gesamt 45m:\n- 2026-10-01  45m  Felix  Review  (Buchung 99)");
        assertThat(stub.last("/api/graphql").body()).contains("\"project\":\"grp/app\"", "\"iid\":\"12\"");

        stub.on("/api/graphql", "{\"data\":{\"timelogCreate\":{\"timelog\":null,\"errors\":[\"Time tracking disabled\"]}}}");
        assertThatThrownBy(() -> new TicketTimeTools(env).logTime("#12", "1h", null, null, null, null, null))
                .hasMessageContaining("Zeit nicht gebucht – Time tracking disabled");
    }

    @Test
    void youtrackPostsWorkItemWithTypeAndListsThem() {
        stub.on("/api/admin/timeTrackingSettings/workItemTypes", """
                [{"id":"58-0","name":"Development"},{"id":"58-1","name":"Testing"}]""");
        stub.on("/api/issues/ABC-7/timeTracking/workItems", r -> StubServer.Reply.json("POST".equals(r.method())
                ? "{\"id\":\"8-1\"}"
                : """
                [{"id":"8-2","author":{"fullName":"Anna"},"date":1790935200000,"duration":{"minutes":30},"type":null},
                 {"id":"8-1","author":{"fullName":"Felix"},"date":1790848800000,"duration":{"minutes":90},
                  "text":"Bugfix","type":{"name":"Development"}}]"""));
        TicketEnvironment env = env("youtrack.enabled", "true", "youtrack.baseUrl", stub.url() + "/", "youtrack.token", "perm:x");

        assertThat(new TicketTimeTools(env).logTime("ABC-7", "1,5h", "2026-10-01", "Bugfix", "dev", null, null))
                .isEqualTo("ABC-7: 1h 30m gebucht am 2026-10-01 (dev)\n" + stub.url() + "/issue/ABC-7");
        String body = stub.last("/api/issues/ABC-7/timeTracking/workItems").body();
        assertThat(body).contains("\"duration\":{\"minutes\":90}", "\"text\":\"Bugfix\"", "\"type\":{\"id\":\"58-0\"}");
        assertThatThrownBy(() -> new TicketTimeTools(env).logTime("ABC-7", "1h", null, null, "Meeting", null, null))
                .hasMessageContaining("gibt es nicht").hasMessageContaining("Development, Testing");

        // chronologisch sortiert, obwohl YouTrack anders liefert
        assertThat(new TicketTools(env).worklogs("ABC-7", null, null))
                .startsWith("2 Buchung(en) auf ABC-7, gesamt 2h:\n- 2026-10-01  1h 30m  Felix  [Development]  Bugfix");
    }

    @Test
    void openprojectPostsTimeEntryWithActivityAndListsThem() {
        stub.on("/api/v3/work_packages/123", "{\"id\":123,\"_embedded\":{\"project\":{\"identifier\":\"demo\"}}}");
        stub.on("/api/v3/time_entries/form", """
                {"_embedded":{"schema":{"activity":{"_embedded":{"allowedValues":[
                  {"name":"Entwicklung","_links":{"self":{"href":"/api/v3/time_entries/activities/3"}}},
                  {"name":"Management","_links":{"self":{"href":"/api/v3/time_entries/activities/1"}}}]}}}}}""");
        stub.on("/api/v3/time_entries", r -> StubServer.Reply.json("POST".equals(r.method())
                ? "{\"id\":55,\"_links\":{\"activity\":{\"title\":\"Entwicklung\"}}}"
                : """
                {"total":1,"_embedded":{"elements":[{"id":55,"hours":"PT1H30M","spentOn":"2026-10-01",
                  "comment":{"raw":"Umsetzung"},"_links":{"user":{"title":"Felix"},"activity":{"title":"Entwicklung"}}}]}}"""));
        TicketEnvironment env = env("openproject.enabled", "true", "openproject.baseUrl", stub.url() + "/api/v3",
                "openproject.token", "k");

        assertThat(new TicketTimeTools(env).logTime("#123", "1:30", "2026-10-01", "Umsetzung", "entwicklung", null, null))
                .isEqualTo("#123: 1h 30m gebucht am 2026-10-01 (Entwicklung), Buchung 55\n" + stub.url()
                        + "/work_packages/123/activity");
        assertThat(stub.last("/api/v3/time_entries").body()).isEqualTo("{\"hours\":\"PT1H30M\",\"spentOn\":\"2026-10-01\","
                + "\"comment\":{\"raw\":\"Umsetzung\"},\"_links\":{\"workPackage\":{\"href\":\"/api/v3/work_packages/123\"},"
                + "\"activity\":{\"href\":\"/api/v3/time_entries/activities/3\"}}}");

        assertThat(new TicketTools(env).worklogs("#123", null, null))
                .isEqualTo("1 Buchung(en) auf #123, gesamt 1h 30m:\n- 2026-10-01  1h 30m  Felix  [Entwicklung]  Umsetzung  (Buchung 55)");
        assertThat(stub.last("/api/v3/time_entries").decodedQuery()).contains("\"workPackage\":{\"operator\":\"=\",\"values\":[\"123\"]}");

        stub.on("/api/v3/time_entries", r -> new StubServer.Reply(422,
                "{\"message\":\"Activity can't be blank.\"}", Map.of()));
        assertThatThrownBy(() -> new TicketTimeTools(env).logTime("#123", "1h", null, null, null, null, null))
                .hasMessageContaining("Activity can't be blank").hasMessageContaining("'activity' angeben: Entwicklung, Management");
    }

    @Test
    void githubHasNoTimeTracking() {
        TicketEnvironment env = env("github.enabled", "true", "github.baseUrl", stub.url(), "github.token", "ghp");
        assertThatThrownBy(() -> new TicketTimeTools(env).logTime("octo/app#1", "1h", null, null, null, null, null))
                .hasMessageContaining("Zeiten buchen wird vom Ticket-System 'github' nicht unterstützt");
    }
}
