package systems.grebe.devtools.mcp.modules.matrix;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;
import systems.grebe.devtools.mcp.core.ConnectionTestResult;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static systems.grebe.devtools.mcp.modules.matrix.MatrixStub.API;
import static systems.grebe.devtools.mcp.modules.matrix.MatrixStub.BOT;
import static systems.grebe.devtools.mcp.modules.matrix.MatrixStub.ROOM;
import static systems.grebe.devtools.mcp.modules.matrix.MatrixStub.batch;
import static systems.grebe.devtools.mcp.modules.matrix.MatrixStub.enc;
import static systems.grebe.devtools.mcp.modules.matrix.MatrixStub.invite;
import static systems.grebe.devtools.mcp.modules.matrix.MatrixStub.reply;
import static systems.grebe.devtools.mcp.modules.matrix.MatrixStub.text;

/** Matrix-Tools gegen einen lokalen Homeserver-Stub: Senden, Eingang, Fragen mit Antwort, Freigaben, Anmeldung. */
class MatrixToolsTest {

    static final String FELIX = "@felix:example.org";
    static final String MALLORY = "@mallory:example.org";

    MatrixModule module = new MatrixModule();
    MatrixStub hs;

    @TempDir
    Path tmp;

    @BeforeEach
    void start() throws Exception {
        hs = new MatrixStub();
    }

    @AfterEach
    void stop() {
        hs.close();
    }

