package systems.grebe.devtools.mcp.modules.chat.matrix;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import systems.grebe.devtools.mcp.modules.chat.spi.ChatSettings;
import systems.grebe.devtools.mcp.modules.chat.spi.ChatSystem;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Matrix über die Client-Server-API: Räume als Unterhaltungen, {@code /sync} mit Long-Polling als Eingang. Beim
 * allerersten Abruf gelten die Nachrichten als neu, die der Server als ungelesen zählt ({@code unread_notifications},
 * gemessen an der Lesebestätigung des Kontos). Einladungen freigegebener Absender werden beim Abruf angenommen.
 */
final class MatrixChat implements ChatSystem {

    static final int INITIAL_LIMIT = 20;
    static final int TIMELINE_LIMIT = 50;
    /** Matrix begrenzt Ereignisse auf 64 KiB; etwas Luft für Hülle und Relationen. */
    static final int MAX_EVENT_BYTES = 60_000;
    private static final String HISTORY_FILTER = "{\"types\":[\"m.room.message\",\"m.room.encrypted\"]}";
    private static final String ENCRYPTED_NOTE = "verschlüsselt – Nachrichten nicht lesbar";

    private final ChatSettings settings;
    private final List<String> rooms;
    private final List<String> trusted;
    private final boolean autoJoin;
    private final Map<String, String> aliasIds = new ConcurrentHashMap<>();

    // ------------------------------------------------------------------ geschützt durch this
    private MatrixClient client;
    private String userId;
    private final Map<String, String> names = new HashMap<>();
    private final Map<String, String> canonicalAliases = new HashMap<>();
    private final Set<String> encrypted = new HashSet<>();
    /** Räume, deren Name/Alias/Verschlüsselung abgefragt wurden (der Sync liefert nur gesetzte Werte). */
    private final Set<String> described = new HashSet<>();
    /** Offene Einladungen: Raum → Einladender. */
    private final Map<String, String> invites = new LinkedHashMap<>();

    MatrixChat(ChatSettings settings) {
        this.settings = settings;
        this.rooms = settings.getList(MatrixChatProvider.ROOMS);
        this.trusted = settings.getList(MatrixChatProvider.TRUSTED).stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
        this.autoJoin = settings.getBoolean(MatrixChatProvider.AUTO_JOIN, true);
    }

    @Override
    public String id() {
        return MatrixChatProvider.ID;
    }

    // ------------------------------------------------------------------ Sitzung

    synchronized MatrixClient client() {
        if (client == null) {
            String homeserver = settings.get(MatrixChatProvider.HOMESERVER).orElseThrow(() -> new IllegalStateException(
                    "Matrix: Homeserver-URL fehlt – in der DevTools-App unter Module → Chat → Matrix eintragen."));
            String token = settings.getString(MatrixChatProvider.TOKEN, "");
            String user = settings.getString(MatrixChatProvider.USER, "");
            String password = settings.getString(MatrixChatProvider.PASSWORD, "");
            if (token.isEmpty() && (user.isEmpty() || password.isEmpty())) {
                throw new IllegalStateException("Matrix: weder Zugangstoken noch Benutzer und Passwort konfiguriert – "
                        + "in der DevTools-App unter Module → Chat → Matrix eintragen.");
            }
            for (String t : trusted) {
                if (!t.startsWith("@") || !t.contains(":")) {
                    throw new IllegalStateException("Matrix: '" + t + "' in 'Freigegebene Absender' ist keine Matrix-ID "
                            + "(Form @benutzer:server).");
                }
            }
            client = new MatrixClient(homeserver, token, user, password, settings.timeout());
        }
        return client;
    }

    synchronized String userId() {
        if (userId == null) {
            String id = client().whoami().path("user_id").asString("");
            if (id.isEmpty()) {
                throw new IllegalStateException("Matrix: Homeserver nennt keinen Benutzer zum Token (whoami).");
            }
            userId = id;
        }
        return userId;
    }

