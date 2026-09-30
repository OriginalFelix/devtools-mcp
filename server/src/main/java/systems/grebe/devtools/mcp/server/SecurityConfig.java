package systems.grebe.devtools.mcp.server;

import com.vaadin.flow.spring.security.VaadinSecurityConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import systems.grebe.devtools.mcp.account.Sha3Pbkdf2PasswordEncoder;
import systems.grebe.devtools.mcp.account.TokenService;
import systems.grebe.devtools.mcp.web.LoginView;

/**
 * Zwei Filterketten:
 *
 * <ol>
 *   <li>{@code /api/**}: REST-API für die Desktop-Apps – zustandslos, ohne CSRF und Session, angemeldet per
 *       Desktop-Token ({@link ApiTokenFilter}).</li>
 *   <li>alles andere: Vaadin-Web-UI mit Formular-Anmeldung gegen die Benutzerkonten
 *       ({@link systems.grebe.devtools.mcp.account.AccountService}); Zugriff je View per {@code @PermitAll} /
 *       {@code @RolesAllowed}.</li>
 * </ol>
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfig {

    public static final String API = "/api";

    @Bean
    Sha3Pbkdf2PasswordEncoder passwordEncoder() {
        return new Sha3Pbkdf2PasswordEncoder();
    }

    @Bean
    @Order(1)
    SecurityFilterChain apiSecurity(HttpSecurity http, TokenService tokens) throws Exception {
        return http.securityMatcher(API + "/**")
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(c -> c.disable())
                .addFilterBefore(new ApiTokenFilter(tokens), AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain webSecurity(HttpSecurity http) throws Exception {
        return http.with(VaadinSecurityConfigurer.vaadin(), c -> c.loginView(LoginView.class)).build();
    }
}
