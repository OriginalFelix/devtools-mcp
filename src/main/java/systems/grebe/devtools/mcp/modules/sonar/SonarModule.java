package systems.grebe.devtools.mcp.modules.sonar;

import java.time.Duration;
import java.util.List;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.ConnectionTestResult;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolModule;
import tools.jackson.databind.JsonNode;

/** Anbindung an SonarQube bzw. SonarCloud (Issues, Quality Gate, Metriken, Regeln, Hotspots). */
@Component
public class SonarModule implements ToolModule {

    static final String BASE_URL = "baseUrl";
    static final String TOKEN = "token";
    static final String ORGANIZATION = "organization";
    static final String DEFAULT_PROJECT = "defaultProjectKey";
    static final String TIMEOUT = "timeoutSeconds";

    @Override
    public String id() {
        return "sonar";
    }

    @Override
    public String displayName() {
        return "SonarQube";
    }

    @Override
    public String description() {
        return "Liest Issues, Quality-Gate-Status, Metriken, Security Hotspots und Regelbeschreibungen aus SonarQube oder SonarCloud.";
    }

    @Override
    public List<ConfigField> configSchema() {
        return List.of(
                ConfigField.of(BASE_URL, "Server-URL", FieldType.URL).asRequired().withDefault("http://localhost:9000")
                        .withHelp("z.B. https://sonar.firma.de oder https://sonarcloud.io"),
                ConfigField.of(TOKEN, "Token", FieldType.SECRET)
                        .withHelp("User-Token (Mein Konto → Sicherheit). Wird verschlüsselt gespeichert."),
                ConfigField.of(ORGANIZATION, "Organisation", FieldType.STRING)
                        .withHelp("Nur für SonarCloud erforderlich."),
                ConfigField.of(DEFAULT_PROJECT, "Standard-Projektschlüssel", FieldType.STRING)
                        .withHelp("Wird verwendet, wenn das LLM keinen Projektschlüssel angibt."),
                ConfigField.of(TIMEOUT, "Timeout (Sekunden)", FieldType.INT).withDefault("30"));
    }

    @Override
    public List<ToolCallback> createTools(ModuleConfig config) {
        return List.of(ToolCallbacks.from(new SonarTools(() -> client(config), config.getString(DEFAULT_PROJECT, null))));
    }

    static SonarClient client(ModuleConfig config) {
        return new SonarClient(config.require(BASE_URL), config.getString(TOKEN, ""),
                config.getString(ORGANIZATION, ""), Duration.ofSeconds(Math.max(5, config.getInt(TIMEOUT, 30))));
    }

    @Override
    public ConnectionTestResult testConnection(ModuleConfig config) {
        List<String> errors = config.validate();
        if (!errors.isEmpty()) {
            return ConnectionTestResult.failed(String.join("\n", errors));
        }
        SonarClient client = client(config);
        JsonNode status = client.get("/api/system/status", SonarClient.params());
        String version = status.path("version").asString("?");
        String state = status.path("status").asString("?");
        JsonNode auth = client.get("/api/authentication/validate", SonarClient.params());
        boolean valid = auth.path("valid").asBoolean(false);
        String msg = "Server " + client.baseUrl() + " erreichbar – Version " + version + ", Status " + state + ".";
        if (config.get(TOKEN).isEmpty()) {
            return ConnectionTestResult.ok(msg + "\nKein Token gesetzt: nur öffentliche Projekte sichtbar.");
        }
        return valid
                ? ConnectionTestResult.ok(msg + "\nToken gültig.")
                : ConnectionTestResult.failed(msg + "\nToken ungültig oder abgelaufen.");
    }
}
