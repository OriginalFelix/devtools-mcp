package systems.grebe.devtools.mcp.remote;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import systems.grebe.devtools.mcp.backend.BackendConfig;
import systems.grebe.devtools.mcp.backend.account.AccountService;
import systems.grebe.devtools.mcp.backend.account.TokenService;
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

    /** {@code devtools.local-user.email} setzt die E-Mail fest (z.B. in Tests statt der Git-E-Mail). */
    @Bean
    LocalUser localUser(AccountService accounts, TokenService tokens, SettingsStore store,
                        @Value("${devtools.local-user.email:}") String email) {
        return new LocalUser(accounts, tokens, store, email);
    }
}
