package systems.grebe.devtools.mcp.modules.chat.teams;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import systems.grebe.devtools.mcp.modules.chat.spi.ChatSettings;
import systems.grebe.devtools.mcp.modules.chat.spi.ChatSystem;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Microsoft Teams (1:1-, Gruppen- und Besprechungs-Chats) über Microsoft Graph v1.0 mit delegierten Berechtigungen –
 * das Modul schreibt also <em>unter dem Konto des Nutzers</em>. Eigene Nachrichten erkennt das Modul an den IDs der
 * zuletzt gesendeten (siehe Chat-Modul); was der Nutzer selbst schreibt, gilt als freigegeben.
 *
 * <p>Abruf: die 50 zuletzt aktiven Chats mit ihrer letzten Nachricht ({@code lastMessagePreview}), für Chats mit
 * Neuem deren Nachrichten seit dem letzten Stand. Der Stand ist je Chat der Zeitpunkt der letzten gesehenen Nachricht
 * (Serverzeit); beim allerersten Abruf gilt die Lesemarke des Nutzers ({@code viewpoint.lastMessageReadDateTime}).
 *
 * <p>Microsoft erlaubt in den Nutzungsbedingungen der Teams-APIs kein regelmäßiges Abfragen auf Änderungen. Deshalb
 * ruft ein Tool-Aufruf standardmäßig genau einmal ab; Warten durch wiederholtes Abfragen ({@code pollSeconds}) muss
 * ausdrücklich eingeschaltet werden.
 */
final class TeamsChat implements ChatSystem {

    /** Teams begrenzt Nachrichten auf rund 28 KB. */
    static final int MAX_CONTENT = 28_000;
    static final int MIN_POLL_SECONDS = 10;
    static final int MAX_CHAT_PAGES = 4;
    /** Seiten à 50 geänderter Nachrichten je Chat und Abruf; was darüber hinausgeht, meldet der Abruf als fehlend. */
    static final int MAX_MESSAGE_PAGES = 4;

    private final ChatSettings settings;
    private final GraphHttp http;
    private final GraphAuth auth;
    private final String graph;
    private final List<String> chatList;
    private final List<String> trusted;
    private final int pollSeconds;

    // ------------------------------------------------------------------ geschützt durch this
    private JsonNode me;
    /** Chat-ID → Chat (topic, chatType, tenantId) und Mitglieder (Benutzer-ID → E-Mail/Name). */
    private final Map<String, ChatInfo> chats = new HashMap<>();

    private record Member(String userId, String email, String displayName) {
    }

    private record ChatInfo(String id, String topic, String type, String tenantId, Map<String, Member> members) {
    }

    TeamsChat(ChatSettings settings) {
        this.settings = settings;
        this.http = new GraphHttp(settings.timeout());
        this.auth = new GraphAuth(http, settings.vault(),
                settings.getString(TeamsChatProvider.AUTHORITY, TeamsChatProvider.DEFAULT_AUTHORITY),
                settings.getString(TeamsChatProvider.TENANT, "organizations"),
                settings.getString(TeamsChatProvider.CLIENT_ID, ""));
        this.graph = strip(settings.getString(TeamsChatProvider.GRAPH, TeamsChatProvider.DEFAULT_GRAPH));
        this.chatList = settings.getList(TeamsChatProvider.CHATS);
        this.trusted = settings.getList(TeamsChatProvider.TRUSTED).stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
        int poll = settings.getInt(TeamsChatProvider.POLL_SECONDS, 0);
        this.pollSeconds = poll <= 0 ? 0 : Math.max(MIN_POLL_SECONDS, poll);
    }

    @Override
    public String id() {
        return TeamsChatProvider.ID;
    }

    // ------------------------------------------------------------------ HTTP

    private JsonNode get(String path) {
        return http.graph("GET", graph + path, null, auth::accessToken, auth::invalidate);
    }

    private JsonNode post(String path, JsonNode body) {
        return http.graph("POST", graph + path, body, auth::accessToken, auth::invalidate);
    }

