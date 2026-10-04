package systems.grebe.devtools.mcp.modules.matrix;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.Text;
import systems.grebe.devtools.mcp.core.ToolHints;
import systems.grebe.devtools.mcp.core.ToolProgress;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Matrix-Tools: Räume, Senden, Fragen mit Warten auf die Antwort, neue Nachrichten, Verlauf, Reaktionen. */
public class MatrixTools {

    private static final String ROOM_PARAM = "Raum: ID (!…:server), Alias (#…:server) oder Name; leer = Standardraum";
    /** Matrix begrenzt Ereignisse auf 64 KiB; etwas Luft für Hülle und Relationen. */
    private static final int MAX_EVENT_BYTES = 60_000;
    private static final int MAX_RECEIVE = 50;
    private static final String HISTORY_FILTER = "{\"types\":[\"m.room.message\",\"m.room.encrypted\"]}";

    private final MatrixEnvironment env;

    MatrixTools(MatrixEnvironment env) {
        this.env = env;
    }

    @Tool(name = "rooms", description = "Matrix-Konto, Standardraum, freigegebene Absender und die Räume, in denen das "
            + "Konto Mitglied ist (ID, Name, Alias, Mitglieder, verschlüsselt?), dazu offene Einladungen."
            + ShellHints.MATRIX)
    @ToolHints(readOnly = true)
    public String rooms() {
        MatrixEnvironment.Session s = env.session();
        MatrixInbox.Policy policy = env.policy();
        s.inbox().catchUp(policy);
        StringBuilder sb = new StringBuilder("Angemeldet als ").append(s.userId()).append(" auf ")
                .append(s.client().baseUrl()).append('\n');
        String defaultRoom = null;
        if (env.defaultRoom() != null) {
            try {
                defaultRoom = env.resolve(env.defaultRoom());
                sb.append("Standardraum: ").append(s.inbox().roomLabel(defaultRoom)).append(" – ").append(defaultRoom);
            } catch (RuntimeException e) {
                sb.append("Standardraum ").append(env.defaultRoom()).append(": ").append(e.getMessage());
            }
            sb.append('\n');
        }
        sb.append("Freigegebene Absender: ").append(env.hasTrustedSenders() ? String.join(", ", env.trustedSenders())
                : "alle Raummitglieder (keine Liste gesetzt)").append("\n\n");
        List<String> joined = s.client().joinedRooms().stream().filter(policy::roomAllowed).toList();
        sb.append("Räume (").append(joined.size()).append("):\n");
        for (String roomId : joined.stream().limit(50).toList()) {
            env.roomName(roomId);
            int members = s.client().joinedMembers(roomId).path("joined").size();
            sb.append("  ").append(roomId).append("  ").append(s.inbox().roomLabel(roomId))
                    .append("  ").append(members).append(" Mitglieder");
            if (roomId.equals(defaultRoom)) {
                sb.append("  [Standard]");
            }
            if (s.inbox().encrypted(roomId)) {
                sb.append("  [verschlüsselt – Nachrichten nicht lesbar]");
            }
            sb.append('\n');
        }
        if (joined.isEmpty()) {
            sb.append("  (keine – das Konto in einen Raum einladen")
                    .append(env.hasTrustedSenders() ? "; Einladungen freigegebener Absender werden angenommen)\n" : ")\n");
        }
        List<MatrixInbox.Invite> invites = s.inbox().invites();
        if (!invites.isEmpty()) {
            sb.append("\nOffene Einladungen:\n");
            invites.forEach(i -> sb.append("  ").append(i.roomId()).append(i.name() == null ? "" : "  „" + i.name() + "“")
                    .append("  von ").append(i.inviter()).append('\n'));
        }
        appendNotices(sb, s.inbox());
        return sb.toString().strip();
    }

    @Tool(name = "send", description = "Sendet eine Nachricht (Markdown) in einen Matrix-Raum, optional als Antwort auf "
            + "eine Nachricht oder in deren Thread. Für Statusmeldungen, Ergebnisse und Benachrichtigungen an den Nutzer."
            + " Erwartest du eine Antwort, matrix_ask verwenden." + ShellHints.MATRIX)
    @ToolHints(destructive = false)
    public String send(
            @ToolParam(description = "Nachricht, Markdown erlaubt (wird als HTML formatiert)") String message,
            @ToolParam(required = false, description = ROOM_PARAM + " bzw. der Raum von replyTo") String room,
            @ToolParam(required = false, description = "Event-ID der Nachricht, auf die geantwortet wird ($…)") String replyTo,
            @ToolParam(required = false, description = "true = im Thread von replyTo antworten statt als Antwort im Raum") Boolean thread) {
        String roomId = env.room(room, replyTo);
        String eventId = sendMessage(roomId, message, replyTo, Boolean.TRUE.equals(thread));
        return "Gesendet in " + env.inbox().roomLabel(roomId) + " (event " + eventId + ")." + encryptionWarning(roomId);
    }

