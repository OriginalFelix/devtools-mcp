package systems.grebe.devtools.mcp.backend;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import systems.grebe.devtools.mcp.backend.account.Sha3Pbkdf2PasswordEncoder;

/**
 * Einstieg ins Backend: alle Beans unter {@code systems.grebe.devtools.mcp.backend}. Der Team-Server nimmt es immer
 * auf, die Desktop-App nur ohne eingetragene Server-URL ({@code devtools.backend.embedded=true}).
 *
 * <p>Benötigte Properties (GraphQL über HTTP und WebSocket unter {@code /graphql}) setzen Server und Desktop in ihrer
 * {@code application.properties}; Schemas und Pfade der API-Versionen stellt
 * {@link systems.grebe.devtools.mcp.backend.api.ApiConfig} bereit.
 */
@Configuration(proxyBeanMethods = false)
@ComponentScan(basePackageClasses = BackendConfig.class)
public class BackendConfig {

    @Bean
    Sha3Pbkdf2PasswordEncoder passwordEncoder() {
        return new Sha3Pbkdf2PasswordEncoder();
    }
}
