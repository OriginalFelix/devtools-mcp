package systems.grebe.devtools.mcp.modules.pr;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import systems.grebe.devtools.mcp.core.ModuleConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Bitbucket Cloud (API 2.0) und Data Center (REST 1.0) gegen Stubs. */
class BitbucketServerProviderTest {

    static final String CLOUD_PR = "/repositories/team/web/pullrequests/5";
    static final String DC_PR = "/rest/api/1.0/projects/PROJ/repos/app/pull-requests/5";

    StubServer bb;
    PrModule module = new PrModule(new GitServerProviders());

    @BeforeEach
    void start() throws IOException {
        bb = new StubServer();
    }

    @AfterEach
    void stop() {
        bb.close();
    }

    private PrEnvironment env(String deployment, Map<String, String> extra) {
        Map<String, String> v = new HashMap<>(Map.of("bitbucket.enabled", "true", "bitbucket.baseUrl", bb.url(),
                "bitbucket.deployment", deployment, "bitbucket.token", "tok"));
        v.putAll(extra);
        return new PrEnvironment(module.providers(), ModuleConfig.of(module.configSchema(), v));
    }

    // ------------------------------------------------------------------ Cloud

    @Test
    void cloudListUsesStateAndQueryFilter() {
        bb.on("/repositories/team/web/pullrequests", """
                {"values":[{"id":5,"title":"Login","state":"OPEN","author":{"nickname":"anna"},
                  "source":{"branch":{"name":"feature/login"}},"destination":{"branch":{"name":"main"}},
                  "updated_on":"2026-09-30T00:00:00Z","links":{"html":{"href":"https://bitbucket.org/team/web/pull-requests/5"}}}]}""");
        String out = new PrTools(env("cloud", Map.of("bitbucket.user", "me@example.com")))
                .list("all", "anna", "feature/login", null, null, null, "team/web", null);
        assertThat(out).contains("- team/web#5  [open]  @anna  feature/login → main  Login");
        StubServer.Request r = bb.last("/repositories/team/web/pullrequests");
        assertThat(r.decodedQuery()).contains("state=OPEN", "state=MERGED", "state=DECLINED",
                "q=source.branch.name=\"feature/login\" AND author.nickname=\"anna\"");
        assertThat(r.headers().get("authorization")).startsWith("Basic ");
    }

    @Test
    void cloudThreadsGroupRepliesAndShowResolution() {
        bb.on(CLOUD_PR + "/comments", """
                {"values":[
                 {"id":1,"content":{"raw":"Warum so?"},"user":{"nickname":"anna"},"created_on":"2026-09-29T01:00:00Z",
                  "inline":{"path":"src/A.java","to":10},"resolution":{"type":"comment_resolution"}},
                 {"id":2,"content":{"raw":"Weil"},"user":{"nickname":"felix"},"created_on":"2026-09-29T02:00:00Z","parent":{"id":1},
                  "inline":{"path":"src/A.java","to":10}},
                 {"id":3,"content":{"raw":"Allgemein"},"user":{"nickname":"bob"},"created_on":"2026-09-29T00:30:00Z"},
                 {"id":4,"content":{"raw":"gelöscht"},"user":{"nickname":"bob"},"created_on":"2026-09-29T00:40:00Z","deleted":true}]}""");
        String out = new PrTools(env("cloud", Map.of())).comments("team/web#5", null, null, null, null, null);
        assertThat(out).contains("2 Thread(s), davon 0 offen", "### [3] Kommentar\n",
                "### [1] Code  src/A.java:10  ERLEDIGT", "  Weil").doesNotContain("gelöscht");
    }

    @Test
    void cloudDiffSplitsUnifiedDiffPerFile() {
        bb.on(CLOUD_PR + "/diffstat", """
                {"values":[{"status":"modified","lines_added":1,"lines_removed":1,"old":{"path":"src/A.java"},"new":{"path":"src/A.java"}}]}""");
        bb.on(CLOUD_PR + "/diff", r -> new StubServer.Reply(200, """
                diff --git a/src/A.java b/src/A.java
                --- a/src/A.java
                +++ b/src/A.java
                @@ -1 +1 @@
                -alt
                +neu
                """, Map.of()));
        String out = new PrTools(env("cloud", Map.of())).diff("team/web#5", null, null, null, null, null);
        assertThat(out).contains("modified src/A.java  (+1 −1)", "@@ -1 +1 @@\n-alt\n+neu");
        assertThat(bb.last(CLOUD_PR + "/diff").headers().get("accept")).isEqualTo("text/plain");
    }

