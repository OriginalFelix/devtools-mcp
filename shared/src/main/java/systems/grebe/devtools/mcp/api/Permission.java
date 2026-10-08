package systems.grebe.devtools.mcp.api;

import java.util.Arrays;
import java.util.Optional;

/**
 * Systemrechte, die Rollen vergeben. Dazu kommen die Rechte auf Module und Tools ({@link Grants#module},
 * {@link Grants#tool}); {@link Grants#ALL} umfasst alles.
 *
 * <p>Gespeichert wird der {@link #key()} (Tabelle {@code role_permission}); die Namen der Konstanten dienen in der
 * Web-UI als Spring-Security-Rolle ({@code @RolesAllowed("USERS_MANAGE")}).
 */
public enum Permission {

    USERS_MANAGE("users.manage", "Benutzer und Rollen verwalten",
            "Benutzer anlegen, sperren, löschen, Passwörter setzen; Rollen und ihre Rechte festlegen."),
    SETTINGS_GLOBAL("settings.global", "Globale Einstellungen",
            "Vorgaben für alle Benutzer setzen und Felder sperren."),
    SETTINGS_OWN("settings.own", "Eigene Einstellungen",
            "Modul-Einstellungen im eigenen Konto und in Profilen überschreiben, Profile anlegen und löschen."),
    PROJECTS_CREATE("projects.create", "Projekte anlegen",
            "Eigene Projekte anlegen, ändern und freigeben."),
    PROJECTS_MANAGE_ALL("projects.manage-all", "Alle Projekte verwalten",
            "Projekte anderer Benutzer ändern, freigeben und löschen."),
    TEMPLATES_PUBLISH("templates.publish", "Vorlagen veröffentlichen",
            "Skills und Skripte als globale Vorlage für alle Benutzer veröffentlichen und zurückziehen."),
    TOKENS_CREATE("tokens.create", "Desktop-Tokens erzeugen",
            "Persönliche Tokens für den Start ohne Anmeldedialog (headless, Automatisierung).");

    private final String key;
    private final String label;
    private final String description;

    Permission(String key, String label, String description) {
        this.key = key;
        this.label = label;
        this.description = description;
    }

    /** Gespeicherter Schlüssel, z.B. {@code users.manage}. */
    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    public static Optional<Permission> byKey(String key) {
        return Arrays.stream(values()).filter(p -> p.key.equals(key)).findFirst();
    }
}
