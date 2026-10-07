package systems.grebe.devtools.mcp.remote;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import systems.grebe.devtools.mcp.backend.BackendConfig;
import systems.grebe.devtools.mcp.backend.account.AccountService;
import systems.grebe.devtools.mcp.backend.graph.GraphStorage;
import systems.grebe.devtools.mcp.config.GraphDatabaseSettings;
import systems.grebe.devtools.mcp.config.SettingsStore;

/**
 * Backend eingebettet in der Desktop-App (Core- und Skill-Datenbank im Ordner der App, GraphQL unter
 * {@code http://127.0.0.1:<port>/graphql}) – immer, außer in den Einstellungen ist ein Team-Server eingetragen.
 * {@link systems.grebe.devtools.mcp.DevToolsMcpApplication} setzt dafür {@code devtools.backend.embedded}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = EmbeddedBackend.PROPERTY, havingValue = "true", matchIfMissing = true)
@Import(BackendConfig.class)
public class EmbeddedBackend {

    public static final String PROPERTY = "devtools.backend.embedded";

    /** {@code devtools.local-user.email} setzt die E-Mail des ersten Kontos fest (z.B. in Tests statt Git-E-Mail). */
    @Bean
    EmbeddedAccounts embeddedAccounts(AccountService accounts, SettingsStore store,
                                      @Value("${devtools.local-user.email:}") String email) {
        return new EmbeddedAccounts(accounts, store, email);
    }

    /** Graph-Datenbank aus dem Reiter „Backend“ – hat Vorrang vor {@code devtools.graph.*}. */
    @Bean
    GraphStorage.Configured graphSettings(SettingsStore store) {
        return () -> graphSettings(store.graph());
    }

    /** Einstellung der App als Einstellung der Graph-Storage; {@code null} = nicht eingestellt. */
    public static GraphStorage.Settings graphSettings(GraphDatabaseSettings g) {
        if (!g.configured()) {
            return null;
        }
        return g.remote() ? GraphStorage.Settings.remote(g.host().isEmpty() ? "localhost" : g.host(), g.port(),
                g.database().isEmpty() ? "devtools" : g.database(), g.user().isEmpty() ? "root" : g.user(),
                g.password()) : GraphStorage.Settings.embedded(null);
    }
}