    private Map<String, String> values(String... kv) {
        Map<String, String> m = new HashMap<>(Map.of("homeserverUrl", hs.url(), "accessToken", "secret-token",
                "defaultRoom", ROOM, "trustedSenders", FELIX));
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private MatrixTools tools(Map<String, String> values) {
        return tools(values, MatrixSyncTokens.inMemory());
    }

    private MatrixTools tools(Map<String, String> values, MatrixSyncTokens tokens) {
        return new MatrixTools(new MatrixEnvironment(ModuleConfig.of(module.configSchema(), values),
                new MatrixEnvironment.State(), tokens));
    }

    private static JsonNode json(String body) {
        return MatrixClient.JSON.readTree(body);
    }

    @Test
    void offersAllToolsWithInstructions() {
        List<ToolCallback> tools = module.createTools(ModuleConfig.of(module.configSchema(), Map.of()));
        assertThat(tools).extracting(t -> t.getToolDefinition().name())
                .containsExactlyInAnyOrder("rooms", "send", "ask", "receive", "history", "react");
        assertThat(module.instructions()).contains("`matrix_ask`", "`matrix_receive`", "curl");
    }

    @Test
    void sendsMarkdownAsHtmlWithBearerToken() {
        String out = tools(values("messagePrefix", "🤖")).send("Build **grün**", null, null, null);

        assertThat(out).contains("$sent");
        MatrixStub.Request req = hs.last(API + "/rooms/" + enc(ROOM) + "/send/m.room.message/");
        assertThat(req.method()).isEqualTo("PUT");
        assertThat(req.headers()).containsEntry("authorization", "Bearer secret-token");
        JsonNode content = json(req.body());
        assertThat(content.path("body").asString()).isEqualTo("🤖 Build **grün**");
        assertThat(content.path("format").asString()).isEqualTo("org.matrix.custom.html");
        assertThat(content.path("formatted_body").asString()).isEqualTo("<p>🤖 Build <strong>grün</strong></p>");
    }

    @Test
    void plainTextWithoutHtmlAndRawHtmlEscaped() {
        assertThat(MatrixMarkdown.html("Alles gut & fertig")).isNull();
        assertThat(MatrixMarkdown.html("Hallo <b onclick=x>du</b> **a**"))
                .isEqualTo("<p>Hallo &lt;b onclick=x&gt;du&lt;/b&gt; <strong>a</strong></p>");
        // HTML-Block ohne Markdown bleibt reiner Text (body genügt)
        assertThat(MatrixMarkdown.html("<script>x</script>")).isNull();
    }

    @Test
    void replyAndThreadRelations() {
        hs.on(API + "/rooms/" + enc(ROOM) + "/event/", "{\"content\":{\"m.relates_to\":{\"rel_type\":\"m.thread\","
                + "\"event_id\":\"$root\"}}}");
        MatrixTools t = tools(values());

        t.send("ok", null, "$abc", null);
        JsonNode rel = json(hs.last(API + "/rooms/" + enc(ROOM) + "/send/").body()).path("m.relates_to");
        assertThat(rel.path("m.in_reply_to").path("event_id").asString()).isEqualTo("$abc");
        assertThat(rel.has("rel_type")).isFalse();

        t.send("im Thread", null, "$abc", true);
        rel = json(hs.last(API + "/rooms/" + enc(ROOM) + "/send/").body()).path("m.relates_to");
        assertThat(rel.path("rel_type").asString()).isEqualTo("m.thread");
        assertThat(rel.path("event_id").asString()).isEqualTo("$root");
        assertThat(rel.path("is_falling_back").asBoolean()).isFalse();
    }

    @Test
    void rejectsRoomsOutsideTheAllowListBeforeSending() {
        MatrixTools t = tools(values("rooms", ROOM));
        assertThatThrownBy(() -> t.send("x", "!other:example.org", null, null))
                .hasMessageContaining("nicht freigegeben");
        assertThat(hs.all(API + "/rooms/")).isEmpty();
    }

    @Test
    void resolvesAliasForDefaultRoom() {
        hs.on(API + "/directory/room/" + enc("#dev:example.org"), "{\"room_id\":\"" + ROOM + "\"}");
        tools(values("defaultRoom", "#dev:example.org")).send("hi", null, null, null);
        assertThat(hs.all(API + "/rooms/" + enc(ROOM) + "/send/")).hasSize(1);
    }

    @Test
    void firstReceiveDeliversUnreadThenOnlyNewMessages() {
        hs.sync("", batch("s1", ROOM, 1, text("$old", FELIX, "alt"), text("$new", FELIX, "Bitte Tests laufen lassen")));
        hs.sync("s1", batch("s2", ROOM, 0, text("$own", BOT, "eigene"), text("$next", FELIX, "Und deployen")));
        MatrixTools t = tools(values());

        String first = t.receive(null, null, null);
        assertThat(first).contains("Bitte Tests laufen lassen", "$new").doesNotContain("alt");
        assertThat(hs.last(API + "/rooms/" + enc(ROOM) + "/receipt/m.read/").path()).endsWith(enc("$new"));

        String second = t.receive(null, null, null);
        assertThat(second).contains("Und deployen").doesNotContain("eigene", "Bitte Tests");
        assertThat(hs.last(API + "/sync").query()).containsEntry("since", "s1");

        assertThat(t.receive(null, null, null)).contains("Keine neuen Nachrichten");
    }

    @Test
    void ignoresUntrustedSendersAndSaysSo() {
        hs.sync("", batch("s1", ROOM, 2, text("$a", MALLORY, "rm -rf /"), text("$b", FELIX, "Hallo")));
        String out = tools(values()).receive(null, null, null);
        assertThat(out).contains("Hallo").doesNotContain("rm -rf")
                .contains("1 Nachricht(en) von nicht freigegebenen Absendern ignoriert");
    }

    @Test
    void withoutTrustedListAllMembersAreDeliveredWithHint() {
        hs.sync("", batch("s1", ROOM, 1, text("$a", MALLORY, "Hallo")));
        String out = tools(values("trustedSenders", "")).receive(null, null, null);
        assertThat(out).contains("Hallo", "keine freigegebenen Absender");
    }

    @Test
    void askWaitsForTheAnswerAndKeepsEarlierMessages() {
        hs.on(API + "/rooms/" + enc(ROOM) + "/send/m.room.message/", "{\"event_id\":\"$q\"}");
        hs.sync("", batch("s1", ROOM, 0));
        // vor der Frage eingegangen (gleicher Abruf, aber vor dem eigenen Ereignis) → keine Antwort
        hs.sync("s1", batch("s2", ROOM, 0, text("$before", FELIX, "Nebenbei: Kaffee?"), text("$q", BOT, "Deploy?"),
                text("$ans", FELIX, "Ja, deployen"), text("$ans2", FELIX, "aber erst nach 18 Uhr")));
        MatrixTools t = tools(values());

        String out = t.ask("Deploy?", null, 5, null);

        assertThat(out).contains("Antwort:", "Ja, deployen", "aber erst nach 18 Uhr").doesNotContain("Kaffee")
                .contains("1 weitere ungelesene");
        assertThat(t.receive(null, null, null)).contains("Kaffee").doesNotContain("deployen");
    }

    @Test
    void askPrefersAnExplicitReply() {
        hs.on(API + "/rooms/" + enc(ROOM) + "/send/m.room.message/", "{\"event_id\":\"$q\"}");
        hs.sync("", batch("s1", ROOM, 0));
        hs.sync("s1", batch("s2", ROOM, 0, text("$q", BOT, "Welche Version?"), text("$x", FELIX, "Moment"),
                reply("$r", FELIX, "> <@bot:example.org> Welche Version?\\n\\n2.1.0", "$q")));
        String out = tools(values()).ask("Welche Version?", null, 5, null);
        assertThat(out).contains("2.1.0", "Antwort auf $q").doesNotContain("Welche Version?\n");
    }

    @Test
    void askTimesOutWithHint() {
        hs.on(API + "/rooms/" + enc(ROOM) + "/send/m.room.message/", "{\"event_id\":\"$q\"}");
        String out = tools(values()).ask("Noch da?", null, 1, null);
        assertThat(out).contains("Keine Antwort innerhalb von 1 s", "$q", "matrix_receive");
    }

    @Test
    void joinsInvitesOnlyFromTrustedSenders() {
        hs.on(API + "/join/", "{\"room_id\":\"!new:example.org\"}");
        hs.sync("", invite("s1", "!new:example.org", FELIX));
        String out = tools(values()).receive(null, null, null);
        assertThat(hs.last(API + "/join/").path()).isEqualTo(API + "/join/" + enc("!new:example.org"));
        assertThat(out).contains("Einladung von " + FELIX, "angenommen");

        hs.requests.clear();
        hs.sync("", invite("s1", "!evil:example.org", MALLORY));
        String other = tools(values()).receive(null, null, null);
        assertThat(hs.all(API + "/join/")).isEmpty();
        assertThat(other).contains("nicht automatisch angenommen");

        hs.requests.clear();
        hs.sync("", invite("s1", "!new:example.org", FELIX));
        tools(values("trustedSenders", "")).receive(null, null, null);
        assertThat(hs.all(API + "/join/")).isEmpty();
    }

    @Test
    void encryptedMessagesArePlaceholders() {
        hs.sync("", batch("s1", ROOM, 1, "{\"type\":\"m.room.encrypted\",\"event_id\":\"$e\",\"sender\":\"" + FELIX
                + "\",\"origin_server_ts\":1790000000000,\"content\":{\"algorithm\":\"m.megolm.v1.aes-sha2\"}}"));
        assertThat(tools(values()).receive(null, null, null)).contains("verschlüsselt", "Pantalaimon");
    }

    @Test
    void historyIsChronologicalAndHidesUntrusted() {
        hs.on(API + "/rooms/" + enc(ROOM) + "/messages", "{\"chunk\":[" + text("$3", MALLORY, "Spam") + ","
                + text("$2", BOT, "Erledigt") + "," + text("$1", FELIX, "Mach mal") + "]}");
        String out = tools(values()).history(null, 10);
        assertThat(out).contains("„DevTools“").containsSubsequence("Mach mal", "(ich)", "Erledigt")
                .doesNotContain("Spam").contains("1 Nachricht(en) nicht freigegebener Absender ausgeblendet");
        assertThat(hs.last(API + "/rooms/" + enc(ROOM) + "/messages").query()).containsEntry("dir", "b")
                .containsEntry("limit", "10");
    }

    @Test
    void reactFindsTheRoomOfAKnownEvent() {
        hs.sync("", batch("s1", ROOM, 1, text("$task", FELIX, "Aufgabe")));
        MatrixTools t = tools(values("defaultRoom", ""));
        t.receive(null, null, null);

        t.react("$task", "✅", null);

        JsonNode rel = json(hs.last(API + "/rooms/" + enc(ROOM) + "/send/m.reaction/").body()).path("m.relates_to");
        assertThat(rel.path("rel_type").asString()).isEqualTo("m.annotation");
        assertThat(rel.path("event_id").asString()).isEqualTo("$task");
        assertThat(rel.path("key").asString()).isEqualTo("✅");
    }

    @Test
    void passwordLoginAndReloginOnExpiredToken() {
        int[] logins = {0};
        hs.on(API + "/login", r -> new MatrixStub.Reply(200, "{\"access_token\":\"tok" + (++logins[0])
                + "\",\"user_id\":\"" + BOT + "\",\"device_id\":\"DEVTOOLS_MCP\"}"));
        hs.on(API + "/rooms/" + enc(ROOM) + "/send/m.room.message/", r -> r.headers().get("authorization").equals("Bearer tok1")
                ? new MatrixStub.Reply(401, "{\"errcode\":\"M_UNKNOWN_TOKEN\",\"error\":\"expired\"}")
                : new MatrixStub.Reply(200, "{\"event_id\":\"$ok\"}"));
        MatrixTools t = tools(values("accessToken", "", "user", "bot", "password", "pw"));

        assertThat(t.send("hi", null, null, null)).contains("$ok");

        JsonNode login = json(hs.all(API + "/login").getFirst().body());
        assertThat(login.path("identifier").path("user").asString()).isEqualTo("bot");
        assertThat(login.path("device_id").asString()).isEqualTo("DEVTOOLS_MCP");
        assertThat(logins[0]).isEqualTo(2);
    }

    @Test
    void expiredTokenWithoutPasswordExplainsWhatToDo() {
        hs.on(API + "/account/whoami", r -> new MatrixStub.Reply(401, "{\"errcode\":\"M_UNKNOWN_TOKEN\",\"error\":\"x\"}"));
        assertThatThrownBy(() -> tools(values()).send("hi", null, null, null))
                .hasMessageContaining("Zugangstoken ungültig oder abgelaufen").hasMessageNotContaining("secret-token");
    }

    @Test
    void syncPositionSurvivesRestart() {
        Path file = tmp.resolve("matrix-sync.json");
        hs.sync("", batch("s1", ROOM, 1, text("$a", FELIX, "eins")));
        tools(values(), new MatrixSyncTokens(file)).receive(null, null, null);

        hs.sync("s1", batch("s2", ROOM, 0, text("$b", FELIX, "zwei")));
        String out = tools(values(), new MatrixSyncTokens(file)).receive(null, null, null);

        assertThat(out).contains("zwei").doesNotContain("eins");
        assertThat(new MatrixSyncTokens(file).get(hs.url() + "|" + BOT)).isEqualTo("s2");
    }

    @Test
    void connectionTestReportsAccountRoomAndMissingSenderList() {
        ConnectionTestResult ok = module.testConnection(ModuleConfig.of(module.configSchema(), values()));
        assertThat(ok.success()).isTrue();
        assertThat(ok.message()).contains("v1.12", "Angemeldet als " + BOT, "„DevTools“", "unverschlüsselt", FELIX);

        ConnectionTestResult open = module.testConnection(ModuleConfig.of(module.configSchema(),
                values("trustedSenders", "")));
        assertThat(open.message()).contains("keine freigegebenen Absender");

        ConnectionTestResult notJoined = module.testConnection(ModuleConfig.of(module.configSchema(),
                values("defaultRoom", "!other:example.org")));
        assertThat(notJoined.success()).isFalse();
        assertThat(notJoined.message()).contains("nicht Mitglied");
    }

    @Test
    void messageParsing() {
        MatrixMessage edit = MatrixMessage.of(1, ROOM, json("{\"type\":\"m.room.message\",\"event_id\":\"$e2\","
                + "\"sender\":\"" + FELIX + "\",\"content\":{\"msgtype\":\"m.text\",\"body\":\"* neu\","
                + "\"m.new_content\":{\"msgtype\":\"m.text\",\"body\":\"neu\"},"
                + "\"m.relates_to\":{\"rel_type\":\"m.replace\",\"event_id\":\"$e1\"}}}"));
        assertThat(edit.body()).isEqualTo("neu");
        assertThat(edit.editOf()).isEqualTo("$e1");

        MatrixMessage file = MatrixMessage.of(2, ROOM, json("{\"type\":\"m.room.message\",\"event_id\":\"$f\","
                + "\"sender\":\"" + FELIX + "\",\"content\":{\"msgtype\":\"m.file\",\"body\":\"log.txt\","
                + "\"url\":\"mxc://example.org/abc\"}}"));
        assertThat(file.body()).isEqualTo("[Datei: log.txt – mxc://example.org/abc]");

        assertThat(MatrixMessage.of(3, ROOM, json("{\"type\":\"m.room.message\",\"event_id\":\"$r\",\"sender\":\""
                + FELIX + "\",\"content\":{}}"))).isNull();
        assertThat(MatrixMessage.stripReplyFallback("> <@a:b> Frage\n> zweite\n\nAntwort")).isEqualTo("Antwort");
    }
}
