package systems.grebe.devtools.mcp.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import static org.assertj.core.api.Assertions.assertThat;

class CredentialsTest {

    @SafeVarargs
    private static Credentials credentials(Map<String, String>... entries) {
        return Credentials.of(ModuleConfig.of(new CredentialsModule().configSchema(),
                Map.of(CredentialsModule.ENTRIES, ModuleConfig.formatRecords(List.of(entries)))));
    }

    private static Map<String, String> entry(String name, String url, String user, String secret) {
        return Map.of(CredentialsModule.NAME, name, CredentialsModule.URL, url, CredentialsModule.USER, user,
                CredentialsModule.SECRET, secret);
    }

    /** Modul mit einer Provider-Gruppe „jira“ und Feldern ohne Gruppe (Server-URL + Token wie SonarQube). */
    private static ToolModule module(List<ConfigField> schema) {
        return new ToolModule() {
            @Override
            public String id() {
                return "test";
            }

            @Override
            public String displayName() {
                return "Test";
            }

            @Override
            public String description() {
                return "";
            }

            @Override
            public List<ConfigField> configSchema() {
                return schema;
            }

            @Override
            public List<ToolCallback> createTools(ModuleConfig config) {
                return List.of();
            }
        };
    }

    private static final ConfigGroup JIRA = new ConfigGroup("jira", "Jira");

    private static final List<ConfigField> SCHEMA = new ArrayList<>();

    static {
        SCHEMA.add(ConfigField.of("baseUrl", "Server-URL", FieldType.URL).withDefault("https://sonar.example.com"));
        SCHEMA.add(ConfigField.of("token", "Token", FieldType.SECRET));
        SCHEMA.addAll(JIRA.fields(true, List.of(
                ConfigField.of("baseUrl", "Server-URL", FieldType.URL),
                ConfigField.of("user", "Benutzer / E-Mail", FieldType.STRING),
                ConfigField.of("token", "API-Token", FieldType.SECRET))));
    }

    @Test
    void derivesOneBindingPerScopeWithSingleUrl() {
        assertThat(CredentialField.derive(SCHEMA)).containsExactly(
                CredentialField.of("baseUrl", null, "token"),
                CredentialField.of("jira.baseUrl", "jira.user", "jira.token"));
        // zwei URLs ohne Gruppe: nicht eindeutig, das Modul muss selbst zuordnen
        assertThat(CredentialField.derive(List.of(ConfigField.of("a", "A", FieldType.URL),
                ConfigField.of("b", "B", FieldType.URL), ConfigField.of("t", "T", FieldType.SECRET)))).isEmpty();
    }

    @Test
    void matchesHostSubdomainPortAndPathLikeGitCredentialManager() {
        Credentials c = credentials(
                entry("GitHub", "https://github.com", "", "gh"),
                entry("GitHub API", "https://api.github.com/", "", "api"),
                entry("GitLab Gruppe", "https://gitlab.example.com/gruppe", "", "gruppe"),
                entry("GitLab", "https://gitlab.example.com", "", "gl"),
                entry("Sonar lokal", "http://localhost:9000", "", "sonar"));

        assertThat(c.lookup("https://api.github.com").map(Credentials.Entry::secret)).contains("api");
        assertThat(c.lookup("https://uploads.github.com/x").map(Credentials.Entry::secret)).contains("gh");
        assertThat(c.lookup("https://gitlab.example.com/gruppe/projekt").map(Credentials.Entry::secret)).contains("gruppe");
        assertThat(c.lookup("https://gitlab.example.com/andere").map(Credentials.Entry::secret)).contains("gl");
        assertThat(c.lookup("http://localhost:9000/").map(Credentials.Entry::secret)).contains("sonar");
        assertThat(c.lookup("http://localhost:8080")).isEmpty(); // anderer Port
        assertThat(c.lookup("http://github.com")).isEmpty(); // anderes Schema
        assertThat(c.lookup("https://notgithub.com")).isEmpty(); // keine Subdomain
        assertThat(c.lookup("https://gitlab.example.com/gruppe2").map(Credentials.Entry::secret)).contains("gl");
        assertThat(c.lookup("kein url")).isEmpty();
    }

    @Test
    void fillsOnlyEmptySecretsAndUser() {
        Credentials c = credentials(entry("Sonar", "https://sonar.example.com", "", "sonar-token"),
                entry("Jira", "https://firma.atlassian.net", "me@example.com", "jira-token"));
        ToolModule m = module(SCHEMA);

        Map<String, String> applied = c.apply(m, Map.of("jira.baseUrl", "https://firma.atlassian.net"));
        assertThat(applied).containsEntry("token", "sonar-token") // URL aus dem Default des Feldes
                .containsEntry("jira.token", "jira-token")
                .containsEntry("jira.user", "me@example.com");

        Map<String, String> own = c.apply(m, Map.of("token", "eigenes", "jira.baseUrl", "https://firma.atlassian.net",
                "jira.user", "anderer@example.com"));
        assertThat(own).containsEntry("token", "eigenes")
                .containsEntry("jira.token", "jira-token")
                .containsEntry("jira.user", "anderer@example.com");

        Map<String, String> none = Map.of("baseUrl", "https://andere.example.com");
        assertThat(c.apply(m, none)).isSameAs(none);
    }

    @Test
    void marksOAuthTokensSoProvidersSendThemAsBearer() {
        Map<String, String> oauth = new java.util.HashMap<>(entry("Jira", "https://firma.atlassian.net", "", "at-3lo"));
        oauth.put(CredentialsModule.AUTH, CredentialsModule.AUTH_OAUTH);
        Credentials c = credentials(oauth, entry("Sonar", "https://sonar.example.com", "", "sonar-token"));

        Map<String, String> applied = c.apply(module(SCHEMA), Map.of("jira.baseUrl", "https://firma.atlassian.net"));

        assertThat(applied).containsEntry("jira.token", "at-3lo").containsEntry("jira.token@oauth", "true")
                .containsEntry("token", "sonar-token").doesNotContainKey("token@oauth");
        assertThat(CredentialField.oauthKey("token")).isEqualTo("token@oauth");
    }

    @Test
    void usesDefaultUrlWhenUrlFieldIsEmpty() {
        ToolModule m = new ToolModule() {
            @Override
            public String id() {
                return "claude";
            }

            @Override
            public String displayName() {
                return "Claude";
            }

            @Override
            public String description() {
                return "";
            }

            @Override
            public List<ConfigField> configSchema() {
                return List.of(ConfigField.of("apiKey", "API-Key", FieldType.SECRET),
                        ConfigField.of("baseUrl", "API-URL", FieldType.URL));
            }

            @Override
            public List<CredentialField> credentialFields() {
                return List.of(CredentialField.of("baseUrl", null, "apiKey").withDefaultUrl("https://api.anthropic.com"));
            }

            @Override
            public List<ToolCallback> createTools(ModuleConfig config) {
                return List.of();
            }
        };
        Credentials c = credentials(entry("Claude", "https://api.anthropic.com", "", "sk-ant"));
        assertThat(c.apply(m, Map.of())).containsEntry("apiKey", "sk-ant");
        assertThat(c.apply(m, Map.of("baseUrl", "https://gateway.example.com"))).doesNotContainKey("apiKey");
    }
}
