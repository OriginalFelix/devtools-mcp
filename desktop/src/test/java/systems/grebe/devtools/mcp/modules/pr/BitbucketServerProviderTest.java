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
        assertThat(new PrCommentTools(env).reply("1", "Erledigt", "team/web#5", null, null, null, null, null)).contains("[ID 7]");
        assertThat(bb.last(CLOUD_PR + "/comments").body()).contains("\"parent\":{\"id\":1}", "\"raw\":\"Erledigt\"");
        new PrResolveTools(env).resolve("1", false, "team/web#5", null, null, null);
        assertThat(bb.last(CLOUD_PR + "/comments/1/resolve").method()).isEqualTo("DELETE");
        assertThatThrownBy(() -> new PrResolveTools(env).resolve("abc", true, "team/web#5", null, null, null))
                .hasMessageContaining("keine Kommentar-ID");
    }

    @Test
    void cloudFileCommentsAppUsersAndCodeInsights() {
        bb.on(CLOUD_PR + "/comments", r -> StubServer.Reply.json(r.method().equals("POST")
                ? "{\"id\":8,\"links\":{\"html\":{\"href\":\"https://bitbucket.org/c/8\"}}}"
                : """
                {"values":[{"id":1,"content":{"raw":"Datei zu groß"},"user":{"nickname":"scanner","type":"app_user"},
                  "created_on":"2026-09-29T01:00:00Z","inline":{"path":"src/C.java"}}]}"""));
        PrEnvironment env = env("cloud", Map.of());
        assertThat(new PrTools(env).comments("team/web#5", null, null, null, null, null))
                .contains("### [1] Datei  src/C.java  OFFEN", "- scanner [Integration], ");

        new PrCommentTools(env).comment("Aufteilen", "team/web#5", "src/C.java", null, null, null, null, null, null);
        assertThat(bb.last(CLOUD_PR + "/comments").body()).contains("\"inline\":{\"path\":\"src/C.java\"}");

        bb.on(CLOUD_PR, "{\"id\":5,\"source\":{\"commit\":{\"hash\":\"abc\"}}}");
        bb.on("/repositories/team/web/commit/abc/reports", """
                {"values":[{"uuid":"{r1}","title":"SonarQube","reporter":"SonarQube","result":"FAILED","details":"Quality Gate",
                  "data":[{"title":"Abdeckung","type":"PERCENTAGE","value":81.5},{"title":"Neu","type":"BOOLEAN","value":true}]}]}""");
        bb.on("/repositories/team/web/commit/abc/reports/%7Br1%7D/annotations", """
                {"values":[{"path":"src/A.java","line":4,"severity":"HIGH","annotation_type":"BUG","summary":"NPE"}]}""");
        assertThat(new PrTools(env).insights("team/web#5", null, null, null, null)).contains("### SonarQube  FAILED",
                "Quality Gate", "- Abdeckung: 81.5 %", "- Neu: ja", "- src/A.java:4  HIGH BUG  NPE");
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

        new PrCommentTools(env).comment("Hm", "PROJ/app#5", "src/A.java", 2, null, null, null, null, null);
        assertThat(bb.last(DC_PR + "/comments").body()).contains("\"lineType\":\"ADDED\"", "\"fileType\":\"TO\"", "\"line\":2");
        assertThatThrownBy(() -> new PrCommentTools(env).comment("Hm", "PROJ/app#5", "src/A.java", 99, null, null, null, null, null))
                .hasMessageContaining("liegt nicht im Diff");

        new PrResolveTools(env).resolve("40", true, "PROJ/app#5", null, null, null);
        StubServer.Request put = bb.requests.reversed().stream().filter(r -> r.method().equals("PUT")).findFirst().orElseThrow();
        assertThat(put.body()).contains("\"version\":4", "\"threadResolved\":true");
    }

    @Test
    void dataCenterFileCommentsServiceUsersAndCodeInsights() {
        bb.on(DC_PR + "/activities", """
                {"isLastPage":true,"values":[
                 {"action":"COMMENTED","commentAction":"ADDED","commentAnchor":{"path":"src/C.java"},
                  "comment":{"id":70,"text":"Lizenzkopf fehlt","author":{"name":"ci-bot","type":"SERVICE"},
                   "createdDate":1759190400000,"threadResolved":false}}]}""");
        bb.on(DC_PR + "/comments", "{\"id\":71,\"version\":0}");
        PrEnvironment env = env("datacenter", Map.of());
        assertThat(new PrTools(env).comments("PROJ/app#5", null, null, null, null, null))
                .contains("### [70] Datei  src/C.java  OFFEN", "- ci-bot [Integration], ");

        new PrCommentTools(env).comment("Ergänzt", "PROJ/app#5", "src/C.java", null, null, null, null, null, null);
        assertThat(bb.last(DC_PR + "/comments").body()).contains("\"anchor\":{\"path\":\"src/C.java\",\"diffType\":\"EFFECTIVE\"}");
        assertThat(bb.requests).noneMatch(r -> r.path().startsWith(DC_PR + "/diff"));

        String insights = "/rest/insights/1.0/projects/PROJ/repos/app/commits/c0ffee/reports";
        bb.on(DC_PR, "{\"id\":5,\"fromRef\":{\"latestCommit\":\"c0ffee\"}}");
        bb.on(insights, """
                {"isLastPage":true,"values":[{"key":"sonar","title":"SonarQube","reporter":"SonarQube","result":"FAIL",
                  "link":"https://sonar/p","createdDate":1759190400000,"data":[{"title":"Laufzeit","type":"DURATION","value":90000},
                  {"title":"Bericht","type":"LINK","value":{"linktext":"öffnen","href":"https://sonar/r"}}]}]}""");
        bb.on(insights + "/sonar/annotations", """
                {"totalCount":3,"annotations":[{"path":"src/A.java","line":0,"severity":"MEDIUM","type":"CODE_SMELL","message":"Zu lang"},
                 {"path":"src/B.java","line":9,"severity":"HIGH","type":"VULNERABILITY","message":"SQL-Injection"}]}""");
        assertThat(new PrTools(env).insights("PROJ/app#5", null, null, null, null)).contains(
                "1 Bericht(e) von Integrationen, 3 Befund(e)", "### SonarQube  FAIL",
                "Schlüssel sonar · erstellt 2025-09-30T00:00:00Z", "https://sonar/p", "- Laufzeit: 1m30s",
                "- Bericht: öffnen https://sonar/r", "Befunde (3: 1 HIGH, 1 MEDIUM):",
                "- src/B.java:9  HIGH VULNERABILITY  SQL-Injection\n- src/A.java  MEDIUM CODE_SMELL  Zu lang",
                "… 1 weitere beim Server");
    }

    @Test
    void dataCenterGetSummarizesFailedIntegrations() {
        bb.on(DC_PR, """
                {"id":5,"title":"API","state":"OPEN","author":{"user":{"name":"felix"}},
                 "fromRef":{"displayId":"feature/api","latestCommit":"c0ffee"},"toRef":{"displayId":"main"}}""");
        String insights = "/rest/insights/1.0/projects/PROJ/repos/app/commits/c0ffee/reports";
        bb.on(insights, """
                {"isLastPage":true,"values":[{"key":"de.css.api-scanner","title":"API-Scanner","result":"FAIL"},
                 {"key":"compile","title":"Compile","result":"PASS"}]}""");
        bb.on(insights + "/de.css.api-scanner/annotations", """
                {"totalCount":2,"annotations":[{"path":"src/A.java","line":3,"severity":"LOW","message":"GENEHMIGT: …"},
                 {"path":"src/B.java","line":7,"severity":"HIGH","message":"API-BRUCH: java.method.removed"}]}""");
        bb.on(insights + "/compile/annotations", "{\"totalCount\":0,\"annotations\":[]}");
        assertThat(new PrTools(env("datacenter", Map.of())).get("PROJ/app#5", null, null, null)).contains(
                "Integrationen: 2 Bericht(e), 1 fehlgeschlagen: API-Scanner (2 Befund(e): 1 HIGH, 1 LOW) – Details mit pr_insights");
    }

    @Test
    void dataCenterCreateUsesDefaultBranchAndRefs() {
        bb.on("/rest/api/1.0/projects/PROJ/repos/app/default-branch", "{\"displayId\":\"develop\"}");
        bb.on("/rest/api/1.0/projects/PROJ/repos/app/pull-requests", r -> new StubServer.Reply(201,
                "{\"id\":6,\"links\":{\"self\":[{\"href\":\"https://bb/pr/6\"}]}}", Map.of()));
        String out = new PrCreateTools(env("datacenter", Map.of())).create("Login", "Text", "feature/login", null, null,
                java.util.List.of("anna"), null, null, "proj/app", null, null, null);
        assertThat(out).contains("PROJ/app#6: Pull Request angelegt: feature/login → develop", "https://bb/pr/6");
        assertThat(out).doesNotContain("bind_pr");
        assertThat(bb.last("/rest/api/1.0/projects/PROJ/repos/app/pull-requests").body())
                .contains("\"id\":\"refs/heads/feature/login\"", "\"id\":\"refs/heads/develop\"", "\"key\":\"PROJ\"",
                        "\"slug\":\"app\"", "\"name\":\"anna\"");
    }

    @Test
    void cloudApproveAndRequestChanges() {
        bb.on(CLOUD_PR + "/approve", "{\"approved\":true}");
        bb.on(CLOUD_PR + "/request-changes", "{\"state\":\"changes_requested\"}");
        bb.on(CLOUD_PR + "/comments", r -> new StubServer.Reply(201,
                "{\"id\":9,\"links\":{\"html\":{\"href\":\"https://bitbucket.org/c/9\"}}}", Map.of()));
        PrReviewTools tools = new PrReviewTools(env("cloud", Map.of()));

        assertThat(tools.approve("team/web#5", null, null, null, null, null, null)).contains("team/web#5: freigegeben");
        assertThat(bb.last(CLOUD_PR + "/approve").method()).isEqualTo("POST");
        assertThat(bb.last(CLOUD_PR + "/request-changes").method()).isEqualTo("DELETE");

        assertThat(tools.changesRequired("team/web#5", "Bitte Tests", null, null, null, null, null))
                .contains("Änderungen angefordert, Kommentar hinzugefügt", "https://bitbucket.org/c/9");
        assertThat(bb.last(CLOUD_PR + "/request-changes").method()).isEqualTo("POST");
        assertThat(bb.last(CLOUD_PR + "/approve").method()).isEqualTo("DELETE");
        assertThat(bb.last(CLOUD_PR + "/comments").body()).contains("Bitte Tests");
    }

    @Test
    void dataCenterSetsParticipantStatusWithUserSlug() {
        bb.on("/rest/api/1.0/application-properties", r -> new StubServer.Reply(200, "{\"version\":\"9.4.0\"}",
                Map.of("X-AUSERNAME", "felix@firma.de")));
        bb.on("/rest/api/1.0/users", "{\"values\":[{\"name\":\"felix@firma.de\",\"slug\":\"felix_firma.de\"}]}");
        bb.on(DC_PR + "/participants/felix_firma.de", "{\"status\":\"APPROVED\"}");
        bb.on(DC_PR + "/comments", "{\"id\":61,\"version\":0}");
        PrReviewTools tools = new PrReviewTools(env("datacenter", Map.of()));

        assertThat(tools.approve("PROJ/app#5", null, null, null, null, null, null)).contains("PROJ/app#5: freigegeben");
        StubServer.Request put = bb.last(DC_PR + "/participants/felix_firma.de");
        assertThat(put.method()).isEqualTo("PUT");
        assertThat(put.body()).contains("\"status\":\"APPROVED\"");

        assertThat(tools.changesRequired("PROJ/app#5", "Bitte Tests", null, null, null, null, null))
                .contains("Änderungen angefordert (Needs work), Kommentar hinzugefügt", "commentId=61");
        assertThat(bb.last(DC_PR + "/participants/felix_firma.de").body()).contains("\"status\":\"NEEDS_WORK\"");
        assertThat(bb.requests).filteredOn(r -> r.path().equals("/rest/api/1.0/users")).hasSize(1);
    }

    @Test
    void cloudMarkReadyClearsDraft() {
        bb.on(CLOUD_PR, "{\"id\":5,\"draft\":true}");
        PrCreateTools tools = new PrCreateTools(env("cloud", Map.of()));
        assertThat(tools.markReady("team/web#5", null, null, null)).contains("team/web#5: als bereit zum Review markiert");
        StubServer.Request put = bb.last(CLOUD_PR);
        assertThat(put.method()).isEqualTo("PUT");
        assertThat(put.body()).isEqualTo("{\"draft\":false}");

        bb.on(CLOUD_PR, "{\"id\":5,\"draft\":false}");
        assertThat(tools.markReady("team/web#5", null, null, null)).contains("ist kein Entwurf");
        assertThat(bb.last(CLOUD_PR).method()).isEqualTo("GET");
    }

    @Test
    void dataCenterMarkReadyKeepsVersionTitleAndReviewers() {
        bb.on(DC_PR, """
                {"id":5,"version":3,"title":"API","description":"Text","draft":true,
                 "reviewers":[{"user":{"name":"anna"}}]}""");
        PrCreateTools tools = new PrCreateTools(env("datacenter", Map.of()));
        assertThat(tools.markReady("PROJ/app#5", null, null, null))
                .contains("PROJ/app#5: als bereit zum Review markiert", "/projects/PROJ/repos/app/pull-requests/5");
        StubServer.Request put = bb.last(DC_PR);
        assertThat(put.method()).isEqualTo("PUT");
        assertThat(put.body()).contains("\"version\":3", "\"title\":\"API\"", "\"description\":\"Text\"",
                "\"name\":\"anna\"", "\"draft\":false");
    }

    // ------------------------------------------------------------------ Ablehnen und Branches löschen

    static final String DC_REPO = "/rest/api/1.0/projects/PROJ/repos/app";
    static final String DC_BRANCH_UTILS = "/rest/branch-utils/1.0/projects/PROJ/repos/app/branches";

    private void dataCenterBranches(String defaultBranch, String branchesJson) {
        bb.on(DC_REPO + "/default-branch", "{\"displayId\":\"" + defaultBranch + "\"}");
        bb.on(DC_REPO + "/branches", branchesJson);
        bb.on(DC_BRANCH_UTILS, r -> new StubServer.Reply(204, "", Map.of()));
    }

    @Test
    void dataCenterDeclineCommentsThenDeclinesAndDeletesSourceBranch() {
        String pr = """
                {"id":5,"version":3,"title":"Alt","state":"OPEN","author":{"user":{"name":"felix"}},
                 "fromRef":{"id":"refs/heads/feature/alt","displayId":"feature/alt","latestCommit":"c0ffee",
                            "repository":{"id":1,"slug":"app","project":{"key":"PROJ"}}},
                 "toRef":{"displayId":"master","repository":{"id":1}}}""";
        bb.on(DC_PR, pr);
        bb.on(DC_PR + "/merge", "{\"canMerge\":true,\"conflicted\":false,\"vetoes\":[]}");
        bb.on("/rest/build-status/1.0/commits/c0ffee", "{\"isLastPage\":true,\"values\":[]}");
        bb.on(DC_REPO + "/pull-requests", "{\"isLastPage\":true,\"values\":[" + pr + "]}");
        bb.on(DC_PR + "/comments", "{\"id\":61,\"version\":0}");
        bb.on(DC_PR + "/decline", "{\"state\":\"DECLINED\"}");
        dataCenterBranches("master", "{\"values\":[{\"displayId\":\"feature/alt\",\"latestCommit\":\"c0ffee\"}]}");

        String out = new PrDeclineTools(env("datacenter", Map.of()))
                .decline("PROJ/app#5", "Überholt", true, null, null, null, null, null);
        assertThat(out).contains("PROJ/app#5: abgelehnt, Kommentar hinzugefügt, Branch feature/alt gelöscht "
                + "(letzter Commit c0ffee)");
        StubServer.Request decline = bb.last(DC_PR + "/decline");
        assertThat(decline.method()).isEqualTo("POST");
        assertThat(decline.query()).isEqualTo("version=3");
        assertThat(bb.requests.indexOf(bb.last(DC_PR + "/comments"))).isLessThan(bb.requests.indexOf(decline));
        assertThat(bb.last(DC_PR + "/comments").body()).contains("Überholt");
        StubServer.Request del = bb.last(DC_BRANCH_UTILS);
        assertThat(del.method()).isEqualTo("DELETE");
        assertThat(del.body()).contains("\"name\":\"refs/heads/feature/alt\"", "\"dryRun\":false");
    }

    @Test
    void dataCenterDeclineLeavesClosedPullRequestAlone() {
        bb.on(DC_PR, "{\"id\":5,\"version\":3,\"state\":\"DECLINED\",\"fromRef\":{\"latestCommit\":\"c0ffee\"}}");
        bb.on(DC_PR + "/merge", "{\"canMerge\":false,\"conflicted\":false,\"vetoes\":[]}");
        bb.on("/rest/build-status/1.0/commits/c0ffee", "{\"isLastPage\":true,\"values\":[]}");
        String out = new PrDeclineTools(env("datacenter", Map.of()))
                .decline("PROJ/app#5", null, null, null, null, null, null, null);
        assertThat(out).contains("PROJ/app#5: ist nicht offen (closed) – unverändert");
        assertThat(bb.requests).extracting(StubServer.Request::method).containsOnly("GET");
    }

    @Test
    void deleteBranchRefusesDefaultBranchOpenPullRequestsAndUnknownBranches() {
        PrDeclineTools tools = new PrDeclineTools(env("datacenter", Map.of()));
        assertThatThrownBy(() -> tools.deleteBranch("master", null, "PROJ/app", null))
                .hasMessageContaining("Standard-Branch");
        assertThat(bb.requests).isEmpty();

        bb.on(DC_REPO + "/pull-requests", """
                {"isLastPage":true,"values":[{"id":7,"title":"Y","state":"OPEN","fromRef":{"displayId":"feature/y"},
                 "toRef":{"displayId":"develop"}}]}""");
        dataCenterBranches("develop", "{\"values\":[]}");
        assertThatThrownBy(() -> tools.deleteBranch("feature/y", null, "PROJ/app", null))
                .hasMessageContaining("noch Pull Requests offen: PROJ/app#7");
        assertThatThrownBy(() -> tools.deleteBranch("develop", null, "PROJ/app", null))
                .hasMessageContaining("'develop' ist der Standard-Branch");
        assertThatThrownBy(() -> tools.deleteBranch("feature/weg", null, "PROJ/app", null))
                .hasMessageContaining("Branch 'feature/weg' gibt es in PROJ/app nicht");
        assertThat(bb.requests).noneMatch(r -> r.path().equals(DC_BRANCH_UTILS));

        bb.on(DC_REPO + "/branches", "{\"values\":[{\"displayId\":\"feature/z\",\"latestCommit\":\"abc123\"}]}");
        assertThat(tools.deleteBranch("refs/heads/feature/z", null, "PROJ/app", null))
                .isEqualTo("PROJ/app: Branch feature/z gelöscht (letzter Commit abc123)");
        assertThat(bb.last(DC_BRANCH_UTILS).body()).contains("\"name\":\"refs/heads/feature/z\"");
    }

    @Test
    void declineRefusesBranchDeletionWhileAnotherPullRequestIsOpen() {
        String pr = """
                {"id":5,"version":3,"title":"Alt","state":"OPEN","fromRef":{"displayId":"feature/alt","latestCommit":"c0ffee"},
                 "toRef":{"displayId":"master"}}""";
        String other = """
                {"id":6,"title":"Backport","state":"OPEN","fromRef":{"displayId":"feature/alt"},"toRef":{"displayId":"43.1"}}""";
        bb.on(DC_PR, pr);
        bb.on(DC_PR + "/merge", "{\"canMerge\":true,\"conflicted\":false,\"vetoes\":[]}");
        bb.on("/rest/build-status/1.0/commits/c0ffee", "{\"isLastPage\":true,\"values\":[]}");
        bb.on(DC_REPO + "/pull-requests", "{\"isLastPage\":true,\"values\":[" + pr + "," + other + "]}");
        assertThatThrownBy(() -> new PrDeclineTools(env("datacenter", Map.of()))
                .decline("PROJ/app#5", null, true, null, null, null, null, null))
                .hasMessageContaining("weitere Pull Requests offen: PROJ/app#6");
        assertThat(bb.requests).noneMatch(r -> r.path().equals(DC_PR + "/decline"));
    }

    @Test
    void cloudDeclinePostsDeclineAndDeletesBranchRef() {
        bb.on(CLOUD_PR, """
                {"id":5,"state":"OPEN","source":{"branch":{"name":"feature/alt"},"repository":{"full_name":"team/web"}},
                 "destination":{"branch":{"name":"main"},"repository":{"full_name":"team/web"}}}""");
        bb.on(CLOUD_PR + "/decline", "{\"state\":\"DECLINED\"}");
        bb.on("/repositories/team/web/pullrequests", "{\"values\":[]}");
        bb.on("/repositories/team/web", "{\"mainbranch\":{\"name\":\"main\"}}");
        bb.on("/repositories/team/web/refs/branches/feature%2Falt", r -> "DELETE".equals(r.method())
                ? new StubServer.Reply(204, "", Map.of()) : StubServer.Reply.json("{\"target\":{\"hash\":\"beef\"}}"));
        String out = new PrDeclineTools(env("cloud", Map.of()))
                .decline("team/web#5", null, true, null, null, null, null, null);
        assertThat(out).contains("team/web#5: abgelehnt, Branch feature/alt gelöscht (letzter Commit beef)");
        assertThat(bb.last(CLOUD_PR + "/decline").method()).isEqualTo("POST");
        assertThat(bb.last("/repositories/team/web/refs/branches/feature%2Falt").method()).isEqualTo("DELETE");
    }
}
