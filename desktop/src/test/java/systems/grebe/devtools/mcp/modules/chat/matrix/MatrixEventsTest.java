package systems.grebe.devtools.mcp.modules.chat.matrix;

import org.junit.jupiter.api.Test;
import systems.grebe.devtools.mcp.modules.chat.spi.ChatSystem;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/** Matrix-Ereignisse → Nachrichten: Bearbeitungen, Dateien, Redaktionen, Antwort-Zitate, eigene Transaktionen. */
class MatrixEventsTest {

    static final String ROOM = "!room:example.org";
    static final String ME = "@bot:example.org";

    private static JsonNode json(String s) {
        return MatrixClient.JSON.readTree(s);
    }

    private static ChatSystem.Message parse(String event) {
        return MatrixEvents.message(ROOM, json(event), ME, s -> s.equals("@felix:example.org"));
    }

    @Test
    void editsFilesAndRedactions() {
        ChatSystem.Message edit = parse("{\"type\":\"m.room.message\",\"event_id\":\"$e2\",\"sender\":\"@felix:example.org\","
                + "\"content\":{\"msgtype\":\"m.text\",\"body\":\"* neu\",\"m.new_content\":{\"msgtype\":\"m.text\","
                + "\"body\":\"neu\"},\"m.relates_to\":{\"rel_type\":\"m.replace\",\"event_id\":\"$e1\"}}}");
        assertThat(edit.text()).isEqualTo("neu");
        assertThat(edit.editOf()).isEqualTo("$e1");
        assertThat(edit.trusted()).isTrue();

        ChatSystem.Message file = parse("{\"type\":\"m.room.message\",\"event_id\":\"$f\",\"sender\":\"@x:example.org\","
                + "\"content\":{\"msgtype\":\"m.file\",\"body\":\"log.txt\",\"url\":\"mxc://example.org/abc\"}}");
        assertThat(file.text()).isEqualTo("[Datei: log.txt – mxc://example.org/abc]");
        assertThat(file.trusted()).isFalse();

        assertThat(parse("{\"type\":\"m.room.message\",\"event_id\":\"$r\",\"sender\":\"@x:example.org\",\"content\":{}}"))
                .isNull();
        assertThat(parse("{\"type\":\"m.room.member\",\"event_id\":\"$m\",\"sender\":\"@x:example.org\",\"content\":{}}"))
                .isNull();
        assertThat(MatrixEvents.stripReplyFallback("> <@a:b> Frage\n> zweite\n\nAntwort")).isEqualTo("Antwort");
    }

    @Test
    void ownTransactionsAndSameAccount() {
        ChatSystem.Message own = parse("{\"type\":\"m.room.message\",\"event_id\":\"$o\",\"sender\":\"" + ME + "\","
                + "\"unsigned\":{\"transaction_id\":\"dtmcp-123\"},\"content\":{\"msgtype\":\"m.text\",\"body\":\"x\"}}");
        assertThat(own.own()).isTrue();
        assertThat(own.fromMe()).isTrue();

        ChatSystem.Message typed = parse("{\"type\":\"m.room.message\",\"event_id\":\"$t\",\"sender\":\"" + ME + "\","
                + "\"content\":{\"msgtype\":\"m.text\",\"body\":\"y\"}}");
        assertThat(typed.own()).isFalse();
        assertThat(typed.fromMe()).isTrue();
        assertThat(typed.trusted()).isTrue();
    }
}
