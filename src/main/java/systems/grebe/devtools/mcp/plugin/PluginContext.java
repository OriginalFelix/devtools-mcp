package systems.grebe.devtools.mcp.plugin;

import java.nio.file.Path;
import java.util.Optional;

import org.slf4j.Logger;
import systems.grebe.devtools.mcp.core.ToolModule;

/** Was die App einem Plugin bereitstellt. Eine Instanz je Plugin. */
public interface PluginContext {

    /** Version der Plugin-API, gegen die die laufende App gebaut ist ({@link PluginApi#VERSION}). */
    int apiVersion();

    PluginDescriptor descriptor();

    /** Logger {@code plugin.<name>}. */
    Logger logger();

    /** Eigener Datenordner {@code plugins/<name>/}, wird beim ersten Zugriff angelegt. */
    Path dataFolder();

    /**
     * Registriert ein Modul – es erscheint wie ein eingebautes Modul in der App (Schalter, Formular, Tool-Liste) und
     * seine Tools am MCP-Server. Nur zulässig, solange das Plugin aktiv ist bzw. aktiviert wird. Die Modul-ID muss
     * app-weit eindeutig sein (Kleinbuchstaben/Ziffern); Konfiguration und Schalter bleiben über Deinstallation und
     * Updates hinweg erhalten.
     *
     * @throws IllegalArgumentException bei ungültiger oder bereits vergebener ID
     * @throws IllegalStateException    wenn das Plugin nicht aktiv ist
     */
    void registerModule(ToolModule module);

    /** Ein anderes aktives Plugin (z.B. eine Abhängigkeit aus {@code depend}). */
    Optional<DevToolsPlugin> plugin(String name);
}
