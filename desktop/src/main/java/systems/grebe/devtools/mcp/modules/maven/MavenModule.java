package systems.grebe.devtools.mcp.modules.maven;

import java.time.Duration;
import java.util.List;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.ConnectionTestResult;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolBeans;
import systems.grebe.devtools.mcp.core.ToolModule;

/** Auskunft über Maven-Artefakte: neueste Version, POM-Metadaten und Breaking Changes zwischen Versionen. */
@Component
public class MavenModule implements ToolModule {

    static final String REPOSITORY_URL = "repositoryUrl";
    static final String USERNAME = "username";
    static final String PASSWORD = "password";
    static final String GITHUB_API_URL = "githubApiUrl";
    static final String GITHUB_TOKEN = "githubToken";
    static final String MAX_JAR_MB = "maxJarMb";
    static final String TIMEOUT = "timeoutSeconds";

    static final String CENTRAL = "https://repo.maven.apache.org/maven2";

    @Override
    public String id() {
        return "maven";
    }

    @Override
    public String displayName() {
        return "Maven-Artefakte";
    }

    @Override
    public String description() {
        return "Neueste Versionen, POM-Metadaten und Breaking Changes (API-Vergleich, Release Notes) von Maven-Artefakten.";
    }

    @Override
    public String instructions() {
        return """
                Für Fragen zu Bibliotheken aus Maven-Repositories diese Tools statt `curl` gegen Maven Central, \
                search.maven.org, `mvn versions:*`/`mvn dependency:*` oder eines Browsers verwenden: \
                `maven_latest_version` (neueste Version, Update-Einschätzung mit currentVersion), \
                `maven_artifact_info` (Lizenz, SCM, Java-Version, Abhängigkeiten aus dem POM) und \
                `maven_breaking_changes` (vor einem Upgrade: API-Vergleich der JARs und Breaking-Hinweise aus den \
                Release Notes).""";
    }

    @Override
    public List<ConfigField> configSchema() {
        return List.of(
                ConfigField.of(REPOSITORY_URL, "Repository-URL", FieldType.URL).asRequired().withDefault(CENTRAL)
                        .withHelp("Maven Central oder ein eigener Mirror (Nexus, Artifactory) im Maven-2-Layout."),
                ConfigField.of(USERNAME, "Benutzer", FieldType.STRING)
                        .withHelp("Nur für Repositories mit Anmeldung."),
                ConfigField.of(PASSWORD, "Passwort / Token", FieldType.SECRET)
                        .withHelp("Wird verschlüsselt gespeichert."),
                ConfigField.of(GITHUB_TOKEN, "GitHub-Token", FieldType.SECRET)
                        .withHelp("Optional, für Release Notes: ohne Token erlaubt GitHub 60 Abfragen pro Stunde."),
                ConfigField.of(GITHUB_API_URL, "GitHub-API-URL", FieldType.URL).withDefault("https://api.github.com"),
                ConfigField.of(MAX_JAR_MB, "Max. JAR-Größe für API-Vergleich (MB)", FieldType.INT).withDefault("64"),
                ConfigField.of(TIMEOUT, "Timeout (Sekunden)", FieldType.INT).withDefault("30"));
    }

    @Override
    public List<ToolCallback> createTools(ModuleConfig config) {
        return ToolBeans.callbacks(new MavenTools(() -> repository(config), () -> github(config)));
    }

    static MavenRepositoryClient repository(ModuleConfig config) {
        return new MavenRepositoryClient(config.getString(REPOSITORY_URL, CENTRAL), config.getString(USERNAME, ""),
                config.getString(PASSWORD, ""), timeout(config),
                Math.max(1, config.getInt(MAX_JAR_MB, 64)) * 1024L * 1024L);
    }

    static GitHubReleaseNotes github(ModuleConfig config) {
        return new GitHubReleaseNotes(config.getString(GITHUB_API_URL, "https://api.github.com"),
                config.getString(GITHUB_TOKEN, ""), timeout(config));
    }

    private static Duration timeout(ModuleConfig config) {
        return Duration.ofSeconds(Math.max(5, config.getInt(TIMEOUT, 30)));
    }

    @Override
    public ConnectionTestResult testConnection(ModuleConfig config) {
        List<String> errors = config.validate();
        if (!errors.isEmpty()) {
            return ConnectionTestResult.failed(String.join("\n", errors));
        }
        MavenRepositoryClient client = repository(config);
        MavenRepositoryClient.Metadata meta = client.metadata(Coordinates.parse("org.apache.maven:maven-core"));
        return ConnectionTestResult.ok("Repository " + client.baseUrl() + " erreichbar (Testabfrage "
                + "org.apache.maven:maven-core: " + meta.versions().size() + " Versionen).");
    }

    @Override
    public boolean enabledByDefault() {
        return true;
    }
}
