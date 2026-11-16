package systems.grebe.devtools.mcp.modules.http;

import java.util.List;

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
                der Mitschnitt „TLS-Handshake fehlgeschlagen“, vertraut der Prozess der CA nicht.""";
    }

    @Override
    public List<ToolCallback> createTools(ModuleConfig config) {
        return createTools(config, ToolScope.LOCAL);
    }

    @Override
    public List<ToolCallback> createTools(ModuleConfig config, ToolScope scope) {
        // Proxys laufen über Konfigurationsänderungen hinweg weiter und enden mit dem Scope
        var proxies = scope.state("http.proxies", HttpProxyTools.Proxies::new);
        return ToolBeans.callbacks(new HttpProxyTools(Home.defaultHome().resolve("http-proxy"), proxies));
    }
}
