package systems.grebe.devtools.mcp.remote;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import systems.grebe.devtools.mcp.config.SettingsStore;
import systems.grebe.devtools.mcp.modules.scripts.ScriptCache;
import systems.grebe.devtools.mcp.modules.scripts.ScriptViews;

import static org.assertj.core.api.Assertions.assertThat;

/** Skript-Cache für den Team-Server: verschlüsselt, an die Server-Adresse gebunden, eingebettet ohne Datei. */
class ScriptCacheFileTest {

    @TempDir
    Path home;

    private static ScriptCache.Entry entry(String name) {
        return new ScriptCache.Entry(new ScriptViews.Summary(name, "Beschreibung " + name, ScriptViews.Scope.GLOBAL, 3,
                Instant.parse("2026-10-05T10:00:00Z"), "anna@example.com"), "module { description 'geheim-url' }");
    }

    @Test
    void roundTripIsEncryptedAndBoundToTheServer() throws Exception {
        AtomicReference<String> url = new AtomicReference<>("https://team.example.com");
        ScriptCacheFile cache = new ScriptCacheFile(new SettingsStore(home), url::get);
        cache.store(List.of(entry("jira"), entry("deploy")));

        Path file = home.resolve("scripts-cache.json");
        assertThat(Files.readString(file)).doesNotContain("geheim-url", "jira");
        assertThat(cache.load()).containsExactly(entry("jira"), entry("deploy"));

        url.set("https://anderer.example.com");
        assertThat(cache.load()).isEmpty();
    }

    @Test
    void embeddedBackendNeedsNoCache() {
        ScriptCacheFile cache = new ScriptCacheFile(new SettingsStore(home), () -> null);
        cache.store(List.of(entry("jira")));
        assertThat(home.resolve("scripts-cache.json")).doesNotExist();
        assertThat(cache.load()).isEmpty();
    }
}
