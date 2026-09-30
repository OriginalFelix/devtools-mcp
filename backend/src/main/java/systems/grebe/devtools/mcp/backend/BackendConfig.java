package systems.grebe.devtools.mcp.backend;

import org.springframework.boot.graphql.autoconfigure.GraphQlSourceBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import systems.grebe.devtools.mcp.backend.account.Sha3Pbkdf2PasswordEncoder;

/**
 * Einstieg ins Backend: alle Beans unter {@code systems.grebe.devtools.mcp.backend}. Der Team-Server nimmt es immer
 * auf, die Desktop-App nur ohne eingetragene Server-URL ({@code devtools.backend.embedded=true}).
 *
 * <p>Benötigte Properties (GraphQL über HTTP und WebSocket unter {@code /graphql}) setzen Server und Desktop in ihrer
 * {@code application.properties}.
 */
@Configuration(proxyBeanMethods = false)
@ComponentScan(basePackageClasses = BackendConfig.class)
public class BackendConfig {

    /** Schema der GraphQL-API. */
    public static final String SCHEMA = "backend-graphql/schema.graphqls";

    @Bean
    Sha3Pbkdf2PasswordEncoder passwordEncoder() {
        return new Sha3Pbkdf2PasswordEncoder();
    }

    /**
     * Schema als einzelne Ressource statt über die Pattern-Suche {@code classpath*:graphql/**}: die findet im WAR
     * unter WildFly (VFS) nichts, und ohne Schema schaltete sich GraphQL ganz ab.
     */
    @Bean
    GraphQlSourceBuilderCustomizer backendSchema() {
        return builder -> builder.schemaResources(new ClassPathResource(SCHEMA));
    }
}
