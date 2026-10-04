package systems.grebe.devtools.mcp.modules.chat.matrix;

import java.util.Arrays;
import java.util.function.Predicate;

import systems.grebe.devtools.mcp.modules.chat.spi.ChatSystem;
import tools.jackson.databind.JsonNode;

/** Timeline-Ereignisse ({@code m.room.message}, {@code m.room.encrypted}) → {@link ChatSystem.Message}. */
final class MatrixEvents {

    /** Präfix der Transaktions-IDs des Moduls – der Homeserver liefert sie dem sendenden Gerät zurück. */
    static final String TXN_PREFIX = "dtmcp-";

    static final String ENCRYPTED_TEXT = "[Ende-zu-Ende-verschlüsselt – nicht lesbar. Das Matrix-Modul unterstützt "
            + "keine Verschlüsselung: einen unverschlüsselten Raum verwenden oder den Homeserver über Pantalaimon anbinden.]";

    private MatrixEvents() {
    }

    /**
     * Nachricht aus einem Timeline-Ereignis; {@code null} für andere Typen und entfernte (redigierte) Nachrichten.
     *
     * @param me      eigene Matrix-ID
     * @param trusted ob ein (fremder) Absender freigegeben ist
     */
    static ChatSystem.Message message(String roomId, JsonNode event, String me, Predicate<String> trusted) {
        String type = event.path("type").asString("");
        String eventId = event.path("event_id").asString("");
        String sender = event.path("sender").asString("");
        long ts = event.path("origin_server_ts").asLong(0);
        boolean fromMe = sender.equals(me);
        boolean own = event.path("unsigned").path("transaction_id").asString("").startsWith(TXN_PREFIX);
        boolean ok = fromMe || trusted.test(sender);
        if ("m.room.encrypted".equals(type)) {
            return new ChatSystem.Message(eventId, roomId, sender, null, ts, ENCRYPTED_TEXT, null, null, null, own,
                    fromMe, ok);
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
        return new ChatSystem.Message(eventId, roomId, sender, null, ts, body, replyTo, threadRoot, editOf, own, fromMe, ok);
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
}
