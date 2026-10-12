package systems.grebe.devtools.mcp.modules.ticket.jira;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import systems.grebe.devtools.mcp.modules.ticket.spi.ProviderSettings;
import systems.grebe.devtools.mcp.modules.ticket.spi.TicketSystem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Jira Cloud mit OAuth-2.0-Token (3LO): REST über api.atlassian.com/ex/jira/&lt;cloudId&gt;, Links auf die Site. */
class JiraCloudOAuthTest {

    private static final String SITE = "https://firma.atlassian.net";

    private HttpServer api;
    private final List<String> requests = new ArrayList<>();
    private String previous;

    @BeforeEach
    void start() throws IOException {
        api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        api.createContext("/", ex -> {
            requests.add(ex.getRequestURI().getPath() + " " + ex.getRequestHeaders().getFirst("Authorization"));
            String path = ex.getRequestURI().getPath();
            if (path.equals("/oauth/token/accessible-resources")) {
                json(ex, """
                        [{"id":"c-1","url":"https://andere.atlassian.net","name":"andere"},
                         {"id":"c-2","url":"https://firma.atlassian.net/","name":"firma"}]""");
            } else if (path.equals("/ex/jira/c-2/rest/api/2/issue/ABC-1")) {
                json(ex, """
                        {"key":"ABC-1","fields":{"summary":"Login","status":{"name":"Offen",
                         "statusCategory":{"key":"new"}},"issuetype":{"name":"Bug"}}}""");
            } else {
                ex.sendResponseHeaders(404, -1);
                ex.close();
            }
        });
        api.start();
        previous = JiraTicketProvider.atlassianApi;
        JiraTicketProvider.atlassianApi = "http://127.0.0.1:" + api.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        JiraTicketProvider.atlassianApi = previous;
        api.stop(0);
    }

    private static void json(HttpExchange ex, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(b);
        }
    }

    private TicketSystem jira(Map<String, String> values) {
        return new JiraTicketProvider().create(ProviderSettings.of(values));
    }

    @Test
    void oauthTokenOnCloudGoesThroughTheAtlassianApiWithTheSitesCloudId() {
        TicketSystem jira = jira(Map.of("baseUrl", SITE, "user", "me@example.com", "token", "at-3lo",
                "token@oauth", "true"));

        var ticket = jira.ticket("ABC-1", null, 0).ticket();

        assertThat(requests.getFirst()).isEqualTo("/oauth/token/accessible-resources Bearer at-3lo");
        assertThat(requests.subList(1, requests.size())).isNotEmpty()
                .allMatch(r -> r.startsWith("/ex/jira/c-2/rest/") && r.endsWith(" Bearer at-3lo"))
                .contains("/ex/jira/c-2/rest/api/2/issue/ABC-1 Bearer at-3lo");
        assertThat(ticket.url()).isEqualTo(SITE + "/browse/ABC-1"); // Links zeigen auf die Site
    }

    @Test
    void siteWithoutAccessIsExplained() {
        TicketSystem jira = jira(Map.of("baseUrl", "https://fremd.atlassian.net", "token", "at", "token@oauth", "true"));
        assertThatThrownBy(() -> jira.ticket("ABC-1", null, 0)).hasMessageContaining("keinen Zugriff auf https://fremd.atlassian.net")
                .hasMessageContaining("https://firma.atlassian.net");
    }

    @Test
    void handEnteredCloudTokenStaysOnTheSite() {
        TicketSystem jira = jira(Map.of("baseUrl", SITE, "user", "me@example.com", "token", "api-token"));
        assertThatThrownBy(() -> jira.ticket("ABC-1", null, 0)); // Site nicht erreichbar – aber kein Umweg über die Atlassian-API
        assertThat(requests).isEmpty();
    }
}
