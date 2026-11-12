package systems.grebe.devtools.mcp.modules.a2a;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolRegistry;
import systems.grebe.devtools.mcp.modules.ticket.spi.HttpJson;
import systems.grebe.devtools.mcp.server.LocalOnlyFilter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * HTTP-Seite des A2A-Servers: Agent Card und JSON-RPC-Endpunkt. Aktiv nur mit eingeschaltetem Modul und Schalter
 * „Als A2A-Agent erreichbar“. Von anderen Rechnern nur mit Zugriffstoken.
 */
@RestController
public class A2aController {

    public static final String CARD = "/.well-known/agent-card.json";
    public static final String RPC = "/a2a";

    private final ToolRegistry registry;
    private final A2aServer server;

    public A2aController(ToolRegistry registry, A2aServer server) {
        this.registry = registry;
        this.server = server;
    }

    @GetMapping(path = CARD, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> card(@RequestHeader(name = "A2A-Version", required = false) String version) {
        ModuleConfig config = config();
        if (config == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(HttpJson.JSON.writeValueAsString(card(config,
                ServletUriComponentsBuilder.fromCurrentContextPath().path(RPC).toUriString(),
                version != null && version.trim().startsWith("1."))));
    }

    @PostMapping(path = RPC, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> rpc(@RequestBody String body,
                                      @RequestHeader(name = "A2A-Version", required = false) String version,
                                      @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String auth,
                                      HttpServletRequest request) {
        ModuleConfig config = config();
        if (config == null) {
            return ResponseEntity.notFound().build();
        }
        String token = config.getString(A2aModule.SERVER_TOKEN, "").trim();
        if (token.isEmpty() ? !LocalOnlyFilter.local(request.getRemoteAddr()) : !matches(auth, token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"a2a\"").build();
        }
        JsonNode req;
        try {
            req = HttpJson.JSON.readTree(body);
        } catch (RuntimeException e) {
            return ResponseEntity.ok(HttpJson.JSON.writeValueAsString(A2aWire.error(null, -32700, "Kein gültiges JSON.")));
        }
        if (!req.isObject()) {
            return ResponseEntity.ok(HttpJson.JSON.writeValueAsString(
                    A2aWire.error(null, -32600, "Erwartet ein JSON-RPC-Objekt (keine Stapel).")));
        }
        Duration wait = Duration.ofSeconds(Math.max(0, config.getInt(A2aModule.WAIT, 120)));
        return ResponseEntity.ok(HttpJson.JSON.writeValueAsString(
                server.handle(req, version, request.getRemoteAddr(), wait)));
    }

    /** Konfiguration, wenn der Server aktiv ist, sonst {@code null}. */
    private ModuleConfig config() {
        if (!registry.hasModule(A2aModule.ID) || !registry.settings(A2aModule.ID).enabled()) {
            return null;
        }
        ModuleConfig config = registry.config(A2aModule.ID);
        return config.getBoolean(A2aModule.SERVER) ? config : null;
    }

    private static boolean matches(String header, String token) {
        return header != null && header.regionMatches(true, 0, "Bearer ", 0, 7) && MessageDigest.isEqual(
                header.substring(7).trim().getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Agent Card. Ohne {@code A2A-Version: 1.x} in der Form von v0.3 ({@code url}, {@code protocolVersion}), aber mit
     * {@code supportedInterfaces}, damit auch v1.0-Clients den Endpunkt finden.
     */
    static ObjectNode card(ModuleConfig config, String rpcUrl, boolean v1) {
        ObjectNode c = HttpJson.object();
        c.put("name", config.getString(A2aModule.SERVER_NAME, "DevTools Claude"));
        c.put("description", config.getString(A2aModule.SERVER_DESCRIPTION, "Claude-Code-Sitzung"));
        c.put("version", "1.0.0");
        ArrayNode interfaces = c.putArray("supportedInterfaces");
        interfaces.addObject().put("url", rpcUrl).put("protocolBinding", "JSONRPC").put("protocolVersion", A2aWire.V1);
        interfaces.addObject().put("url", rpcUrl).put("protocolBinding", "JSONRPC").put("protocolVersion", A2aWire.V03);
        if (!v1) {
            c.put("protocolVersion", "0.3.0");
            c.put("url", rpcUrl);
            c.put("preferredTransport", "JSONRPC");
        }
        c.putObject("capabilities").put("streaming", false).put("pushNotifications", false);
        c.putArray("defaultInputModes").add("text/plain");
        c.putArray("defaultOutputModes").add("text/plain");
        ObjectNode skill = c.putArray("skills").addObject();
        skill.put("id", "assistant");
        skill.put("name", "Entwicklungsassistent");
        skill.put("description", "Beantwortet Fragen und erledigt Aufgaben mit Claude Code und den lokalen "
                + "Entwicklerwerkzeugen. Antworten kommen, sobald die Sitzung sie gibt – ggf. später per GetTask.");
        skill.putArray("tags").add("claude").add("development");
        if (!config.getString(A2aModule.SERVER_TOKEN, "").isBlank()) {
            ObjectNode bearer = c.putObject("securitySchemes").putObject("bearer");
            if (v1) {
                bearer.putObject("httpAuthSecurityScheme").put("scheme", "Bearer");
                c.putArray("securityRequirements").addObject().putObject("schemes").putObject("bearer")
                        .putArray("list");
            } else {
                bearer.put("type", "http").put("scheme", "bearer");
                c.putArray("security").addObject().putArray("bearer");
            }
        }
        return c;
    }
}
