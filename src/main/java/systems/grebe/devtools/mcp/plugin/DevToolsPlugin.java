package systems.grebe.devtools.mcp.plugin;

import java.nio.file.Path;
import java.util.Objects;

import org.slf4j.Logger;
import systems.grebe.devtools.mcp.core.ToolModule;

/**
 * Basisklasse eines Plugins – das Gegenstück zu Bukkits {@code JavaPlugin}. Die in {@code plugin.yml} unter
 * {@code main} genannte Klasse erweitert sie.
 *
 * <p>Jedes Plugin hat einen eigenen Spring-Kontext; die Hauptklasse ist darin eine Bean und Konfigurationsklasse.
 * {@code @Component}s im Paket der Hauptklasse werden gefunden, {@code @Autowired}/Konstruktor-Injektion,
 * {@code @Value}, {@code @Bean}, {@code @PostConstruct}/{@code @PreDestroy} funktionieren, Beans der App (z.B.
 * {@code SettingsStore}, {@code ToolRegistry}) sind injizierbar. {@code ToolModule}-Beans werden automatisch als
 * Module aufgenommen:
 *
 * <pre>{@code
 * public class JiraPlugin extends DevToolsPlugin { }        // reicht, wenn JiraModule ein @Component ist
 *
 * @Component
 * class JiraModule implements ToolModule {
 *     JiraModule(JiraClient client, PluginContext plugin) { … }
 * }
 * }</pre>
 *
 * <p>Ohne Spring-Annotationen geht es wie bei Bukkit: {@code registerModule(new JiraModule(dataFolder()))} in
 * {@link #onEnable()}.
 *
 * <p>Lebenszyklus: Spring-Kontext aufbauen (Beans, {@code @PostConstruct}) → {@link #onLoad()} →
 * {@code ToolModule}-Beans aufnehmen → {@link #onEnable()}; beim Deaktivieren, Deinstallieren, Aktualisieren oder
 * Beenden der App {@link #onDisable()} → Module entfernen → Kontext schließen ({@code @PreDestroy}). Eine Exception in {@code onLoad}/{@code onEnable}
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
