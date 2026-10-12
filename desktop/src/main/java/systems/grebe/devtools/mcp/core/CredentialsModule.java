package systems.grebe.devtools.mcp.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

/**
 * Zentrale Zugangsdaten wie beim Git Credential Manager: Tokens und Passwörter je Server-URL, einmal hinterlegt.
 * Module, deren Geheimnis leer bleibt, bekommen das des passenden Eintrags (siehe {@link Credentials} und
 * {@link ToolModule#credentialFields()}). Stellt selbst keine Tools bereit; angewendet von der {@link ToolRegistry}.
 *
 * <p>Die Einträge liegen nicht in den Moduleinstellungen, sondern je Benutzer verschlüsselt im Backend (Tabelle
 * {@code credential}); {@code BackendSettingsResolver} liest und speichert sie dort.
 */
@Component
public class CredentialsModule implements ToolModule {

    public static final String ID = "credentials";
    public static final String ENTRIES = "entries";
    public static final String NAME = "name";
    public static final String URL = "url";
    public static final String USER = "user";
    public static final String SECRET = "secret";
    public static final String DESCRIPTION = "description";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Zugangsdaten";
    }

    @Override
    public String description() {
        return "Tokens und Passwörter zentral je Server hinterlegen, wie beim Git Credential Manager: Module, deren "
                + "Token- bzw. Passwortfeld leer bleibt, verwenden den Eintrag, dessen URL zu ihrer Server-URL passt "
                + "(Tickets, Pull Requests, CI, SonarQube, Maven, Chat …).";
    }

    @Override
    public boolean hasTools() {
        return false;
    }

    @Override
    public boolean enabledByDefault() {
        return true;
    }

    @Override
    public int order() {
        return 2;
    }

    @Override
    public List<ConfigField> configSchema() {
        return List.of(ConfigField.records(ENTRIES, "Zugangsdaten",
                        ConfigField.of(NAME, "Name", FieldType.STRING).asRequired(),
                        ConfigField.of(URL, "URL", FieldType.URL).asRequired()
                                .withHelp("Server, für den die Zugangsdaten gelten, z.B. https://github.com. Gilt auch "
                                        + "für Subdomains (api.github.com) und Pfade darunter; der genaueste Eintrag "
                                        + "gewinnt."),
                        ConfigField.of(USER, "Benutzer", FieldType.STRING)
                                .withHelp("Optional, z.B. die E-Mail zum Jira-Cloud-Token. Wird nur eingesetzt, wenn "
                                        + "das Modul keinen eigenen Benutzer hat."),
                        ConfigField.of(SECRET, "Token / Passwort", FieldType.SECRET).asRequired(),
                        ConfigField.of(DESCRIPTION, "Beschreibung", FieldType.STRING))
                .withHelp("Ein eigenes Token im Modul hat immer Vorrang. Gespeichert im Backend je Benutzer "
                        + "(verschlüsselt, für alle Profile); geht nie an das LLM."));
    }

    @Override
    public List<ToolCallback> createTools(ModuleConfig config) {
        return List.of();
    }

    @Override
    public List<CredentialField> credentialFields() {
        return List.of();
    }

    @Override
    public ConnectionTestResult testConnection(ModuleConfig config) {
        List<String> errors = config.validate();
        if (!errors.isEmpty()) {
            return ConnectionTestResult.failed(String.join("\n", errors));
        }
        List<Map<String, String>> entries = config.getRecords(ENTRIES);
        if (entries.isEmpty()) {
            return ConnectionTestResult.ok("Keine Zugangsdaten hinterlegt.");
        }
        List<String> lines = new ArrayList<>();
        lines.add(entries.size() + " Zugangsdaten hinterlegt:");
        entries.forEach(e -> lines.add("- " + e.get(NAME) + ": " + e.get(URL)));
        return ConnectionTestResult.ok(String.join("\n", lines));
    }
}
