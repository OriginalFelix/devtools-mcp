package systems.grebe.devtools.mcp.modules.pr;

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

/** GitLab-Provider gegen einen Stub der REST API v4 (Merge Requests, Diskussionen, Pipelines). */
class GitLabServerProviderTest {

    static final String MR = "/api/v4/projects/grp%2Fapp/merge_requests/12";

    StubServer gl;
    PrModule module = new PrModule(new GitServerProviders());

    @BeforeEach
    void start() throws IOException {
        gl = new StubServer();
    }

    @AfterEach
    void stop() {
        gl.close();
    }

    private PrEnvironment env() {
        Map<String, String> v = new HashMap<>(Map.of("gitlab.enabled", "true", "gitlab.baseUrl", gl.url() + "/",
                "gitlab.token", "glpat-x"));
        return new PrEnvironment(module.providers(), ModuleConfig.of(module.configSchema(), v));
    }

    @Test
    void listMapsFiltersAndMe() {
        gl.on("/api/v4/projects/grp%2Fapp/merge_requests", """
                [{"iid":12,"title":"Export","state":"opened","draft":true,"author":{"username":"felix"},
                  "source_branch":"feature/export","target_branch":"main","updated_at":"2026-09-30T00:00:00Z",
                  "web_url":"https://gitlab.example.com/grp/app/-/merge_requests/12"}]""");
        String out = new PrTools(env()).list(null, "me", "feature/export", null, 5, null, "grp/app", null);
        assertThat(out).contains("- grp/app!12  [open]  @felix  feature/export → main  Export  (Entwurf)");
        assertThat(gl.last("/api/v4/projects/grp%2Fapp/merge_requests").decodedQuery()).contains("state=opened",
                "scope=created_by_me", "source_branch=feature/export", "per_page=5");
        assertThat(gl.last("/api/v4/projects/grp%2Fapp/merge_requests").headers().get("private-token")).isEqualTo("glpat-x");
    }

    @Test
    void getShowsApprovalsMergeStatusAndFailedJobs() {
        gl.on(MR, """
                {"iid":12,"title":"Export","state":"opened","author":{"username":"felix"},"description":"Text",
                 "source_branch":"feature/export","target_branch":"main","reviewers":[{"username":"anna"}],
                 "detailed_merge_status":"discussions_not_resolved","user_notes_count":4,"sha":"def456",
                 "head_pipeline":{"id":99,"project_id":5,"status":"failed","web_url":"https://gl/p/99"},
                 "web_url":"https://gitlab.example.com/grp/app/-/merge_requests/12"}""");
        gl.on(MR + "/approvals", "{\"approved_by\":[{\"user\":{\"username\":\"bob\"}}]}");
        gl.on("/api/v4/projects/5/pipelines/99/jobs", "[{\"name\":\"test\",\"stage\":\"test\",\"status\":\"failed\",\"web_url\":\"https://gl/j/1\"}]");
        String out = new PrTools(env()).get("grp/app!12", null, null, null);
        assertThat(out).contains("grp/app!12: Export", "Reviewer:   anna", "Freigaben:  bob",
                "blockiert: offene Diskussionen", "- Pipeline #99: failed  https://gl/p/99", "Job test (test): failed");
    }

    @Test
    void threadsSkipSystemNotesAndAggregateResolution() {
        gl.on(MR + "/discussions", """
                [{"id":"d1","notes":[{"id":1,"system":true,"body":"added 1 commit","author":{"username":"felix"},"created_at":"2026-09-29T00:00:00Z"}]},
                 {"id":"d2","notes":[{"id":2,"body":"Warum?","author":{"username":"anna"},"created_at":"2026-09-29T01:00:00Z",
                   "resolvable":true,"resolved":false,"position":{"new_path":"src/A.java","new_line":7}},
                  {"id":3,"body":"Darum","author":{"username":"felix"},"created_at":"2026-09-29T02:00:00Z","resolvable":true,"resolved":false}]},
                 {"id":"d3","notes":[{"id":4,"body":"LGTM","author":{"username":"bob"},"created_at":"2026-09-29T03:00:00Z"}]}]""");
        String out = new PrTools(env()).comments("12", null, null, null, "grp/app", null);
        assertThat(out).contains("2 Thread(s), davon 1 offen", "### [d2] Code  src/A.java:7  OFFEN", "- felix",
                "### [d3] Kommentar").doesNotContain("added 1 commit");
    }

