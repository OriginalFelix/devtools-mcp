package systems.grebe.devtools.mcp.modules.browser;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.openqa.selenium.JavascriptExecutor;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolImages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Anzeige der Aktionen im Browser ({@link Overlay}): Zeiger, Leiste, „Stopp“/„Fortsetzen“. Der Browser läuft ohne
 * Fenster über ein eigenes Argument, damit das Modul ihn als sichtbar behandelt (Safari mit Fenster).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BrowserOverlayTest {

    private static final String BROWSER = System.getProperty("devtools.test.browser", "chrome");
    private static final String PAGE = """
            <!doctype html><html><head><title>Overlay</title></head><body style="margin:0">
            <button id="go" style="position:absolute;left:300px;top:200px;width:100px;height:40px"
                onclick="document.getElementById('out').innerText = 'geklickt ' + (++window.n || (window.n = 1))">Los</button>
            <p id="out" style="position:absolute;top:400px"></p>
            </body></html>
            """;

    private HttpServer server;
    private BrowserSessions sessions;
    private BrowserTools read;
    private BrowserNavigationTools nav;
    private BrowserInteractTools act;

    @BeforeAll
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            byte[] bytes = PAGE.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        String headlessArg = switch (BROWSER) {
            case "firefox" -> "-headless";
            case "safari" -> "";
            default -> "--headless=new";
        };
        sessions = new BrowserSessions();
        BrowserEnvironment env = new BrowserEnvironment(ModuleConfig.of(new BrowserModule().configSchema(), Map.of(
                BrowserModule.HEADLESS, "false", BrowserModule.BROWSER_ARGS, headlessArg,
                BrowserModule.ANIMATION_MS, "60", BrowserModule.OVERLAY_LABEL, "Test steuert")), sessions);
        read = new BrowserTools(env);
        nav = new BrowserNavigationTools(env);
        act = new BrowserInteractTools(env);
        try {
            nav.open(BROWSER, "http://127.0.0.1:" + server.getAddress().getPort() + "/", null, false, null);
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

    private Object js(String script) {
        return ((JavascriptExecutor) sessions.current().driver).executeScript(script);
    }

    @Test
    void pointerMovesToTargetAndStopHaltsInput() {
        assertThat(js("return !!document.getElementById('__mcp_overlay')")).isEqualTo(true);
        assertThat(js("return document.getElementById('__mcp_overlay').shadowRoot.querySelector('.label').textContent"))
                .isEqualTo("Test steuert");

        act.click(null, null, "#go", null, null, null, null);
        assertThat(js("return document.getElementById('out').innerText")).isEqualTo("geklickt 1");
        // Zeiger mit der Spitze auf der Mitte des Buttons (350/220)
        assertThat(String.valueOf(js("return document.getElementById('__mcp_overlay').shadowRoot"
                + ".querySelector('.cursor').style.transform"))).contains("345px").contains("217.5px");
        assertThat(sessions.current().cursorX).isEqualTo(350);

        // Leiste und Zeiger tauchen weder in der Seitenstruktur noch im Screenshot auf
        assertThat(read.readPage(null, null, null, null, null)).contains("Los").doesNotContain("Stopp");
        ToolImages.collect(() -> read.screenshot(null, null, null, null, null));
        assertThat(js("return document.getElementById('__mcp_overlay').style.display")).isEqualTo("block");

        // Nutzer drückt „Stopp“
        js("document.getElementById('__mcp_overlay').shadowRoot.querySelector('button').click()");
        assertThatThrownBy(() -> act.click(null, null, "#go", null, null, null, null))
                .hasMessageContaining("angehalten");
        assertThat(js("return document.getElementById('out').innerText")).isEqualTo("geklickt 1");
        assertThat(js("return document.getElementById('__mcp_overlay').shadowRoot.querySelector('button').textContent"))
                .isEqualTo("Fortsetzen");

        // … und „Fortsetzen“
        js("document.getElementById('__mcp_overlay').shadowRoot.querySelector('button').click()");
        act.click(null, null, "#go", null, null, null, null);
        assertThat(js("return document.getElementById('out').innerText")).isEqualTo("geklickt 2");
    }
}
