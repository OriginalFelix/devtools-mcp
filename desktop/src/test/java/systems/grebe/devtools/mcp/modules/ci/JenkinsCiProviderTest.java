package systems.grebe.devtools.mcp.modules.ci;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import systems.grebe.devtools.mcp.core.ModuleConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Jenkins-Provider gegen einen Stub der Remote-API. */
class JenkinsCiProviderTest {

    StubServer jk;
    CiModule module = new CiModule(new CiProviders());

    @BeforeEach
    void start() throws IOException {
        jk = new StubServer();
    }

    @AfterEach
    void stop() {
        jk.close();
    }

    private CiEnvironment env(Map<String, String> extra) {
        Map<String, String> v = new HashMap<>(Map.of("jenkins.enabled", "true", "jenkins.baseUrl", jk.url(),
                "jenkins.user", "felix", "jenkins.token", "t0k"));
        v.putAll(extra);
        return new CiEnvironment(module.providers(), ModuleConfig.of(module.configSchema(), v));
    }

    private static String build(int n, String result, boolean building, String branch) {
        return """
                {"number":%d,"result":%s,"building":%s,"timestamp":1760000000000,"duration":95000,
                 "url":"http://jenkins/job/team/job/app/%d/","displayName":"#%d",
                 "actions":[{"causes":[{"shortDescription":"Started by user Felix"}]},{},
                  {"lastBuiltRevision":{"SHA1":"abcdef1234567","branch":[{"name":"refs/remotes/origin/%s"}]}}]}"""
                .formatted(n, result == null ? "null" : "\"" + result + "\"", building, n, n, branch);
    }

    @Test
    void listsBuildsOfJobFilteredByBranchAndStatus() {
        jk.on("/job/team/job/app/api/json", r -> StubServer.Reply.json(r.decodedQuery().contains("builds[")
                ? "{\"builds\":[" + build(3, null, true, "main") + "," + build(2, "FAILURE", false, "feature/x") + ","
                        + build(1, "SUCCESS", false, "main") + "]}"
                : "{\"_class\":\"org.jenkinsci.plugins.workflow.job.WorkflowJob\",\"buildable\":true}"));
        CiTools tools = new CiTools(env(Map.of()));

        String out = tools.list(null, null, null, null, null, "team/app", null);
        assertThat(out).contains("3 Build(s) in team/app (jenkins)", "- team/app#3  [running (BUILDING)]  main  abcdef12",
                "- team/app#2  [failed (FAILURE)]  feature/x", "Started by user Felix", "1m 35s");
        assertThat(jk.last("/job/team/job/app/api/json").headers().get("authorization"))
                .isEqualTo("Basic ZmVsaXg6dDBr");

        assertThat(tools.list(null, "main", null, null, null, "team/app", null))
                .contains("2 Build(s)", "team/app#3", "team/app#1").doesNotContain("team/app#2");
        assertThat(tools.list("failed", null, null, null, null, "team/app", null))
                .contains("1 Build(s)", "team/app#2");
    }

    @Test
    void multibranchListsLastBuildPerBranchAndUsesBranchJob() {
        jk.on("/job/team/job/app/api/json", """
                {"_class":"org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject","jobs":[
                 {"name":"main","lastBuild":%s},
                 {"name":"feature%%2Fx","lastBuild":%s}]}""".formatted(build(5, "SUCCESS", false, "main"),
                build(9, "UNSTABLE", false, "feature/x")));
        jk.on("/job/team/job/app/job/feature%252Fx/api/json", "{\"builds\":[" + build(9, "UNSTABLE", false, "x") + "]}");
        CiTools tools = new CiTools(env(Map.of()));

        assertThat(tools.list(null, null, null, null, null, "team/app", null))
                .contains("team/app/main#5  [success]  main", "team/app/feature%2Fx#9  [unstable]  feature/x");
        // mit Branch: der Branch-Job, Branch aus dem Job statt aus der Revision
        assertThat(tools.list(null, "feature/x", null, null, null, "team/app", null))
                .contains("- team/app/feature%2Fx#9  [unstable]  feature/x");
    }

