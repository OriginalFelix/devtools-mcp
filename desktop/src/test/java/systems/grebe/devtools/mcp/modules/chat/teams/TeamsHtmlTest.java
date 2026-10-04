package systems.grebe.devtools.mcp.modules.chat.teams;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** HTML von Teams-Nachrichten ↔ Klartext und Tenant-ID aus dem ID-Token. */
class TeamsHtmlTest {

    @Test
    void htmlToText() {
        assertThat(TeamsHtml.toText("<p>Hallo <at id=\"0\">Felix</at>,</p><p>bitte <b>prüfen</b>&nbsp;&amp; "
                + "<emoji id=\"smile\" alt=\"😄\" title=\"Smile\"></emoji></p><ul><li>eins</li><li>zwei</li></ul>"))
                .isEqualTo("Hallo @Felix,\nbitte prüfen & 😄\n- eins\n- zwei");
        assertThat(TeamsHtml.toText("<attachment id=\"123\"></attachment><p>Antwort</p>")).isEqualTo("Antwort");
        assertThat(TeamsHtml.toText("a&#39;b&#x27;c")).isEqualTo("a'b'c");
    }

    @Test
    void textToHtml() {
        assertThat(TeamsHtml.fromText("a < b & \"c\"\nd")).isEqualTo("a &lt; b &amp; &quot;c&quot;<br>d");
    }

    @Test
    void tenantFromIdToken() {
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"tid\":\"t-1\",\"name\":\"x\"}".getBytes(StandardCharsets.UTF_8));
        assertThat(GraphAuth.claim("h." + payload + ".s", "tid")).isEqualTo("t-1");
        assertThat(GraphAuth.claim("kaputt", "tid")).isNull();
        assertThat(GraphAuth.claim(null, "tid")).isNull();
    }
}
