package systems.grebe.devtools.mcp.remote;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.api.CredentialInfo;
import systems.grebe.devtools.mcp.api.Grants;
import systems.grebe.devtools.mcp.api.ProjectInfo;
import systems.grebe.devtools.mcp.api.SettingsSnapshot;
import systems.grebe.devtools.mcp.config.ModuleSettings;
import systems.grebe.devtools.mcp.core.CredentialsModule;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.SettingsResolver;
import systems.grebe.devtools.mcp.core.ToolModule;

/**
 * Wirksame Einstellungen aus dem Backend: Vorbelegung des Moduls, darüber die Vorgaben Global → Benutzer → aktives
 * Profil. Git, Build und Code-Graph bekommen zusätzlich die Projekte, denen in dieser App ein Verzeichnis zugeordnet
 * ist – unter dem Namen, den die Tools kennen ({@code name} bzw. {@code name@eigentümer}). Änderungen gehen als
 * Überschreibung ins aktive Profil – außer beim Modul {@link CredentialsModule „Zugangsdaten“}: dessen Einträge liegen
 * je Benutzer verschlüsselt in einer eigenen Tabelle des Backends und kommen mit dem Settings-Stand.
 *
 * <p>Ohne Anmeldung ist jedes Modul aus. Welche Tools registriert werden, bestimmen außerdem die Rechte des Benutzers
 * ({@link #permitted}: {@code module:*}, {@code module:<id>} – auch das übergeordnete Modul, z.B. {@code scripts} –
 * oder {@code tool:<name>}).
 */
@Component
public class BackendSettingsResolver implements SettingsResolver {

    private final BackendConnection backend;

    public BackendSettingsResolver(BackendConnection backend) {
        this.backend = backend;
    }

    @Override
    public ModuleSettings effective(ToolModule module, ModuleSettings local) {
        if (!backend.signedIn()) {
            return defaults(module).withEnabled(false); // niemand angemeldet: keine Tools
        }
        if (CredentialsModule.ID.equals(module.id())) {
            return backend.settings().map(snapshot -> credentials(module, snapshot.credentials())).orElse(local);
        }
        ModuleSettings s = backend.settings()
                .map((SettingsSnapshot snapshot) -> snapshot.module(module.id()).applyTo(defaults(module)))
                .orElse(local);
        return withProjects(module, s);
    }

    /** Einstellungen liegen immer im Backend – ohne Anmeldung lässt sich nichts speichern. */
    @Override
    public boolean handlesWrites() {
        return true;
    }

    @Override
    public void save(ToolModule module, ModuleSettings before, ModuleSettings after) {
        if (!backend.signedIn()) {
            throw new IllegalStateException("Nicht angemeldet.");
        }
        if (CredentialsModule.ID.equals(module.id())) {
            backend.saveCredentials(credentials(module, after));
            return;
        }
        backend.save(module, before, after);
    }

    /** Zugangsdaten aus dem Backend als Wert des Moduls (Datensatzliste wie im Formular). */
    static ModuleSettings credentials(ToolModule module, List<CredentialInfo> entries) {
        List<Map<String, String>> records = new ArrayList<>();
        for (CredentialInfo e : entries) {
            Map<String, String> r = new LinkedHashMap<>();
            r.put(CredentialsModule.NAME, e.name());
            r.put(CredentialsModule.URL, e.url());
            r.put(CredentialsModule.USER, e.username());
            r.put(CredentialsModule.SECRET, e.secret());
            r.put(CredentialsModule.DESCRIPTION, e.description());
            records.add(r);
        }
        return defaults(module).withValues(records.isEmpty() ? Map.of()
                : Map.of(CredentialsModule.ENTRIES, ModuleConfig.formatRecords(records)));
    }

    /** Gegenstück zu {@link #credentials(ToolModule, List)}: die Datensätze aus dem Formular. */
    static List<CredentialInfo> credentials(ToolModule module, ModuleSettings settings) {
        ModuleConfig config = ModuleConfig.of(module.configSchema(), settings.values());
        return config.getRecords(CredentialsModule.ENTRIES).stream()
                .map(r -> new CredentialInfo(null, r.get(CredentialsModule.NAME), r.get(CredentialsModule.URL),
                        r.get(CredentialsModule.USER), r.get(CredentialsModule.SECRET),
                        r.get(CredentialsModule.DESCRIPTION)))
                .toList();
    }

    @Override
    public boolean permitted(ToolModule module, String toolName) {
        Grants g = backend.grants();
        return g.tool(module.id(), toolName) || module.parentModule() != null && g.moduleFull(module.parentModule());
    }

    @Override
    public Set<String> locked(ToolModule module) {
        return backend.settings().map(s -> s.module(module.id()).lockedKeys()).orElse(Set.of());
    }

    @Override
    public String target() {
        return backend.settings().map(s -> "Profil „" + s.profileName() + "“").orElse("settings.json")
                + (backend.embedded() ? " (eingebettetes Backend)" : " (Team-Server)");
    }

    /** Vorbelegung des Moduls (ohne lokale Einstellungen). */
    static ModuleSettings defaults(ToolModule module) {
        return new ModuleSettings(module.enabledByDefault(), Set.of(), module.initialValues(other -> Map.of()));
    }

    private ModuleSettings withProjects(ToolModule module, ModuleSettings s) {
        String field = ProjectInfo.DIRECTORY_FIELDS.get(module.id());
        if (field == null) {
            return s;
        }
        List<String> projects = new ArrayList<>();
        for (ProjectInfo p : backend.projects()) {
            backend.projectPath(p.id()).ifPresent(dir -> projects.add(p.toolName() + "=" + dir));
        }
        if (projects.isEmpty()) {
            return s;
        }
        List<String> lines = new ArrayList<>(ModuleConfig.splitLines(s.values().getOrDefault(field, "")));
        for (String entry : projects) {
            Path dir = Path.of(entry.substring(entry.indexOf('=') + 1));
            lines.removeIf(l -> sameDir(l, dir)); // schon eingetragen: das Projekt gewinnt (Name)
            lines.add(entry);
        }
        Map<String, String> values = new LinkedHashMap<>(s.values());
        values.put(field, String.join("\n", lines));
        return s.withValues(values);
    }

    private static boolean sameDir(String line, Path dir) {
        String raw = line.contains("=") ? line.substring(line.indexOf('=') + 1) : line;
        try {
            return Path.of(raw.strip()).toAbsolutePath().normalize().equals(dir);
        } catch (RuntimeException e) {
            return false;
        }
    }
}