    @Override
    public Account account() {
        String me = userId();
        return new Account(me, me, client().baseUrl() + "|" + me);
    }

    // ------------------------------------------------------------------ Freigaben

    private boolean trusted(String sender) {
        return trusted.isEmpty() || trusted.contains(sender.toLowerCase(Locale.ROOT));
    }

    /** IDs der freigegebenen Räume; leer = alle Räume, in denen das Konto Mitglied ist. */
    Set<String> allowedRooms() {
        Set<String> out = new LinkedHashSet<>();
        for (String r : rooms) {
            if (!r.startsWith("!") && !r.startsWith("#")) {
                throw new IllegalStateException("Matrix: '" + r + "' in 'Nur diese Räume' ist weder Raum-ID (!…:server) "
                        + "noch Alias (#…:server).");
            }
            out.add(r.startsWith("!") ? r : alias(r));
        }
        return out;
    }

    private boolean allowed(Set<String> allowedRooms, String roomId) {
        return allowedRooms.isEmpty() || allowedRooms.contains(roomId);
    }

    @Override
    public String senderPolicy() {
        return trusted.isEmpty() ? "alle Raummitglieder (keine Liste gesetzt)" : String.join(", ", trusted);
    }

    // ------------------------------------------------------------------ Unterhaltungen

    @Override
    public boolean ownsConversation(String ref) {
        return (ref.startsWith("!") || ref.startsWith("#")) && ref.contains(":");
    }

    @Override
    public boolean ownsMessage(String messageId) {
        return messageId.startsWith("$");
    }

    @Override
    public List<Conversation> conversations() {
        MatrixClient c = client();
        Set<String> allowed = allowedRooms();
        List<Conversation> out = new ArrayList<>();
        for (String roomId : c.joinedRooms()) {
            if (!allowed(allowed, roomId)) {
                continue;
            }
            describeRoom(roomId);
            int members = c.joinedMembers(roomId).path("joined").size();
            synchronized (this) {
                out.add(new Conversation(roomId, names.get(roomId), canonicalAliases.get(roomId), members,
                        encrypted.contains(roomId) ? ENCRYPTED_NOTE : null));
            }
        }
        synchronized (this) {
            invites.forEach((roomId, inviter) -> out.add(new Conversation(roomId, names.get(roomId), null, -1,
                    "Einladung von " + inviter + ", nicht angenommen")));
        }
        return out;
    }

    @Override
    public String resolve(String ref) {
        String roomId;
        if (ref.startsWith("!")) {
            roomId = ref;
        } else if (ref.startsWith("#")) {
            roomId = alias(ref);
        } else {
            roomId = byName(ref);
        }
        Set<String> allowed = allowedRooms();
        if (!allowed(allowed, roomId)) {
            throw new IllegalStateException("Raum " + roomId + " ist nicht freigegeben. Der Nutzer kann ihn in der "
                    + "DevTools-App unter Module → Chat → 'Matrix: Nur diese Räume' ergänzen.");
        }
        return roomId;
    }

    private String byName(String name) {
        List<String> matches = new ArrayList<>();
        for (String roomId : client().joinedRooms()) {
            describeRoom(roomId);
            String n;
            synchronized (this) {
                n = names.get(roomId);
            }
            if (n != null && n.equalsIgnoreCase(name)) {
                matches.add(roomId);
            }
        }
        if (matches.size() == 1) {
            return matches.getFirst();
        }
        throw new IllegalArgumentException(matches.isEmpty()
                ? "Kein beigetretener Raum heißt '" + name + "' – Raum-ID oder #alias angeben (siehe chat_conversations)."
                : "Mehrere Räume heißen '" + name + "': " + matches + " – Raum-ID angeben.");
    }

    private String alias(String alias) {
        MatrixClient c = client();
        return aliasIds.computeIfAbsent(alias.toLowerCase(Locale.ROOT), a -> {
            try {
                return c.resolveAlias(alias);
            } catch (MatrixClient.MatrixException e) {
                if (e.notFound()) {
                    throw new IllegalArgumentException("Matrix: Raum-Alias " + alias + " existiert nicht.", e);
                }
                throw e;
            }
        });
    }

