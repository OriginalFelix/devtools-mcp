package systems.grebe.devtools.mcp.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import systems.grebe.devtools.mcp.plugin.store.PluginRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Lädt und speichert alle Einstellungen als JSON in {@code ~/.devtools-mcp/settings.json}.
 * Geheimnisse (Felder vom Typ SECRET) werden verschlüsselt abgelegt. Thread-sicher.
 */
public class SettingsStore {

    private static final Logger LOG = LoggerFactory.getLogger(SettingsStore.class);

    private final Path file;
    private final SecretCipher cipher;
    private final JsonMapper json = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    private ServerSettings server = ServerSettings.defaults();
    private PluginSettings plugins = PluginSettings.defaults();
    private final Map<String, ModuleSettings> modules = new LinkedHashMap<>();
    private final Map<String, Set<String>> secretKeys = new LinkedHashMap<>();

    public SettingsStore(Path home) {
        this.file = home.resolve("settings.json");
        this.cipher = new SecretCipher(home.resolve("secret.key"));
        load();
    }

    /** {@code DEVTOOLS_MCP_HOME} oder {@code ~/.devtools-mcp}. */
    public static Path defaultHome() {
        String override = System.getProperty("devtools.mcp.home", System.getenv("DEVTOOLS_MCP_HOME"));
        return override != null && !override.isBlank()
                ? Path.of(override)
                : Path.of(System.getProperty("user.home"), ".devtools-mcp");
    }

    public Path file() {
        return file;
    }

    public synchronized ServerSettings server() {
        return server;
    }

    public synchronized void saveServer(ServerSettings value) {
        this.server = value;
        persist();
    }

    public synchronized PluginSettings plugins() {
        return plugins;
    }

    /** Speichert den Zustand des Plugin-Systems; Repository-Passwörter werden verschlüsselt abgelegt. */
    public synchronized void savePlugins(PluginSettings value) {
        this.plugins = value;
        persist();
    }

    public synchronized Optional<ModuleSettings> module(String moduleId) {
        return Optional.ofNullable(modules.get(moduleId));
    }

    /**
     * Speichert den Modulzustand.
     *
     * @param secrets Schlüssel der Werte, die verschlüsselt abgelegt werden
     */
    public synchronized void saveModule(String moduleId, ModuleSettings value, Set<String> secrets) {
        modules.put(moduleId, value);
        secretKeys.put(moduleId, Set.copyOf(secrets));
        persist();
    }

    // ---------------------------------------------------------------- Persistenz

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            JsonNode root = json.readTree(file.toFile());
            JsonNode s = root.path("server");
            if (s.isObject()) {
                server = new ServerSettings(
                        s.path("port").asInt(ServerSettings.DEFAULT_PORT),
                        cipher.decrypt(s.path("authToken").asString("")),
                        s.path("closeToTray").asBoolean(true),
                        s.path("startMinimized").asBoolean(false));
            }
            JsonNode p = root.path("plugins");
            if (p.isObject()) {
                plugins = readPlugins(p);
            }
            JsonNode mods = root.path("modules");
            for (var entry : mods.properties()) {
                JsonNode m = entry.getValue();
                Set<String> disabled = new LinkedHashSet<>();
                m.path("disabledTools").forEach(n -> disabled.add(n.asString()));
                Map<String, String> values = new LinkedHashMap<>();
                m.path("values").properties().forEach(e -> values.put(e.getKey(), e.getValue().asString()));
                Set<String> secrets = new LinkedHashSet<>();
                m.path("secrets").properties().forEach(e -> {
                    values.put(e.getKey(), cipher.decrypt(e.getValue().asString()));
                    secrets.add(e.getKey());
                });
                modules.put(entry.getKey(), new ModuleSettings(m.path("enabled").asBoolean(false), disabled, values));
                secretKeys.put(entry.getKey(), secrets);
            }
        } catch (RuntimeException e) {
            LOG.error("Einstellungen konnten nicht gelesen werden ({}), starte mit Standardwerten", file, e);
            backupBroken();
        }
    }

    private void persist() {
        ObjectNode root = json.createObjectNode();
        ObjectNode s = root.putObject("server");
        s.put("port", server.port());
        s.put("authToken", cipher.encrypt(server.authToken()));
        s.put("closeToTray", server.closeToTray());
        s.put("startMinimized", server.startMinimized());

        writePlugins(root.putObject("plugins"));

        ObjectNode mods = root.putObject("modules");
        modules.forEach((id, m) -> {
            ObjectNode node = mods.putObject(id);
            node.put("enabled", m.enabled());
            var arr = node.putArray("disabledTools");
            new TreeSet<>(m.disabledTools()).forEach(arr::add);
            ObjectNode values = node.putObject("values");
            ObjectNode secrets = node.putObject("secrets");
            Set<String> secretSet = secretKeys.getOrDefault(id, Set.of());
            new TreeMap<>(m.values()).forEach((k, v) -> {
                if (secretSet.contains(k)) {
                    secrets.put(k, cipher.encrypt(v));
                } else {
                    values.put(k, v);
                }
            });
        });
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            json.writeValue(tmp.toFile(), root);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Einstellungen konnten nicht gespeichert werden: " + file, e);
        }
    }

    private PluginSettings readPlugins(JsonNode p) {
        Set<String> disabled = new LinkedHashSet<>();
        p.path("disabled").forEach(n -> disabled.add(n.asString()));
        List<PluginRepository> repos = new ArrayList<>();
        p.path("repositories").forEach(r -> repos.add(new PluginRepository(
                r.path("id").asString(""), r.path("name").asString(""), r.path("url").asString(""),
                r.path("username").asString(""), cipher.decrypt(r.path("password").asString("")),
                r.path("snapshots").asBoolean(false), r.path("catalog").asString(""),
                r.path("enabled").asBoolean(true))));
        Map<String, String> sources = new LinkedHashMap<>();
        p.path("sources").properties().forEach(e -> sources.put(e.getKey(), e.getValue().asString()));
        // "repositories" fehlt nur vor dem ersten Speichern – eine bewusst geleerte Liste bleibt leer
        return new PluginSettings(disabled, p.has("repositories") ? repos : PluginSettings.defaults().repositories(),
                sources);
    }

    private void writePlugins(ObjectNode p) {
        var disabled = p.putArray("disabled");
        new TreeSet<>(plugins.disabled()).forEach(disabled::add);
        var repos = p.putArray("repositories");
        for (PluginRepository r : plugins.repositories()) {
            ObjectNode n = repos.addObject();
            n.put("id", r.id());
            n.put("name", r.name());
            n.put("url", r.url());
            n.put("username", r.username());
            n.put("password", cipher.encrypt(r.password()));
            n.put("snapshots", r.snapshots());
            n.put("catalog", r.catalog());
            n.put("enabled", r.enabled());
        }
        ObjectNode sources = p.putObject("sources");
        new TreeMap<>(plugins.sources()).forEach(sources::put);
    }

    private void backupBroken() {
        try {
            Files.copy(file, file.resolveSibling("settings.broken.json"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // best effort
        }
    }
}
