package systems.grebe.devtools.mcp.server;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class LocalOnlyFilterTest {

    private final LocalOnlyFilter filter = new LocalOnlyFilter();

    private int status(String remote, String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRemoteAddr(remote);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response.getStatus();
    }

    @Test
    void localRequestsPass() throws Exception {
        assertThat(status("127.0.0.1", "/mcp")).isEqualTo(200);
        assertThat(status("0:0:0:0:0:0:0:1", "/mcp/channel/events")).isEqualTo(200);
    }

    @Test
    void remoteOnlyReachesTheBackend() throws Exception {
        assertThat(status("192.168.1.30", "/graphql")).isEqualTo(200);
        assertThat(status("192.168.1.30", "/blobs/abc")).isEqualTo(200);
        assertThat(status("192.168.1.30", "/mcp")).isEqualTo(403);
        assertThat(status("192.168.1.30", "/graphqlx")).isEqualTo(403);
        assertThat(status("192.168.1.30", "/")).isEqualTo(403);
    }
}
