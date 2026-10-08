package systems.grebe.devtools.mcp.server;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import systems.grebe.devtools.mcp.config.ServerSettings;
import systems.grebe.devtools.mcp.config.SettingsStore;

import static org.assertj.core.api.Assertions.assertThat;

/** Der Zugriffstoken-Schutz folgt dem konfigurierten MCP-Endpunkt und trifft nur ihn und seine Unterpfade. */
class BearerTokenFilterTest {

    @TempDir
    Path home;

    private int status(BearerTokenFilter filter, String uri, String authorization) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        req.setRequestURI(uri);
        if (authorization != null) {
            req.addHeader("Authorization", authorization);
        }
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, res, chain);
        return chain.getRequest() != null ? 200 : res.getStatus();
    }

    private BearerTokenFilter filter(String endpoint) {
        SettingsStore store = new SettingsStore(home);
        store.saveServer(new ServerSettings(ServerSettings.DEFAULT_PORT, "geheim", true, false));
        return new BearerTokenFilter(store, endpoint);
    }

    @Test
    void protectsTheEndpointAndEverythingBelowButNotSimilarPaths() throws Exception {
        BearerTokenFilter f = filter("/mcp");
        assertThat(status(f, "/mcp", null)).isEqualTo(401);
        assertThat(status(f, "/mcp/channel/events", null)).isEqualTo(401);
        assertThat(status(f, "/mcp", "Bearer geheim")).isEqualTo(200);
        assertThat(status(f, "/mcpfoo", null)).isEqualTo(200); // gehört nicht zum Endpunkt
        assertThat(status(f, "/graphql", null)).isEqualTo(200); // prüft ihre eigenen Tokens
    }

    @Test
    void followsAChangedEndpointProperty() throws Exception {
        BearerTokenFilter f = filter("/api/mcp");
        assertThat(status(f, "/api/mcp", null)).isEqualTo(401); // früher ungeschützt, weil nur "/mcp" geprüft wurde
        assertThat(status(f, "/api/mcp/channel/events", "Bearer falsch")).isEqualTo(401);
        assertThat(status(f, "/api/mcp", "Bearer geheim")).isEqualTo(200);
        assertThat(status(f, "/mcp", null)).isEqualTo(200);
    }
}