    private static String enc(String s) {
        return GraphHttp.enc(s);
    }

    // ------------------------------------------------------------------ Anmeldung und Konto

    @Override
    public String login(Consumer<String> prompt) {
        auth.login(prompt);
        synchronized (this) {
            me = null;
        }
        JsonNode m = me();
        return "Angemeldet als " + m.path("displayName").asString("?") + " <" + upn(m) + ">.";
    }

    @Override
    public String loginStatus() {
        String status = auth.status();
        synchronized (this) {
            if (me != null && "angemeldet".equals(status)) {
                return "angemeldet als " + me.path("displayName").asString("?") + " <" + upn(me) + ">";
            }
        }
        return status;
    }

    private synchronized JsonNode me() {
        if (me == null) {
            me = get("/me?$select=id,displayName,userPrincipalName,mail");
        }
        return me;
    }

    private static String upn(JsonNode me) {
        return me.path("userPrincipalName").asString(me.path("mail").asString(me.path("id").asString("?")));
    }

    @Override
    public Account account() {
        JsonNode m = me();
        String id = m.path("id").asString("");
        return new Account(upn(m), m.path("displayName").asString(null),
                graph + "|" + settings.getString(TeamsChatProvider.TENANT, "organizations") + "|" + id);
    }

    // ------------------------------------------------------------------ Freigaben

    private boolean trusted(String userId, String email) {
        if (trusted.isEmpty()) {
            return true;
        }
        return userId != null && trusted.contains(userId.toLowerCase(Locale.ROOT))
                || email != null && trusted.contains(email.toLowerCase(Locale.ROOT));
    }

    private boolean allowed(String chatId) {
        return chatList.isEmpty() || chatList.contains(chatId);
    }

    @Override
    public String senderPolicy() {
        return (trusted.isEmpty() ? "alle Chat-Mitglieder (keine Liste gesetzt)" : String.join(", ", trusted))
                + "; das eigene Konto immer";
    }

    @Override
    public boolean canWait() {
        return pollSeconds > 0;
    }

    // ------------------------------------------------------------------ Unterhaltungen

    @Override
    public boolean ownsConversation(String ref) {
        return ref.startsWith("19:") && ref.contains("@");
    }

    @Override
    public boolean ownsMessage(String messageId) {
        return messageId.matches("\\d{10,}");
    }

    /** Lädt die Chats des Kontos (höchstens {@value #MAX_CHAT_PAGES} Seiten à 50) samt Mitgliedern. */
    private List<ChatInfo> loadChats() {
        List<ChatInfo> out = new ArrayList<>();
        String next = graph + "/me/chats?$expand=members&$top=50";
        for (int page = 0; next != null && page < MAX_CHAT_PAGES; page++) {
            JsonNode res = http.graph("GET", next, null, auth::accessToken, auth::invalidate);
            for (JsonNode c : res.path("value")) {
                ChatInfo info = info(c);
                synchronized (this) {
                    chats.put(info.id(), info);
                }
                out.add(info);
            }
            next = res.path("@odata.nextLink").asString(null);
        }
        return out;
    }

    private static ChatInfo info(JsonNode c) {
        Map<String, Member> members = new LinkedHashMap<>();
        for (JsonNode m : c.path("members")) {
            String userId = m.path("userId").asString(null);
            if (userId != null) {
                members.put(userId, new Member(userId, m.path("email").asString(null), m.path("displayName").asString(null)));
            }
        }
        return new ChatInfo(c.path("id").asString(), c.path("topic").asString(null), c.path("chatType").asString("?"),
                c.path("tenantId").asString(null), members);
    }

    private ChatInfo chat(String chatId) {
        synchronized (this) {
            ChatInfo info = chats.get(chatId);
            if (info != null) {
                return info;
            }
        }
        ChatInfo info = info(get("/chats/" + enc(chatId) + "?$expand=members"));
        synchronized (this) {
            chats.put(chatId, info);
        }
        return info;
    }

