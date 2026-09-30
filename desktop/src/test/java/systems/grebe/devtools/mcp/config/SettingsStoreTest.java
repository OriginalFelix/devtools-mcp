package systems.grebe.devtools.mcp.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class SettingsStoreTest {

    @TempDir
    Path home;

    @Test
    void roundTripWithEncryptedSecrets() throws Exception {
        SettingsStore store = new SettingsStore(home);
        store.saveServer(new ServerSettings(9001, "tok-123", false, true));
        store.saveModule("sonar", new ModuleSettings(true, Set.of("sonar_rule"),
                Map.of("baseUrl", "https://sonar.example", "token", "squ_geheim")), Set.of("token"));

        String json = Files.readString(home.resolve("settings.json"));
        assertThat(json).contains("https://sonar.example").doesNotContain("squ_geheim").doesNotContain("tok-123")
                .contains("enc:v1:");

        SettingsStore reloaded = new SettingsStore(home);
        assertThat(reloaded.server()).isEqualTo(new ServerSettings(9001, "tok-123", false, true));
        ModuleSettings sonar = reloaded.module("sonar").orElseThrow();
        assertThat(sonar.enabled()).isTrue();
        assertThat(sonar.disabledTools()).containsExactly("sonar_rule");
        assertThat(sonar.values()).containsEntry("token", "squ_geheim").containsEntry("baseUrl", "https://sonar.example");
    }

    @Test
    void brokenFileFallsBackToDefaults() throws Exception {
        Files.writeString(home.resolve("settings.json"), "{ kaputt");
        SettingsStore store = new SettingsStore(home);
        assertThat(store.server()).isEqualTo(ServerSettings.defaults());
        assertThat(home.resolve("settings.broken.json")).exists();
    }
}