    @Override
    public String label(String roomId) {
        describeRoom(roomId);
        synchronized (this) {
            String name = names.get(roomId);
            String alias = canonicalAliases.get(roomId);
            if (name != null) {
                return "„" + name + "“" + (alias != null ? " (" + alias + ")" : "");
            }
            return alias != null ? alias : roomId;
        }
    }

    /** Name, Alias und Verschlüsselung einmalig aus dem Raumzustand nachladen. */
    private void describeRoom(String roomId) {
        synchronized (this) {
            if (described.contains(roomId)) {
                return;
            }
        }
        MatrixClient c = client();
        JsonNode name = c.state(roomId, "m.room.name");
        JsonNode alias = c.state(roomId, "m.room.canonical_alias");
        boolean enc = c.state(roomId, "m.room.encryption") != null;
        synchronized (this) {
            described.add(roomId);
            put(names, roomId, name == null ? null : name.path("name").asString(null));
            put(canonicalAliases, roomId, alias == null ? null : alias.path("alias").asString(null));
            if (enc) {
                encrypted.add(roomId);
            }
        }
    }

    // ------------------------------------------------------------------ Senden

    @Override
    public boolean supportsThreads() {
        return true;
    }

    @Override
    public Sent send(String roomId, Outgoing message) {
        ObjectNode content = MatrixClient.JSON.createObjectNode();
        content.put("msgtype", "m.text");
        content.put("body", message.text());
        if (message.html() != null) {
            content.put("format", "org.matrix.custom.html");
            content.put("formatted_body", message.html());
        }
        if (message.replyTo() != null) {
            ObjectNode rel = content.putObject("m.relates_to");
            if (message.thread()) {
                String root = threadRoot(roomId, message.replyTo());
                rel.put("rel_type", "m.thread");
                rel.put("event_id", root);
                // Antwort auf die Wurzel selbst ist nur die Thread-Zuordnung, kein Zitat
                rel.put("is_falling_back", root.equals(message.replyTo()));
            }
            rel.putObject("m.in_reply_to").put("event_id", message.replyTo());
        }
        int size = MatrixClient.JSON.writeValueAsString(content).getBytes(StandardCharsets.UTF_8).length;
        if (size > MAX_EVENT_BYTES) {
            throw new IllegalArgumentException("Nachricht zu lang (" + size / 1024 + " KiB, Matrix erlaubt rund 60 KiB) "
                    + "– kürzen oder auf mehrere Nachrichten aufteilen.");
        }
        describeRoom(roomId);
        String eventId = client().sendEvent(roomId, "m.room.message", content);
        boolean enc;
        synchronized (this) {
            enc = encrypted.contains(roomId);
        }
        return new Sent(eventId, enc ? "Der Raum ist Ende-zu-Ende-verschlüsselt – die Nachricht ging unverschlüsselt "
                + "raus, Antworten dort kann das Modul nicht lesen." : null);
    }

    /** Wurzel des Threads, in dem {@code eventId} steht – oder das Ereignis selbst, wenn es (noch) keinen Thread hat. */
    private String threadRoot(String roomId, String eventId) {
        JsonNode rel = client().event(roomId, eventId).path("content").path("m.relates_to");
        return "m.thread".equals(rel.path("rel_type").asString("")) ? rel.path("event_id").asString(eventId) : eventId;
    }

    @Override
    public void react(String roomId, String messageId, String reaction) {
        ObjectNode content = MatrixClient.JSON.createObjectNode();
        ObjectNode rel = content.putObject("m.relates_to");
        rel.put("rel_type", "m.annotation");
        rel.put("event_id", messageId);
        rel.put("key", reaction);
        client().sendEvent(roomId, "m.reaction", content);
    }

    @Override
    public void markRead(String roomId, String messageId) {
        client().receipt(roomId, messageId);
    }

