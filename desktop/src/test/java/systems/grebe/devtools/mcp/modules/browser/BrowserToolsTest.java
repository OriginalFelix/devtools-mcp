package systems.grebe.devtools.mcp.modules.browser;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolImages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Die Tools gegen einen echten Browser (Treiber über Selenium Manager) und eine lokale Testseite: Chrome headless,
 * mit {@code -Pbrowser=firefox|edge|safari} ein anderer (Safari mit Fenster). Ohne startbaren Browser werden die Tests
 * übersprungen.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BrowserToolsTest {

    private static final String PAGE = """
            <!doctype html><html><head><title>Testseite</title></head><body>
            <h1>Hallo Welt</h1>
            <label for="name">Name</label><input id="name" placeholder="Ihr Name">
            <select id="farbe"><option value="r">Rot</option><option value="g">Grün</option></select>
            <button id="go" onclick="document.getElementById('out').innerText = 'Hallo ' +
                document.getElementById('name').value + ' ' + document.getElementById('farbe').value;
                console.log('geklickt'); fetch('/fehlt')">Senden</button>
            <button onclick="alert('Achtung!')">Warnung</button>
            <p id="out"></p>
            <a href="/zwei">Weiter</a>
            <a href="https://example.org/">Extern</a>
            </body></html>
            """;

    private static final String BROWSER = System.getProperty("devtools.test.browser", "chrome");

    private HttpServer server;
    private String base;
    private BrowserSessions sessions;
    private BrowserEnvironment env;
    private BrowserTools read;
    private BrowserNavigationTools nav;
    private BrowserInteractTools act;

    @BeforeAll
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            String body = path.equals("/") ? PAGE : path.equals("/zwei")
                    ? "<html><head><title>Zwei</title></head><body><p>Seite zwei</p></body></html>" : null;
            byte[] bytes = (body == null ? "nicht da" : body).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(body == null ? 404 : 200, bytes.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        sessions = new BrowserSessions();
        env = new BrowserEnvironment(ModuleConfig.of(new BrowserModule().configSchema(), Map.of(
                BrowserModule.ALLOWED_HOSTS, "127.0.0.1", BrowserModule.HEADLESS, String.valueOf(!BROWSER.equals("safari")),
                BrowserModule.WINDOW_SIZE, "1000x700")), sessions);
        read = new BrowserTools(env);
        nav = new BrowserNavigationTools(env);
        act = new BrowserInteractTools(env);
        try {
            nav.open(BROWSER, base + "/", null, false, null);
        } catch (RuntimeException e) {
            assumeTrue(false, BROWSER + " nicht startbar: " + e.getMessage());
        }
    }

    @AfterAll
    void stop() {
        if (sessions != null) {
            sessions.closeAll();
        }
        if (server != null) {
            server.stop(0);
        }
    }

    private static String ref(String page, String contains) {
        for (String line : page.lines().toList()) {
            if (line.contains(contains)) {
                Matcher m = Pattern.compile("^\\[(e\\d+)]").matcher(line);
                if (m.find()) {
                    return m.group(1);
                }
            }
        }
        throw new AssertionError("Keine Zeile mit " + contains + " in\n" + page);
    }

    @Test
    void readTypeSelectClickAndObserve() {
        nav.navigate(null, base + "/", null);
        String page = read.readPage(null, null, null, null, null);
        assertThat(page).contains("„Testseite“").contains("textbox \"Name\"").contains("combobox")
                .contains("button \"Senden\"").contains("link \"Weiter\" href=/zwei");

        act.type(null, ref(page, "textbox \"Name\""), null, "Ada", null, null);
        act.select(null, null, "#farbe", List.of("Grün"));
        String clicked = act.click(null, ref(page, "button \"Senden\""), null, null, null, null, null);
        assertThat(clicked).contains("Geklickt auf").contains("Senden");

        assertThat(read.text(null, null, null)).contains("Hallo Ada g");
        assertThat(read.readPage(null, "all", null, null, null)).contains("heading1 \"Hallo Welt\"")
                .contains("value=\"Ada\"").contains("selected=\"Grün\"");
        assertThat(read.find(null, "hallo ada", null, null)).contains("p \"Hallo Ada g\"");

        nav.waitFor(null, null, null, null, null, null, 2);
        assertThat(read.console(null, "info", null, null, null)).contains("geklickt");
        assertThat(read.network(null, null, "fehlt", null, null)).contains("/fehlt");
        if (!BROWSER.equals("safari")) {
            // Safari: Resource Timing ohne Status
            assertThat(read.network(null, true, null, null, null)).contains("404").contains("/fehlt");
        }
    }

    @Test
    void screenshotGoesToImageChannelAndCoordinatesMapToCss() {
        nav.navigate(null, base + "/", null);
        ToolImages.Collected<String> c = ToolImages.collect(() -> read.screenshot(null, null, null, null, null));
        assertThat(c.result()).contains("sichtbarer Ausschnitt");
        assertThat(c.images()).singleElement().satisfies(i -> {
            assertThat(i.mimeType()).isEqualTo("image/png");
            assertThat(i.data()).isNotEmpty();
        });
        assertThat(sessions.current().cssPerImagePixel).isGreaterThan(0);

        ToolImages.Collected<String> el = ToolImages.collect(() -> read.screenshot(null, null, "h1", null, null));
        assertThat(el.result()).contains("Element");
        assertThat(el.images()).hasSize(1);

        ToolImages.Collected<String> full = ToolImages.collect(() -> read.screenshot(null, null, null, true, null));
        assertThat(full.result()).contains("ganze Seite");

        // ohne Bild-Kanal (Skript) und ohne Ablageordner: Hinweis statt Bild
        assertThat(read.screenshot(null, null, null, null, null)).contains("nicht übertragen");
    }

    @Test
    void dialogsTabsKeysAndBlockedNavigation() {
        nav.navigate(null, base + "/", null);
        String page = read.readPage(null, null, null, null, null);
        act.click(null, ref(page, "button \"Warnung\""), null, null, null, null, null);
        assertThatThrownBy(() -> read.readPage(null, null, null, null, null)).hasMessageContaining("Achtung!");
        assertThat(nav.dialog(null, null, null)).contains("Achtung!");
        assertThat(nav.dialog(null, "accept", null)).contains("bestätigt");

        assertThatThrownBy(() -> act.click(null, ref(page, "link \"Extern\""), null, null, null, null, null))
                .hasMessageContaining("example.org").hasMessageContaining("nicht freigegeben");
        assertThat(read.sessions()).contains("Testseite");
        assertThatThrownBy(() -> nav.navigate(null, "https://example.org", null))
                .hasMessageContaining("nicht freigegeben");

        assertThat(nav.tabs(null, "new", null, base + "/zwei")).contains("Zwei");
        // Reihenfolge der Tabs je nach Browser (Safari: neuer Tab zuerst)
        assertThat(nav.tabs(null, "list", null, null)).contains("2 Tab(s)").containsPattern("\\* \\d\\. „Zwei“");
        assertThat(nav.tabs(null, "close", null, null)).contains("Testseite");

        act.type(null, null, "#name", "xyz", null, null);
        act.key(null, "Backspace", 2, null);
        assertThat(read.readPage(null, null, null, null, null)).contains("value=\"x\"");

        act.click(null, ref(read.readPage(null, null, null, null, null), "link \"Weiter\""), null, null, null, null, null);
        assertThat(nav.navigate(null, null, "back")).contains("Testseite");
        assertThat(act.scroll(null, "down", 200, null, null, null, null)).contains("Gescrollt");
    }
}