    @Tool(name = "ask", description = "Stellt dem Nutzer per Matrix eine Frage und wartet auf die Antwort (blockiert bis "
            + "waitSeconds). Für Rückfragen, Entscheidungen und Freigaben, wenn der Nutzer per Matrix erreichbar sein "
            + "will. Als Antwort zählt eine Antwort/Thread-Nachricht auf die Frage, sonst die erste Nachricht eines "
            + "freigegebenen Absenders im Raum danach. Ohne Antwort bis zum Ablauf: später mit matrix_receive nachsehen."
            + ShellHints.MATRIX)
    @ToolHints(destructive = false)
    public String ask(
            @ToolParam(description = "Frage, Markdown erlaubt; Antwortmöglichkeiten ggf. nennen") String question,
            @ToolParam(required = false, description = ROOM_PARAM) String room,
            @ToolParam(required = false, description = "Höchstens so lange warten (Sekunden); leer = Standard aus der App") Integer waitSeconds,
            @ToolParam(required = false, description = "Event-ID einer Nachricht, auf die sich die Frage bezieht (wird als Antwort darauf gesendet)") String replyTo) {
        String roomId = env.room(room, replyTo);
        MatrixInbox.Policy policy = env.policy();
        MatrixInbox inbox = env.inbox();
        // Stand holen, bevor die Frage rausgeht: ältere Nachrichten gelten so nicht als Antwort
        inbox.catchUp(policy);
        String questionId = sendMessage(roomId, question, replyTo, false);
        int wait = env.waitSeconds(waitSeconds, env.askWaitSeconds());
        long start = System.currentTimeMillis();
        String label = inbox.roomLabel(roomId);
        List<MatrixMessage> answer = inbox.await(policy, () -> inbox.takeAnswer(roomId, questionId), wait * 1000L,
                () -> ToolProgress.report("Warte auf Antwort in " + label + " … "
                        + (System.currentTimeMillis() - start) / 1000 + "/" + wait + " s"));
        StringBuilder sb = new StringBuilder();
        if (answer.isEmpty() && inbox.encrypted(roomId)) {
            sb.append("Keine lesbare Antwort: ").append(label).append(" ist Ende-zu-Ende-verschlüsselt, Antworten kann ")
                    .append("das Matrix-Modul nicht entschlüsseln. Einen unverschlüsselten Raum verwenden.");
        } else if (answer.isEmpty()) {
            sb.append("Keine Antwort innerhalb von ").append(wait).append(" s (Frage: event ").append(questionId)
                    .append(" in ").append(label).append("). Eine spätere Antwort liefert matrix_receive – sie ist dort ")
                    .append("als „Antwort auf ").append(questionId).append("“ erkennbar, wenn der Nutzer direkt antwortet.");
        } else {
            sb.append("Antwort:\n");
            answer.forEach(m -> sb.append(m.format(null, false)).append("\n\n"));
            markRead(answer);
        }
        int more = inbox.pendingCount(m -> true);
        if (more > 0) {
            sb.append("\n").append(more).append(" weitere ungelesene Nachricht(en) – matrix_receive.");
        }
        appendNotices(sb, inbox);
        return Text.limitLines(sb.toString().strip(), env.maxLines());
    }

