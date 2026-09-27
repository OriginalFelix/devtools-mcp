package systems.grebe.devtools.mcp;

import java.util.Map;

import javafx.application.Application;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import systems.grebe.devtools.mcp.config.SettingsStore;
import systems.grebe.devtools.mcp.fx.FxApp;

/**
 * Einstiegspunkt. Die Klasse erweitert bewusst NICHT {@link Application}, damit die App auch
 * aus einem Fat-Jar (JavaFX auf dem Classpath) startet.
 */
@SpringBootApplication
public class DevToolsMcpApplication {

    public static void main(String[] args) {
        Application.launch(FxApp.class, args);
    }

    /**
     * Startet den Spring-Kontext (inkl. MCP-Server). Die Einstellungen werden vorab geladen, weil der
     * HTTP-Port vor dem Start des Webservers feststehen muss.
     */
    public static ConfigurableApplicationContext startSpring(String[] args) {
        SettingsStore store = new SettingsStore(SettingsStore.defaultHome());
        return new SpringApplicationBuilder(DevToolsMcpApplication.class)
                .headless(false) // AWT-SystemTray
                .properties(Map.of("server.port", store.server().port()))
                .initializers(ctx -> ctx.getBeanFactory().registerSingleton("settingsStore", store))
                .run(args);
    }
}
