package systems.grebe.devtools.mcp.server;

import com.vaadin.flow.spring.security.VaadinSecurityConfigurer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import systems.grebe.devtools.mcp.account.Sha3Pbkdf2PasswordEncoder;
import systems.grebe.devtools.mcp.web.LoginView;

/**
 * Zwei Filterketten:
 *
 * <ol>
 *   <li>{@code /mcp}: zustandslos, ohne CSRF und Session – wer die Anfrage bedienen darf, entscheidet
 *       {@link McpAccess} im {@link McpDispatcherConfig Dispatcher} (JWT je Benutzer oder lokal).</li>
 *   <li>alles andere: Vaadin-Web-UI mit Formular-Anmeldung gegen die Benutzerkonten
 *       ({@link systems.grebe.devtools.mcp.account.AccountService}); Zugriff je View per {@code @PermitAll} /
 *       {@code @RolesAllowed}.</li>
 * </ol>
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    Sha3Pbkdf2PasswordEncoder passwordEncoder() {
        return new Sha3Pbkdf2PasswordEncoder();
    }

    @Bean
    @Order(1)
    SecurityFilterChain mcpSecurity(HttpSecurity http,
                                    @Value("${spring.ai.mcp.server.streamable-http.mcp-endpoint:/mcp}") String endpoint)
            throws Exception {
        return http.securityMatcher(endpoint, endpoint + "/**")
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(c -> c.disable())
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain webSecurity(HttpSecurity http) throws Exception {
        return http.with(VaadinSecurityConfigurer.vaadin(), c -> c.loginView(LoginView.class)).build();
    }
}
