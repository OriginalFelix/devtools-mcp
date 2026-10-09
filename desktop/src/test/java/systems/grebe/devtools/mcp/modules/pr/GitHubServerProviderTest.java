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

/** GitHub-Provider gegen einen Stub der REST- und GraphQL-API. */
class GitHubServerProviderTest {

    StubServer gh;
    PrModule module = new PrModule(new GitServerProviders());

    @BeforeEach
    void start() throws IOException {
        gh = new StubServer();
    }

    @AfterEach
    void stop() {
        gh.close();
    }

    private PrEnvironment env(Map<String, String> extra) {
        Map<String, String> v = new HashMap<>(Map.of("github.enabled", "true", "github.baseUrl", gh.url(),
                "github.token", "ghp_x", "commentSuffix", "(via DevTools)"));
        v.putAll(extra);
        return new PrEnvironment(module.providers(), ModuleConfig.of(module.configSchema(), v));
    }

    private static String pr(int n, String state, String merged, String head) {
        return """
                {"number":%d,"title":"PR %d","state":"%s","merged_at":%s,"draft":false,"user":{"login":"anna"},
                 "head":{"ref":"%s","sha":"abc123","repo":{"full_name":"octo/app"}},"base":{"ref":"main"},
                 "updated_at":"2026-09-30T10:00:00Z","html_url":"https://github.com/octo/app/pull/%d"}"""
                .formatted(n, n, state, merged == null ? "null" : "\"" + merged + "\"", head, n);
    }

    @Test
    void listFiltersMergedAndAuthorClientSide() {
        gh.on("/repos/octo/app/pulls", "[" + pr(1, "closed", "2026-09-01T00:00:00Z", "a") + ","
                + pr(2, "closed", null, "b") + "]");
        String out = new PrTools(env(Map.of())).list("merged", "anna", null, null, null, null, "octo/app", null);
        assertThat(out).contains("1 Pull Request(s) in octo/app (github)", "- octo/app#1  [merged]  @anna  a → main  PR 1");
        assertThat(gh.last("/repos/octo/app/pulls").decodedQuery()).contains("state=closed", "per_page=100");
        assertThat(gh.last("/repos/octo/app/pulls").headers().get("authorization")).isEqualTo("Bearer ghp_x");
    }

    @Test
    void getShowsReviewsMergeStateAndChecks() {
        gh.on("/repos/octo/app/pulls/7", """
                {"number":7,"title":"Export","state":"open","draft":false,"user":{"login":"anna"},"body":"Beschreibung",
                 "head":{"ref":"feature/export","sha":"abc123"},"base":{"ref":"main"},"mergeable_state":"blocked",
                 "requested_reviewers":[{"login":"bob"}],"commits":3,"additions":10,"deletions":2,"changed_files":4,
                 "comments":1,"review_comments":2,"labels":[{"name":"backend"}],"created_at":"2026-09-29T00:00:00Z",
                 "html_url":"https://github.com/octo/app/pull/7"}""");
        gh.on("/repos/octo/app/pulls/7/reviews", """
                [{"user":{"login":"carl"},"state":"CHANGES_REQUESTED"},{"user":{"login":"carl"},"state":"APPROVED"},
                 {"user":{"login":"dora"},"state":"CHANGES_REQUESTED"},{"user":{"login":"eve"},"state":"COMMENTED"}]""");
        gh.on("/repos/octo/app/commits/abc123/check-runs",
                "{\"check_runs\":[{\"name\":\"build\",\"status\":\"completed\",\"conclusion\":\"failure\",\"html_url\":\"https://ci/1\"}]}");
        gh.on("/repos/octo/app/commits/abc123/status", "{\"statuses\":[]}");
        String out = new PrTools(env(Map.of())).get("octo/app#7", null, null, null);
        assertThat(out).contains("octo/app#7: Export", "feature/export → main", "Reviewer:   bob", "Freigaben:  carl",
                "Änderungen angefordert: dora", "blockiert (Freigaben oder Pflicht-Checks fehlen)", "- build: failure  https://ci/1",
                "## Beschreibung\nBeschreibung");
    }