    // ------------------------------------------------------------------ Eingang

    @Override
    public Poll poll(String cursor, int timeoutMs) {
        String me = userId();
        MatrixClient c = client();
        Set<String> allowed = allowedRooms();
        boolean initial = cursor == null;
        JsonNode res = c.sync(cursor, filter(initial ? INITIAL_LIMIT : TIMELINE_LIMIT), initial ? 0 : timeoutMs);
        List<Message> messages = new ArrayList<>();
        List<String> notices = new ArrayList<>();
        JsonNode roomsNode = res.path("rooms");
        for (var entry : roomsNode.path("join").properties()) {
            String roomId = entry.getKey();
            JsonNode room = entry.getValue();
            synchronized (this) {
                invites.remove(roomId);
                readState(roomId, room.path("state").path("events"));
            }
            if (!allowed(allowed, roomId)) {
                continue;
            }
            JsonNode timeline = room.path("timeline");
            if (!initial && timeline.path("limited").asBoolean(false)) {
                notices.add("In " + label(roomId) + " kamen mehr Nachrichten als ein Abruf fasst – ältere fehlen hier, "
                        + "siehe chat_history.");
            }
            List<Message> fresh = new ArrayList<>();
            for (JsonNode event : timeline.path("events")) {
                Message m = MatrixEvents.message(roomId, event, me, this::trusted);
                // beim ersten Abruf zählt eigene Vorgeschichte nicht – „ungelesen“ meint Nachrichten anderer
                if (m != null && !(initial && m.fromMe())) {
                    fresh.add(m);
                }
            }
            if (initial) {
                int unread = room.path("unread_notifications").path("notification_count").asInt(0);
                fresh = fresh.subList(Math.max(0, fresh.size() - unread), fresh.size());
            }
            messages.addAll(fresh);
        }
        for (var entry : roomsNode.path("invite").properties()) {
            String roomId = entry.getKey();
            String inviter = null;
            String name = null;
            for (JsonNode e : entry.getValue().path("invite_state").path("events")) {
                String type = e.path("type").asString("");
                if ("m.room.member".equals(type) && me.equals(e.path("state_key").asString())) {
                    inviter = e.path("sender").asString(null);
                } else if ("m.room.name".equals(type)) {
                    name = e.path("content").path("name").asString(null);
                }
            }
            String label = name == null ? roomId : "„" + name + "“ (" + roomId + ")";
            String who = inviter == null ? "?" : inviter;
            boolean join = inviter != null && autoJoin && !trusted.isEmpty()
                    && trusted.contains(inviter.toLowerCase(Locale.ROOT)) && allowed(allowed, roomId);
            if (join) {
                // nie ohne Absenderliste: sonst könnte jeder das Konto in einen Raum holen und dort Anweisungen geben
                try {
                    c.join(roomId);
                    notices.add("Einladung von " + who + " in " + label + " angenommen.");
                    synchronized (this) {
                        invites.remove(roomId);
                    }
                } catch (RuntimeException ex) {
                    notices.add("Einladung von " + who + " in " + label + " nicht angenommen: " + ex.getMessage());
                }
                continue;
            }
            synchronized (this) {
                put(names, roomId, name);
                if (invites.put(roomId, who) == null) {
                    notices.add("Einladung von " + who + " in " + label + " (nicht automatisch angenommen).");
                }
            }
        }
        synchronized (this) {
            for (var entry : roomsNode.path("leave").properties()) {
                invites.remove(entry.getKey());
            }
        }
        return new Poll(messages, notices, res.path("next_batch").asString(cursor));
    }

    private void readState(String roomId, JsonNode events) {
        for (JsonNode e : events) {
            JsonNode content = e.path("content");
            switch (e.path("type").asString("")) {
                case "m.room.name" -> put(names, roomId, content.path("name").asString(""));
                case "m.room.canonical_alias" -> put(canonicalAliases, roomId, content.path("alias").asString(""));
                case "m.room.encryption" -> encrypted.add(roomId);
                default -> { }
            }
        }
    }

