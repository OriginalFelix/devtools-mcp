package systems.grebe.devtools.mcp;

import java.util.Arrays;
import java.util.Map;

import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.theme.aura.Aura;
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
 * <p>Mit {@code --headless} (oder {@code DEVTOOLS_MCP_HEADLESS=true}) startet nur der Server samt Web-UI, ohne
 * JavaFX-Fenster und Tray – für den Betrieb als Team-Server, z.B.
 * {@code java -jar devtools-mcp.jar --headless --server.address=0.0.0.0}.
 *
 * <p>Zugleich App-Shell der Web-UI: legt das Vaadin-Theme (Aura) ausdrücklich fest, statt es beim ersten
 * Request automatisch laden zu lassen.
 */
@SpringBootApplication
@StyleSheet(Aura.STYLESHEET)
public class DevToolsMcpApplication implements AppShellConfigurator {

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
        return configure(new SpringApplicationBuilder(), store, headless)
                .properties(Map.of("server.port", store.server().port()))
                .run(springArgs);
    }

    /** Gemeinsame Konfiguration für den Start mit eingebettetem Jetty und als WAR ({@link WildFlyInitializer}). */
    static SpringApplicationBuilder configure(SpringApplicationBuilder builder, SettingsStore store, boolean headless) {
        return builder.sources(DevToolsMcpApplication.class)
                .headless(headless) // ohne headless: AWT-SystemTray
                .initializers(ctx -> ctx.getBeanFactory().registerSingleton("settingsStore", store));
    }
}