    @Tool(name = "receive", description = "Neue Matrix-Nachrichten seit dem letzten Abruf (Anweisungen, Antworten), "
            + "optional mit Warten, bis etwas eingeht. Jede Nachricht wird nur einmal geliefert. Soll auf Anweisungen "
            + "gewartet werden, mit waitSeconds in einer Schleife aufrufen." + ShellHints.MATRIX)
    @ToolHints(destructive = false)
    public String receive(
            @ToolParam(required = false, description = "Nur diesen Raum (ID, #alias oder Name); leer = alle freigegebenen Räume") String room,
            @ToolParam(required = false, description = "Warten, bis mindestens eine Nachricht da ist (Sekunden); leer/0 = nicht warten") Integer waitSeconds,
            @ToolParam(required = false, description = "Höchstens so viele Nachrichten (Standard 20, max. 50)") Integer limit) {
        MatrixInbox.Policy policy = env.policy();
        MatrixInbox inbox = env.inbox();
        String roomId = room == null || room.isBlank() ? null : env.room(room, null);
        Predicate<MatrixMessage> filter = m -> roomId == null || m.roomId().equals(roomId);
        int max = Math.max(1, Math.min(MAX_RECEIVE, limit == null ? 20 : limit));
        int wait = env.waitSeconds(waitSeconds, 0);
        long start = System.currentTimeMillis();
        List<MatrixMessage> messages = inbox.await(policy, () -> inbox.take(filter, max), wait * 1000L,
                () -> ToolProgress.report("Warte auf Nachrichten … " + (System.currentTimeMillis() - start) / 1000
                        + "/" + wait + " s"));
        StringBuilder sb = new StringBuilder();
        if (messages.isEmpty()) {
            sb.append(wait > 0 ? "Keine neuen Nachrichten innerhalb von " + wait + " s." : "Keine neuen Nachrichten.");
        } else {
            sb.append("Neue Nachrichten (").append(messages.size()).append("):\n\n");
            messages.forEach(m -> sb.append(m.format(inbox.roomLabel(m.roomId()), false)).append("\n\n"));
            markRead(messages);
        }
        int more = inbox.pendingCount(filter);
        if (more > 0) {
            sb.append("\n").append(more).append(" weitere – erneut matrix_receive aufrufen.");
        }
        if (!env.hasTrustedSenders()) {
            sb.append("\nHinweis: keine freigegebenen Absender konfiguriert – geliefert werden Nachrichten aller "
                    + "Raummitglieder.");
        }
        appendNotices(sb, inbox);
        return Text.limitLines(sb.toString().strip(), env.maxLines());
    }

    @Tool(name = "history", description = "Die letzten Nachrichten eines Matrix-Raums (Kontext, Vorgeschichte, eigene "
            + "Nachrichten). Ändert nichts daran, was matrix_receive als neu liefert." + ShellHints.MATRIX)
    @ToolHints(readOnly = true)
    public String history(
            @ToolParam(required = false, description = ROOM_PARAM) String room,
            @ToolParam(required = false, description = "Anzahl (Standard 20, max. 100)") Integer limit) {
        String roomId = env.room(room, null);
        MatrixEnvironment.Session s = env.session();
        MatrixInbox.Policy policy = env.policy();
        int n = Math.max(1, Math.min(100, limit == null ? 20 : limit));
        JsonNode chunk = s.client().messages(roomId, n, HISTORY_FILTER).path("chunk");
        describe(roomId);
        List<String> lines = new ArrayList<>();
        int hidden = 0;
        for (int i = chunk.size() - 1; i >= 0; i--) {
            JsonNode event = chunk.get(i);
            MatrixMessage m = MatrixMessage.of(0, roomId, event);
            if (m == null) {
                continue;
            }
            s.inbox().noteEvent(roomId, m.eventId());
            boolean own = m.sender().equals(s.userId());
            if (!own && !policy.trusted(m.sender())) {
                hidden++;
                continue;
            }
            lines.add(m.format(null, own));
        }
        StringBuilder sb = new StringBuilder("Verlauf ").append(s.inbox().roomLabel(roomId)).append(" – ")
                .append(roomId).append(" (älteste zuerst):\n\n");
        sb.append(lines.isEmpty() ? "(keine Nachrichten)" : String.join("\n\n", lines));
        if (hidden > 0) {
            sb.append("\n\n").append(hidden).append(" Nachricht(en) nicht freigegebener Absender ausgeblendet.");
        }
        return Text.limitLines(sb.toString().strip(), env.maxLines());
    }

    @Tool(name = "react", description = "Reagiert mit einem Emoji auf eine Matrix-Nachricht – z.B. 👀 beim Beginnen einer "
            + "Anweisung und ✅ wenn erledigt, ohne eigene Nachricht." + ShellHints.MATRIX)
    @ToolHints(destructive = false, idempotent = true)
    public String react(
            @ToolParam(description = "Event-ID der Nachricht ($…)") String eventId,
            @ToolParam(description = "Emoji oder kurzer Text, z.B. 👍, ✅, 👀") String reaction,
            @ToolParam(required = false, description = ROOM_PARAM + " bzw. der Raum der Nachricht") String room) {
        if (eventId == null || !eventId.strip().startsWith("$")) {
            throw new IllegalArgumentException("'eventId' muss eine Event-ID sein ($…, siehe matrix_receive).");
        }
        if (reaction == null || reaction.isBlank()) {
            throw new IllegalArgumentException("'reaction' fehlt, z.B. ✅.");
        }
        String roomId = env.room(room, eventId);
        ObjectNode content = MatrixClient.JSON.createObjectNode();
        ObjectNode rel = content.putObject("m.relates_to");
        rel.put("rel_type", "m.annotation");
        rel.put("event_id", eventId.strip());
        rel.put("key", reaction.strip());
        String id = env.client().sendEvent(roomId, "m.reaction", content);
        return "Reaktion " + reaction.strip() + " auf " + eventId.strip() + " gesendet (event " + id + ").";
    }