    @Test
    void cloudReplyAndResolve() {
        bb.on(CLOUD_PR + "/comments", "{\"id\":7,\"links\":{\"html\":{\"href\":\"https://bitbucket.org/c/7\"}}}");
        bb.on(CLOUD_PR + "/comments/1/resolve", "{}");
        PrEnvironment env = env("cloud", Map.of());
        assertThat(new PrCommentTools(env).reply("1", "Erledigt", "team/web#5", null, null, null)).contains("[ID 7]");
        assertThat(bb.last(CLOUD_PR + "/comments").body()).contains("\"parent\":{\"id\":1}", "\"raw\":\"Erledigt\"");
        new PrResolveTools(env).resolve("1", false, "team/web#5", null, null, null);
        assertThat(bb.last(CLOUD_PR + "/comments/1/resolve").method()).isEqualTo("DELETE");
        assertThatThrownBy(() -> new PrResolveTools(env).resolve("abc", true, "team/web#5", null, null, null))
                .hasMessageContaining("keine Kommentar-ID");
    }

    // ------------------------------------------------------------------ Data Center

    @Test
    void dataCenterProbeReadsUserHeaderAndGetShowsVetoes() {
        bb.on("/rest/api/1.0/application-properties", r -> new StubServer.Reply(200, "{\"version\":\"9.4.0\"}",
                Map.of("X-AUSERNAME", "felix")));
        bb.on(DC_PR, """
                {"id":5,"version":3,"title":"Login","state":"OPEN","author":{"user":{"name":"felix"}},"description":"Text",
                 "fromRef":{"displayId":"feature/login","latestCommit":"c0ffee"},"toRef":{"displayId":"main"},
                 "reviewers":[{"user":{"name":"anna"},"status":"APPROVED"},{"user":{"name":"bob"},"status":"NEEDS_WORK"}],
                 "createdDate":1759190400000,"updatedDate":1759194000000,"properties":{"commentCount":2,"openTaskCount":1},
                 "links":{"self":[{"href":"https://bb.example.com/projects/PROJ/repos/app/pull-requests/5"}]}}""");
        bb.on(DC_PR + "/merge", "{\"canMerge\":false,\"conflicted\":false,\"vetoes\":[{\"summaryMessage\":\"Offene Aufgaben\"}]}");
        bb.on("/rest/build-status/1.0/commits/c0ffee", "{\"isLastPage\":true,\"values\":[{\"name\":\"CI\",\"state\":\"SUCCESSFUL\",\"url\":\"https://ci/9\"}]}");
        PrEnvironment env = env("datacenter", Map.of());
        assertThat(new PrTools(env).providers()).contains("Data Center 9.4.0, angemeldet als felix");
        String out = new PrTools(env).get("proj/app#5", null, null, null);
        assertThat(out).contains("PROJ/app#5: Login", "Freigaben:  anna", "Überarbeitung nötig: bob",
                "Merge:      blockiert: Offene Aufgaben", "- CI: SUCCESSFUL  https://ci/9", "Erstellt:   2025-09-30T00:00:00Z");
        assertThat(bb.last(DC_PR).headers().get("authorization")).isEqualTo("Bearer tok");
    }