    @Override
    public List<Conversation> conversations() {
        String myId = me().path("id").asString("");
        List<Conversation> out = new ArrayList<>();
        for (ChatInfo c : loadChats()) {
            if (allowed(c.id())) {
                out.add(new Conversation(c.id(), name(c, myId), type(c.type()), c.members().size(), null));
            }
        }
        return out;
    }

    private static String type(String chatType) {
        return switch (chatType) {
            case "oneOnOne" -> "1:1-Chat";
            case "group" -> "Gruppenchat";
            case "meeting" -> "Besprechungschat";
            default -> chatType;
        };
    }

    /** Thema; ohne Thema die Namen der anderen Mitglieder. */
    private static String name(ChatInfo c, String myId) {
        if (c.topic() != null && !c.topic().isBlank()) {
            return c.topic();
        }
        String others = c.members().values().stream().filter(m -> !m.userId().equals(myId))
                .map(m -> m.displayName() == null ? m.userId() : m.displayName()).collect(Collectors.joining(", "));
        return others.isEmpty() ? null : others;
    }

    @Override
    public String resolve(String ref) {
        String chatId;
        if (ownsConversation(ref)) {
            chatId = ref;
        } else {
            String myId = me().path("id").asString("");
            List<ChatInfo> matches = loadChats().stream()
                    .filter(c -> ref.equalsIgnoreCase(name(c, myId))
                            || c.members().values().stream().anyMatch(m -> !m.userId().equals(myId)
                            && (ref.equalsIgnoreCase(m.displayName()) || ref.equalsIgnoreCase(m.email()))
                            && "oneOnOne".equals(c.type())))
                    .toList();
            if (matches.size() != 1) {
                throw new IllegalArgumentException(matches.isEmpty()
                        ? "Kein Teams-Chat passt zu '" + ref + "' – Chat-ID angeben (siehe chat_conversations)."
                        : "Mehrere Teams-Chats passen zu '" + ref + "' – Chat-ID angeben (siehe chat_conversations).");
            }
            chatId = matches.getFirst().id();
        }
        if (!allowed(chatId)) {
            throw new IllegalStateException("Teams-Chat " + chatId + " ist nicht freigegeben. Der Nutzer kann ihn in "
                    + "der DevTools-App unter Module → Chat → 'Teams: Nur diese Chats' ergänzen.");
        }
        return chatId;
    }

    @Override
    public String label(String chatId) {
        try {
            String n = name(chat(chatId), me().path("id").asString(""));
            return n == null ? chatId : "„" + n + "“";
        } catch (RuntimeException e) {
            return chatId;
        }
    }

    // ------------------------------------------------------------------ Senden

    @Override
    public Sent send(String chatId, Outgoing message) {
        String content = message.html() != null ? message.html() : TeamsHtml.fromText(message.text());
        if (content.length() > MAX_CONTENT) {
            throw new IllegalArgumentException("Nachricht zu lang (" + content.length() / 1024 + " KB, Teams erlaubt "
                    + "rund 28 KB) – kürzen oder auf mehrere Nachrichten aufteilen.");
        }
        ObjectNode body = GraphHttp.JSON.createObjectNode();
        body.put("contentType", "html");
        body.put("content", content);
        JsonNode res;
        if (message.replyTo() != null) {
            ObjectNode req = GraphHttp.JSON.createObjectNode();
            req.putArray("messageIds").add(message.replyTo());
            req.putObject("replyMessage").set("body", body);
            res = post("/chats/" + enc(chatId) + "/messages/replyWithQuote", req);
        } else {
            ObjectNode req = GraphHttp.JSON.createObjectNode();
            req.set("body", body);
            res = post("/chats/" + enc(chatId) + "/messages", req);
        }
        return new Sent(res.path("id").asString(), null);
    }

    @Override
    public void react(String chatId, String messageId, String reaction) {
        ObjectNode req = GraphHttp.JSON.createObjectNode();
        req.put("reactionType", reaction);
        post("/chats/" + enc(chatId) + "/messages/" + enc(messageId) + "/setReaction", req);
    }

