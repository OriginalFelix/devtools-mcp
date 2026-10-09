package systems.grebe.devtools.mcp.modules.ci;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import systems.grebe.devtools.mcp.core.ModuleConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** GitLab-CI-Provider gegen einen Stub der REST API v4. */
class GitLabCiProviderTest {

    StubServer gl;
    CiModule module = new CiModule(new CiProviders());

    static final String API = "/api/v4/projects/grp%2Fapp";

    @BeforeEach
    void start() throws IOException {
        gl = new StubServer();
    }

    @AfterEach
    void stop() {
        gl.close();
    }

    private CiEnvironment env() {
        Map<String, String> v = new HashMap<>(Map.of("gitlab.enabled", "true", "gitlab.baseUrl", gl.url(),
                "gitlab.token", "glpat"));
        return new CiEnvironment(module.providers(), ModuleConfig.of(module.configSchema(), v));
    }

    private static String pipeline(int id, String status, String ref) {
        return """
                {"id":%d,"status":"%s","ref":"%s","sha":"0123456789abc","source":"push","created_at":"2026-10-01T10:00:00Z",
                 "web_url":"https://gitlab/grp/app/-/pipelines/%d"}""".formatted(id, status, ref, id);
    }

    private static final String JOBS = """
            [{"id":303,"name":"deploy","stage":"deploy","status":"skipped","web_url":"https://gitlab/j/303"},
             {"id":302,"name":"lint","stage":"test","status":"failed","allow_failure":true,"duration":5.2,"web_url":"https://gitlab/j/302"},
             {"id":301,"name":"unit","stage":"test","status":"failed","failure_reason":"script_failure","duration":61.4,"web_url":"https://gitlab/j/301"},
             {"id":300,"name":"compile","stage":"build","status":"success","duration":30,"web_url":"https://gitlab/j/300"}]""";

    @Test
    void listUsesServerFilterAndToken() {
        gl.on(API + "/pipelines", "[" + pipeline(12, "running", "main") + "," + pipeline(11, "pending", "main") + "]");
        CiTools tools = new CiTools(env());
        assertThat(tools.list("running", "main", null, 5, null, "grp/app", null))
                .contains("2 Build(s) in grp/app (gitlab) – Branch main", "- grp/app#12  [running]  main  01234567  push");
        assertThat(gl.last(API + "/pipelines").decodedQuery()).contains("ref=main", "status=running", "per_page=5");
        assertThat(gl.last(API + "/pipelines").headers().get("private-token")).isEqualTo("glpat");

        // queued hat kein 1:1-Gegenstück – clientseitig filtern
        assertThat(tools.list("queued", null, null, null, null, "grp/app", null))
                .contains("1 Build(s)", "grp/app#11  [queued (pending)]");
        assertThat(gl.last(API + "/pipelines").decodedQuery()).doesNotContain("status=").contains("per_page=100");
    }

    @Test
    void getGroupsJobsByStageInExecutionOrder() {
        gl.on(API + "/pipelines/12", """
                {"id":12,"status":"failed","ref":"main","sha":"0123456789abc","source":"web","user":{"username":"anna"},
                 "started_at":"2026-10-01T10:00:00Z","duration":125,"queued_duration":3.4,"coverage":"81.5",
                 "detailed_status":{"text":"Failed"},"web_url":"https://gitlab/grp/app/-/pipelines/12"}""");
        gl.on(API + "/pipelines/12/jobs", JOBS);
        String out = new CiTools(env()).get("grp/app#12", null, null, null);
        assertThat(out).contains("grp/app#12: failed", "Auslöser:   web von anna", "Dauer:      2m 05s",
                "Wartezeit:  3s", "Coverage:   81.5 %", "## Jobs (4, 2 fehlgeschlagen)",
                "[build]\n- compile: success, 30s  (Job 300)",
                "[test]\n- unit: failed, 1m 01s  (Job 301) – script_failure\n- lint: failed, 5s  (Job 302) – darf fehlschlagen",
                "[deploy]\n- deploy: skipped").doesNotContain("Anzeige:");
    }

