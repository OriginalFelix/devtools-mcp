package systems.grebe.devtools.mcp.modules.ticket;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import systems.grebe.devtools.mcp.core.ModuleConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ticket_link/ticket_unlink je Provider: Methode, Pfad und Body so, wie die jeweilige API sie erwartet. */
class TicketLinkTest {

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

    private TicketLinkTools tools(String... kv) {
        Map<String, String> v = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            v.put(kv[i], kv[i + 1]);
        }
        return new TicketLinkTools(new TicketEnvironment(module.providers(), ModuleConfig.of(module.configSchema(), v)));
    }

    private static StubServer.Reply noContent() {
        return new StubServer.Reply(204, "", Map.of());
    }

    @Test
    void jiraLinksByDirectionAndUnlinksById() {
        stub.on("/rest/api/2/issueLinkType", """
                {"issueLinkTypes":[{"name":"Blocks","inward":"is blocked by","outward":"blocks"},
                 {"name":"Relates","inward":"relates to","outward":"relates to"}]}""");
        stub.on("/rest/api/2/issueLink", r -> new StubServer.Reply(201, "", Map.of()));
        stub.on("/rest/api/2/issueLink/10001", r -> noContent());
        stub.on("/rest/api/2/issue/ABC-1", """
                {"fields":{"issuelinks":[
                 {"id":"10001","type":{"name":"Blocks","inward":"is blocked by","outward":"blocks"},"outwardIssue":{"key":"ABC-2"}},
                 {"id":"10002","type":{"name":"Relates","inward":"relates to","outward":"relates to"},"inwardIssue":{"key":"ABC-2"}},
                 {"id":"10003","type":{"name":"Relates","inward":"relates to","outward":"relates to"},"inwardIssue":{"key":"ABC-3"}}]}}""");
        TicketLinkTools t = tools("jira.enabled", "true", "jira.baseUrl", stub.url(), "jira.token", "pat");

        assertThat(t.link("ABC-1", null, null, null, null)).contains("[Blocks:outward]  ABC-1 blocks target",
                "[Blocks:inward]  ABC-1 is blocked by target", "[Relates:outward]  ABC-1 relates to target")
                .doesNotContain("Relates:inward");

        assertThat(t.link("ABC-1", "is_blocked_by", "ABC-2", null, null)).startsWith("ABC-1: verknüpft: ABC-1 is blocked by ABC-2");
        // Jira: das inwardIssue trägt die outward-Beschreibung – „ABC-2 blocks ABC-1“
        assertThat(stub.last("/rest/api/2/issueLink").body()).isEqualTo("{\"type\":{\"name\":\"Blocks\"},"
                + "\"inwardIssue\":{\"key\":\"ABC-2\"},\"outwardIssue\":{\"key\":\"ABC-1\"}}");
        t.link("ABC-1", "blocks", "ABC-2", null, null);
        assertThat(stub.last("/rest/api/2/issueLink").body()).contains("\"inwardIssue\":{\"key\":\"ABC-1\"}");
        assertThatThrownBy(() -> t.link("ABC-1", "dupliziert", "ABC-2", null, null))
                .hasMessageContaining("nicht eindeutig oder unbekannt").hasMessageContaining("blocks [Blocks:outward]");
        assertThatThrownBy(() -> t.link("ABC-1", "blocks", "abc-1", null, null)).hasMessageContaining("mit sich selbst");

        assertThatThrownBy(() -> t.unlink("ABC-1", "ABC-2", null, null, null))
                .hasMessageContaining("mehrere Verknüpfungen (blocks, relates to)");
        assertThatThrownBy(() -> t.unlink("ABC-1", "ABC-9", null, null, null)).hasMessageContaining("keine Verknüpfung");
        assertThat(t.unlink("ABC-1", "ABC-2", "blocks", null, null)).startsWith("ABC-1: Verknüpfung entfernt: ABC-1 blocks ABC-2");
        assertThat(stub.last("/rest/api/2/issueLink/10001").method()).isEqualTo("DELETE");
    }

    @Test
    void writeProjectsAreCheckedForBothTickets() {
        TicketLinkTools t = tools("jira.enabled", "true", "jira.baseUrl", stub.url(), "jira.token", "pat", "writeProjects", "ABC");
        assertThatThrownBy(() -> t.link("ABC-1", "blocks", "DEF-2", null, null)).hasMessageContaining("'DEF' (jira) ist nicht freigegeben");
        assertThat(stub.requests).isEmpty();
    }

    @Test
    void githubSubIssuesAndDependenciesUseDatabaseIds() {
        stub.on("/repos/octo/app/issues/1", "{\"id\":1001,\"number\":1,\"html_url\":\"https://github.com/octo/app/issues/1\"}");
        stub.on("/repos/octo/app/issues/2", "{\"id\":1002,\"number\":2}");
        stub.on("/repos/octo/app/issues/1/sub_issues", "{}");
        stub.on("/repos/octo/app/issues/2/sub_issues", "{}");
        stub.on("/repos/octo/app/issues/2/dependencies/blocked_by", "{}");
        stub.on("/repos/octo/app/issues/1/sub_issue", "{}");
        stub.on("/graphql", """
                {"data":{"repository":{"issueOrPullRequest":{"__typename":"Issue","parent":null,
                 "subIssues":{"nodes":[{"number":2,"title":"Kind","state":"OPEN","url":"u","repository":{"nameWithOwner":"Octo/App"}}]},
                 "closedByPullRequestsReferences":{"nodes":[]}}}}}""");
        stub.on("/repos/octo/app/issues/1/dependencies/blocking", """
                [{"number":2,"title":"Kind","state":"open","repository_url":"x/repos/octo/app","html_url":"h"}]""");
        TicketLinkTools t = tools("github.enabled", "true", "github.baseUrl", stub.url(), "github.token", "ghp",
                "github.defaultProject", "octo/app");

        assertThat(t.link("#1", "sub-issue", "#2", null, null)).startsWith("octo/app#1: verknüpft: octo/app#1 Sub-Issue octo/app#2");
        assertThat(stub.last("/repos/octo/app/issues/1/sub_issues").body()).isEqualTo("{\"sub_issue_id\":1002}");
        t.link("#1", "Parent", "#2", null, null);
        assertThat(stub.last("/repos/octo/app/issues/2/sub_issues").body()).isEqualTo("{\"sub_issue_id\":1001}");
        t.link("#1", "blocks", "#2", null, null);
        assertThat(stub.last("/repos/octo/app/issues/2/dependencies/blocked_by").body()).isEqualTo("{\"issue_id\":1001}");

        assertThatThrownBy(() -> t.unlink("#1", "#2", null, null, null)).hasMessageContaining("mehrere Verknüpfungen (Sub-Issue, blocks)");
        assertThat(t.unlink("#1", "#2", "sub issue", null, null)).startsWith("octo/app#1: Verknüpfung entfernt");
        StubServer.Request del = stub.last("/repos/octo/app/issues/1/sub_issue");
        assertThat(del.method()).isEqualTo("DELETE");
        assertThat(del.body()).isEqualTo("{\"sub_issue_id\":1002}");
    }

    @Test
    void gitlabLinksWithTargetProjectAndUnlinksByLinkId() {
        stub.on("/api/v4/projects/grp%2Fapp/issues/1/links", r -> StubServer.Reply.json("POST".equals(r.method()) ? "{}" : """
                [{"iid":7,"issue_link_id":55,"link_type":"blocks","references":{"full":"grp/other#7"}}]"""));
        stub.on("/api/v4/projects/grp%2Fapp/issues/1/links/55", "{}");
        TicketLinkTools t = tools("gitlab.enabled", "true", "gitlab.baseUrl", stub.url(), "gitlab.token", "glpat",
                "gitlab.defaultProject", "grp/app");

        assertThat(t.link("#1", "relates to", "grp/other#7", null, null)).startsWith("grp/app#1: verknüpft: grp/app#1 relates to grp/other#7");
        assertThat(stub.last("/api/v4/projects/grp%2Fapp/issues/1/links").body())
                .isEqualTo("{\"target_project_id\":\"grp/other\",\"target_issue_iid\":7,\"link_type\":\"relates_to\"}");
        assertThatThrownBy(() -> t.unlink("#1", "grp/other#7", "relates to", null, null))
                .hasMessageContaining("keine Verknüpfung 'relates to' – vorhanden: blocks");
        assertThat(t.unlink("#1", "grp/other#7", null, null, null)).contains("Verknüpfung entfernt: grp/app#1 blocks grp/other#7");
        assertThat(stub.last("/api/v4/projects/grp%2Fapp/issues/1/links/55").method()).isEqualTo("DELETE");
    }

    @Test
    void youtrackLinksPerCommandAndUnlinksPerRest() {
        stub.on("/api/issueLinkTypes", """
                [{"name":"Subtask","sourceToTarget":"parent for","targetToSource":"subtask of","directed":true},
                 {"name":"Relates","sourceToTarget":"relates to","targetToSource":"relates to","directed":false}]""");
        stub.on("/api/commands", "{}");
        stub.on("/api/issues/ABC-1/links", """
                [{"id":"91-1s","direction":"INWARD","linkType":{"name":"Subtask","sourceToTarget":"parent for","targetToSource":"subtask of"},
                  "issues":[{"id":"2-17","idReadable":"ABC-2"}]}]""");
        stub.on("/api/issues/ABC-1/links/91-1s/issues/2-17", r -> noContent());
        TicketLinkTools t = tools("youtrack.enabled", "true", "youtrack.baseUrl", stub.url() + "/", "youtrack.token", "perm:x");

        assertThat(t.link("ABC-1", "subtask of", "ABC-2", null, null)).startsWith("ABC-1: verknüpft: ABC-1 subtask of ABC-2");
        assertThat(stub.last("/api/commands").body()).isEqualTo("{\"query\":\"subtask of ABC-2\",\"issues\":[{\"idReadable\":\"ABC-1\"}]}");
        assertThat(t.unlink("ABC-1", "ABC-2", null, null, null)).startsWith("ABC-1: Verknüpfung entfernt: ABC-1 subtask of ABC-2");
        assertThat(stub.last("/api/issues/ABC-1/links/91-1s/issues/2-17").method()).isEqualTo("DELETE");
    }

    @Test
    void openProjectRelationsAndParent() {
        stub.on("/api/v3/work_packages/1", r -> StubServer.Reply.json("""
                {"id":1,"lockVersion":3,"_links":{"parent":{"href":"/api/v3/work_packages/2"},"children":[],
                 "project":{"href":"/api/v3/projects/demo"}},"_embedded":{"project":{"identifier":"demo"}}}"""));
        stub.on("/api/v3/work_packages/2", "{\"id\":2,\"lockVersion\":1,\"_embedded\":{\"project\":{\"identifier\":\"demo\"}}}");
        stub.on("/api/v3/work_packages/1/relations", r -> StubServer.Reply.json("POST".equals(r.method()) ? "{}" : """
                {"_embedded":{"elements":[{"id":40,"type":"blocks","reverseType":"blocked",
                  "_links":{"from":{"href":"/api/v3/work_packages/2"},"to":{"href":"/api/v3/work_packages/1"}}}]}}"""));
        stub.on("/api/v3/relations/40", r -> noContent());
        TicketLinkTools t = tools("openproject.enabled", "true", "openproject.baseUrl", stub.url() + "/api/v3",
                "openproject.token", "k");

        assertThat(t.link("#1", "follows", "#2", null, null)).startsWith("#1: verknüpft: #1 follows #2");
        assertThat(stub.last("/api/v3/work_packages/1/relations").body())
                .isEqualTo("{\"type\":\"follows\",\"_links\":{\"to\":{\"href\":\"/api/v3/work_packages/2\"}}}");
        t.link("#1", "Unteraufgabe", "#2", null, null);
        assertThat(stub.last("/api/v3/work_packages/2").body())
                .isEqualTo("{\"lockVersion\":1,\"_links\":{\"parent\":{\"href\":\"/api/v3/work_packages/1\"}}}");

        assertThatThrownBy(() -> t.unlink("#1", "#2", null, null, null)).hasMessageContaining("mehrere Verknüpfungen (Parent, blocked by)");
        assertThat(t.unlink("#1", "#2", "blocked by", null, null)).startsWith("#1: Verknüpfung entfernt: #1 blocked by #2");
        assertThat(stub.last("/api/v3/relations/40").method()).isEqualTo("DELETE");
        t.unlink("#1", "#2", "parent", null, null);
        assertThat(stub.last("/api/v3/work_packages/1").body()).isEqualTo("{\"lockVersion\":3,\"_links\":{\"parent\":{\"href\":null}}}");
    }
}
