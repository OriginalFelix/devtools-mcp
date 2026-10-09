package systems.grebe.devtools.mcp.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

class BackendDiscoveryTest {

    @Test
    void urlFromSenderWhenNoPublicAddress() throws Exception {
        String json = BackendDiscovery.encode(new BackendDiscovery.Announcement("dev-1", BackendDiscovery.KIND_DESKTOP,
                "", "http", 8765));
        BackendDiscovery.Endpoint e = BackendDiscovery.parse(json, InetAddress.getByName("192.168.1.20"));
        assertThat(e).isEqualTo(new BackendDiscovery.Endpoint("http://192.168.1.20:8765", "dev-1",
                BackendDiscovery.KIND_DESKTOP));
    }

    @Test
    void publicAddressWins() throws Exception {
        String json = BackendDiscovery.encode(new BackendDiscovery.Announcement("team", null,
                "https://devtools.example.com/", "http", 8080));
        BackendDiscovery.Endpoint e = BackendDiscovery.parse(json, InetAddress.getByName("10.0.0.5"));
        assertThat(e.url()).isEqualTo("https://devtools.example.com");
        assertThat(e.kind()).isEqualTo(BackendDiscovery.KIND_SERVER);
    }

    @Test
    void foreignPacketsAreIgnored() throws Exception {
        InetAddress sender = InetAddress.getByName("10.0.0.5");
        assertThat(BackendDiscovery.parse("kein json", sender)).isNull();
        assertThat(BackendDiscovery.parse("{\"service\":\"other\",\"port\":80}", sender)).isNull();
        assertThat(BackendDiscovery.parse("{\"service\":\"devtools-backend\"}", sender)).isNull();
        assertThat(BackendDiscovery.parse("{\"service\":\"devtools-backend\",\"url\":\"file:///x\"}", sender))
                .isNull();
    }

    @Test
    void advertiserAnswersSearch() throws Exception {
        try (BackendDiscovery.Advertiser a = BackendDiscovery.Advertiser.start(0,
                () -> new BackendDiscovery.Announcement("test-host", BackendDiscovery.KIND_SERVER, "", "http", 8080))) {
            List<BackendDiscovery.Endpoint> found = BackendDiscovery.search(a.port(), Duration.ofMillis(800));
            assertThat(found).extracting(BackendDiscovery.Endpoint::name).contains("test-host");
            assertThat(found).extracting(BackendDiscovery.Endpoint::url).allMatch(u -> u.endsWith(":8080"));
        }
    }
}