    @Test
    void getShowsStagesParametersAndChanges() {
        jk.on("/job/team/job/app/7/api/json", """
                {"number":7,"result":"FAILURE","building":false,"timestamp":1760000000000,"duration":61000,
                 "url":"http://jenkins/job/team/job/app/7/","displayName":"#7","description":"Nightly",
                 "actions":[{"parameters":[{"name":"ENV","value":"test"},{"name":"API_TOKEN","value":"geheim"}]}],
                 "changeSets":[{"items":[{"msg":"Fix NPE","author":{"fullName":"Anna"}}]}]}""");
        jk.on("/job/team/job/app/7/wfapi/describe", """
                {"stages":[{"id":"6","name":"Build","status":"SUCCESS","durationMillis":20000},
                 {"id":"9","name":"Test","status":"FAILED","durationMillis":41000,"error":{"message":"script returned exit code 1"}}]}""");
        jk.on("/job/team/api/json", "{\"_class\":\"com.cloudbees.hudson.plugins.folder.Folder\"}");

        String out = new CiTools(env(Map.of())).get("team/app#7", null, null, null);
        assertThat(out).contains("team/app#7: failed (FAILURE)", "Parameter:  ENV=test, API_TOKEN=****",
                "Änderungen: Fix NPE (Anna)", "Beschreibung: Nightly", "## Jobs (2, 1 fehlgeschlagen)",
                "- Build: success, 20s", "- Test: failed, 41s – script returned exit code 1").doesNotContain("geheim");
    }

    @Test
    void logReadsConsoleTextFromBuildUrl() {
        jk.on("/job/team/job/app/job/feature%252Fx/4/consoleText", r -> new StubServer.Reply(200,
                "Started\n[Pipeline] sh\nBUILD FAILED\nFinished: FAILURE", Map.of()));
        String out = new CiTools(env(Map.of())).log(jk.url() + "/job/team/job/app/job/feature%252Fx/4/", null, 2,
                null, null, null, null);
        assertThat(out).contains("team/app/feature%2Fx#4 Konsolen-Log – 4 Zeile(n)", "/job/team/job/app/job/feature%252Fx/4/console",
                "BUILD FAILED\nFinished: FAILURE").doesNotContain("[Pipeline]");
        assertThat(new CiTools(env(Map.of())).log("team/app/feature%2Fx#4", null, null, "failed", null, null, null))
                .contains("1 Treffer für 'failed'", "3: BUILD FAILED");
    }

    @Test
    void startUsesBranchParameterAndReportsBuildFromQueue() {
        jk.on("/job/tool/api/json", """
                {"_class":"hudson.model.FreeStyleProject","buildable":true,
                 "property":[{},{"parameterDefinitions":[{"name":"BRANCH"},{"name":"ENV"}]}]}""");
        jk.on("/job/tool/buildWithParameters", r -> new StubServer.Reply(201, "",
                Map.of("Location", jk.url() + "/queue/item/77/")));
        jk.on("/queue/item/77/api/json", "{\"executable\":{\"number\":12,\"url\":\"http://jenkins/job/tool/12/\"}}");
        CiStartTools tools = new CiStartTools(env(Map.of()));

        String out = tools.start("main", null, new LinkedHashMap<>(Map.of("ENV", "test")), null, "tool", null);
        assertThat(out).contains("tool#12: Build gestartet mit ", "ENV=test", "BRANCH=main", "http://jenkins/job/tool/12/");
        assertThat(jk.last("/job/tool/buildWithParameters").decodedQuery()).contains("ENV=test", "BRANCH=main");
        assertThat(jk.last("/job/tool/buildWithParameters").method()).isEqualTo("POST");

        assertThatThrownBy(() -> tools.start(null, null, Map.of("NOPE", "1"), null, "tool", null))
                .hasMessageContaining("kennt den Parameter 'NOPE' nicht");
    }

