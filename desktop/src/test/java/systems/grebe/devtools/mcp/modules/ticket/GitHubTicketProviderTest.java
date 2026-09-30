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

/** GitHub-Provider: REST für Issues/Suche, GraphQL für Projects – gegen einen Stub im Format der echten APIs. */
class GitHubTicketProviderTest {

    StubServer gh;
    TicketModule module = new TicketModule(new TicketProviders());

    @BeforeEach
    void start() throws IOException {
        gh = new StubServer();
    }

    @AfterEach
    void stop() {
        gh.close();
    }

    private TicketTools tools(Map<String, String> extra) {
        Map<String, String> v = new HashMap<>(Map.of("github.enabled", "true", "github.baseUrl", gh.url(),
                "github.token", "ghp_x", "github.defaultProject", "octo/app"));
        v.putAll(extra);
        return new TicketTools(new TicketEnvironment(module.providers(), ModuleConfig.of(module.configSchema(), v)));
    }

    private String issueJson(int number, String state, String reason, boolean pr) {
        return """
                {"number":%d,"title":"Titel %d","state":"%s","state_reason":%s,"repository_url":"%s/repos/octo/app",
                 "html_url":"https://github.com/octo/app/issues/%d","assignees":[{"login":"felix"}],"labels":[{"name":"bug"}],
                 "updated_at":"2026-09-28T10:00:00Z","created_at":"2026-09-01T10:00:00Z","user":{"login":"anna"},
                 "body":"Schritte:\\n1. öffnen","comments":3,"milestone":{"title":"v2"}%s}"""
                .formatted(number, number, state, reason == null ? "null" : "\"" + reason + "\"", gh.url(), number,
                        pr ? ",\"pull_request\":{\"merged_at\":null}" : "");
    }

    @Test
    void searchBuildsQualifiersForRepoAndMarksPullRequests() {
        gh.on("/search/issues", "{\"total_count\":5,\"items\":[" + issueJson(12, "open", null, false) + ","
                + issueJson(13, "closed", "completed", true) + "]}");
        String out = tools(Map.of()).search(null, "crash on start", "all", "none", List.of("bug", "needs triage"),
                "milestone:v2", 2, null, null);
        assertThat(out).startsWith("Treffer: 5 (github)")
                .contains("- octo/app#12  [open]  @felix  Titel 12  (bug)")
                .contains("- octo/app#13  [closed (completed)]  @felix  Titel 13  (Pull Request; bug)")
                .contains("cursor=2");
        StubServer.Request r = gh.last("/search/issues");
        assertThat(r.decodedQuery()).contains("q=is:issue repo:octo/app no:assignee label:bug label:\"needs triage\" "
                + "milestone:v2 crash on start").contains("per_page=2").contains("sort=updated")
                .doesNotContain("advanced_search"); // nur auf github.com
        assertThat(r.headers().get("authorization")).isEqualTo("Bearer ghp_x");
        assertThat(r.headers().get("x-github-api-version")).isEqualTo("2022-11-28");

        tools(Map.of()).search("octo", null, null, "me", null, null, null, null, null);
        assertThat(gh.last("/search/issues").decodedQuery()).contains("q=is:issue user:octo is:open assignee:@me");
    }

    @Test
    void getAcceptsShortKeyWithDefaultRepoAndLoadsNewestComments() {
        gh.on("/repos/octo/app/issues/12", issueJson(12, "open", null, false));
        // 3 Kommentare, 2 gewünscht: letzte Seite (2) enthält nur #3, davor Seite 1 mit #1, #2
        gh.on("/repos/octo/app/issues/12/comments", r -> StubServer.Reply.json(r.decodedQuery().contains("&page=2")
                ? "[{\"user\":{\"login\":\"c\"},\"created_at\":\"t3\",\"body\":\"drei\"}]"
                : "[{\"user\":{\"login\":\"a\"},\"created_at\":\"t1\",\"body\":\"eins\"},"
                        + "{\"user\":{\"login\":\"b\"},\"created_at\":\"t2\",\"body\":\"zwei\"}]"));

        String out = tools(Map.of()).get("#12", null, 2, null);
        assertThat(out).startsWith("octo/app#12: Titel 12")
                .contains("Status:     open [TODO]", "Zuständig:  felix", "Autor:      anna", "Milestone:  v2",
                        "## Beschreibung\nSchritte:\n1. öffnen", "## Kommentare (2 von 3, neueste)", "### b, t2\nzwei",
                        "### c, t3\ndrei")
                .doesNotContain("eins");
        assertThat(out.indexOf("zwei")).isLessThan(out.indexOf("drei"));
    }

