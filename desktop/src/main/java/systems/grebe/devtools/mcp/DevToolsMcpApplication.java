package systems.grebe.devtools.mcp;

import java.util.Arrays;
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
 *
 * <p>Mit {@code --headless} (oder {@code DEVTOOLS_MCP_HEADLESS=true}) startet nur der MCP-Server, ohne
 * JavaFX-Fenster und Tray. Benutzer, Profile und Skills für mehrere Entwickler verwaltet der separate
 * Team-Server ({@code server}-Projekt).
 */
@SpringBootApplication
public class DevToolsMcpApplication {

    public static final String HEADLESS_ARG = "--headless";

    public static void main(String[] args) {
        if (headless(args)) {
            startSpring(args, true);
        } else {
            Application.launch(FxApp.class, args);
        }
    }

    static boolean headless(String[] args) {
        return Arrays.asList(args).contains(HEADLESS_ARG)
                || Boolean.parseBoolean(System.getenv("DEVTOOLS_MCP_HEADLESS"));
    }

    /** Start aus der Desktop-App (JavaFX, Tray). */
    public static ConfigurableApplicationContext startSpring(String[] args) {
        return startSpring(args, false);
    }

    /**
     * Startet den Spring-Kontext (inkl. MCP-Server). Die Einstellungen werden vorab geladen, weil der
     * HTTP-Port vor dem Start des Webservers feststehen muss.
     */
    static ConfigurableApplicationContext startSpring(String[] args, boolean headless) {
        SettingsStore store = new SettingsStore(SettingsStore.defaultHome());
        String[] springArgs = Arrays.stream(args).filter(a -> !HEADLESS_ARG.equals(a)).toArray(String[]::new);
        return new SpringApplicationBuilder(DevToolsMcpApplication.class)
                .headless(headless) // ohne headless: AWT-SystemTray
                .properties(Map.of("server.port", store.server().port()))
                .initializers(ctx -> ctx.getBeanFactory().registerSingleton("settingsStore", store))
                .run(springArgs);
    }
}
