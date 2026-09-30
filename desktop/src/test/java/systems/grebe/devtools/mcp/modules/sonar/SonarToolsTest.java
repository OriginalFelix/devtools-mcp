package systems.grebe.devtools.mcp.modules.sonar;

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

/** Sonar-Tools gegen einen lokalen HTTP-Stub mit realistischen Antworten der Web-API. */
class SonarToolsTest {

    HttpServer server;
    final List<String> requests = new CopyOnWriteArrayList<>();
    final List<String> authHeaders = new CopyOnWriteArrayList<>();
    SonarModule module = new SonarModule();
    SonarTools tools;
    ModuleConfig config;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        config = ModuleConfig.of(module.configSchema(), Map.of(
                SonarModule.BASE_URL, "http://127.0.0.1:" + server.getAddress().getPort(),
                SonarModule.TOKEN, "squ_test",
                SonarModule.DEFAULT_PROJECT, "demo"));
        tools = new SonarTools(() -> SonarModule.client(config), "demo");
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
            case "/api/system/status" -> body = """
                    {"id":"x","version":"10.7.0.96327","status":"UP"}""";
            case "/api/authentication/validate" -> body = """
                    {"valid":true}""";
            case "/api/qualitygates/project_status" -> body = """
                    {"projectStatus":{"status":"ERROR","conditions":[
                      {"status":"ERROR","metricKey":"new_coverage","comparator":"LT","errorThreshold":"80","actualValue":"61.5"},
                      {"status":"OK","metricKey":"new_bugs","comparator":"GT","errorThreshold":"0","actualValue":"0"}]}}""";
            case "/api/issues/search" -> body = ex.getRequestURI().getQuery().contains("issues=AX1") ? """
                    {"total":1,"issues":[{"key":"AX1","rule":"java:S1192","severity":"CRITICAL","type":"CODE_SMELL",
                      "component":"demo:src/App.java","line":2,"message":"Define a constant","status":"OPEN",
                      "effort":"10min","tags":["design"],"textRange":{"startLine":2,"endLine":2},
                      "impacts":[{"softwareQuality":"MAINTAINABILITY","severity":"HIGH"}]}]}""" : """
                    {"paging":{"pageIndex":1,"pageSize":100,"total":2},"issues":[
                      {"key":"AX1","rule":"java:S1192","severity":"CRITICAL","type":"CODE_SMELL","component":"demo:src/App.java","line":2,"message":"Define a constant"},
                      {"key":"AX2","rule":"java:S2259","severity":"MAJOR","type":"BUG","component":"demo:src/Util.java","line":14,"message":"NPE possible"}]}""";
            case "/api/sources/raw" -> body = "class App {\n  String a = \"x\";\n  String b = \"x\";\n}\n";
            case "/api/rules/show" -> body = """
                    {"rule":{"key":"java:S1192","name":"String literals should not be duplicated","langName":"Java",
                      "type":"CODE_SMELL","severity":"CRITICAL","descriptionSections":[
                      {"key":"root_cause","content":"<p>Duplicated string literals make refactoring &amp; maintenance harder.</p>"}]}}""";
            case "/api/measures/component" -> body = """
                    {"component":{"key":"demo","measures":[{"metric":"coverage","value":"72.3"},
                      {"metric":"new_coverage","periods":[{"index":1,"value":"61.5"}]}]}}""";
            case "/api/hotspots/search" -> body = """
                    {"paging":{"pageIndex":1,"pageSize":100,"total":1},"hotspots":[{"key":"H1","component":"demo:src/Db.java",
                      "securityCategory":"sql-injection","vulnerabilityProbability":"HIGH","line":42,"message":"Make sure this is safe"}]}""";
            case "/api/components/search" -> body = """
                    {"paging":{"pageIndex":1,"pageSize":100,"total":1},"components":[{"key":"demo","name":"Demo-Projekt"}]}""";
            default -> {
                code = 404;
                body = """
                        {"errors":[{"msg":"Unknown url"}]}""";
            }
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    @Test
    void connectionTestReportsVersionAndTokenValidity() {
        var result = module.testConnection(config);
        assertThat(result.success()).isTrue();
        assertThat(result.message()).contains("10.7.0.96327").contains("Token gültig");
        assertThat(authHeaders).allMatch(h -> h.startsWith("Basic "));
    }

    @Test
    void qualityGateListsConditions() {
        assertThat(tools.qualityGate(null, "main", null))
                .contains("Quality Gate demo [main]: ERROR")
                .contains("new_coverage LT 80  (Ist: 61.5)");
        assertThat(requests.getLast()).contains("projectKey=demo").contains("branch=main");
    }

    @Test
    void issuesAreCompactAndFiltered() {
        String out = tools.issues(null, null, null, "critical, major", "bug", "src/Util.java", true, null, null);
        assertThat(out).contains("Treffer: 2 (Seite 1 von 1)")
                .contains("AX1  CRITICAL CODE_SMELL    java:S1192  src/App.java:2  Define a constant");
        assertThat(requests.getLast()).contains("severities=CRITICAL%2CMAJOR").contains("types=BUG")
                .contains("components=demo%3Asrc%2FUtil.java").contains("resolved=false").contains("inNewCodePeriod=true");
    }

    @Test
    void issueDetailIncludesSourceSnippet() {
        assertThat(tools.issueDetail("AX1")).contains("java:S1192").contains("MAINTAINABILITY=HIGH")
                .contains("    2 |   String a = \"x\";");
    }

    @Test
    void ruleMeasuresHotspotsProjects() {
        assertThat(tools.rule("java:S1192")).contains("String literals should not be duplicated")
                .contains("refactoring & maintenance").doesNotContain("<p>");
        assertThat(tools.measures(null, null, null, null)).contains("coverage").contains("72.3").contains("61.5");
        assertThat(tools.hotspots(null, null, null, null, null)).contains("H1  HIGH").contains("src/Db.java:42");
        assertThat(tools.listProjects(null, null)).contains("demo  –  Demo-Projekt");
    }

    @Test
    void httpErrorsAreTranslated() {
        SonarClient client = SonarModule.client(config);
        assertThatThrownBy(() -> client.get("/api/unknown", Map.of())).hasMessageContaining("404").hasMessageContaining("Unknown url");
        SonarTools noDefault = new SonarTools(() -> client, null);
        assertThatThrownBy(() -> noDefault.qualityGate(null, null, null)).hasMessageContaining("Kein Projektschlüssel");
    }
}
