package systems.grebe.devtools.mcp.api;

import java.util.List;

/**
 * Vorgaben des Backends für alle Module im aktiven Profil des Benutzers (Query {@code settings}, Subscription
 * {@code settingsChanged}).
 *
 * @param modules  Module mit Vorgaben oder Sperren; andere fehlen
 * @param revision    Änderungszähler des Backends zum Zeitpunkt des Lesens – ein Stand mit kleinerer Revision ist
 *                    älter (Antworten und Subscription-Nachrichten können sich überholen)
 * @param credentials eigene Zugangsdaten des Benutzers (ab API-Version 1)
 */
public record SettingsSnapshot(long profileId, String profileName, List<ModuleOverlay> modules, long revision,
                               List<CredentialInfo> credentials) {

    public SettingsSnapshot {
        modules = modules == null ? List.of() : List.copyOf(modules);
        credentials = credentials == null ? List.of() : List.copyOf(credentials);
    }

    public SettingsSnapshot(long profileId, String profileName, List<ModuleOverlay> modules, long revision) {
        this(profileId, profileName, modules, revision, List.of());
    }

    public SettingsSnapshot withRevision(long value) {
        return new SettingsSnapshot(profileId, profileName, modules, value, credentials);
    }

    public SettingsSnapshot withCredentials(List<CredentialInfo> value) {
        return new SettingsSnapshot(profileId, profileName, modules, revision, value);
    }

    public ModuleOverlay module(String moduleId) {
        return modules.stream().filter(m -> moduleId.equals(m.moduleId())).findFirst()
                .orElse(ModuleOverlay.none(moduleId));
    }
}