    @Test
    void commentsCombineIssueCommentsReviewsAndGraphQlThreads() {
        gh.on("/repos/octo/app/issues/7/comments",
                "[{\"id\":11,\"user\":{\"login\":\"bob\"},\"created_at\":\"2026-09-29T01:00:00Z\",\"body\":\"Allgemein\"}]");
        gh.on("/repos/octo/app/pulls/7/reviews",
                "[{\"id\":12,\"user\":{\"login\":\"carl\"},\"state\":\"CHANGES_REQUESTED\",\"submitted_at\":\"2026-09-29T02:00:00Z\",\"body\":\"Bitte ändern\"}]");
        gh.on("/graphql", """
                {"data":{"repository":{"pullRequest":{"reviewThreads":{"pageInfo":{"hasNextPage":false},"nodes":[
                 {"id":"PRRT_a","isResolved":false,"isOutdated":false,"path":"src/A.java","line":42,"originalLine":40,
                  "comments":{"nodes":[{"databaseId":21,"author":{"login":"carl"},"createdAt":"2026-09-29T03:00:00Z","body":"Null-Check fehlt"},
                                       {"databaseId":22,"author":{"login":"anna"},"createdAt":"2026-09-29T04:00:00Z","body":"Mache ich"}]}},
                 {"id":"PRRT_b","isResolved":true,"isOutdated":true,"path":"src/B.java","line":null,"originalLine":5,
                  "comments":{"nodes":[{"databaseId":23,"author":{"login":"carl"},"createdAt":"2026-09-29T05:00:00Z","body":"ok"}]}}]}}}}}""");
        PrTools tools = new PrTools(env(Map.of()));
        String out = tools.comments("octo/app#7", null, null, null, null, null);
        assertThat(out).contains("4 Thread(s), davon 1 offen", "### [c11] Kommentar", "### [r12] Review CHANGES_REQUESTED",
                "### [PRRT_a] Code  src/A.java:42  OFFEN", "  Null-Check fehlt", "### [PRRT_b] Code  src/B.java:5  ERLEDIGT  (veraltet");
        assertThat(out.indexOf("[c11]")).isLessThan(out.indexOf("[PRRT_a]"));
        assertThat(tools.comments("octo/app#7", true, null, null, null, null)).contains("[PRRT_a]").doesNotContain("[PRRT_b]");
    }

    @Test
    void replyResolveAndInlineCommentUseRightEndpoints() {
        gh.on("/graphql", r -> StubServer.Reply.json(r.body().contains("addPullRequestReviewThreadReply")
                ? "{\"data\":{\"addPullRequestReviewThreadReply\":{\"comment\":{\"databaseId\":99,\"url\":\"https://github.com/c/99\"}}}}"
                : "{\"data\":{\"resolveReviewThread\":{\"thread\":{\"isResolved\":true}}}}"));
        gh.on("/repos/octo/app/pulls/7", pr(7, "open", null, "feature/x"));
        gh.on("/repos/octo/app/pulls/7/comments", "{\"id\":5,\"html_url\":\"https://github.com/c/5\"}");
        PrEnvironment env = env(Map.of());

        assertThat(new PrCommentTools(env).reply("[PRRT_a]", "Erledigt in abc", "octo/app#7", null, null, null))
                .contains("Antwort in Thread PRRT_a", "https://github.com/c/99");
        assertThat(gh.last("/graphql").body()).contains("PRRT_a", "Erledigt in abc\\n\\n(via DevTools)");

        assertThat(new PrResolveTools(env).resolve("PRRT_a", null, "octo/app#7", null, null, null))
                .contains("als erledigt markiert");
        assertThat(gh.last("/graphql").body()).contains("resolveReviewThread");
        assertThatThrownBy(() -> new PrResolveTools(env).resolve("c11", null, "octo/app#7", null, null, null))
                .hasMessageContaining("nur Code-Threads");

        new PrCommentTools(env).comment("Hier fehlt was", "octo/app#7", "src\\A.java", 12, null, null, null);
        assertThat(gh.last("/repos/octo/app/pulls/7/comments").body())
                .contains("\"commit_id\":\"abc123\"", "\"path\":\"src/A.java\"", "\"line\":12", "\"side\":\"RIGHT\"");
    }

    @Test
    void fileCommentsAndBotAuthors() {
        gh.on("/repos/octo/app/issues/7/comments", """
                [{"id":13,"user":{"login":"dependabot[bot]","type":"Bot"},"created_at":"2026-09-29T01:00:00Z","body":"Bump"},
                 {"id":14,"user":{"login":"anna","type":"User"},"created_at":"2026-09-29T01:30:00Z","body":"Danke"}]""");
        gh.on("/repos/octo/app/pulls/7/reviews", "[]");
        gh.on("/graphql", """
                {"data":{"repository":{"pullRequest":{"reviewThreads":{"pageInfo":{"hasNextPage":false},"nodes":[
                 {"id":"PRRT_f","isResolved":false,"isOutdated":false,"path":"src/C.java","line":null,"originalLine":null,
                  "subjectType":"FILE","comments":{"nodes":[{"databaseId":31,"author":{"__typename":"Bot","login":"sonar"},
                  "createdAt":"2026-09-29T02:00:00Z","body":"Datei zu lang"}]}}]}}}}}""");
        String out = new PrTools(env(Map.of())).comments("octo/app#7", null, null, null, null, null);
        assertThat(out).contains("- dependabot[bot] [Integration], ", "- anna, ", "### [PRRT_f] Datei  src/C.java  OFFEN",
                "- sonar [Integration], ");
        assertThat(gh.last("/graphql").body()).contains("subjectType", "__typename");

        gh.on("/repos/octo/app/pulls/7", pr(7, "open", null, "feature/x"));
        gh.on("/repos/octo/app/pulls/7/comments", "{\"id\":6,\"html_url\":\"https://github.com/c/6\"}");
        assertThat(new PrCommentTools(env(Map.of())).comment("Bitte aufteilen", "octo/app#7", "src/C.java", null, null,
                null, null)).contains("Datei-Kommentar an src/C.java hinzugefügt", "[ID c6]");
        assertThat(gh.last("/repos/octo/app/pulls/7/comments").body())
                .contains("\"subject_type\":\"file\"", "\"path\":\"src/C.java\"").doesNotContain("\"line\"", "\"side\"");
        assertThatThrownBy(() -> new PrCommentTools(env(Map.of())).comment("x", "octo/app#7", null, 3, null, null, null))
                .hasMessageContaining("'line' braucht 'path'");
    }

