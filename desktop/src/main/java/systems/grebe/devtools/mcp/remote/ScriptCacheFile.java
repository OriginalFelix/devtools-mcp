package systems.grebe.devtools.mcp.remote;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.config.SettingsStore;
import systems.grebe.devtools.mcp.modules.scripts.ScriptCache;
import tools.jackson.databind.json.JsonMapper;
import systems.grebe.devtools.mcp.config.AtomicFiles;

/**
 * Skripte des Team-Servers als verschlüsselte Datei {@code scripts-cache.json} im Einstellungsordner – wie
 * {@code team-cache.json} für die Einstellungen. Gilt nur für den Server und Benutzer, von dem der Stand stammt; das
 * eingebettete Backend braucht keinen Cache (es ist immer da).
 */
@Component
public class ScriptCacheFile implements ScriptCache {

    private static final Logger LOG = LoggerFactory.getLogger(ScriptCacheFile.class);
    private static final JsonMapper JSON = JsonMapper.shared();

    /**
     * Inhalt der Datei.
     *
     * @param url Server und Benutzer ({@link BackendConnection#cacheKey()})
     */
    record Stored(String url, List<Entry> scripts) {
    }

    private final SettingsStore store;
    private final Path file;
    /** Team-Server und Benutzer; {@code null} = eingebettetes Backend oder niemand angemeldet (kein Cache). */
    private final Supplier<String> teamUrl;

    @Autowired
    public ScriptCacheFile(SettingsStore store, BackendConnection backend) {
        this(store, backend::cacheKey);
    }

    ScriptCacheFile(SettingsStore store, Supplier<String> teamUrl) {
        this.store = store;
        this.file = store.dir().resolve("scripts-cache.json");
        this.teamUrl = teamUrl;
    }

    @Override
    public void store(List<Entry> scripts) {
        String url = teamUrl.get();
        if (url == null) {
            return;
        }
        try {
            AtomicFiles.writeString(file, store.encrypt(JSON.writeValueAsString(new Stored(url, List.copyOf(scripts)))));
        } catch (IOException | RuntimeException e) {
            LOG.warn("Skript-Cache {} nicht schreibbar", file, e);
        }
    }

    @Override
    public List<Entry> load() {
        String url = teamUrl.get();
        if (url == null || !Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            Stored s = JSON.readValue(store.decrypt(Files.readString(file)), Stored.class);
            return url.equals(s.url()) && s.scripts() != null ? s.scripts() : List.of();
        } catch (IOException | RuntimeException e) {
            LOG.warn("Skript-Cache {} nicht lesbar – wird beim nächsten Abgleich neu geschrieben", file, e);
            return List.of();
        }
    }
}
