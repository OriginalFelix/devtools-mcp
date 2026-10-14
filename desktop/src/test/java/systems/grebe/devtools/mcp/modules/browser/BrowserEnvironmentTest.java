package systems.grebe.devtools.mcp.modules.browser;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.openqa.selenium.Keys;
import systems.grebe.devtools.mcp.core.ModuleConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Teile des Browser-Moduls, die ohne Browser laufen. */
class BrowserEnvironmentTest {

    private static BrowserEnvironment env(Map<String, String> values) {
        return new BrowserEnvironment(ModuleConfig.of(new BrowserModule().configSchema(), values), new BrowserSessions());
    }

    @Test
    void hostPatterns() {
        assertThat(BrowserEnvironment.hostMatches("*.firma.de", "firma.de", "firma.de")).isTrue();
        assertThat(BrowserEnvironment.hostMatches("*.firma.de", "jira.firma.de", "jira.firma.de")).isTrue();
        assertThat(BrowserEnvironment.hostMatches("*.firma.de", "firma.de.evil.com", "firma.de.evil.com")).isFalse();
        assertThat(BrowserEnvironment.hostMatches("localhost:8080", "localhost", "localhost:8080")).isTrue();
        assertThat(BrowserEnvironment.hostMatches("localhost:8080", "localhost", "localhost:9090")).isFalse();
        assertThat(BrowserEnvironment.hostMatches("https://intranet.firma.de/start", "intranet.firma.de",
                "intranet.firma.de")).isTrue();
    }

    @Test
    void urlRules() {
        BrowserEnvironment open = env(Map.of());
        assertThat(open.urlProblem("https://example.org/x")).isNull();
        assertThat(open.urlProblem("about:blank")).isNull();
        assertThat(open.urlProblem("file:///etc/passwd")).contains("file://");
        assertThat(open.urlProblem("javascript:alert(1)")).contains("http(s)");
        assertThat(open.checkUrl("example.org")).isEqualTo("https://example.org");
        assertThat(open.checkUrl("localhost:8080/app")).isEqualTo("http://localhost:8080/app");

        BrowserEnvironment limited = env(Map.of(BrowserModule.ALLOWED_HOSTS, "*.firma.de\nlocalhost",
                BrowserModule.BLOCKED_HOSTS, "admin.firma.de", BrowserModule.ALLOW_FILE_URLS, "true"));
        assertThat(limited.urlProblem("https://wiki.firma.de/")).isNull();
        assertThat(limited.urlProblem("http://localhost:3000/")).isNull();
        assertThat(limited.urlProblem("https://example.org/")).contains("nicht freigegeben");
        assertThat(limited.urlProblem("https://admin.firma.de/")).contains("gesperrt");
        assertThat(limited.urlProblem("file:///tmp/x.html")).isNull();
        assertThatThrownBy(() -> limited.checkUrl("https://example.org")).hasMessageContaining("nicht freigegeben");
    }

    @Test
    void unknownBrowserListsChoices() {
        BrowserEnvironment e = env(Map.of(BrowserModule.CUSTOM_BROWSERS, ModuleConfig.formatRecords(List.of(
                Map.of("name", "brave", "engine", "chrome", "binary", "/opt/brave")))));
        assertThat(e.customBrowsers()).singleElement().satisfies(c -> {
            assertThat(c.engine()).isEqualTo("chrome");
            assertThat(c.binary()).isEqualTo("/opt/brave");
        });
        assertThatThrownBy(() -> e.open("netscape", true, false, null))
                .hasMessageContaining("Unbekannter Browser")
                .hasMessageContaining("brave");
    }

    @Test
    void attachNeedsSwitchAndLocalAddress() {
        assertThatThrownBy(() -> env(Map.of()).open("chrome", false, false, "localhost:9222"))
                .hasMessageContaining("abgeschaltet");
        assertThatThrownBy(() -> env(Map.of(BrowserModule.ALLOW_ATTACH, "true"))
                .open("chrome", false, false, "10.0.0.5:9222"))
                .hasMessageContaining("localhost");
    }

    @Test
    void keyChords() {
        List<BrowserInteractTools.Keyboard.Chord> chords = BrowserInteractTools.Keyboard.parse("ctrl+shift+Tab Enter a ctrl++");
        assertThat(chords).hasSize(4);
        assertThat(chords.get(0).modifiers()).containsExactly(Keys.CONTROL, Keys.SHIFT);
        assertThat(chords.get(0).key()).isEqualTo(Keys.TAB);
        assertThat(chords.get(1).key()).isEqualTo(Keys.ENTER);
        assertThat(chords.get(2).key()).isEqualTo("a");
        assertThat(chords.get(3).modifiers()).containsExactly(Keys.CONTROL);
        assertThat(chords.get(3).key()).isEqualTo("+");
        assertThat(BrowserInteractTools.Keyboard.parse("F5").getFirst().key()).isEqualTo(Keys.F5);
        assertThatThrownBy(() -> BrowserInteractTools.Keyboard.parse("hyper+x")).hasMessageContaining("Modifikator");
        assertThatThrownBy(() -> BrowserInteractTools.Keyboard.parse("Blubb")).hasMessageContaining("Unbekannte Taste");
    }

    @Test
    void screenshotsAreScaledToMaxEdge() throws IOException {
        BufferedImage big = new BufferedImage(3000, 1500, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(big, "png", out);

        Screenshots.Shot shot = Screenshots.scale(out.toByteArray(), 1000, "");
        assertThat(shot.width()).isEqualTo(1000);
        assertThat(shot.height()).isEqualTo(500);
        assertThat(shot.sourceWidth()).isEqualTo(3000);
        BufferedImage read = ImageIO.read(new ByteArrayInputStream(shot.data()));
        assertThat(read.getWidth()).isEqualTo(1000);

        Screenshots.Shot same = Screenshots.scale(out.toByteArray(), 4000, "");
        assertThat(same.width()).isEqualTo(3000);
        assertThat(same.data()).isEqualTo(out.toByteArray());
    }
}
