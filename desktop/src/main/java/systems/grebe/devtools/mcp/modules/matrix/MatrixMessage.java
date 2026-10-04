package systems.grebe.devtools.mcp.modules.matrix;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;

import tools.jackson.databind.JsonNode;

/**
 * Eine Nachricht aus einem Raum, aufbereitet für das LLM.
 *
 * @param seq        Reihenfolge im Eingang (steigt mit jedem verarbeiteten Ereignis)
 * @param replyTo    Ereignis, auf das geantwortet wird, oder {@code null}
 * @param threadRoot Wurzel des Threads oder {@code null}
 * @param editOf     bei Bearbeitungen das ursprüngliche Ereignis, sonst {@code null}
 */
record MatrixMessage(long seq, String roomId, String eventId, String sender, long timestamp, String body,
                     String replyTo, String threadRoot, String editOf, boolean encrypted) {

    static final String ENCRYPTED_TEXT = "[Ende-zu-Ende-verschlüsselt – nicht lesbar. Das Matrix-Modul unterstützt "
            + "keine Verschlüsselung: einen unverschlüsselten Raum verwenden oder den Homeserver über Pantalaimon anbinden.]";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    /**
     * Aus einem Timeline-Ereignis ({@code m.room.message} oder {@code m.room.encrypted}); {@code null} für andere Typen
     * und entfernte (redigierte) Nachrichten.
     */
    static MatrixMessage of(long seq, String roomId, JsonNode event) {
        String type = event.path("type").asString("");
        String eventId = event.path("event_id").asString("");
        String sender = event.path("sender").asString("");
        long ts = event.path("origin_server_ts").asLong(0);
        if ("m.room.encrypted".equals(type)) {
            return new MatrixMessage(seq, roomId, eventId, sender, ts, ENCRYPTED_TEXT, null, null, null, true);
        }
        if (!"m.room.message".equals(type)) {
            return null;
        }
        JsonNode content = event.path("content");
        if (!content.has("msgtype") && !content.has("body")) {
            return null; // redigiert
        }
        JsonNode rel = content.path("m.relates_to");
        String relType = rel.path("rel_type").asString("");
        String replyTo = text(rel.path("m.in_reply_to").path("event_id"));
        String threadRoot = "m.thread".equals(relType) ? text(rel.path("event_id")) : null;
        String editOf = "m.replace".equals(relType) ? text(rel.path("event_id")) : null;
        // Fallback-Antwort im Thread ist keine echte Antwort
        if (threadRoot != null && rel.path("is_falling_back").asBoolean(false)) {
            replyTo = null;
        }
        JsonNode shown = editOf != null && content.path("m.new_content").isObject() ? content.path("m.new_content") : content;
        String body = describe(shown);
        if (replyTo != null) {
            body = stripReplyFallback(body);
        }
        return new MatrixMessage(seq, roomId, eventId, sender, ts, body, replyTo, threadRoot, editOf, false);
    }

    /** Text der Nachricht; Dateien, Bilder und Orte als Platzhalter mit Name und Adresse. */
    private static String describe(JsonNode content) {
        String body = content.path("body").asString("");
        String msgtype = content.path("msgtype").asString("m.text");
        String media = switch (msgtype) {
            case "m.image" -> "Bild";
            case "m.audio" -> "Audio";
            case "m.video" -> "Video";
            case "m.file" -> "Datei";
            default -> null;
        };
        if (media != null) {
            // body ist der Dateiname oder – wenn filename gesetzt ist – eine Bildunterschrift
            String name = content.path("filename").asString(body);
            String url = content.path("url").asString("");
            return "[" + media + ": " + name + (url.isEmpty() ? "" : " – " + url) + "]"
                    + (name.equals(body) ? "" : "\n" + body);
        }
        return switch (msgtype) {
            case "m.emote" -> "* " + body;
            case "m.location" -> body + " [Ort: " + content.path("geo_uri").asString("?") + "]";
            default -> body;
        };
    }

    /** Ältere Clients stellen einer Antwort das Zitat („> …“ und Leerzeile) voran. */
    static String stripReplyFallback(String body) {
        if (!body.startsWith("> ")) {
            return body;
        }
        String[] lines = body.split("\n", -1);
        int i = 0;
        while (i < lines.length && lines[i].startsWith(">")) {
            i++;
        }
        if (i < lines.length && lines[i].isBlank()) {
            i++;
        }
        return i >= lines.length ? body : String.join("\n", Arrays.copyOfRange(lines, i, lines.length));
    }

    private static String text(JsonNode n) {
        String s = n.isString() ? n.asString() : null;
        return s == null || s.isBlank() ? null : s;
    }

    String time() {
        return timestamp <= 0 ? "?" : TIME.format(Instant.ofEpochMilli(timestamp));
    }

    /**
     * Kopfzeile und Text, z.B. {@code [2026-10-04 17:22:05] @felix:example.org in „DevTools“ (event $abc, Antwort auf $xyz):}.
     *
     * @param roomLabel Anzeigename des Raums oder {@code null}, wenn er nicht genannt werden soll
     */
    String format(String roomLabel, boolean own) {
        StringBuilder sb = new StringBuilder("[").append(time()).append("] ").append(sender);
        if (own) {
            sb.append(" (ich)");
        }
        if (roomLabel != null) {
            sb.append(" in ").append(roomLabel);
        }
        sb.append(" (event ").append(eventId);
        if (replyTo != null) {
            sb.append(", Antwort auf ").append(replyTo);
        }
        if (threadRoot != null) {
            sb.append(", Thread ").append(threadRoot);
        }
        if (editOf != null) {
            sb.append(", bearbeitet ").append(editOf);
        }
        return sb.append("):\n").append(body.strip()).toString();
    }

    /** Ob die Nachricht auf {@code eventId} antwortet (direkt oder im Thread dieses Ereignisses). */
    boolean answers(String eventId) {
        return eventId.equals(replyTo) || eventId.equals(threadRoot);
    }
}