    @Test
    void dataCenterThreadsAndDiffFromJson() {
        bb.on(DC_PR + "/activities", """
                {"isLastPage":true,"values":[
                 {"action":"COMMENTED","commentAction":"ADDED","commentAnchor":{"path":"src/A.java","line":3},
                  "comment":{"id":40,"text":"Name?","author":{"name":"anna"},"createdDate":1759190400000,"threadResolved":false,
                   "comments":[{"id":41,"text":"Geändert","author":{"name":"felix"},"createdDate":1759194000000,"comments":[]}]}},
                 {"action":"APPROVED"},
                 {"action":"COMMENTED","commentAction":"ADDED","comment":{"id":42,"text":"Bitte Test","severity":"BLOCKER",
                  "state":"RESOLVED","author":{"name":"bob"},"createdDate":1759197600000}}]}""");
        PrTools tools = new PrTools(env("datacenter", Map.of()));
        assertThat(tools.comments("PROJ/app#5", null, null, null, null, null))
                .contains("### [40] Code  src/A.java:3  OFFEN", "- felix", "### [42] Aufgabe  ERLEDIGT");

        bb.on(DC_PR + "/changes", "{\"isLastPage\":true,\"values\":[{\"path\":{\"toString\":\"src/A.java\"},\"type\":\"MODIFY\"}]}");
        bb.on(DC_PR + "/diff", """
                {"diffs":[{"source":{"toString":"src/A.java"},"destination":{"toString":"src/A.java"},"hunks":[
                 {"sourceLine":1,"sourceSpan":2,"destinationLine":1,"destinationSpan":2,"segments":[
                  {"type":"CONTEXT","lines":[{"line":"a","source":1,"destination":1}]},
                  {"type":"REMOVED","lines":[{"line":"b","source":2,"destination":2}]},
                  {"type":"ADDED","lines":[{"line":"c","source":2,"destination":2}]}]}]}]}""");
        assertThat(tools.diff("PROJ/app#5", null, null, null, null, null))
                .contains("modified src/A.java  (+1 −1)", "@@ -1,2 +1,2 @@\n a\n-b\n+c");
    }

    @Test
    void dataCenterInlineCommentPicksLineTypeAndResolveUsesVersion() {
        bb.on(DC_PR + "/diff/src/A.java", """
                {"diffs":[{"hunks":[{"segments":[{"type":"CONTEXT","lines":[{"destination":1}]},
                 {"type":"ADDED","lines":[{"destination":2}]}]}]}]}""");
        bb.on(DC_PR + "/comments", "{\"id\":60,\"version\":0}");
        bb.on(DC_PR + "/comments/40", r -> StubServer.Reply.json("{\"id\":40,\"version\":4}"));
        PrEnvironment env = env("datacenter", Map.of());

        new PrCommentTools(env).comment("Hm", "PROJ/app#5", "src/A.java", 2, null, null, null);
        assertThat(bb.last(DC_PR + "/comments").body()).contains("\"lineType\":\"ADDED\"", "\"fileType\":\"TO\"", "\"line\":2");
        assertThatThrownBy(() -> new PrCommentTools(env).comment("Hm", "PROJ/app#5", "src/A.java", 99, null, null, null))
                .hasMessageContaining("liegt nicht im Diff");

        new PrResolveTools(env).resolve("40", true, "PROJ/app#5", null, null, null);
        StubServer.Request put = bb.requests.reversed().stream().filter(r -> r.method().equals("PUT")).findFirst().orElseThrow();
        assertThat(put.body()).contains("\"version\":4", "\"threadResolved\":true");
    }

    @Test
    void dataCenterCreateUsesDefaultBranchAndRefs() {
        bb.on("/rest/api/1.0/projects/PROJ/repos/app/default-branch", "{\"displayId\":\"develop\"}");
        bb.on("/rest/api/1.0/projects/PROJ/repos/app/pull-requests", r -> new StubServer.Reply(201,
                "{\"id\":6,\"links\":{\"self\":[{\"href\":\"https://bb/pr/6\"}]}}", Map.of()));
        String out = new PrCreateTools(env("datacenter", Map.of())).create("Login", "Text", "feature/login", null, null,
                java.util.List.of("anna"), null, null, "proj/app", null);
        assertThat(out).contains("PROJ/app#6: Pull Request angelegt: feature/login → develop", "https://bb/pr/6");
        assertThat(bb.last("/rest/api/1.0/projects/PROJ/repos/app/pull-requests").body())
                .contains("\"id\":\"refs/heads/feature/login\"", "\"id\":\"refs/heads/develop\"", "\"key\":\"PROJ\"",
                        "\"slug\":\"app\"", "\"name\":\"anna\"");
    }
}