    @Test
    void inlineCommentReplyAndResolve() {
        gl.on(MR, "{\"iid\":12,\"diff_refs\":{\"base_sha\":\"b\",\"head_sha\":\"h\",\"start_sha\":\"s\"}}");
        gl.on(MR + "/discussions", "{\"id\":\"d9\",\"notes\":[{\"id\":50}]}");
        gl.on(MR + "/discussions/d2/notes", "{\"id\":51}");
        gl.on(MR + "/discussions/d2", "{\"id\":\"d2\"}");
        PrEnvironment env = env();

        String out = new PrCommentTools(env).comment("Bitte prüfen", "grp/app!12", "src/A.java", 7, null, null, null);
        assertThat(out).contains("Code-Kommentar an src/A.java:7", "Thread d9", "/grp/app/-/merge_requests/12#note_50");
        assertThat(gl.last(MR + "/discussions").body()).contains("\"base_sha\":\"b\"", "\"head_sha\":\"h\"",
                "\"start_sha\":\"s\"", "\"new_line\":7", "\"position_type\":\"text\"");

        assertThat(new PrCommentTools(env).reply("d2", "Umgesetzt", "grp/app!12", null, null, null)).contains("#note_51");
        new PrResolveTools(env).resolve("d2", true, "grp/app!12", null, null, null);
        StubServer.Request r = gl.last(MR + "/discussions/d2");
        assertThat(r.method()).isEqualTo("PUT");
        assertThat(r.decodedQuery()).isEqualTo("resolved=true");
    }

    @Test
    void createMarksDraftResolvesReviewersAndDefaultBranch() {
        gl.on("/api/v4/projects/grp%2Fapp", "{\"default_branch\":\"main\"}");
        gl.on("/api/v4/users", r -> StubServer.Reply.json(r.decodedQuery().contains("anna") ? "[{\"id\":3}]" : "[]"));
        gl.on("/api/v4/projects/grp%2Fapp/merge_requests", r -> new StubServer.Reply(201,
                "{\"iid\":13,\"web_url\":\"https://gl/mr/13\"}", Map.of()));
        String out = new PrCreateTools(env()).create("Export", null, "feature/export", null, true, List.of("anna", "nobody"),
                true, null, "grp/app", null);
        assertThat(out).contains("grp/app!13: Entwurf angelegt: feature/export → main – unbekannte Reviewer ignoriert: nobody");
        assertThat(gl.last("/api/v4/projects/grp%2Fapp/merge_requests").body()).contains("\"title\":\"Draft: Export\"",
                "\"target_branch\":\"main\"", "\"reviewer_ids\":[3]", "\"remove_source_branch\":true");
    }

    @Test
    void mergeRejectsRebaseAndSendsSha() {
        gl.on(MR, "{\"iid\":12,\"sha\":\"h1\"}");
        gl.on(MR + "/merge", "{\"state\":\"merged\",\"merge_commit_sha\":\"m1\",\"web_url\":\"https://gl/mr/12\"}");
        PrMergeTools tools = new PrMergeTools(env());
        assertThatThrownBy(() -> tools.merge("grp/app!12", "rebase", null, null, null, null, null))
                .hasMessageContaining("legt das Projekt fest");
        assertThat(tools.merge("grp/app!12", "squash", "Export", true, null, null, null)).contains("gemergt (m1)");
        assertThat(gl.last(MR + "/merge").body()).contains("\"sha\":\"h1\"", "\"squash\":true",
                "\"squash_commit_message\":\"Export\"", "\"should_remove_source_branch\":true");
    }
}
