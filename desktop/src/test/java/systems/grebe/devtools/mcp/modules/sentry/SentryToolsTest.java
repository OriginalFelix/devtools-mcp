package systems.grebe.devtools.mcp.modules.sentry;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import systems.grebe.devtools.mcp.core.ModuleConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Sentry-Tools gegen einen lokalen HTTP-Stub mit realistischen Antworten der Web-API. */
class SentryToolsTest {

    HttpServer server;
    final List<String> requests = new CopyOnWriteArrayList<>();
    final List<String> authHeaders = new CopyOnWriteArrayList<>();
    SentryModule module = new SentryModule();
    SentryTools tools;
    ModuleConfig config;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        config = ModuleConfig.of(module.configSchema(), Map.of(
                SentryModule.BASE_URL, "http://127.0.0.1:" + server.getAddress().getPort() + "/",
                SentryModule.TOKEN, "sntryu_test",
                SentryModule.ORGANIZATION, "acme"));
        tools = new SentryTools(() -> SentryModule.client(config), "acme", "backend");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        requests.add(ex.getRequestURI().toString());
        authHeaders.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
        String body;
        int code = 200;
        switch (path) {
            case "/api/0/organizations/acme/" -> body = """
                    {"id":"1","slug":"acme","name":"ACME GmbH"}""";
            case "/api/0/organizations/acme/projects/" -> body = """
                    [{"id":"42","slug":"backend","name":"Backend","platform":"java"}]""";
            case "/api/0/projects/acme/backend/" -> body = """
                    {"id":"42","slug":"backend"}""";
            case "/api/0/organizations/acme/issues/" -> {
                ex.getResponseHeaders().add("Link", "<http://x/?cursor=0:0:1>; rel=\"previous\"; results=\"false\"; "
                        + "cursor=\"0:0:1\", <http://x/?cursor=0:25:0>; rel=\"next\"; results=\"true\"; cursor=\"0:25:0\"");
                body = """
                        [{"id":"1001","shortId":"BACKEND-1A","title":"NullPointerException: name is null",
                          "culprit":"com.acme.UserService in load","level":"error","count":"42","userCount":7,
                          "lastSeen":"2026-10-30T09:00:00Z","project":{"slug":"backend"}}]""";
            }
            case "/api/0/organizations/acme/issues/BACKEND-1A/" -> body = """
                    {"id":"1001","shortId":"BACKEND-1A","title":"NullPointerException: name is null",
                     "culprit":"com.acme.UserService in load","level":"error","status":"unresolved","count":"42",
                     "userCount":7,"firstSeen":"2026-10-01T08:00:00Z","lastSeen":"2026-10-30T09:00:00Z",
                     "permalink":"https://sentry.io/organizations/acme/issues/1001/"}""";
            case "/api/0/organizations/acme/issues/BACKEND-1A/events/latest/" -> body = """
                    {"eventID":"abc123","dateCreated":"2026-10-30T09:00:00Z",
                     "tags":[{"key":"release","value":"1.2.0"},{"key":"environment","value":"prod"}],
                     "entries":[
                      {"type":"exception","data":{"values":[
                        {"type":"IllegalStateException","module":"java.lang","value":"db down","stacktrace":{"frames":[]}},
                        {"type":"NullPointerException","module":"java.lang","value":"name is null","stacktrace":{"frames":[
                          {"module":"org.springframework.web.Servlet","function":"service","filename":"Servlet.java","lineNo":10,"inApp":false},
                          {"module":"com.acme.UserService","function":"load","filename":"UserService.java","lineNo":42,"inApp":true,
                           "context":[[41,"  User u = repo.find(id);"],[42,"  return u.name().trim();"]]}]}}]}},
                      {"type":"request","data":{"method":"GET","url":"https://app.acme.de/users/5"}},
                      {"type":"breadcrumbs","data":{"values":[
                        {"timestamp":"2026-10-30T08:59:59Z","category":"http","level":"info","message":"GET /users/5"}]}}]}""";
            default -> {
                code = 404;
                body = """
                        {"detail":"The requested resource does not exist"}""";
            }
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    @Test
    void connectionTestReportsOrganization() {
        var result = module.testConnection(config);
        assertThat(result.success()).isTrue();
        assertThat(result.message()).contains("ACME GmbH");
        assertThat(authHeaders).allMatch(h -> h.equals("Bearer sntryu_test"));
    }

    @Test
    void listProjects() {
        assertThat(tools.listProjects()).isEqualTo("backend  –  Backend  (java)");
    }

    @Test
    void issuesResolveProjectIdAndReportCursor() {
        String out = tools.issues(null, null, "freq", "24h", 500, null);
        assertThat(out).contains("BACKEND-1A  error   42×/7 Nutzer  zuletzt 2026-10-30T09:00:00Z  NullPointerException")
                .doesNotContain("[backend]")
                .endsWith("Weitere Treffer: cursor=0:25:0");
        assertThat(requests.getLast()).contains("project=42").contains("query=is%3Aunresolved")
                .contains("sort=freq").contains("statsPeriod=24h").contains("limit=100");
    }

    @Test
    void issuesWithoutProjectSpanOrganization() {
        SentryTools all = new SentryTools(() -> SentryModule.client(config), "acme", null);
        assertThat(all.issues(null, "level:error", null, null, null, "0:25:0")).contains("[backend]");
        assertThat(requests.getLast()).doesNotContain("project=").contains("query=level%3Aerror").contains("cursor=0%3A25%3A0");
    }

    @Test
    void issueDetailShowsStacktraceInnermostFirst() {
        String out = tools.issueDetail(" BACKEND-1A ");
        assertThat(out).contains("BACKEND-1A (ID 1001) – NullPointerException")
                .contains("42 Events, 7 Nutzer")
                .contains("Request: GET https://app.acme.de/users/5")
                .contains("Tags: release=1.2.0, environment=prod")
                .contains("GET /users/5");
        // gefangene Ausnahme vor der Ursache, Fehlerstelle vor dem Aufrufer, Quelltextzeile nur für eigenen Code
        assertThat(out.indexOf("Ausnahme: java.lang.NullPointerException: name is null"))
                .isLessThan(out.indexOf("Verursacht durch: java.lang.IllegalStateException: db down"));
        assertThat(out).contains("  * at com.acme.UserService.load (UserService.java:42)\n"
                + "        > return u.name().trim();\n"
                + "    at org.springframework.web.Servlet.service (Servlet.java:10)\n");
    }

    @Test
    void unknownIssueIsReadableError() {
        assertThatThrownBy(() -> tools.issueDetail("NOPE-1")).hasMessageContaining("404")
                .hasMessageContaining("does not exist");
        assertThat(SentryTools.nextCursor(null)).isNull();
    }
}
