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

/** GitHub-Actions-Provider gegen einen Stub der REST-API. */
class GitHubCiProviderTest {

    StubServer gh;
    CiModule module = new CiModule(new CiProviders());

    @BeforeEach
    void start() throws IOException {
        gh = new StubServer();
    }

    @AfterEach
    void stop() {
        gh.close();
    }

    private CiEnvironment env() {
        Map<String, String> v = new HashMap<>(Map.of("github.enabled", "true", "github.baseUrl", gh.url(),
                "github.token", "ghp_x"));
        return new CiEnvironment(module.providers(), ModuleConfig.of(module.configSchema(), v));
    }

    private static String run(long id, String status, String conclusion, int attempt) {
        return """
                {"id":%d,"name":"CI","display_title":"Fix login","status":"%s","conclusion":%s,"run_attempt":%d,
                 "event":"push","actor":{"login":"anna"},"head_branch":"main","head_sha":"fedcba9876543",
                 "path":".github/workflows/ci.yml","run_started_at":"2026-10-01T10:00:00Z","updated_at":"2026-10-01T10:04:10Z",
                 "head_commit":{"message":"Fix login\\n\\nDetails"},"pull_requests":[{"number":7}],
                 "html_url":"https://github.com/octo/app/actions/runs/%d"}"""
                .formatted(id, status, conclusion == null ? "null" : "\"" + conclusion + "\"", attempt, id);
    }

    private static final String WORKFLOWS = """
            {"workflows":[{"id":161,"name":"CI","path":".github/workflows/ci.yml","state":"active","html_url":"https://github.com/octo/app/blob/main/.github/workflows/ci.yml"},
             {"id":162,"name":"Release","path":".github/workflows/release.yaml","state":"disabled_manually"}]}""";

    @Test
    void listMapsStatusAndFiltersByWorkflowFile() {
        gh.on("/repos/octo/app/actions/workflows/ci.yml/runs", "{\"workflow_runs\":[" + run(900, "completed", "failure", 2)
                + "," + run(899, "in_progress", null, 1) + "]}");
        String out = new CiTools(env()).list("failed", "main", "ci.yml", null, null, "octo/app", null);
        assertThat(out).contains("- octo/app#900  [failed (failure)]  main  fedcba98  push von anna  2026-10-01T10:00:00Z  "
                + "4m 10s  CI: Fix login (Versuch 2)", "- octo/app#899  [running (in_progress)]");
        assertThat(gh.last("/repos/octo/app/actions/workflows/ci.yml/runs").decodedQuery())
                .contains("branch=main", "status=failure");
        assertThat(gh.last("/repos/octo/app/actions/workflows/ci.yml/runs").headers().get("authorization"))
                .isEqualTo("Bearer ghp_x");
    }

    @Test
    void workflowNameIsResolvedOverList() {
        gh.on("/repos/octo/app/actions/workflows", WORKFLOWS);
        gh.on("/repos/octo/app/actions/workflows/release.yaml/runs", "{\"workflow_runs\":[]}");
        CiTools tools = new CiTools(env());
        assertThat(tools.list(null, null, "Release", null, null, "octo/app", null)).contains("(keine gefunden)");
        assertThatThrownBy(() -> tools.list(null, null, "Nightly", null, null, "octo/app", null))
                .hasMessageContaining("ci.yml (CI)");
        assertThat(tools.workflows(null, "octo/app", null)).contains("- ci.yml  CI  .github/workflows/ci.yml  [active]",
                "- release.yaml  Release  .github/workflows/release.yaml  [disabled_manually]");
    }

    @Test
    void getShowsJobsWithFailedSteps() {
        gh.on("/repos/octo/app/actions/runs/900", run(900, "completed", "failure", 1));
        gh.on("/repos/octo/app/actions/runs/900/jobs", """
                {"jobs":[{"id":1,"name":"build","status":"completed","conclusion":"success","started_at":"2026-10-01T10:00:05Z",
                  "completed_at":"2026-10-01T10:01:05Z","html_url":"https://github.com/j/1","steps":[]},
                 {"id":2,"name":"test","status":"completed","conclusion":"failure","started_at":"2026-10-01T10:01:10Z",
                  "completed_at":"2026-10-01T10:04:00Z","html_url":"https://github.com/j/2",
                  "steps":[{"name":"Checkout","conclusion":"success"},{"name":"Run tests","conclusion":"failure"}]}]}""");
        String out = new CiTools(env()).get("https://github.com/octo/app/actions/runs/900/job/2", null, null, null);
        assertThat(out).contains("octo/app#900: failed (failure)  CI: Fix login", "Workflow:   .github/workflows/ci.yml",
                "Nachricht:  Fix login\n", "Pull Requests: #7", "- build: success, 1m 00s  (Job 1)",
                "- test: failed, 2m 50s  (Job 2) – fehlgeschlagen: Run tests");
    }

