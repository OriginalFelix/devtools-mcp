package systems.grebe.devtools.mcp.intellij;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.intellij.openapi.application.ApplicationInfo;

/**
 * MCP über Streamable HTTP ({@code POST /mcp}), zustandslos und ohne SSE: jede Anfrage bekommt ihre Antwort als JSON,
 * Benachrichtigungen ein {@code 202}. Zwei Tools – {@code editor} und {@code cursor}, wie {@code ide_editor} und
 * {@code ide_cursor} der DevTools-App, aber ohne deren Projekt-Freigaben: der Client sieht alle offenen Projekte.
 */
final class McpEndpoint {

    static final List<String> VERSIONS = List.of("2025-11-25", "2025-06-18", "2025-03-26");

    private static final String INSTRUCTIONS = """
            Die IDE des Nutzers. `editor`: was er gerade offen bzw. markiert hat – aufrufen, wenn er sich auf „das \
            hier“, „die Markierung“ oder „diese Datei/Methode“ bezieht. `cursor`: dein eigener, beschrifteter Cursor \
            in seinem Editor; setzen, bevor du eine Datei bearbeitest oder wenn du eine Stelle erklärst – er sieht, \
            wo du bist, und verfolgt deine Änderungen live. Am Ende ohne `file` aufrufen (entfernt ihn).""";

    private McpEndpoint() {
    }

    /** Antwort auf eine JSON-RPC-Nachricht; {@code null} für Benachrichtigungen und Antworten des Clients. */
    static String handle(String body) {
        JsonObject req;
        try {
            req = JsonParser.parseString(body).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            return error(null, -32700, "Kein JSON-RPC-Objekt");
        }
        JsonElement id = req.get("id");
        if (id == null || !req.has("method")) {
            return null;
        }
        JsonObject params = req.has("params") ? req.getAsJsonObject("params") : new JsonObject();
        return switch (req.get("method").getAsString()) {
            case "initialize" -> result(id, initialize(params));
            case "ping" -> result(id, new JsonObject());
            case "tools/list" -> result(id, tools());
            case "tools/call" -> result(id, call(params));
            default -> error(id, -32601, "Unbekannte Methode " + req.get("method").getAsString());
        };
    }

    private static JsonObject initialize(JsonObject params) {
        String asked = params.has("protocolVersion") ? params.get("protocolVersion").getAsString() : "";
        JsonObject r = new JsonObject();
        r.addProperty("protocolVersion", VERSIONS.contains(asked) ? asked : VERSIONS.get(1));
        JsonObject caps = new JsonObject();
        caps.add("tools", new JsonObject());
        r.add("capabilities", caps);
        JsonObject info = new JsonObject();
        info.addProperty("name", "devtools-mcp-intellij");
        info.addProperty("version", ApplicationInfo.getInstance().getFullApplicationName());
        r.add("serverInfo", info);
        r.addProperty("instructions", INSTRUCTIONS);
        return r;
    }

    private static JsonObject tools() {
        JsonArray tools = new JsonArray();
        tools.add(tool("editor", "Was der Nutzer gerade in seiner IDE vor sich hat: aktive Datei mit Cursor-Position, "
                + "markierter Text und weitere offene Dateien – je geöffnetem Projekt.", Map.of(), true));
        Map<String, String[]> cursor = new LinkedHashMap<>();
        cursor.put("file", new String[]{"string", "Datei: absoluter Pfad oder relativ zum zuletzt aktiven Projekt. "
                + "Leer = Cursor entfernen"});
        cursor.put("line", new String[]{"integer", "Zeile (ab 1), Standard 1"});
        cursor.put("column", new String[]{"integer", "Spalte (ab 1), Standard 1"});
        cursor.put("endLine", new String[]{"integer", "Ende eines markierten Bereichs: Zeile (ab 1)"});
        cursor.put("endColumn", new String[]{"integer", "Ende des Bereichs: Spalte (ab 1), Standard Zeilenende"});
        cursor.put("label", new String[]{"string", "Beschriftung, Standard „Claude“"});
        tools.add(tool("cursor", "Zeigt dem Nutzer in seiner IDE, wo du gerade bist: ein eigener, beschrifteter "
                + "Cursor (wie bei Code With Me) an Zeile/Spalte, mit endLine/endColumn ein markierter Bereich. Die IDE "
                + "folgt ihm und zeigt danach deine Änderungen an der Datei live mit. Ohne file entfernen.", cursor,
                false));
        JsonObject r = new JsonObject();
        r.add("tools", tools);
        return r;
    }

    private static JsonObject tool(String name, String description, Map<String, String[]> props, boolean readOnly) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject properties = new JsonObject();
        props.forEach((k, v) -> {
            JsonObject p = new JsonObject();
            p.addProperty("type", v[0]);
            p.addProperty("description", v[1]);
            properties.add(k, p);
        });
        schema.add("properties", properties);
        JsonObject annotations = new JsonObject();
        annotations.addProperty("readOnlyHint", readOnly);
        annotations.addProperty("destructiveHint", false);
        annotations.addProperty("openWorldHint", false);
        JsonObject t = new JsonObject();
        t.addProperty("name", name);
        t.addProperty("description", description);
        t.add("inputSchema", schema);
        t.add("annotations", annotations);
        return t;
    }

    private static JsonObject call(JsonObject params) {
        String name = params.has("name") ? params.get("name").getAsString() : "";
        JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject()
                ? params.getAsJsonObject("arguments") : new JsonObject();
        String text;
        boolean error = false;
        try {
            text = switch (name) {
                case "editor" -> EditorState.text(EditorState.json());
                case "cursor" -> AgentCursor.getInstance().apply(cursorParams(args));
                default -> throw new IllegalArgumentException("Unbekanntes Tool " + name);
            };
        } catch (RuntimeException e) {
            text = String.valueOf(e.getMessage());
            error = true;
        }
        JsonObject content = new JsonObject();
        content.addProperty("type", "text");
        content.addProperty("text", text);
        JsonArray list = new JsonArray();
        list.add(content);
        JsonObject r = new JsonObject();
        r.add("content", list);
        r.addProperty("isError", error);
        return r;
    }

    private static Map<String, String> cursorParams(JsonObject args) {
        Map<String, String> p = new LinkedHashMap<>();
        args.entrySet().forEach(e -> {
            if (!e.getValue().isJsonNull()) {
                p.put(e.getKey(), e.getValue().getAsString());
            }
        });
        String file = p.getOrDefault("file", "");
        if (file.isBlank()) {
            return Map.of("clear", "true");
        }
        if (!Path.of(file).isAbsolute()) {
            for (JsonElement e : EditorState.json().getAsJsonArray("projects")) {
                JsonObject o = e.getAsJsonObject();
                if (o.get("active").getAsBoolean()) {
                    p.put("file", Path.of(o.get("path").getAsString()).resolve(file).normalize().toString());
                }
            }
        }
        return p;
    }

    private static String result(JsonElement id, JsonObject result) {
        JsonObject r = new JsonObject();
        r.addProperty("jsonrpc", "2.0");
        r.add("id", id);
        r.add("result", result);
        return r.toString();
    }

    private static String error(JsonElement id, int code, String message) {
        JsonObject e = new JsonObject();
        e.addProperty("code", code);
        e.addProperty("message", message);
        JsonObject r = new JsonObject();
        r.addProperty("jsonrpc", "2.0");
        r.add("id", id);
        r.add("error", e);
        return r.toString();
    }
}
