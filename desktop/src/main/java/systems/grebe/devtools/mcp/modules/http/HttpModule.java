package systems.grebe.devtools.mcp.modules.http;

import java.nio.file.Path;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.config.Home;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolBeans;
import systems.grebe.devtools.mcp.core.ToolModule;
import systems.grebe.devtools.mcp.core.ToolScope;

/** HTTP(S)-Verkehr eines Prozesses über einen lokalen Proxy mitschneiden, auswerten und mocken (wie Fiddler). */
@Component
public class HttpModule implements ToolModule {

    private static final Logger LOG = LoggerFactory.getLogger(HttpModule.class);

    @Override
    public String id() {
        return "http";
    }

    @Override
    public String displayName() {
        return "HTTP-Proxy (Mitschnitt & Mocks)";
    }

    @Override
    public String description() {
        return "Lokaler HTTP(S)-Proxy wie Fiddler: schneidet den Verkehr eines Prozesses mit (HTTPS über eine eigene "
                + "CA entschlüsselt), wertet ihn aus und beantwortet ausgewählte Anfragen mit Mocks.";
    }

    @Override
    public String instructions() {
        return """
                Welche HTTP-Aufrufe ein Prozess macht (REST-Clients, externe APIs), mit `http_proxy_start` \
                mitschneiden statt Logs oder tcpdump: das Tool nennt Umgebungsvariablen bzw. JVM-Optionen, mit \
                denen der Prozess über den Proxy läuft – Prozess damit (neu) starten. Dann `http_traffic` (Liste), \
                `http_exchange` (Header und Bodies) und `http_analyze` (langsame Endpunkte, Fehler, Wiederholungen, \
                N+1). Fehlerfälle, Timeouts oder noch nicht existierende APIs mit `http_mock_add` simulieren. Meldet \
                der Mitschnitt „TLS-Handshake fehlgeschlagen“, vertraut der Prozess der CA nicht. Für Browser und \
                Desktop-Programme, die sich nicht neu starten lassen: `http_proxy_start` mit system=true und hosts \
                (System-Proxy wie Fiddler) plus einmal `http_ca_trust`; danach immer `http_proxy_stop`.""";
    }

    @Override
    public List<ToolCallback> createTools(ModuleConfig config) {
        return createTools(config, ToolScope.LOCAL);
    }

    @Override
    public List<ToolCallback> createTools(ModuleConfig config, ToolScope scope) {
        // Proxys laufen über Konfigurationsänderungen hinweg weiter und enden mit dem Scope
        var proxies = scope.state("http.proxies", HttpProxyTools.Proxies::new);
        Path dir = Home.defaultHome().resolve("http-proxy");
        var system = new SystemProxy(dir.resolve("system-proxy.properties"), SystemProxy.LOCAL, SystemProxy.Os.current());
        if (proxies.systemPort == null && system.active()) {
            // Sicherung ohne laufenden Proxy: die App ist abgestürzt, während sie System-Proxy war
            try {
                system.restore();
            } catch (RuntimeException e) {
                LOG.warn("System-Proxy nach Absturz nicht zurückgesetzt: {}", e.getMessage());
            }
        }
        return ToolBeans.callbacks(new HttpProxyTools(dir, proxies, system));
    }
}
