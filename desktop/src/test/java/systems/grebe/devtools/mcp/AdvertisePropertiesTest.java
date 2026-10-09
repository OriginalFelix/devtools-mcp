package systems.grebe.devtools.mcp;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import systems.grebe.devtools.mcp.config.SettingsStore;

import static org.assertj.core.api.Assertions.assertThat;

class AdvertisePropertiesTest {

    @TempDir
    Path home;

    @Test
    void localOnlyWithoutAdvertise() {
        Map<String, Object> p = DevToolsMcpApplication.properties(new SettingsStore(home), false, Map.of());
        assertThat(p).doesNotContainKey("server.address").doesNotContainKey("devtools.discovery.advertise");
    }

    @Test
    void advertiseOpensTheEmbeddedBackend() {
        SettingsStore store = new SettingsStore(home);
        store.saveTeam(store.team().withAdvertise(true));
        Map<String, Object> p = DevToolsMcpApplication.properties(store, false, Map.of());
        assertThat(p).containsEntry("server.address", "0.0.0.0").containsEntry("devtools.discovery.advertise", true)
                .containsEntry("devtools.discovery.kind", "desktop");
    }

    @Test
    void noAdvertiseWithTeamServer() {
        SettingsStore store = new SettingsStore(home);
        store.saveTeam(store.team().withAdvertise(true).withServer("https://devtools.example.com"));
        Map<String, Object> p = DevToolsMcpApplication.properties(store, false, Map.of());
        assertThat(p).doesNotContainKey("server.address").doesNotContainKey("devtools.discovery.advertise");
    }
}
