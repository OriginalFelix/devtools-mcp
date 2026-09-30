package systems.grebe.devtools.mcp;

import java.util.Map;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;
import systems.grebe.devtools.mcp.config.SettingsStore;

/**
 * Einstieg beim Deployment als WAR in einen externen Servlet-Container (WildFly, {@code ./gradlew war}). Läuft immer
 * headless als Team-Server; Port und Bind-Adresse bestimmt der Container, nicht {@code settings.json}.
 *
 * <p>{@code server.address} aus {@code application.properties} gilt im Container nicht – der Loopback-Check von
 * {@link systems.grebe.devtools.mcp.server.McpAccess} sähe sonst {@code 127.0.0.1} und ließe Anfragen ohne Token
 * zu. Deshalb ist der anonyme lokale Zugriff hier standardmäßig aus.
 */
public class WildFlyInitializer extends SpringBootServletInitializer {

    @Override
    protected SpringApplicationBuilder configure(SpringApplicationBuilder builder) {
        SettingsStore store = new SettingsStore(SettingsStore.defaultHome());
        return DevToolsMcpApplication.configure(builder, store, true)
                .properties(Map.of("devtools.mcp.allow-anonymous-local", "false"));
    }
}
