package systems.grebe.devtools.mcp.modules.a2a;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import systems.grebe.devtools.mcp.modules.ticket.spi.HttpJson;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Spricht einen entfernten A2A-Agenten über JSON-RPC an. Die Agent Card entscheidet über Endpunkt und Fassung: v1.0,
 * wenn ein JSON-RPC-Interface mit {@code protocolVersion 1.x} angeboten wird, sonst v0.3.
 *
 * @param url   Basis-URL des Agenten oder direkt die URL seiner Agent Card
 * @param token Bearer-Token oder leer
 */
record A2aClient(String name, String url, String token, Duration timeout) {

    static final String CARD_PATH = "/.well-known/agent-card.json";
    private static final AtomicLong IDS = new AtomicLong();

    /** Endpunkt aus der Agent Card. */
    record Endpoint(String url, boolean v1) {
    }

    JsonNode card() {
        String base = HttpJson.stripSlash(url);
        if (base.endsWith(".json")) {
            return http(null).getJson(base);
        }
        try {
            return http(null).getJson(base + CARD_PATH);
        } catch (HttpJson.StatusException e) {
            if (e.status() != 404) {
                throw e;
            }
            return http(null).getJson(base + "/.well-known/agent.json"); // vor v0.3
        }
    }

    static Endpoint endpoint(JsonNode card) {
        Endpoint legacy = null;
        for (JsonNode i : card.path("supportedInterfaces")) {
            String binding = i.path("protocolBinding").asString(i.path("transport").asString(""));
            String u = HttpJson.text(i.path("url"));
            if (!"JSONRPC".equalsIgnoreCase(binding) || u == null) {
                continue;
            }
            if (i.path("protocolVersion").asString("").startsWith("1.")) {
                return new Endpoint(u, true);
            }
            legacy = legacy == null ? new Endpoint(u, false) : legacy;
        }
        if (legacy != null) {
            return legacy;
        }
        String u = HttpJson.text(card.path("url"));
        String transport = card.path("preferredTransport").asString("JSONRPC");
        if (u == null || !"JSONRPC".equalsIgnoreCase(transport)) {
            throw new IllegalStateException("Agent bietet kein JSON-RPC an – nur diese Bindung wird unterstützt.");
        }
        return new Endpoint(u, false);
    }

    /** Sendet Text; {@code wait} = auf Abschluss oder Rückfrage warten. Ergebnis: Task oder Nachricht. */
    JsonNode send(String text, String taskId, String contextId, boolean wait) {
        Endpoint ep = endpoint(card());
        ObjectNode params = HttpJson.object();
        params.set("message", A2aWire.message(A2aWire.newId(), false, text, taskId, contextId, ep.v1()));
        if (ep.v1()) {
            params.putObject("configuration").put("returnImmediately", !wait);
        } else {
            params.putObject("configuration").put("blocking", wait);
        }
        JsonNode r = call(ep, "SendMessage", "message/send", params);
        return ep.v1() ? (r.has("task") ? r.path("task") : r.path("message")) : r;
    }

    JsonNode task(String taskId) {
        Endpoint ep = endpoint(card());
        return call(ep, "GetTask", "tasks/get", HttpJson.object().put("id", taskId));
    }

    JsonNode cancel(String taskId) {
        Endpoint ep = endpoint(card());
        return call(ep, "CancelTask", "tasks/cancel", HttpJson.object().put("id", taskId));
    }

    private JsonNode call(Endpoint ep, String v1Method, String v03Method, ObjectNode params) {
        ObjectNode req = HttpJson.object();
        req.put("jsonrpc", "2.0");
        req.put("id", IDS.incrementAndGet());
        req.put("method", ep.v1() ? v1Method : v03Method);
        req.set("params", params);
        JsonNode res = http(ep.v1() ? A2aWire.V1 : A2aWire.V03).post(ep.url(), req).body();
        JsonNode error = res.path("error");
        if (error.isObject()) {
            throw new IllegalStateException("A2A-Agent " + name + ": Fehler " + error.path("code").asString("?") + " – "
                    + error.path("message").asString(""));
        }
        if (!res.has("result")) {
            throw new IllegalStateException("A2A-Agent " + name + ": Antwort ohne result.");
        }
        return res.path("result");
    }

    private HttpJson http(String version) {
        Map<String, String> headers = new HashMap<>();
        if (token != null && !token.isBlank()) {
            headers.put("Authorization", "Bearer " + token.trim());
        }
        if (version != null) {
            headers.put("A2A-Version", version);
        }
        return new HttpJson("A2A-Agent " + name, "", headers, timeout, "Agent2Agent");
    }
}