    @Test
    void keyFormsAndErrors() {
        gh.on("/repos/other/lib/issues/5", issueJson(5, "open", null, false));
        assertThat(tools(Map.of()).get("https://github.com/other/lib/issues/5#issuecomment-1", null, 0, null))
                .startsWith("octo/app#5"); // Schlüssel aus repository_url der Antwort
        assertThat(gh.requests).extracting(StubServer.Request::path).contains("/repos/other/lib/issues/5");
        assertThat(tools(Map.of()).get("other/lib#5", null, 0, null)).doesNotContain("## Kommentare");

        TicketTools noRepo = tools(Map.of("github.defaultProject", ""));
        assertThatThrownBy(() -> noRepo.get("12", null, null, null)).hasMessageContaining("braucht ein Repository");

        gh.on("/search/issues", r -> new StubServer.Reply(403, "{\"message\":\"API rate limit exceeded\"}",
                Map.of("X-RateLimit-Remaining", "0")));
        assertThatThrownBy(() -> tools(Map.of()).search(null, null, null, null, null, null, null, null, null))
                .hasMessageContaining("Anfragelimit erschöpft");
    }

    @Test
    void projectsV2ListAndBoardGroupedByStatusField() {
        gh.on("/graphql", r -> {
            if (r.body().contains("projectsV2(first:50")) {
                return StubServer.Reply.json("""
                        {"data":{"organization":{"projectsV2":{"nodes":[{"id":"PVT_1","number":3,"title":"Roadmap",
                          "url":"https://github.com/orgs/octo/projects/3","closed":false}]}},"user":null},
                         "errors":[{"type":"NOT_FOUND","message":"Could not resolve to a User with the login of 'octo'."}]}""");
            }
            return StubServer.Reply.json("""
                    {"data":{"viewer":{"login":"felix"},"node":{"id":"PVT_1","number":3,"title":"Roadmap",
                      "url":"https://github.com/orgs/octo/projects/3",
                      "field":{"options":[{"name":"Todo"},{"name":"In Progress"},{"name":"Done"}]},
                      "items":{"totalCount":4,"pageInfo":{"hasNextPage":false,"endCursor":null},"nodes":[
                        {"isArchived":false,"fieldValueByName":{"name":"In Progress"},"content":{"__typename":"Issue","number":12,
                          "title":"Login","url":"u12","state":"OPEN","stateReason":null,"updatedAt":"2026-09-28T00:00:00Z",
                          "repository":{"nameWithOwner":"octo/app"},"assignees":{"nodes":[{"login":"felix"}]},"labels":{"nodes":[]}}},
                        {"isArchived":false,"fieldValueByName":null,"content":{"__typename":"DraftIssue","title":"Idee",
                          "updatedAt":"2026-09-20T00:00:00Z","assignees":{"nodes":[]}}},
                        {"isArchived":false,"fieldValueByName":{"name":"Done"},"content":{"__typename":"PullRequest","number":14,
                          "title":"Fix","url":"u14","state":"MERGED","updatedAt":"2026-09-27T00:00:00Z",
                          "repository":{"nameWithOwner":"octo/app"},"assignees":{"nodes":[{"login":"anna"}]},"labels":{"nodes":[]}}},
                        {"isArchived":true,"fieldValueByName":{"name":"Todo"},"content":{"__typename":"Issue","number":1,
                          "title":"Alt","url":"u1","state":"CLOSED","updatedAt":"2026-01-01T00:00:00Z",
                          "repository":{"nameWithOwner":"octo/app"},"assignees":{"nodes":[]},"labels":{"nodes":[]}}}]}}}}""");
        });

        TicketTools t = tools(Map.of());
        assertThat(t.boards("octo", null)).contains("- PVT_1  Roadmap (#3)  [project]  https://github.com/orgs/octo/projects/3");

        String board = t.board("roadmap", "octo", null, null, null);
        assertThat(board).startsWith("Board Roadmap (#3) (github, ID PVT_1) – alle nicht archivierten Einträge")
                .contains("## Todo (0)", "## In Progress (1)\n- octo/app#12  [open]  @felix  Login",
                        "## Done (1)\n- octo/app#14  [merged]  @anna  Fix  (Pull Request)",
                        "## (ohne Status) (1)\n- (Entwurf)  [Entwurf]  –  Idee  (Draft)")
                .doesNotContain("Alt");
        assertThat(gh.last("/graphql").body()).contains("\"field\":\"Status\"");

        assertThat(t.board("PVT_1", null, "me", null, null)).contains("## In Progress (1)", "## Done (0)");

        TicketTools anonymous = tools(Map.of("github.token", ""));
        assertThatThrownBy(() -> anonymous.boards("octo", null)).hasMessageContaining("brauchen ein Token");
    }
}
