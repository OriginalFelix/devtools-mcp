package systems.grebe.devtools.mcp.server;

import com.vaadin.flow.spring.security.VaadinSecurityConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import systems.grebe.devtools.mcp.backend.account.AccountService;
import systems.grebe.devtools.mcp.backend.blobs.BlobController;
import systems.grebe.devtools.mcp.web.LoginView;
import systems.grebe.devtools.mcp.web.SessionRefreshFilter;

/**
 * Zwei Filterketten:
 *
 * <ol>
 *   <li>{@code /graphql}, {@code /blobs} und {@code /api/…} (dieselben je API-Version, {@code /api/versions}):
 *       GraphQL-API und Dateiablage für die Desktop-Apps – zustandslos, ohne CSRF und Session; angemeldet wird per
 *       Token im GraphQL-Interceptor ({@link systems.grebe.devtools.mcp.backend.GraphQlAuth}) bzw. im
 *       {@link BlobController}.</li>
 *   <li>alles andere: Vaadin-Web-UI mit Formular-Anmeldung gegen die Benutzerkonten ({@link
 *       systems.grebe.devtools.mcp.web.WebLogin}); Zugriff je View per {@code @PermitAll} bzw.
 *       {@code @RolesAllowed} mit dem Namen eines Systemrechts (z.B. {@code USERS_MANAGE}). Rechte und Status liest
 *       {@link SessionRefreshFilter} bei jeder Anfrage neu.</li>
 * </ol>
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain graphQlSecurity(HttpSecurity http) throws Exception {
        return http.securityMatcher("/graphql", "/graphql/**", BlobController.PATH + "/**", "/api/**")
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(c -> c.disable())
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain webSecurity(HttpSecurity http, AccountService accounts) throws Exception {
        return http.with(VaadinSecurityConfigurer.vaadin(), c -> c.loginView(LoginView.class))
                .addFilterAfter(new SessionRefreshFilter(accounts), SecurityContextHolderFilter.class)
                .build();
    }
}