    @Test
    void startWithoutParametersAndInMultibranchProject() {
        jk.on("/job/plain/api/json", "{\"_class\":\"hudson.model.FreeStyleProject\",\"buildable\":true}");
        jk.on("/job/plain/build", r -> new StubServer.Reply(201, "", Map.of("Location", jk.url() + "/queue/item/5/")));
        CiStartTools tools = new CiStartTools(env(Map.of()));
        assertThat(tools.start(null, null, null, null, "plain", null))
                .contains("plain: gestartet – in der Warteschlange (Queue-Item 5)");
        assertThatThrownBy(() -> tools.start("main", null, null, null, "plain", null))
                .hasMessageContaining("keinen Branch-Parameter");

        jk.on("/job/mb/api/json", "{\"_class\":\"org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject\","
                + "\"jobs\":[]}");
        jk.on("/job/mb/job/feature%252Fx/api/json", "{\"_class\":\"WorkflowJob\",\"buildable\":true}");
        jk.on("/job/mb/job/feature%252Fx/build", r -> new StubServer.Reply(201, "", Map.of()));
        assertThat(tools.start("feature/x", null, null, null, "mb", null)).contains("mb/feature%2Fx: gestartet");
        assertThatThrownBy(() -> tools.start(null, null, null, null, "mb", null)).hasMessageContaining("Multibranch");
    }

    @Test
    void cancelStopsOnlyRunningBuildsAndRetryReusesParameters() {
        jk.on("/job/app/3/api/json", "{\"number\":3,\"building\":true,\"url\":\"http://jenkins/job/app/3/\","
                + "\"actions\":[{\"parameters\":[{\"name\":\"ENV\",\"value\":\"prod\"}]}]}");
        jk.on("/job/app/3/stop", r -> new StubServer.Reply(200, "<html>ok</html>", Map.of()));
        jk.on("/job/app/2/api/json", "{\"number\":2,\"building\":false,\"result\":\"SUCCESS\",\"url\":\"u\"}");
        jk.on("/job/app/buildWithParameters", r -> new StubServer.Reply(201, "", Map.of()));
        CiEnvironment env = env(Map.of("writeProjects", "jenkins:app"));

        assertThat(new CiCancelTools(env).cancel("app#3", null, null, null)).contains("app#3: Abbruch angefordert.");
        assertThat(jk.last("/job/app/3/stop").method()).isEqualTo("POST");
        assertThat(new CiCancelTools(env).cancel("app#2", null, null, null)).contains("läuft nicht mehr (SUCCESS)");

        assertThat(new CiRetryTools(env).retry("app#3", null, null, null, null))
                .contains("neu gestartet mit denselben Parametern", "Jenkins wiederholt immer den ganzen Build");
        assertThat(jk.last("/job/app/buildWithParameters").decodedQuery()).isEqualTo("ENV=prod");

        assertThatThrownBy(() -> new CiCancelTools(env).cancel("other#1", null, null, null))
                .hasMessageContaining("nicht freigegeben");
    }

    @Test
    void writeNeedsTokenAndShortKeyNeedsProject() {
        CiEnvironment anon = env(Map.of("jenkins.token", ""));
        assertThatThrownBy(() -> new CiCancelTools(anon).cancel("app#3", null, null, null))
                .hasMessageContaining("API-Token");
        assertThatThrownBy(() -> new CiTools(env(Map.of())).get("42", null, null, null))
                .hasMessageContaining("Projekt");
    }

    @Test
    void workflowsListsJobsWithState() {
        jk.on("/api/json", r -> new StubServer.Reply(200, """
                {"_class":"hudson.model.Hudson","jobs":[
                 {"name":"team","_class":"com.cloudbees.hudson.plugins.folder.Folder","url":"http://jenkins/job/team/"},
                 {"name":"nightly","_class":"hudson.model.FreeStyleProject","color":"red_anime","url":"http://jenkins/job/nightly/"}]}""",
                Map.of("X-Jenkins", "2.500")));
        jk.on("/me/api/json", "{\"id\":\"felix\"}");
        CiTools tools = new CiTools(env(Map.of()));
        assertThat(tools.workflows(null, null, null)).contains("2 Workflow(s)/Job(s) (jenkins)", "- team  [Ordner]",
                "- nightly  [failed, läuft]");
        assertThat(tools.providers()).contains("jenkins (Jenkins, " + jk.url() + "): verfügbar, Jenkins 2.500, "
                + "angemeldet als felix");
    }
}
