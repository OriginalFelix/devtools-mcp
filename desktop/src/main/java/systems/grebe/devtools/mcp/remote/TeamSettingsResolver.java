package systems.grebe.devtools.mcp.remote;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.api.ProjectInfo;
import systems.grebe.devtools.mcp.config.ModuleSettings;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.SettingsResolver;
import systems.grebe.devtools.mcp.core.ToolModule;

/**
 * Wirksame Einstellungen bei Anbindung an einen Team-Server: die lokalen Einstellungen, darüber die Vorgaben des
 * Servers (Global → Benutzer → aktives Profil, nur was dort gesetzt ist). Git, Build und Code-Graph bekommen
 * zusätzlich die Server-Projekte, denen in dieser App ein Verzeichnis zugeordnet ist – unter dem Namen, den die Tools
 * kennen ({@code name} bzw. {@code name@eigentümer}).
 */
@Component
public class TeamSettingsResolver implements SettingsResolver {

    private final TeamServer team;

    public TeamSettingsResolver(TeamServer team) {
        this.team = team;
    }

    @Override
    public ModuleSettings effective(ToolModule module, ModuleSettings local) {
        ModuleSettings s = team.settings().map(snapshot -> snapshot.module(module.id()).applyTo(local)).orElse(local);
        String field = ProjectInfo.DIRECTORY_FIELDS.get(module.id());
        if (field == null) {
            return s;
        }
        List<String> projects = new ArrayList<>();
        for (ProjectInfo p : team.projects()) {
            team.projectPath(p.id()).ifPresent(dir -> projects.add(p.toolName() + "=" + dir));
        }
        if (projects.isEmpty()) {
            return s;
        }
        List<String> lines = new ArrayList<>(ModuleConfig.splitLines(s.values().getOrDefault(field, "")));
        for (String entry : projects) {
            Path dir = Path.of(entry.substring(entry.indexOf('=') + 1));
            lines.removeIf(l -> sameDir(l, dir)); // lokal eingetragen und Projekt: das Projekt gewinnt (Name)
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