    @Override
    public void markRead(String chatId, String messageId) {
        String tenantId = auth.tenantId();
        if (tenantId == null) {
            tenantId = chat(chatId).tenantId();
        }
        ObjectNode req = GraphHttp.JSON.createObjectNode();
        ObjectNode user = req.putObject("user");
        user.put("id", me().path("id").asString());
        user.put("tenantId", tenantId);
        post("/chats/" + enc(chatId) + "/markChatReadForUser", req);
    }

    // ------------------------------------------------------------------ Eingang

    /**
     * Stand: je Chat der Zeitpunkt der letzten gesehenen Nachricht und eine Marke für Chats, die erst später auftauchen.
     */
    private record Cursor(Instant watermark, Map<String, Instant> chats) {

        static Cursor parse(String json) {
            if (json == null || json.isBlank()) {
                return null;
            }
            try {
                JsonNode n = GraphHttp.JSON.readTree(json);
                Map<String, Instant> chats = new LinkedHashMap<>();
                n.path("chats").properties().forEach(e -> chats.put(e.getKey(), Instant.parse(e.getValue().asString())));
                return new Cursor(Instant.parse(n.path("watermark").asString()), chats);
            } catch (RuntimeException e) {
                return null; // unlesbar: wie beim ersten Abruf beginnen
            }
        }

        String format() {
            ObjectNode n = GraphHttp.JSON.createObjectNode();
            n.put("watermark", watermark.toString());
            ObjectNode c = n.putObject("chats");
            // die zuletzt aktiven zuerst, damit der Stand begrenzt bleibt
            chats.entrySet().stream().sorted(Map.Entry.<String, Instant>comparingByValue().reversed()).limit(200)
                    .forEach(e -> c.put(e.getKey(), e.getValue().toString()));
            return GraphHttp.JSON.writeValueAsString(n);
        }
    }

