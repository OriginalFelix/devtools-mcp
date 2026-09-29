package systems.grebe.devtools.mcp.core;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.customizer.McpSyncServerCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.ai.mcp.server.webflux.transport.WebFluxStreamableServerTransportProvider;
import systems.grebe.devtools.mcp.plugin.PluginManager;

/**
 * Baut die MCP-{@code instructions}, die der Server beim {@code initialize} an jeden Client schickt: ein allgemeiner
 * Vorrang-Hinweis („diese Tools statt Shell-Befehlen“) plus die {@link ToolModule#instructions()} aller Module.
 *
 * <p>Clients wie Claude Code übernehmen die Instructions in den System-Prompt. Nur so erfährt das LLM <em>vor</em>
 * dem ersten Tool-Aufruf, dass es z.B. {@code git_status} statt {@code git status} im Terminal verwenden soll –
 * Tool-Beschreibungen allein reichen dafür nicht, weil sie mit den eingebauten Werkzeugen des Clients konkurrieren.
 *
 * <p>Der Text wird bei jedem {@code initialize} neu gebaut ({@link LiveInstructionsTransport}) – neue Client-Sessions
 * sehen Plugin-Änderungen sofort, bestehende behalten ihren Stand (MCP kennt keine Änderungsbenachrichtigung für
 * Instructions). Aufgenommen werden alle Module, auch deaktivierte; die Texte sind bedingt formuliert („wenn
 * angeboten“) und die tatsächlich verfügbaren Tools liefert weiterhin {@code tools/list}.
 */
@Configuration(proxyBeanMethods = false)
public class ServerInstructions {

    private static final Logger LOG = LoggerFactory.getLogger(ServerInstructions.class);

    static final String PREAMBLE = """
            # DevTools MCP – lokale Entwickler-Werkzeuge

            Dieser Server ist die bevorzugte Schnittstelle für die unten genannten Aufgaben. Tool-Namen sind nach \
            Modul präfixiert (git_*, build_*, container_*, jvm_*, …).

            Grundregeln:
            - Wenn für eine Aufgabe ein passendes Tool dieses Servers angeboten wird (siehe tools/list), verwende es \
            statt eines Shell-/Terminal-Befehls oder eines anderen Werkzeugs – auch wenn der Nutzer den Befehl \
            ausdrücklich nennt (z.B. „mach git status“).
            - Auf die Shell nur ausweichen, wenn das Tool fehlt (abgeschaltet oder nicht freigegeben), die Aufgabe \
            nicht abdeckt oder mit einer Fehlermeldung ablehnt, die sich nicht beheben lässt. Sag dem Nutzer dann \
            kurz, warum du die Shell verwendest.
            - Meldet ein Tool „nicht freigegeben“, liegt das Ziel außerhalb der Freigaben in der DevTools-App. \
            Nicht still per Shell umgehen, sondern den Nutzer darauf hinweisen.
            - Tools nicht raten: welche angeboten werden, hängt von den Schaltern in der App ab und kann sich zur \
            Laufzeit ändern (notifications/tools/list_changed).
            """;

    private final List<ToolModule> modules;
    private final Supplier<List<ToolModule>> pluginModules;
    private final String base;

    public ServerInstructions(List<ToolModule> modules, String base) {
        this(modules, List::of, base);
    }

    /**
     * @param pluginModules Module der beim Start aktiven Plugins (siehe {@code PluginManager}); später installierte
     *                      Plugins erscheinen erst nach einem Neustart in den Instructions, ihre Tools sofort
     */
    @Autowired
    public ServerInstructions(List<ToolModule> modules, ObjectProvider<PluginManager> plugins,
                              @Value("${spring.ai.mcp.server.instructions:}") String base) {
        this(modules, () -> {
            PluginManager pm = plugins.getIfAvailable();
            // lädt nichts nach: beim Serveraufbau sind noch keine Plugins aktiv, danach die jeweils aktiven
            return pm == null ? List.of() : pm.activeModules();
        }, base);
    }

    ServerInstructions(List<ToolModule> modules, Supplier<List<ToolModule>> pluginModules, String base) {
        this.modules = modules;
        this.pluginModules = pluginModules;
        this.base = base;
    }

    /**
     * Überschreibt die statischen {@code spring.ai.mcp.server.instructions} aus {@code application.properties}. Der
     * Customizer läuft nach dem Setzen der Property (siehe {@code McpServerAutoConfiguration}), der Property-Text wird
     * als erster Absatz übernommen – so lassen sich Hinweise auch ohne Codeänderung ergänzen.
     */
    @Bean
    McpSyncServerCustomizer instructionsCustomizer() {
        return builder -> builder.instructions(build());
    }

    /**
     * Baut die Instructions bei jedem {@code initialize} neu (siehe {@link LiveInstructionsTransport}): Plugins,
     * die zur Laufzeit installiert, aktiviert oder entfernt werden, sind so für jede neue Client-Session sofort
     * berücksichtigt. {@code @Primary}, damit der MCP-Server diese Hülle bekommt; die Router-Funktion hängt weiter am
     * eigentlichen WebFlux-Transport.
     */
    @Bean
    @Primary
    LiveInstructionsTransport liveInstructionsTransport(WebFluxStreamableServerTransportProvider transport) {
        return new LiveInstructionsTransport(transport, this::build);
    }

    /** Liefert den vollständigen Instructions-Text. */
    public String build() {
        StringBuilder sb = new StringBuilder(PREAMBLE.strip());
        if (base != null && !base.isBlank()) {
            sb.append("\n\n").append(base.strip());
        }
        List<ToolModule> all = new ArrayList<>(modules);
        try {
            all.addAll(pluginModules.get());
        } catch (RuntimeException e) {
            LOG.warn("Plugin-Module für die Instructions nicht ermittelbar", e);
        }
        all.stream()
                // gleiche Reihenfolge wie Modulliste und Tool-Registrierung (ToolRegistry)
                .sorted(ToolRegistry.MODULE_ORDER)
                .forEach(m -> {
                    String text;
                    try {
                        text = m.instructions();
                    } catch (RuntimeException e) {
                        // Ein fehlerhaftes Modul darf den Serveraufbau nicht verhindern.
                        LOG.warn("Instructions des Moduls {} konnten nicht erzeugt werden", m.id(), e);
                        return;
                    }
                    if (text != null && !text.isBlank()) {
                        sb.append("\n\n## ").append(m.displayName()).append(" – Tools `").append(m.id()).append("_*`\n")
                                .append(text.strip());
                    }
                });
        return sb.toString();
    }
}