    // ------------------------------------------------------------------ intern

    private String sendMessage(String roomId, String message, String replyTo, boolean thread) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("Leere Nachricht – Text angeben.");
        }
        describe(roomId);
        String text = env.prefix().isEmpty() ? message.strip() : env.prefix().strip() + " " + message.strip();
        ObjectNode content = MatrixClient.JSON.createObjectNode();
        content.put("msgtype", "m.text");
        content.put("body", text);
        String html = MatrixMarkdown.html(text);
        if (html != null) {
            content.put("format", "org.matrix.custom.html");
            content.put("formatted_body", html);
        }
        String reply = replyTo == null || replyTo.isBlank() ? null : replyTo.strip();
        if (reply != null) {
            ObjectNode rel = content.putObject("m.relates_to");
            if (thread) {
                String root = threadRoot(roomId, reply);
                rel.put("rel_type", "m.thread");
                rel.put("event_id", root);
                // Antwort auf die Wurzel selbst ist nur die Thread-Zuordnung, kein Zitat
                rel.put("is_falling_back", root.equals(reply));
            }
            rel.putObject("m.in_reply_to").put("event_id", reply);
        } else if (thread) {
            throw new IllegalArgumentException("'thread' braucht 'replyTo' (Event-ID einer Nachricht im Thread).");
        }
        int size = MatrixClient.JSON.writeValueAsString(content).getBytes(StandardCharsets.UTF_8).length;
        if (size > MAX_EVENT_BYTES) {
            throw new IllegalArgumentException("Nachricht zu lang (" + size / 1024 + " KiB, Matrix erlaubt rund 60 KiB) "
                    + "– kürzen oder auf mehrere Nachrichten aufteilen.");
        }
        String eventId = env.client().sendEvent(roomId, "m.room.message", content);
        env.inbox().noteEvent(roomId, eventId);
        return eventId;
    }

    /** Name, Alias und Verschlüsselung eines Raums einmalig nachladen, wenn der Sync sie noch nicht geliefert hat. */
    private void describe(String roomId) {
        if (!env.inbox().described(roomId)) {
            env.roomName(roomId);
        }
    }

    private String encryptionWarning(String roomId) {
        return env.inbox().encrypted(roomId) ? "\nHinweis: Der Raum ist Ende-zu-Ende-verschlüsselt – die Nachricht ging "
                + "unverschlüsselt raus, Antworten dort kann das Matrix-Modul nicht lesen." : "";
    }

    /** Wurzel des Threads, in dem {@code eventId} steht – oder das Ereignis selbst, wenn es (noch) keinen Thread hat. */
    private String threadRoot(String roomId, String eventId) {
        JsonNode rel = env.client().event(roomId, eventId).path("content").path("m.relates_to");
        return "m.thread".equals(rel.path("rel_type").asString("")) ? rel.path("event_id").asString(eventId) : eventId;
    }

    /** Lesebestätigung je Raum bis zur letzten gelieferten Nachricht – der Nutzer sieht so, dass sie angekommen ist. */
    private void markRead(List<MatrixMessage> messages) {
        if (!env.readReceipts()) {
            return;
        }
        Map<String, String> last = new LinkedHashMap<>();
        messages.forEach(m -> last.put(m.roomId(), m.eventId()));
        last.forEach((roomId, eventId) -> {
            try {
                env.client().receipt(roomId, eventId);
            } catch (RuntimeException e) {
                // Komfort – die Nachricht ist zugestellt
            }
        });
    }

    private static void appendNotices(StringBuilder sb, MatrixInbox inbox) {
        List<String> notices = inbox.drainNotices();
        if (!notices.isEmpty()) {
            sb.append("\n\nHinweise:\n");
            notices.forEach(n -> sb.append("- ").append(n).append('\n'));
        }
    }
}
