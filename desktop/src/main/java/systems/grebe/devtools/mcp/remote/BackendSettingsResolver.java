package systems.grebe.devtools.mcp.remote;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.api.Grants;
import systems.grebe.devtools.mcp.api.ProjectInfo;
import systems.grebe.devtools.mcp.api.SettingsSnapshot;
import systems.grebe.devtools.mcp.config.ModuleSettings;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.SettingsResolver;
import systems.grebe.devtools.mcp.core.ToolModule;
import systems.grebe.devtools.mcp.core.DirectoryLists;

/**
 * Wirksame Einstellungen aus dem Backend: Vorbelegung des Moduls, darüber die Vorgaben Global → Benutzer → aktives
 * Profil. Git, Build und Code-Graph bekommen zusätzlich die Projekte, denen in dieser App ein Verzeichnis zugeordnet
 * ist – unter dem Namen, den die Tools kennen ({@code name} bzw. {@code name@eigentümer}). Änderungen gehen als
 * Überschreibung ins aktive Profil.
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
        backend.save(module, before, after);
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
        Map<String, String> values = new LinkedHashMap<>(s.values());
        values.put(field, DirectoryLists.merge(s.values().getOrDefault(field, ""), projects, true));
        return s.withValues(values);
    }
}
