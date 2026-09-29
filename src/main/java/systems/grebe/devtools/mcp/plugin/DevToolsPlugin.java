package systems.grebe.devtools.mcp.plugin;

import java.nio.file.Path;
import java.util.Objects;

import org.slf4j.Logger;
import systems.grebe.devtools.mcp.core.ToolModule;

/**
 * Basisklasse eines Plugins – das Gegenstück zu Bukkits {@code JavaPlugin}. Die in {@code plugin.yml} unter
 * {@code main} genannte Klasse erweitert sie und braucht einen öffentlichen Konstruktor ohne Parameter.
 *
 * <pre>{@code
 * public class JiraPlugin extends DevToolsPlugin {
 *     @Override
 *     public void onEnable() {
 *         registerModule(new JiraModule(dataFolder()));
 *     }
 * }
 * }</pre>
 *
 * <p>Lebenszyklus: {@link #onLoad()} direkt nach dem Laden (alle Abhängigkeiten sind geladen), dann
 * {@link #onEnable()}; {@link #onDisable()} beim Deaktivieren, Deinstallieren, Aktualisieren oder Beenden der App.
 * Registrierte Module werden beim Deaktivieren automatisch entfernt. Eine Exception in {@code onLoad}/{@code onEnable}
 * lässt nur dieses Plugin scheitern; die UI zeigt den Fehler.
 *
 * <p>Plugins laufen im Prozess der App mit denselben Rechten – nur Plugins aus vertrauenswürdigen Quellen installieren.
 */
public abstract class DevToolsPlugin {

    private PluginContext context;

    /** Wird vom Plugin-Manager vor {@link #onLoad()} aufgerufen. */
    final void attach(PluginContext ctx) {
        if (this.context != null) {
            throw new IllegalStateException("Plugin bereits initialisiert");
        }
        this.context = Objects.requireNonNull(ctx);
    }

    /** Nach dem Laden, vor {@link #onEnable()}. */
    public void onLoad() {
    }

    /** Aktivieren: Module registrieren, Ressourcen öffnen. */
    public void onEnable() {
    }

    /** Deaktivieren: Ressourcen schließen, Threads beenden. Module entfernt der Manager selbst. */
    public void onDisable() {
    }

    public final PluginContext context() {
        if (context == null) {
            throw new IllegalStateException("Plugin noch nicht initialisiert – Zugriff erst ab onLoad()");
        }
        return context;
    }

    public final PluginDescriptor descriptor() {
        return context().descriptor();
    }

    public final Logger logger() {
        return context().logger();
    }

    /** Eigener Datenordner {@code plugins/<name>/}, wird beim ersten Zugriff angelegt. */
    public final Path dataFolder() {
        return context().dataFolder();
    }

    /** Kurzform für {@link PluginContext#registerModule(ToolModule)}. */
    protected final void registerModule(ToolModule module) {
        context().registerModule(module);
    }
}