    @Override
    public Poll poll(String cursor, int timeoutMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMs));
        Cursor current = Cursor.parse(cursor);
        while (true) {
            List<Message> messages = new ArrayList<>();
            List<String> notices = new ArrayList<>();
            current = pollOnce(current, messages, notices);
            long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (!messages.isEmpty() || !notices.isEmpty() || pollSeconds <= 0 || left <= 0) {
                return new Poll(messages, notices, current.format());
            }
            GraphHttp.sleep(Math.min(left, pollSeconds * 1000L));
        }
    }

    private Cursor pollOnce(Cursor cursor, List<Message> out, List<String> notices) {
        String myId = me().path("id").asString("");
        JsonNode res = get("/me/chats?$expand=lastMessagePreview&$top=50"
                + "&$orderby=" + enc("lastMessagePreview/createdDateTime desc"));
        Instant watermark = cursor == null ? Instant.EPOCH : cursor.watermark();
        Map<String, Instant> seen = cursor == null ? new LinkedHashMap<>() : new LinkedHashMap<>(cursor.chats());
        Instant newWatermark = watermark;
        for (JsonNode c : res.path("value")) {
            String chatId = c.path("id").asString();
            Instant last = instant(c.path("lastMessagePreview").path("createdDateTime").asString(null));
            if (last == null) {
                continue;
            }
            if (last.isAfter(newWatermark)) {
                newWatermark = last;
            }
            if (!allowed(chatId)) {
                continue;
            }
            Instant since = seen.get(chatId);
            if (since == null) {
                // erster Abruf: ab der Lesemarke des Nutzers; später aufgetauchte Chats: ab der Marke des letzten Abrufs
                since = cursor == null ? instant(c.path("viewpoint").path("lastMessageReadDateTime").asString(null))
                        : watermark;
                if (since == null) {
                    since = last;
                }
            }
            if (last.isAfter(since)) {
                Fresh fresh = messagesSince(chatId, since, myId);
                out.addAll(fresh.messages());
                if (fresh.truncated()) { // wie bei Matrix (timeline.limited): nicht still verwerfen
                    notices.add("In " + label(chatId) + " kamen mehr Nachrichten als ein Abruf fasst – ältere fehlen "
                            + "hier, siehe chat_history.");
                }
                Instant newest = fresh.messages().stream().map(m -> Instant.ofEpochMilli(m.timestamp()))
                        .max(Comparator.naturalOrder()).orElse(since);
                since = newest.isAfter(since) ? newest : last;
            }
            seen.put(chatId, since);
        }
        return new Cursor(cursor == null && newWatermark.equals(Instant.EPOCH) ? Instant.now() : newWatermark, seen);
    }

    /** @param truncated es gab noch mehr geänderte Nachrichten als die {@value #MAX_MESSAGE_PAGES} Seiten fassen */
    private record Fresh(List<Message> messages, boolean truncated) {
    }

    /**
     * Neue Nachrichten eines Chats nach {@code since}, älteste zuerst. Die neuesten zuerst, über {@code @odata.nextLink}
     * höchstens {@value #MAX_MESSAGE_PAGES} Seiten; bleibt danach noch ein Link, fehlen ältere Nachrichten.
     */
    private Fresh messagesSince(String chatId, Instant since, String myId) {
        String next = graph + "/chats/" + enc(chatId) + "/messages?$top=50"
                + "&$orderby=" + enc("lastModifiedDateTime desc")
                + "&$filter=" + enc("lastModifiedDateTime gt " + since);
        List<Message> out = new ArrayList<>();
        boolean truncated = false;
        for (int page = 0; next != null; page++) {
            if (page >= MAX_MESSAGE_PAGES) {
                truncated = true;
                break;
            }
            JsonNode res = http.graph("GET", next, null, auth::accessToken, auth::invalidate);
            for (JsonNode m : res.path("value")) {
                Instant created = instant(m.path("createdDateTime").asString(null));
                // geänderte (bearbeitet, Reaktion) ältere Nachrichten sind nicht neu
                if (created == null || !created.isAfter(since)) {
                    continue;
                }
                Message msg = message(chatId, m, myId);
                if (msg != null) {
                    out.add(msg);
                }
            }
            next = res.path("@odata.nextLink").asString(null);
        }
        out.sort(Comparator.comparingLong(Message::timestamp));
        return new Fresh(out, truncated);
    }

    @Override
    public List<Message> history(String chatId, int limit) {
        String myId = me().path("id").asString("");
        JsonNode res = get("/chats/" + enc(chatId) + "/messages?$top=" + Math.max(1, Math.min(50, limit))
                + "&$orderby=" + enc("createdDateTime desc"));
        List<Message> out = new ArrayList<>();
        for (JsonNode m : res.path("value")) {
            Message msg = message(chatId, m, myId);
            if (msg != null) {
                out.add(msg);
            }
        }
        out.sort(Comparator.comparingLong(Message::timestamp));
        return out;
    }

    /** Graph-{@code chatMessage} → Nachricht; {@code null} für Systemereignisse und gelöschte Nachrichten. */
    private Message message(String chatId, JsonNode m, String myId) {
        if (!"message".equals(m.path("messageType").asString("message")) || m.hasNonNull("deletedDateTime")) {
            return null;
        }
        JsonNode user = m.path("from").path("user");
        JsonNode app = m.path("from").path("application");
        String userId = user.path("id").asString(null);
        String name;
        String sender;
        String email = null;
        if (userId != null) {
            name = user.path("displayName").asString(null);
            Member member = member(chatId, userId);
            email = member == null ? null : member.email();
            sender = email != null ? email : userId;
            if (name == null && member != null) {
                name = member.displayName();
            }
        } else {
            name = app.path("displayName").asString("App");
            sender = "app:" + app.path("id").asString("?");
        }
        boolean fromMe = userId != null && userId.equals(myId);
        String replyTo = null;
        List<String> files = new ArrayList<>();
        for (JsonNode a : m.path("attachments")) {
            String type = a.path("contentType").asString("");
            if ("messageReference".equals(type)) {
                replyTo = a.path("id").asString(null);
            } else if ("reference".equals(type) || a.has("contentUrl")) {
                files.add("[Datei: " + a.path("name").asString("?") + " – " + a.path("contentUrl").asString("") + "]");
            }
        }
        JsonNode body = m.path("body");
        String text = "html".equalsIgnoreCase(body.path("contentType").asString("text"))
                ? TeamsHtml.toText(body.path("content").asString("")) : body.path("content").asString("").strip();
        if (!files.isEmpty()) {
            text = (text + "\n" + String.join("\n", files)).strip();
        }
        Instant created = instant(m.path("createdDateTime").asString(null));
        return new Message(m.path("id").asString(), chatId, sender, name, created == null ? 0 : created.toEpochMilli(),
                text, replyTo, null, null, false, fromMe, fromMe || userId != null && trusted(userId, email));
    }

    /** Mitglied eines Chats; lädt die Mitglieder einmal nach, wenn der Absender (noch) unbekannt ist. */
    private Member member(String chatId, String userId) {
        ChatInfo info;
        try {
            info = chat(chatId);
        } catch (RuntimeException e) {
            return null;
        }
        Member m = info.members().get(userId);
        if (m == null && !trusted.isEmpty()) {
            synchronized (this) {
                chats.remove(chatId);
            }
            try {
                m = chat(chatId).members().get(userId);
            } catch (RuntimeException e) {
                return null;
            }
        }
        return m;
    }

    // ------------------------------------------------------------------ Verbindungstest

    @Override
    public Status test(String defaultConversation) {
        StringBuilder sb = new StringBuilder();
        if (settings.get(TeamsChatProvider.CLIENT_ID).isEmpty()) {
            return new Status(false, "Client-ID fehlt – App-Registrierung in Entra ID anlegen (öffentlicher Client, "
                    + "delegiert: User.Read, Chat.ReadWrite, ChatMessage.Send) und die Anwendungs-ID eintragen.");
        }
        if (!auth.loggedIn()) {
            return new Status(false, "Nicht angemeldet – Einstellungen speichern und die Aktion „Anmelden“ ausführen "
                    + "(oder im Client chat_login).");
        }
        JsonNode m = me();
        sb.append("Angemeldet als ").append(m.path("displayName").asString("?")).append(" <").append(upn(m))
                .append(">.\n");
        List<ChatInfo> all = loadChats();
        long usable = all.stream().filter(c -> allowed(c.id())).count();
        sb.append("Zugriff auf ").append(all.size()).append(" Chats, davon ").append(usable)
                .append(" freigegeben.\n");
        boolean ok = true;
        if (defaultConversation != null) {
            try {
                String id = resolve(defaultConversation);
                sb.append("Standard-Chat: ").append(label(id)).append(" – ").append(id).append('\n');
            } catch (RuntimeException e) {
                ok = false;
                sb.append("Standard-Chat ").append(defaultConversation).append(": ").append(e.getMessage()).append('\n');
            }
        }
        Set<String> known = all.stream().map(ChatInfo::id).collect(Collectors.toSet());
        List<String> missing = chatList.stream().filter(c -> !known.contains(c)).toList();
        if (!missing.isEmpty()) {
            sb.append("Nicht unter den Chats des Kontos: ").append(String.join(", ", missing)).append('\n');
        }
        sb.append(pollSeconds > 0 ? "Warten auf Antworten: Abfrage alle " + pollSeconds + " s.\n"
                : "Warten auf Antworten: aus (Abruf nur je Tool-Aufruf).\n");
        sb.append(trusted.isEmpty()
                ? "Hinweis: keine freigegebenen Absender – Nachrichten aller Chat-Mitglieder erreichen das LLM."
                : "Freigegebene Absender: " + String.join(", ", trusted) + " (und das eigene Konto)");
        return new Status(ok, sb.toString());
    }

    // ------------------------------------------------------------------ intern

    private static Instant instant(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(iso);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String strip(String url) {
        String u = url.strip();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }
}