    @Override
    public List<Message> history(String roomId, int limit) {
        String me = userId();
        JsonNode chunk = client().messages(roomId, limit, HISTORY_FILTER).path("chunk");
        List<Message> out = new ArrayList<>();
        for (int i = chunk.size() - 1; i >= 0; i--) {
            Message m = MatrixEvents.message(roomId, chunk.get(i), me, this::trusted);
            if (m != null) {
                out.add(m);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ Verbindungstest

    @Override
    public Status test(String defaultConversation) {
        MatrixClient c = client();
        StringBuilder sb = new StringBuilder();
        boolean ok = true;
        JsonNode versions = c.versions().path("versions");
        sb.append("Homeserver ").append(c.baseUrl()).append(" erreichbar")
                .append(versions.isEmpty() ? "" : " (Spezifikation bis " + versions.get(versions.size() - 1).asString() + ")")
                .append(".\n");
        JsonNode who = c.whoami();
        String device = who.path("device_id").asString("");
        sb.append("Angemeldet als ").append(userId()).append(device.isEmpty() ? "" : " (Gerät " + device + ")")
                .append(settings.get(MatrixChatProvider.TOKEN).isEmpty() ? " – mit Passwort" : "").append(".\n");
        Set<String> allowed = allowedRooms();
        List<String> joined = c.joinedRooms();
        if (defaultConversation != null) {
            String roomId = resolve(defaultConversation);
            if (!joined.contains(roomId)) {
                ok = false;
                sb.append("Standardraum ").append(defaultConversation)
                        .append(": Konto ist nicht Mitglied – einladen bzw. beitreten.\n");
            } else {
                String label = label(roomId);
                boolean enc;
                synchronized (this) {
                    enc = encrypted.contains(roomId);
                }
                sb.append("Standardraum: ").append(label).append(" – ").append(roomId)
                        .append(enc ? " – Ende-zu-Ende-verschlüsselt: Nachrichten dort sind nicht lesbar!\n"
                                : ", unverschlüsselt.\n");
            }
        }
        List<String> missing = new ArrayList<>(allowed);
        missing.removeAll(joined);
        if (!missing.isEmpty()) {
            sb.append("Noch nicht beigetreten: ").append(String.join(", ", missing)).append('\n');
        }
        long usable = joined.stream().filter(r -> allowed(allowed, r)).count();
        sb.append("Mitglied in ").append(usable).append(" freigegebenen Raum/Räumen.\n");
        sb.append(trusted.isEmpty()
                ? "Achtung: keine freigegebenen Absender – jedes Raummitglied kann dem LLM Anweisungen geben."
                : "Freigegebene Absender: " + String.join(", ", trusted));
        return new Status(ok, sb.toString());
    }

    // ------------------------------------------------------------------ intern

    private static void put(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }

    /** Nur Nachrichten (auch verschlüsselte, um darauf hinzuweisen), Name/Alias/Verschlüsselung; keine Presence u.ä. */
    static String filter(int timelineLimit) {
        ObjectNode f = MatrixClient.JSON.createObjectNode();
        f.putObject("presence").putArray("not_types").add("*");
        f.putObject("account_data").putArray("not_types").add("*");
        ObjectNode room = f.putObject("room");
        room.putObject("account_data").putArray("not_types").add("*");
        room.putObject("ephemeral").putArray("not_types").add("*");
        ObjectNode state = room.putObject("state");
        ArrayNode stateTypes = state.putArray("types");
        stateTypes.add("m.room.name").add("m.room.canonical_alias").add("m.room.encryption");
        state.put("lazy_load_members", true);
        ObjectNode timeline = room.putObject("timeline");
        timeline.put("limit", timelineLimit);
        timeline.putArray("types").add("m.room.message").add("m.room.encrypted");
        return MatrixClient.JSON.writeValueAsString(f);
    }
}
