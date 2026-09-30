package systems.grebe.devtools.mcp.remote;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.api.ProjectInfo;
import systems.grebe.devtools.mcp.api.SettingsSnapshot;
import systems.grebe.devtools.mcp.config.ModuleSettings;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.SettingsResolver;
import systems.grebe.devtools.mcp.core.ToolModule;

/**
 * Wirksame Einstellungen aus dem Backend: Vorbelegung des Moduls, darüber die Vorgaben Global → Benutzer → aktives
 * Profil. Git, Build und Code-Graph bekommen zusätzlich die Projekte, denen in dieser App ein Verzeichnis zugeordnet
 * ist – unter dem Namen, den die Tools kennen ({@code name} bzw. {@code name@eigentümer}). Solange das Backend noch
 * keinen Stand geliefert hat, gelten die lokalen Einstellungen. Änderungen gehen als Überschreibung ins aktive Profil.
 */
@Component
public class BackendSettingsResolver implements SettingsResolver {

    private final BackendConnection backend;

    public BackendSettingsResolver(BackendConnection backend) {
        this.backend = backend;
    }

    @Override
    public ModuleSettings effective(ToolModule module, ModuleSettings local) {
        ModuleSettings s = backend.settings()
                .map((SettingsSnapshot snapshot) -> snapshot.module(module.id()).applyTo(defaults(module)))
                .orElse(local);
        return withProjects(module, s);
    }

    @Override
    public boolean handlesWrites() {
        return backend.settings().isPresent();
    }

    @Override
    public void save(ToolModule module, ModuleSettings before, ModuleSettings after) {
        backend.save(module, before, after);
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
