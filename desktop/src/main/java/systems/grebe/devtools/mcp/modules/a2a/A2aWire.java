package systems.grebe.devtools.mcp.modules.a2a;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import systems.grebe.devtools.mcp.modules.ticket.spi.HttpJson;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Drahtformat von A2A in beiden verbreiteten Fassungen: v1.0 ({@code SendMessage}, {@code TASK_STATE_COMPLETED},
 * {@code ROLE_USER}, Parts ohne {@code kind}) und v0.3 ({@code message/send}, {@code completed}, {@code user},
 * {@code {"kind":"text"}}). Intern gelten die Zustände in der Schreibweise von v0.3 ({@code input-required}).
 */
final class A2aWire {

    static final String V1 = "1.0";
    static final String V03 = "0.3";

    private A2aWire() {
    }

    /** {@code TASK_STATE_INPUT_REQUIRED} oder {@code input-required} → {@code input-required}. */
    static String state(String wire) {
        if (wire == null || wire.isBlank()) {
            return "unknown";
        }
        return wire.replaceFirst("^TASK_STATE_", "").toLowerCase(Locale.ROOT).replace('_', '-');
    }

    static String wireState(String state, boolean v1) {
        return v1 ? "TASK_STATE_" + state.toUpperCase(Locale.ROOT).replace('-', '_') : state;
    }

    /** Fertig: keine weiteren Nachrichten an den Task. */
    static boolean terminal(String state) {
        return switch (state) {
            case "completed", "failed", "canceled", "rejected" -> true;
            default -> false;
        };
    }

    /** Wartet auf den Aufrufer (Rückfrage, Anmeldung). */
    static boolean interrupted(String state) {
        return state.equals("input-required") || state.equals("auth-required");
    }

    /** Text aller Parts einer Nachricht bzw. eines Artefakts; Daten als JSON, Dateien als Verweis. */
    static String text(JsonNode withParts) {
        List<String> out = new ArrayList<>();
        for (JsonNode p : withParts.path("parts")) {
            String text = HttpJson.text(p.path("text"));
            if (text != null) {
                out.add(text);
            } else if (p.has("data")) {
                out.add(p.path("data").toString());
            } else {
                JsonNode file = p.has("file") ? p.path("file") : p; // v0.3: {"kind":"file","file":{…}}
                String name = HttpJson.first(HttpJson.text(file.path("filename")), HttpJson.text(file.path("name")));
                String url = HttpJson.first(HttpJson.text(file.path("url")), HttpJson.text(file.path("uri")));
                if (name != null || url != null) {
                    out.add("[Datei " + (name == null ? "" : name + " ") + (url == null ? "(eingebettet)" : url) + "]");
                }
            }
        }
        return String.join("\n", out);
    }

    static ObjectNode textPart(String text, boolean v1) {
        ObjectNode part = HttpJson.object();
        if (!v1) {
            part.put("kind", "text");
        }
        return part.put("text", text);
    }

    static String newId() {
        return UUID.randomUUID().toString();
    }

    /** Nachricht mit einem Text-Part; {@code taskId}/{@code contextId} dürfen fehlen. */
    static ObjectNode message(String messageId, boolean agent, String text, String taskId, String contextId,
                              boolean v1) {
        ObjectNode m = HttpJson.object();
        if (!v1) {
            m.put("kind", "message");
        }
        m.put("messageId", messageId);
        m.put("role", v1 ? (agent ? "ROLE_AGENT" : "ROLE_USER") : (agent ? "agent" : "user"));
        m.putArray("parts").add(textPart(text, v1));
        if (taskId != null) {
            m.put("taskId", taskId);
        }
        if (contextId != null) {
            m.put("contextId", contextId);
        }
        return m;
    }

    static boolean fromAgent(JsonNode message) {
        return message.path("role").asString("").toLowerCase(Locale.ROOT).endsWith("agent"); // agent, ROLE_AGENT
    }

    /** JSON-RPC-Fehlerantwort. */
    static ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode r = HttpJson.object();
        r.put("jsonrpc", "2.0");
        r.set("id", id == null ? HttpJson.JSON.nullNode() : id);
        r.putObject("error").put("code", code).put("message", message);
        return r;
    }
}
