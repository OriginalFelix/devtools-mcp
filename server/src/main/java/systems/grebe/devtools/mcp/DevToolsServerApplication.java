package systems.grebe.devtools.mcp;

import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.theme.aura.Aura;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Team-Server: Web-UI für Benutzer, Profile, Einstellungen und Projekte sowie die REST-API, über die sich die
 * Desktop-Apps Einstellungen, Projekte und Skills holen. Selbst kein MCP-Server – Tools laufen nur in der Desktop-App.
 *
 * <p>Start als Jar mit eingebettetem Jetty ({@code java -jar devtools-server.jar}) oder als WAR in WildFly
 * ({@link WildFlyInitializer}). Zugleich App-Shell der Web-UI mit dem Vaadin-Theme Aura.
 */
@SpringBootApplication
@StyleSheet(Aura.STYLESHEET)
public class DevToolsServerApplication implements AppShellConfigurator {

    public static void main(String[] args) {
        SpringApplication.run(DevToolsServerApplication.class, args);
    }
}
