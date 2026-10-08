package systems.grebe.devtools.mcp.modules.ticket;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import systems.grebe.devtools.mcp.modules.ticket.spi.HttpJson;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Gemeinsame HTTP-Hilfen für Provider und Clients (auch für Plugins öffentlich). */
class HttpJsonTest {

    @Test
    void allClientsShareOneJdkHttpClient() throws IOException {
        assertThat(HttpJson.sharedClient()).isSameAs(HttpJson.sharedClient());
        assertThat(HttpJson.sharedClient().connectTimeout()).contains(Duration.ofSeconds(10));
    }

    @Test
    void basicAuthEncodesUserAndSecret() {
        assertThat(HttpJson.basicAuth("felix", "geheim")).isEqualTo("Basic ZmVsaXg6Z2VoZWlt");
        assertThat(HttpJson.basicAuth("token", "")).isEqualTo("Basic dG9rZW46");
        assertThat(HttpJson.basicAuth("ä", null)).isEqualTo("Basic w6Q6");
    }

    @Test
    void abbreviateCutsLongTextsAndToleratesNull() {
        assertThat(HttpJson.abbreviate(null)).isEmpty();
        assertThat(HttpJson.abbreviate("  kurz  ")).isEqualTo("kurz");
        assertThat(HttpJson.abbreviate("x".repeat(400))).hasSize(301).endsWith("…");
    }

    @Test
    void errorMessageReadsTheMsgFieldOfSonarAndMavenStyleServers() throws IOException {
        try (StubServer server = new StubServer()) {
            server.on("/boom", r -> new StubServer.Reply(500, "{\"msg\":\"Datenbank gesperrt\"}", Map.of()));
            HttpJson http = new HttpJson("Demo", server.url(), Map.of(), Duration.ofSeconds(5));
            assertThatThrownBy(() -> http.get("/boom")).hasMessageContaining("Demo-Fehler 500")
                    .hasMessageContaining("Datenbank gesperrt");
        }
    }
}