    @Test
    void logFollowsRedirectWithoutTokenAndStripsTimestamps() {
        gh.on("/repos/octo/app/actions/runs/900/jobs", """
                {"jobs":[{"id":1,"name":"build","status":"completed","conclusion":"success"},
                 {"id":2,"name":"test","status":"completed","conclusion":"failure"}]}""");
        gh.on("/repos/octo/app/actions/jobs/2/logs", r -> new StubServer.Reply(302, "",
                Map.of("Location", gh.url() + "/blob/log-2.txt?sig=abc")));
        gh.on("/blob/log-2.txt", r -> new StubServer.Reply(200,
                "2026-10-01T10:01:11.1234567Z ##[group]Run tests\n2026-10-01T10:03:59.0000000Z Error: 1 test failed", Map.of()));
        String out = new CiTools(env()).log("octo/app#900", null, null, null, null, null, null);
        assertThat(out).contains("Job test (#2), failed", "##[group]Run tests\nError: 1 test failed")
                .doesNotContain("2026-10-01T10:03:59");
        assertThat(gh.last("/repos/octo/app/actions/jobs/2/logs").headers().get("authorization")).isEqualTo("Bearer ghp_x");
        assertThat(gh.last("/blob/log-2.txt").headers()).doesNotContainKey("authorization");

        gh.on("/repos/octo/app/actions/jobs/1/logs", r -> new StubServer.Reply(410, "", Map.of()));
        assertThatThrownBy(() -> new CiTools(env()).log("octo/app#900", "build", null, null, null, null, null))
                .hasMessageContaining("nicht verfügbar (410)");
    }

    @Test
    void startNeedsWorkflowAndDispatchesWithInputs() {
        gh.on("/repos/octo/app/actions/workflows", WORKFLOWS);
        gh.on("/repos/octo/app", "{\"default_branch\":\"main\"}");
        gh.on("/repos/octo/app/actions/workflows/ci.yml/dispatches", r -> new StubServer.Reply(204, "", Map.of()));
        CiStartTools tools = new CiStartTools(env());
        assertThatThrownBy(() -> tools.start(null, null, null, null, "octo/app", null))
                .hasMessageContaining("'workflow' angeben").hasMessageContaining("ci.yml");
        String out = tools.start(null, "CI", Map.of("level", "full"), null, "octo/app", null);
        assertThat(out).contains("octo/app: Workflow ci.yml auf main gestartet mit Inputs [level]", "ci_list workflow=ci.yml");
        assertThat(gh.last("/repos/octo/app/actions/workflows/ci.yml/dispatches").body())
                .contains("\"ref\":\"main\"", "\"inputs\":{\"level\":\"full\"}");
    }

    @Test
    void cancelAndRerun() {
        gh.on("/repos/octo/app/actions/runs/899", run(899, "in_progress", null, 1));
        gh.on("/repos/octo/app/actions/runs/899/cancel", r -> new StubServer.Reply(202, "{}", Map.of()));
        gh.on("/repos/octo/app/actions/runs/900", run(900, "completed", "failure", 1));
        gh.on("/repos/octo/app/actions/runs/900/rerun-failed-jobs", r -> new StubServer.Reply(201, "{}", Map.of()));
        gh.on("/repos/octo/app/actions/runs/900/rerun", r -> new StubServer.Reply(201, "{}", Map.of()));
        CiEnvironment env = env();

        assertThat(new CiCancelTools(env).cancel("octo/app#899", null, null, null)).contains("Abbruch angefordert.");
        assertThat(gh.last("/repos/octo/app/actions/runs/899/cancel").method()).isEqualTo("POST");
        assertThat(new CiCancelTools(env).cancel("octo/app#900", null, null, null)).contains("läuft nicht mehr (failed)");
        assertThat(new CiRetryTools(env).retry("octo/app#900", null, null, null, null))
                .contains("fehlgeschlagene Jobs neu gestartet");
        assertThat(new CiRetryTools(env).retry("octo/app#900", true, null, null, null))
                .contains("ganzer Lauf neu gestartet");
        assertThat(gh.requests).extracting(StubServer.Request::path).contains("/repos/octo/app/actions/runs/900/rerun");
    }
}
