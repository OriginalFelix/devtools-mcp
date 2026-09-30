package systems.grebe.devtools.mcp.project;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.config.ModuleSettings;
import systems.grebe.devtools.mcp.core.SettingsResolver;
import systems.grebe.devtools.mcp.core.ToolModule;
import systems.grebe.devtools.mcp.core.ToolScope;
import systems.grebe.devtools.mcp.profile.ProfileService;

/**
 * Wirksame Einstellungen eines angemeldeten Benutzers: Global → Benutzer → Profil ({@link ProfileService}), danach
 * ersetzen seine Projekte die Verzeichnis-Felder von Git, Build und Code-Graph. Globale oder überschriebene
 * Verzeichnisse gelten für Benutzer also nicht – sie sehen genau ihre eigenen und die ihnen freigegebenen Projekte.
 *
 * <p>Einträge haben die Form {@code name=pfad} (siehe {@code Workspaces}); fremde Projekte heißen
 * {@code name@eigentümer}.
 */
@Component
public class ProjectSettings implements SettingsResolver {

    private final ProfileService profiles;
    private final ProjectService projects;

    public ProjectSettings(ProfileService profiles, ProjectService projects) {
        this.profiles = profiles;
        this.projects = projects;
    }

    @Override
    public ModuleSettings effective(ToolScope scope, ToolModule module, ModuleSettings global) {
        ModuleSettings s = profiles.effective(scope, module, global);
        String field = ProjectService.DIRECTORY_FIELDS.get(module.id());
        if (field == null || scope.userId().isEmpty()) {
            return s;
        }
        long userId = Long.parseLong(scope.userId().get());
        List<Project.Visible> visible = projects.visible(userId);
        Map<String, String> values = new LinkedHashMap<>(s.values());
        values.put(field, visible.stream()
                .map(v -> v.toolName() + "=" + v.project().root())
                .collect(Collectors.joining("\n")));
        // Standardprojekt nur, wenn es eines der Projekte des Benutzers ist (sonst gilt bei genau einem Projekt das)
        String defaultField = ProjectService.DEFAULT_FIELDS.get(module.id());
        String def = values.get(defaultField);
        if (def != null && visible.stream().noneMatch(v -> v.toolName().equalsIgnoreCase(def.strip()))) {
            values.remove(defaultField);
        }
        return s.withValues(values);
    }
}