    @Test
    void insightsFromCheckRunsWithOutputAndAnnotations() {
        gh.on("/repos/octo/app/pulls/7", pr(7, "open", null, "feature/x"));
        gh.on("/repos/octo/app/commits/abc123/check-runs", """
                {"check_runs":[
                 {"id":1,"name":"build","status":"completed","conclusion":"success","output":{"annotations_count":0}},
                 {"id":2,"name":"SonarCloud Code Analysis","status":"completed","conclusion":"failure",
                  "html_url":"https://github.com/octo/app/runs/2","app":{"name":"SonarCloud"},
                  "output":{"title":"Quality Gate failed","summary":"2 Bugs","annotations_count":2}}]}""");
        gh.on("/repos/octo/app/check-runs/2/annotations", """
                [{"path":"src/A.java","start_line":12,"end_line":12,"annotation_level":"failure","title":"Bug","message":"NPE möglich",
                  "blob_href":"https://github.com/octo/app/blob/abc123/src/A.java"},
                 {"path":"src/B.java","start_line":3,"annotation_level":"warning","message":"Unbenutzt"}]""");
        PrTools tools = new PrTools(env(Map.of()));
        String out = tools.insights("octo/app#7", null, null, null, null);
        assertThat(out).contains("octo/app#7 (github): 1 Bericht(e) von Integrationen, 2 Befund(e)",
                "### SonarCloud Code Analysis  failure", "Schlüssel 2 · Quelle SonarCloud", "Quality Gate failed\n2 Bugs",
                "Befunde (2: 1 failure, 1 warning):", "- src/A.java:12  failure  Bug: NPE möglich  https://github.com/octo/app/blob/abc123/src/A.java",
                "- src/B.java:3  warning  Unbenutzt").doesNotContain("### build");
        assertThat(tools.insights("octo/app#7", "B.java", null, null, null)).contains("Befunde (1 von 2: 1 failure, 1 warning):", "src/B.java:3")
                .doesNotContain("src/A.java");
    }

    @Test
    void createUsesDefaultBranchAndRequestsReviewers() {
        gh.on("/repos/octo/app", "{\"default_branch\":\"develop\"}");
        gh.on("/repos/octo/app/pulls", r -> new StubServer.Reply(201,
                "{\"number\":8,\"html_url\":\"https://github.com/octo/app/pull/8\"}", Map.of()));
        gh.on("/repos/octo/app/pulls/8/requested_reviewers", "{}");
        String out = new PrCreateTools(env(Map.of())).create("Neues Feature", "Text", "feature/x", null, true,
                List.of("bob", "team:core"), null, null, "octo/app", null);
        assertThat(out).contains("octo/app#8: Entwurf angelegt: feature/x → develop, Reviewer angefragt: bob, team:core",
                "https://github.com/octo/app/pull/8");
        assertThat(gh.last("/repos/octo/app/pulls").body()).contains("\"head\":\"feature/x\"", "\"base\":\"develop\"",
                "\"draft\":true");
        assertThat(gh.last("/repos/octo/app/pulls/8/requested_reviewers").body())
                .contains("\"reviewers\":[\"bob\"]", "\"team_reviewers\":[\"core\"]");

        gh.on("/repos/octo/app/pulls", r -> new StubServer.Reply(422,
                "{\"message\":\"Validation Failed\",\"errors\":[{\"message\":\"A pull request already exists for octo:feature/x.\"}]}",
                Map.of()));
        assertThatThrownBy(() -> new PrCreateTools(env(Map.of())).create("T", null, "feature/x", "main", null, null,
                null, null, "octo/app", null)).hasMessageContaining("schon einen offenen Pull Request");
    }

    @Test
    void writesRespectRepositoryRestriction() {
        PrEnvironment env = env(Map.of("writeProjects", "github:octo/allowed"));
        assertThatThrownBy(() -> new PrCommentTools(env).comment("x", "octo/app#7", null, null, null, null, null))
                .hasMessageContaining("nicht freigegeben");
        assertThat(gh.requests).isEmpty();
    }
}
