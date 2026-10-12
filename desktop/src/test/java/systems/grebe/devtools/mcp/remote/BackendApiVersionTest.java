package systems.grebe.devtools.mcp.remote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import systems.grebe.devtools.mcp.api.ApiVersions;

/** Aushandeln der API-Version mit dem Team-Server ({@code GET /api/versions}). */
class BackendApiVersionTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** Backend, das auf {@code /api/versions} mit Status und Rumpf antwortet. */
    private String backend(int status, String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(ApiVersions.VERSIONS_PATH, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            if (status == 302) {
                exchange.getResponseHeaders().add("Location", "/login");
            }
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void versionedBackendIsAddressedUnderTheVersionOfTheApp() throws IOException {
        String url = backend(200, "{\"current\":" + ApiVersions.CURRENT + ",\"versions\":[" + ApiVersions.CURRENT
                + "]}");
        assertThat(BackendConnection.offered(url))
                .isEqualTo(new ApiVersions.Info(ApiVersions.CURRENT, List.of(ApiVersions.CURRENT)));
        assertThat(BackendConnection.negotiate(url)).isEqualTo(ApiVersions.base(ApiVersions.CURRENT));
    }

    @Test
    void backendWithoutVersioningIsVersionZero() throws IOException {
        assertThat(BackendConnection.offered(backend(404, ""))).isNull();
        stop();
        assertThat(BackendConnection.offered(backend(302, ""))).isNull(); // Anmeldeseite der Web-UI
        stop();
        assertThat(BackendConnection.offered(backend(200, "<html>Anmelden</html>"))).isNull();
    }

    @Test
    void backendThatDroppedTheVersionOfTheAppIsRejected() throws IOException {
        int newer = ApiVersions.CURRENT + 1;
        String url = backend(200, "{\"current\":" + newer + ",\"versions\":[" + newer + "]}");
        assertThatThrownBy(() -> BackendConnection.negotiate(url)).isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(BackendConnection.UnreachableException.class)
                .hasMessageContaining("Desktop-App aktualisieren");
    }

    @Test
    void unreachableBackendIsReportedAsSuch() throws IOException {
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        assertThatThrownBy(() -> BackendConnection.offered("http://127.0.0.1:" + port))
                .isInstanceOf(BackendConnection.UnreachableException.class);
    }
}
