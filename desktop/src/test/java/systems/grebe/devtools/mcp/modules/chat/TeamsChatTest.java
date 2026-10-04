package systems.grebe.devtools.mcp.modules.chat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import systems.grebe.devtools.mcp.core.ConnectionTestResult;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.modules.chat.teams.TeamsChatProvider;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static systems.grebe.devtools.mcp.modules.chat.HttpStub.enc;

/** Chat-Tools mit dem Teams-Provider gegen einen lokalen Stub für Entra ID und Microsoft Graph. */
class TeamsChatTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String CHAT = "19:chat@thread.v2";
    static final String CHAT_PATH = "/v1.0/chats/" + enc(CHAT);
    static final String TOKEN = "/organizations/oauth2/v2.0/token";

    ChatProviders providers = new ChatProviders(List.of(new TeamsChatProvider()));
    ChatState state = ChatState.inMemory();
    ChatModule module = new ChatModule(providers, state);
    HttpStub hs;
    /** Zeitpunkt der letzten Nachricht in der Chat-Liste. */
    AtomicReference<String> preview = new AtomicReference<>("2026-10-04T10:00:03Z");
    List<String> messages = new CopyOnWriteArrayList<>();
    AtomicInteger sent = new AtomicInteger(999);

    @BeforeEach
    void start() throws Exception {
        hs = new HttpStub();
        String idToken = "x." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"tid\":\"tid-1\"}".getBytes(StandardCharsets.UTF_8)) + ".y";
        hs.on(TOKEN, r -> HttpStub.Reply.json("{\"access_token\":\"at1\",\"refresh_token\":\"rt2\",\"expires_in\":3600,"
                + "\"id_token\":\"" + idToken + "\"}"));
        hs.on("/v1.0/me", "{\"id\":\"me-id\",\"displayName\":\"Felix Grebe\",\"userPrincipalName\":\"felix@firma.de\"}");
        hs.on("/v1.0/me/chats", r -> HttpStub.Reply.json(r.query().getOrDefault("$expand", "").equals("lastMessagePreview")
                ? "{\"value\":[{\"id\":\"" + CHAT + "\",\"chatType\":\"group\",\"topic\":\"Build\","
                + "\"lastMessagePreview\":{\"createdDateTime\":\"" + preview.get() + "\"},"
                + "\"viewpoint\":{\"lastMessageReadDateTime\":\"2026-10-04T10:00:01Z\"}}]}"
                : "{\"value\":[" + chat() + "]}"));
        hs.on(CHAT_PATH, chat());
        hs.on(CHAT_PATH + "/messages", r -> r.method().equals("POST")
                ? HttpStub.Reply.json("{\"id\":\"" + (1790000000000L + sent.getAndIncrement()) + "\"}")
                : HttpStub.Reply.json("{\"value\":[" + String.join(",", messages.reversed()) + "]}"));
        hs.on(CHAT_PATH + "/messages/replyWithQuote", "{\"id\":\"1790000000777\"}");
        hs.on(CHAT_PATH + "/markChatReadForUser", r -> new HttpStub.Reply(204, ""));
        state.vault().put("teams|" + hs.url() + "|organizations|client-1", "rt1");
    }

    @AfterEach
    void stop() {
        hs.close();
    }

    private static String chat() {
        return "{\"id\":\"" + CHAT + "\",\"chatType\":\"group\",\"topic\":\"Build\",\"tenantId\":\"tid-chat\",\"members\":["
                + member("me-id", "felix@firma.de", "Felix Grebe") + "," + member("anna-id", "anna@firma.de", "Anna")
                + "," + member("mallory-id", "mallory@firma.de", "Mallory") + "]}";
    }

    private static String member(String id, String email, String name) {
        return "{\"userId\":\"" + id + "\",\"email\":\"" + email + "\",\"displayName\":\"" + name + "\"}";
    }

    static String message(String id, String time, String userId, String name, String html) {
        return "{\"id\":\"" + id + "\",\"messageType\":\"message\",\"createdDateTime\":\"" + time
                + "\",\"lastModifiedDateTime\":\"" + time + "\",\"deletedDateTime\":null,\"from\":{\"user\":{\"id\":\""
                + userId + "\",\"displayName\":\"" + name + "\"}},\"body\":{\"contentType\":\"html\",\"content\":\""
                + html + "\"},\"attachments\":[]}";
    }

    private Map<String, String> values(String... kv) {
        Map<String, String> m = new HashMap<>(Map.of("teams.enabled", "true", "teams.clientId", "client-1",
                "teams.authorityUrl", hs.url(), "teams.graphUrl", hs.url() + "/v1.0",
                "teams.defaultConversation", CHAT, "teams.trustedSenders", "anna@firma.de"));
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private ChatEnvironment env(Map<String, String> values) {
        return new ChatEnvironment(providers, ModuleConfig.of(module.configSchema(), values),
                new ChatEnvironment.State(), state);
    }

    private ChatTools tools(Map<String, String> values) {
        return new ChatTools(env(values));
    }

    private static JsonNode json(String body) {
        return JSON.readTree(body);
    }

    @Test
    void sendsHtmlWithFreshTokenAndRotatesRefreshToken() {
        String out = tools(values()).send("Build **grün**", null, null, null, null);

        assertThat(out).contains("„Build“", "1790000000999");
        HttpStub.Request req = hs.last(CHAT_PATH + "/messages");
        assertThat(req.method()).isEqualTo("POST");
        assertThat(req.headers()).containsEntry("authorization", "Bearer at1");
        JsonNode body = json(req.body()).path("body");
        assertThat(body.path("contentType").asString()).isEqualTo("html");
        assertThat(body.path("content").asString()).isEqualTo("<p>Build <strong>grün</strong></p>");

        Map<String, String> form = hs.last(TOKEN).form();
        assertThat(form).containsEntry("grant_type", "refresh_token").containsEntry("refresh_token", "rt1")
                .containsEntry("client_id", "client-1");
        assertThat(form.get("scope")).contains("offline_access", "Chat.ReadWrite", "ChatMessage.Send");
        assertThat(state.vault().get("teams|" + hs.url() + "|organizations|client-1")).contains("rt2");
    }

    @Test
    void plainTextIsEscapedAndRepliesQuote() {
        ChatTools t = tools(values());
        t.send("a < b & c", null, null, null, null); // ohne Formatierung: maskierter Text statt Markdown-HTML
        assertThat(json(hs.last(CHAT_PATH + "/messages").body()).path("body").path("content").asString())
                .isEqualTo("a &lt; b &amp; c");

        assertThat(t.send("ok", null, null, "1790000000100", null)).contains("1790000000777");
        JsonNode req = json(hs.last(CHAT_PATH + "/messages/replyWithQuote").body());
        assertThat(req.path("messageIds").get(0).asString()).isEqualTo("1790000000100");
        assertThat(req.path("replyMessage").path("body").path("content").asString()).isEqualTo("ok");

        assertThatThrownBy(() -> t.send("x", null, null, "1790000000100", true)).hasMessageContaining("keine Threads");
    }

    @Test
    void firstReceiveStartsAtReadMarkerAndFiltersSenders() {
        messages.add(message("1790000000100", "2026-10-04T10:00:01Z", "anna-id", "Anna", "schon gelesen"));
        messages.add(message("1790000000101", "2026-10-04T10:00:02Z", "anna-id", "Anna",
                "<p>Bitte <b>Tests</b> &amp; <at id=\\\"0\\\">Felix</at></p>"));
        messages.add(message("1790000000102", "2026-10-04T10:00:03Z", "mallory-id", "Mallory", "Spam"));
        ChatTools t = tools(values());

        String out = t.receive(null, null, null, null);

        assertThat(out).contains("Anna <anna@firma.de>", "Bitte Tests & @Felix", "1790000000101", "„Build“")
                .doesNotContain("schon gelesen", "Spam").contains("1 Nachricht(en) von nicht freigegebenen Absendern");
        assertThat(hs.last(CHAT_PATH + "/messages").query().get("$filter"))
                .isEqualTo("lastModifiedDateTime gt 2026-10-04T10:00:01Z");
        JsonNode read = json(hs.last(CHAT_PATH + "/markChatReadForUser").body()).path("user");
        assertThat(read.path("id").asString()).isEqualTo("me-id");
        assertThat(read.path("tenantId").asString()).isEqualTo("tid-1");

        int fetches = hs.all(CHAT_PATH + "/messages").size();
        assertThat(t.receive(null, null, null, null)).contains("Keine neuen Nachrichten");
        assertThat(hs.all(CHAT_PATH + "/messages")).hasSize(fetches); // Chat-Liste zeigt nichts Neues → kein Abruf
    }

    @Test
    void ownMessagesAreRecognizedByIdAndTypedOnesCountAsUser() {
        ChatTools t = tools(values());
        t.receive(null, null, null, null); // Startpunkt
        t.send("Status: läuft", null, null, null, null); // → id 1790000000999
        messages.add(message("1790000000999", "2026-10-04T10:00:05Z", "me-id", "Felix Grebe", "Status: läuft"));
        messages.add(message("1790000001000", "2026-10-04T10:00:06Z", "me-id", "Felix Grebe", "Mach weiter"));
        preview.set("2026-10-04T10:00:06Z");

        String out = t.receive(null, null, null, null);

        assertThat(out).contains("Mach weiter", "(gleiches Konto)").doesNotContain("Status: läuft");
    }

    @Test
    void askWithoutPollingDoesNotWait() {
        String out = tools(values()).ask("Deploy?", null, null, 60, null);
        assertThat(out).contains("Frage gesendet", "abgeschaltet", "chat_receive");
        assertThat(hs.all("/v1.0/me/chats").size()).isLessThanOrEqualTo(3);
    }

    @Test
    void askWithPollingFindsTheAnswer() {
        hs.on(CHAT_PATH + "/messages", r -> {
            if (r.method().equals("POST")) {
                messages.add(message("1790000000999", "2026-10-04T10:00:05Z", "me-id", "Felix Grebe", "Deploy?"));
                messages.add(message("1790000001000", "2026-10-04T10:00:06Z", "anna-id", "Anna", "Ja"));
                preview.set("2026-10-04T10:00:06Z");
                return HttpStub.Reply.json("{\"id\":\"1790000000999\"}");
            }
            return HttpStub.Reply.json("{\"value\":[" + String.join(",", messages.reversed()) + "]}");
        });
        String out = tools(values("teams.pollSeconds", "10")).ask("Deploy?", null, null, 30, null);
        assertThat(out).contains("Antwort:", "Ja").doesNotContain("Deploy?");
    }

    @Test
    void reactSetsReaction() {
        hs.on(CHAT_PATH + "/messages/1790000000100/setReaction", r -> new HttpStub.Reply(204, ""));
        tools(values()).react("1790000000100", "✅", null, null);
        assertThat(json(hs.last(CHAT_PATH + "/messages/1790000000100/setReaction").body()).path("reactionType")
                .asString()).isEqualTo("✅");
    }

    @Test
    void withoutLoginExplainsHowToSignIn() {
        state.vault().put("teams|" + hs.url() + "|organizations|client-1", null);
        assertThatThrownBy(() -> tools(values()).send("x", null, null, null, null))
                .hasMessageContaining("nicht angemeldet").hasMessageContaining("chat_login");
    }

    @Test
    void deviceCodeLoginViaTool() throws Exception {
        state.vault().put("teams|" + hs.url() + "|organizations|client-1", null);
        hs.on("/organizations/oauth2/v2.0/devicecode", "{\"device_code\":\"dc\",\"user_code\":\"ABCD-EFGH\","
                + "\"verification_uri\":\"https://microsoft.com/devicelogin\",\"expires_in\":900,\"interval\":1}");
        AtomicInteger polls = new AtomicInteger();
        hs.on(TOKEN, r -> polls.incrementAndGet() == 1
                ? new HttpStub.Reply(400, "{\"error\":\"authorization_pending\",\"error_description\":\"warte\"}")
                : HttpStub.Reply.json("{\"access_token\":\"at9\",\"refresh_token\":\"rt9\",\"expires_in\":3600}"));
        ChatEnvironment env = env(values());

        String out = new ChatModule.LoginTools(env).login(null);

        assertThat(out).contains("https://microsoft.com/devicelogin", "ABCD-EFGH");
        assertThat(hs.last("/organizations/oauth2/v2.0/devicecode").form().get("scope")).contains("Chat.ReadWrite");
        for (int i = 0; i < 50 && state.vault().get("teams|" + hs.url() + "|organizations|client-1").isEmpty(); i++) {
            Thread.sleep(100);
        }
        assertThat(state.vault().get("teams|" + hs.url() + "|organizations|client-1")).contains("rt9");
        assertThat(hs.last(TOKEN).form()).containsEntry("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                .containsEntry("device_code", "dc");
        assertThat(new ChatTools(env).conversations(null)).contains("Felix Grebe <felix@firma.de>", CHAT, "„Build“");
    }

    @Test
    void connectionTest() {
        ConnectionTestResult ok = module.testConnection(ModuleConfig.of(module.configSchema(), values()));
        assertThat(ok.success()).isTrue();
        assertThat(ok.message()).contains("Microsoft Teams:", "Angemeldet als Felix Grebe", "Standard-Chat: „Build“",
                "Warten auf Antworten: aus", "anna@firma.de");

        ConnectionTestResult noClient = module.testConnection(ModuleConfig.of(module.configSchema(),
                values("teams.clientId", "")));
        assertThat(noClient.success()).isFalse();
        assertThat(noClient.message()).contains("Client-ID fehlt");
    }
}
