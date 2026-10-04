package systems.grebe.devtools.mcp.modules.chat;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import systems.grebe.devtools.mcp.core.ConnectionTestResult;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.modules.chat.matrix.MatrixChatProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static systems.grebe.devtools.mcp.modules.chat.HttpStub.enc;

/** Chat-Tools mit dem Matrix-Provider gegen einen lokalen Homeserver-Stub. */
class MatrixChatTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String API = "/_matrix/client/v3";
    static final String ROOM = "!room:example.org";
    static final String BOT = "@bot:example.org";
    static final String FELIX = "@felix:example.org";
    static final String MALLORY = "@mallory:example.org";

    ChatProviders providers = new ChatProviders(List.of(new MatrixChatProvider()));
    ChatModule module = new ChatModule(providers);
    HttpStub hs;
    /** {@code since} (leer = erster Abruf) → Antwort von {@code /sync}. */
    Map<String, String> syncs = new ConcurrentHashMap<>();

    @TempDir
    Path tmp;

    @BeforeEach
    void start() throws Exception {
        hs = new HttpStub();
        hs.on(API + "/account/whoami", "{\"user_id\":\"" + BOT + "\",\"device_id\":\"DEV1\"}");
        hs.on("/_matrix/client/versions", "{\"versions\":[\"v1.11\",\"v1.12\"]}");
        hs.on(API + "/joined_rooms", "{\"joined_rooms\":[\"" + ROOM + "\"]}");
        hs.on(API + "/rooms/" + enc(ROOM) + "/send/m.room.message/", "{\"event_id\":\"$sent\"}");
        hs.on(API + "/rooms/" + enc(ROOM) + "/send/m.reaction/", "{\"event_id\":\"$reaction\"}");
        hs.on(API + "/rooms/" + enc(ROOM) + "/receipt/", "{}");
        hs.on(API + "/rooms/" + enc(ROOM) + "/state/", r -> new HttpStub.Reply(404, "{\"errcode\":\"M_NOT_FOUND\"}"));
        hs.on(API + "/rooms/" + enc(ROOM) + "/state/m.room.name", "{\"name\":\"DevTools\"}");
        hs.on(API + "/rooms/" + enc(ROOM) + "/joined_members", "{\"joined\":{\"" + BOT + "\":{},\"" + FELIX + "\":{}}}");
        hs.on(API + "/sync", r -> {
            String since = r.query().getOrDefault("since", "");
            String json = syncs.get(since);
            return HttpStub.Reply.json(json != null ? json
                    : "{\"next_batch\":\"" + (since.isEmpty() ? "s0" : since) + "\",\"rooms\":{}}");
        });
    }

    @AfterEach
    void stop() {
        hs.close();
    }

    private Map<String, String> values(String... kv) {
        Map<String, String> m = new HashMap<>(Map.of("matrix.enabled", "true", "matrix.homeserverUrl", hs.url(),
                "matrix.accessToken", "secret-token", "matrix.defaultConversation", ROOM,
                "matrix.trustedSenders", FELIX));
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private ChatTools tools(Map<String, String> values) {
        return tools(values, ChatState.inMemory());
    }

    private ChatTools tools(Map<String, String> values, ChatState state) {
        return new ChatTools(new ChatEnvironment(providers, ModuleConfig.of(module.configSchema(), values),
                new ChatEnvironment.State(), state));
    }

    private static JsonNode json(String body) {
        return JSON.readTree(body);
    }

    static String text(String eventId, String sender, String body) {
        return "{\"type\":\"m.room.message\",\"event_id\":\"" + eventId + "\",\"sender\":\"" + sender
                + "\",\"origin_server_ts\":1790000000000,\"content\":{\"msgtype\":\"m.text\",\"body\":\"" + body + "\"}}";
    }

    static String own(String eventId, String body) {
        return "{\"type\":\"m.room.message\",\"event_id\":\"" + eventId + "\",\"sender\":\"" + BOT
                + "\",\"origin_server_ts\":1790000000000,\"unsigned\":{\"transaction_id\":\"dtmcp-1\"},"
                + "\"content\":{\"msgtype\":\"m.text\",\"body\":\"" + body + "\"}}";
    }

    static String reply(String eventId, String sender, String body, String to) {
        return "{\"type\":\"m.room.message\",\"event_id\":\"" + eventId + "\",\"sender\":\"" + sender
                + "\",\"origin_server_ts\":1790000000000,\"content\":{\"msgtype\":\"m.text\",\"body\":\"" + body
                + "\",\"m.relates_to\":{\"m.in_reply_to\":{\"event_id\":\"" + to + "\"}}}}";
    }

    static String batch(String next, int unread, String... events) {
        return "{\"next_batch\":\"" + next + "\",\"rooms\":{\"join\":{\"" + ROOM + "\":{\"timeline\":{\"events\":["
                + String.join(",", events) + "],\"limited\":false},\"unread_notifications\":{\"notification_count\":"
                + unread + "}}}}}";
    }

    static String invite(String next, String roomId, String inviter) {
        return "{\"next_batch\":\"" + next + "\",\"rooms\":{\"invite\":{\"" + roomId + "\":{\"invite_state\":{\"events\":["
                + "{\"type\":\"m.room.member\",\"state_key\":\"" + BOT + "\",\"sender\":\"" + inviter
                + "\",\"content\":{\"membership\":\"invite\"}},{\"type\":\"m.room.name\",\"state_key\":\"\",\"sender\":\""
                + inviter + "\",\"content\":{\"name\":\"Neu\"}}]}}}}}";
    }

    @Test
    void sendsMarkdownAsHtmlWithBearerToken() {
        String out = tools(values("messagePrefix", "🤖")).send("Build **grün**", null, null, null, null);

        assertThat(out).contains("$sent", "„DevTools“");
        HttpStub.Request req = hs.last(API + "/rooms/" + enc(ROOM) + "/send/m.room.message/dtmcp-");
        assertThat(req.method()).isEqualTo("PUT");
        assertThat(req.headers()).containsEntry("authorization", "Bearer secret-token");
        JsonNode content = json(req.body());
        assertThat(content.path("body").asString()).isEqualTo("🤖 Build **grün**");
        assertThat(content.path("format").asString()).isEqualTo("org.matrix.custom.html");
        assertThat(content.path("formatted_body").asString()).isEqualTo("<p>🤖 Build <strong>grün</strong></p>");
    }

    @Test
    void plainTextWithoutHtmlAndRawHtmlEscaped() {
        assertThat(ChatMarkdown.html("Alles gut & fertig")).isNull();
        assertThat(ChatMarkdown.html("Hallo <b onclick=x>du</b> **a**"))
                .isEqualTo("<p>Hallo &lt;b onclick=x&gt;du&lt;/b&gt; <strong>a</strong></p>");
        // HTML-Block ohne Markdown bleibt reiner Text (body genügt)
        assertThat(ChatMarkdown.html("<script>x</script>")).isNull();
    }

    @Test
    void replyAndThreadRelations() {
        hs.on(API + "/rooms/" + enc(ROOM) + "/event/", "{\"content\":{\"m.relates_to\":{\"rel_type\":\"m.thread\","
                + "\"event_id\":\"$root\"}}}");
        ChatTools t = tools(values());

        t.send("ok", null, null, "$abc", null);
        JsonNode rel = json(hs.last(API + "/rooms/" + enc(ROOM) + "/send/").body()).path("m.relates_to");
        assertThat(rel.path("m.in_reply_to").path("event_id").asString()).isEqualTo("$abc");
        assertThat(rel.has("rel_type")).isFalse();

        t.send("im Thread", null, null, "$abc", true);
        rel = json(hs.last(API + "/rooms/" + enc(ROOM) + "/send/").body()).path("m.relates_to");
        assertThat(rel.path("rel_type").asString()).isEqualTo("m.thread");
        assertThat(rel.path("event_id").asString()).isEqualTo("$root");
        assertThat(rel.path("is_falling_back").asBoolean()).isFalse();
    }

    @Test
    void rejectsRoomsOutsideTheAllowListBeforeSending() {
        ChatTools t = tools(values("matrix.rooms", ROOM));
        assertThatThrownBy(() -> t.send("x", "!other:example.org", null, null, null))
                .hasMessageContaining("nicht freigegeben");
        assertThat(hs.all(API + "/rooms/")).isEmpty();
    }

    @Test
    void resolvesAliasForDefaultRoom() {
        hs.on(API + "/directory/room/" + enc("#dev:example.org"), "{\"room_id\":\"" + ROOM + "\"}");
        tools(values("matrix.defaultConversation", "#dev:example.org")).send("hi", null, null, null, null);
        assertThat(hs.all(API + "/rooms/" + enc(ROOM) + "/send/")).hasSize(1);
    }

    @Test
    void firstReceiveDeliversUnreadThenOnlyNewMessages() {
        syncs.put("", batch("s1", 1, text("$old", FELIX, "alt"), text("$new", FELIX, "Bitte Tests laufen lassen")));
        syncs.put("s1", batch("s2", 0, own("$own", "eigene"), text("$next", FELIX, "Und deployen")));
        ChatTools t = tools(values());

        String first = t.receive(null, null, null, null);
        assertThat(first).contains("Bitte Tests laufen lassen", "$new").doesNotContain("alt");
        assertThat(hs.last(API + "/rooms/" + enc(ROOM) + "/receipt/m.read/").path()).endsWith(enc("$new"));

        String second = t.receive(null, null, null, null);
        assertThat(second).contains("Und deployen").doesNotContain("eigene", "Bitte Tests");
        assertThat(hs.last(API + "/sync").query()).containsEntry("since", "s1");

        assertThat(t.receive(null, null, null, null)).contains("Keine neuen Nachrichten");
    }

    @Test
    void messagesFromTheSameAccountNotSentByTheModuleCountAsUser() {
        // eigenes Konto statt Bot: was der Nutzer selbst schreibt, ist eine Anweisung
        syncs.put("", batch("s1", 0));
        syncs.put("s1", batch("s2", 0, text("$typed", BOT, "Selbst getippt"), own("$tool", "vom Modul")));
        ChatTools t = tools(values());
        t.receive(null, null, null, null); // erster Abruf: Startpunkt
        String out = t.receive(null, null, null, null);
        assertThat(out).contains("Selbst getippt", "(gleiches Konto)").doesNotContain("vom Modul");
    }

    @Test
    void ignoresUntrustedSendersAndSaysSo() {
        syncs.put("", batch("s1", 2, text("$a", MALLORY, "rm -rf /"), text("$b", FELIX, "Hallo")));
        String out = tools(values()).receive(null, null, null, null);
        assertThat(out).contains("Hallo").doesNotContain("rm -rf")
                .contains("1 Nachricht(en) von nicht freigegebenen Absendern ignoriert");
    }

    @Test
    void askWaitsForTheAnswerAndKeepsEarlierMessages() {
        hs.on(API + "/rooms/" + enc(ROOM) + "/send/m.room.message/", "{\"event_id\":\"$q\"}");
        syncs.put("", batch("s1", 0));
        // vor der Frage eingegangen (gleicher Abruf, aber vor dem eigenen Ereignis) → keine Antwort
        syncs.put("s1", batch("s2", 0, text("$before", FELIX, "Nebenbei: Kaffee?"), own("$q", "Deploy?"),
                text("$ans", FELIX, "Ja, deployen"), text("$ans2", FELIX, "aber erst nach 18 Uhr")));
        ChatTools t = tools(values());

        String out = t.ask("Deploy?", null, null, 5, null);

        assertThat(out).contains("Antwort:", "Ja, deployen", "aber erst nach 18 Uhr").doesNotContain("Kaffee")
                .contains("1 weitere ungelesene");
        assertThat(t.receive(null, null, null, null)).contains("Kaffee").doesNotContain("deployen");
    }

    @Test
    void askPrefersAnExplicitReply() {
        hs.on(API + "/rooms/" + enc(ROOM) + "/send/m.room.message/", "{\"event_id\":\"$q\"}");
        syncs.put("", batch("s1", 0));
        syncs.put("s1", batch("s2", 0, own("$q", "Welche Version?"), text("$x", FELIX, "Moment"),
                reply("$r", FELIX, "> <@bot:example.org> Welche Version?\\n\\n2.1.0", "$q")));
        String out = tools(values()).ask("Welche Version?", null, null, 5, null);
        assertThat(out).contains("2.1.0", "Antwort auf $q").doesNotContain("Welche Version?\n");
    }

    @Test
    void askTimesOutWithHint() {
        hs.on(API + "/rooms/" + enc(ROOM) + "/send/m.room.message/", "{\"event_id\":\"$q\"}");
        String out = tools(values()).ask("Noch da?", null, null, 1, null);
        assertThat(out).contains("Keine Antwort innerhalb von 1 s", "$q", "chat_receive");
    }

    @Test
    void joinsInvitesOnlyFromTrustedSenders() {
        hs.on(API + "/join/", "{\"room_id\":\"!new:example.org\"}");
        syncs.put("", invite("s1", "!new:example.org", FELIX));
        String out = tools(values()).receive(null, null, null, null);
        assertThat(hs.last(API + "/join/").path()).isEqualTo(API + "/join/" + enc("!new:example.org"));
        assertThat(out).contains("Einladung von " + FELIX, "angenommen");

        hs.requests.clear();
        syncs.put("", invite("s1", "!evil:example.org", MALLORY));
        String other = tools(values()).receive(null, null, null, null);
        assertThat(hs.all(API + "/join/")).isEmpty();
        assertThat(other).contains("nicht automatisch angenommen");

        hs.requests.clear();
        syncs.put("", invite("s1", "!new:example.org", FELIX));
        tools(values("matrix.trustedSenders", "")).receive(null, null, null, null);
        assertThat(hs.all(API + "/join/")).isEmpty();
    }

    @Test
    void encryptedMessagesArePlaceholders() {
        syncs.put("", batch("s1", 1, "{\"type\":\"m.room.encrypted\",\"event_id\":\"$e\",\"sender\":\"" + FELIX
                + "\",\"origin_server_ts\":1790000000000,\"content\":{\"algorithm\":\"m.megolm.v1.aes-sha2\"}}"));
        assertThat(tools(values()).receive(null, null, null, null)).contains("verschlüsselt", "Pantalaimon");
    }

    @Test
    void historyIsChronologicalAndHidesUntrusted() {
        hs.on(API + "/rooms/" + enc(ROOM) + "/messages", "{\"chunk\":[" + text("$3", MALLORY, "Spam") + ","
                + own("$2", "Erledigt") + "," + text("$1", FELIX, "Mach mal") + "]}");
        String out = tools(values()).history(null, null, 10);
        assertThat(out).contains("„DevTools“").containsSubsequence("Mach mal", "(ich)", "Erledigt")
                .doesNotContain("Spam").contains("1 Nachricht(en) nicht freigegebener Absender ausgeblendet");
        assertThat(hs.last(API + "/rooms/" + enc(ROOM) + "/messages").query()).containsEntry("dir", "b")
                .containsEntry("limit", "10");
    }

    @Test
    void reactFindsTheRoomOfAKnownEvent() {
        syncs.put("", batch("s1", 1, text("$task", FELIX, "Aufgabe")));
        ChatTools t = tools(values("matrix.defaultConversation", ""));
        t.receive(null, null, null, null);

        t.react("$task", "✅", null, null);

        JsonNode rel = json(hs.last(API + "/rooms/" + enc(ROOM) + "/send/m.reaction/").body()).path("m.relates_to");
        assertThat(rel.path("rel_type").asString()).isEqualTo("m.annotation");
        assertThat(rel.path("event_id").asString()).isEqualTo("$task");
        assertThat(rel.path("key").asString()).isEqualTo("✅");
    }

    @Test
    void passwordLoginAndReloginOnExpiredToken() {
        int[] logins = {0};
        hs.on(API + "/login", r -> HttpStub.Reply.json("{\"access_token\":\"tok" + (++logins[0])
                + "\",\"user_id\":\"" + BOT + "\",\"device_id\":\"DEVTOOLS_MCP\"}"));
        hs.on(API + "/rooms/" + enc(ROOM) + "/send/m.room.message/", r -> r.headers().get("authorization").equals("Bearer tok1")
                ? new HttpStub.Reply(401, "{\"errcode\":\"M_UNKNOWN_TOKEN\",\"error\":\"expired\"}")
                : HttpStub.Reply.json("{\"event_id\":\"$ok\"}"));
        ChatTools t = tools(values("matrix.accessToken", "", "matrix.user", "bot", "matrix.password", "pw"));

        assertThat(t.send("hi", null, null, null, null)).contains("$ok");

        JsonNode login = json(hs.all(API + "/login").getFirst().body());
        assertThat(login.path("identifier").path("user").asString()).isEqualTo("bot");
        assertThat(login.path("device_id").asString()).isEqualTo("DEVTOOLS_MCP");
        assertThat(logins[0]).isEqualTo(2);
    }

    @Test
    void expiredTokenWithoutPasswordExplainsWhatToDo() {
        hs.on(API + "/account/whoami", r -> new HttpStub.Reply(401, "{\"errcode\":\"M_UNKNOWN_TOKEN\",\"error\":\"x\"}"));
        assertThatThrownBy(() -> tools(values()).send("hi", null, null, null, null))
                .hasMessageContaining("Zugangstoken ungültig oder abgelaufen").hasMessageNotContaining("secret-token");
    }

    @Test
    void syncPositionSurvivesRestart() {
        Path file = tmp.resolve("chat-state.json");
        syncs.put("", batch("s1", 1, text("$a", FELIX, "eins")));
        tools(values(), new ChatState(file, UnaryOperator.identity(), UnaryOperator.identity()))
                .receive(null, null, null, null);

        syncs.put("s1", batch("s2", 0, text("$b", FELIX, "zwei")));
        String out = tools(values(), new ChatState(file, UnaryOperator.identity(), UnaryOperator.identity()))
                .receive(null, null, null, null);

        assertThat(out).contains("zwei").doesNotContain("eins");
        assertThat(new ChatState(file, UnaryOperator.identity(), UnaryOperator.identity())
                .cursor("matrix|" + hs.url() + "|" + BOT)).isEqualTo("s2");
    }

    @Test
    void connectionTestReportsAccountRoomAndMissingSenderList() {
        ConnectionTestResult ok = module.testConnection(ModuleConfig.of(module.configSchema(), values()));
        assertThat(ok.success()).isTrue();
        assertThat(ok.message()).contains("Matrix:", "v1.12", "Angemeldet als " + BOT, "„DevTools“", "unverschlüsselt",
                FELIX);

        ConnectionTestResult open = module.testConnection(ModuleConfig.of(module.configSchema(),
                values("matrix.trustedSenders", "")));
        assertThat(open.message()).contains("keine freigegebenen Absender");

        ConnectionTestResult notJoined = module.testConnection(ModuleConfig.of(module.configSchema(),
                values("matrix.defaultConversation", "!other:example.org")));
        assertThat(notJoined.success()).isFalse();
        assertThat(notJoined.message()).contains("nicht Mitglied");
    }

    @Test
    void conversationsListsAccountAndRooms() {
        String out = tools(values()).conversations(null);
        assertThat(out).contains("Konto: " + BOT, "Standard: „DevTools“", ROOM, "2 Mitglieder", "[Standard]",
                "Freigegebene Absender: " + FELIX);
    }
}