    @Test
    void logPicksFailedJobThatMustNotFail() {
        gl.on(API + "/pipelines/12/jobs", JOBS);
        gl.on(API + "/jobs/301/trace", r -> new StubServer.Reply(200, "line1\nAssertionError: expected 2\nline3", Map.of()));
        gl.on(API + "/jobs/300/trace", r -> new StubServer.Reply(200, "compiled", Map.of()));
        CiTools tools = new CiTools(env());
        assertThat(tools.log("grp/app#12", null, null, null, null, null, null))
                .contains("Job unit (#301), Stage test, failed – 3 Zeile(n)", "https://gitlab/j/301", "AssertionError");
        assertThat(tools.log("12", "compile", null, null, null, "grp/app", null)).contains("Job compile (#300)", "compiled");
        assertThatThrownBy(() -> tools.log("12", "nope", null, null, null, "grp/app", null))
                .hasMessageContaining("Jobs: compile (300, success)").hasMessageContaining("deploy (303, skipped)");
    }

    @Test
    void jobUrlResolvesToItsPipelineAndLog() {
        gl.on(API + "/jobs/301", "{\"id\":301,\"name\":\"unit\",\"stage\":\"test\",\"status\":\"failed\","
                + "\"web_url\":\"https://gitlab/j/301\",\"pipeline\":{\"id\":12}}");
        gl.on(API + "/jobs/301/trace", r -> new StubServer.Reply(200, "boom", Map.of()));
        String url = gl.url() + "/grp/app/-/jobs/301";
        assertThat(new CiTools(env()).log(url, null, null, null, null, null, null)).contains("Job unit (#301)", "boom");
    }

    @Test
    void startUsesDefaultBranchAndVariables() {
        gl.on("/api/v4/projects/grp%2Fapp", "{\"default_branch\":\"main\"}");
        gl.on(API + "/pipeline", r -> StubServer.Reply.json(pipeline(13, "created", "main")));
        String out = new CiStartTools(env()).start(null, null, Map.of("DEPLOY", "1"), null, "grp/app", null);
        assertThat(out).contains("grp/app#13: Pipeline auf main gestartet (created) mit Variablen [DEPLOY]",
                "https://gitlab/grp/app/-/pipelines/13");
        assertThat(gl.last(API + "/pipeline").body()).contains("\"ref\":\"main\"", "\"key\":\"DEPLOY\"", "\"value\":\"1\"");
    }

    @Test
    void cancelAndRetry() {
        gl.on(API + "/pipelines/12", pipeline(12, "running", "main"));
        gl.on(API + "/pipelines/12/cancel", pipeline(12, "canceling", "main"));
        gl.on(API + "/pipelines/12/retry", pipeline(12, "pending", "main"));
        gl.on(API + "/pipelines/11", pipeline(11, "success", "main"));
        gl.on(API + "/pipeline", r -> StubServer.Reply.json(pipeline(14, "created", "main")));
        CiEnvironment env = env();

        assertThat(new CiCancelTools(env).cancel("grp/app#12", null, null, null))
                .contains("grp/app#12: Abbruch angefordert (canceling).");
        assertThat(new CiCancelTools(env).cancel("grp/app#11", null, null, null)).contains("läuft nicht mehr (success)");
        assertThat(new CiRetryTools(env).retry("grp/app#12", null, null, null, null))
                .contains("fehlgeschlagene und abgebrochene Jobs neu gestartet (pending)");
        assertThat(new CiRetryTools(env).retry("grp/app#11", true, null, null, null))
                .contains("grp/app#14: Pipeline auf main gestartet", "Wiederholung von #11");
    }

    @Test
    void workflowsExplainsSinglePipeline() {
        assertThatThrownBy(() -> new CiTools(env()).workflows(null, "grp/app", null))
                .hasMessageContaining(".gitlab-ci.yml");
    }
}
