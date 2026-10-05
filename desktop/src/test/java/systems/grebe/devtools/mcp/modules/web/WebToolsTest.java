package systems.grebe.devtools.mcp.modules.web;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import systems.grebe.devtools.mcp.core.ModuleConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Web-Tools gegen einen lokalen HTTP-Stub, mit einem Fake-Modell statt Jlama. */
class WebToolsTest {

    static final String ARTICLE = """
            <!doctype html><html><head><title>Release 2.0</title><script>var x = 1;</script></head><body>
            <header><a href="/">Logo</a> <nav><a href="/a">Menü</a></nav></header>
            <main><h1>Release 2.0</h1>
            <p>Version <b>2.0</b> entfernt die veraltete Methode <code>Foo.bar()</code>.</p>
            <ul><li>Java 21 ist Mindestversion</li><li>Neue Option <code>--fast</code></li></ul>
            <pre>gradle build --fast</pre>
            <p>Weitere Details folgen in den nächsten Wochen. Dieser Absatz macht den Hauptinhalt lang genug, damit er
            als Hauptinhalt erkannt wird und die Navigation außen vor bleibt.</p></main>
            <footer>© Firma</footer></body></html>""";

    @TempDir
    Path home;

    HttpServer server;
    String base;
    final List<String> requests = new CopyOnWriteArrayList<>();
    final List<String> prompts = new CopyOnWriteArrayList<>();
    final List<Instant> deadlines = new CopyOnWriteArrayList<>();
    final Map<String, String> pages = new HashMap<>();
    final MutableClock clock = new MutableClock(Instant.parse("2026-10-05T10:00:00Z"));
    WebModule module;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        module = new WebModule(home, new LocalLlm());
        pages.put("/release", ARTICLE);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        requests.add(path);
        if (path.equals("/old")) {
            ex.getResponseHeaders().add("Location", "/release");
            ex.sendResponseHeaders(301, -1);
            ex.close();
            return;
        }
        if (path.equals("/image.png")) {
            reply(ex, 200, "image/png", "PNG");
            return;
        }
        String body = pages.get(path);
        if (body == null) {
            reply(ex, 404, "text/plain", "nicht da");
            return;
        }
        reply(ex, 200, path.endsWith(".txt") ? "text/plain; charset=utf-8" : "text/html; charset=utf-8", body);
    }

    private static void reply(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    WebTools tools(Map<String, String> values) {
        Map<String, String> v = new HashMap<>(Map.of(WebModule.ALLOW_PRIVATE, "true"));
        v.putAll(values);
        ModuleConfig config = ModuleConfig.of(module.configSchema(), v);
        return module.tools(config, (request, deadline) -> {
            StringBuilder user = new StringBuilder(request.header());
            request.segments().forEach(seg -> user.append(seg.text()).append('\n'));
            prompts.add(user.append(request.instruction()).toString());
            deadlines.add(deadline);
            return new LocalLlm.Completion("Überblick " + prompts.size(), 100, 10, request.segments().size(),
                    "STOP_TOKEN", 1234);
        }, clock);
    }

    @Test
    void summarizesAndCaches() {
        WebTools tools = tools(Map.of());
        Instant before = Instant.now();
        String first = tools.fetch(base + "/release#abschnitt", null, null);

        assertThat(first).startsWith("# Release 2.0\nURL: " + base + "/release")
                .contains("Zusammenfassung von " + WebModule.DEFAULT_MODEL + " (lokal, 1.2 s)")
                .contains("Überblick 1\n\nKernaussagen (wörtlich von der Seite):\n- ")
                .contains("- Java 21 ist Mindestversion")
                .doesNotContain("aus dem Cache");
        assertThat(prompts).hasSize(1);
        assertThat(prompts.getFirst()).contains("`Foo.bar()`", "Java 21 ist Mindestversion", "Summarize the page")
                .doesNotContain("Menü", "Logo", "© Firma", "var x", "gradle build --fast");
        assertThat(deadlines.getFirst()).isBetween(before.plusSeconds(7), Instant.now().plusSeconds(8));

        String second = tools(Map.of()).fetch(base + "/release", "", false);
        assertThat(second).contains("aus dem Cache", "Überblick 1", "- Java 21 ist Mindestversion");
        assertThat(requests).containsExactly("/release");
        assertThat(prompts).hasSize(1);
    }

    @Test
    void focusSelectsMatchingSentencesAndHasOwnCacheEntry() {
        WebTools tools = tools(Map.of());
        tools.fetch(base + "/release", null, null);
        String focused = tools.fetch(base + "/release", "Welche Java-Version wird gebraucht?", null);

        assertThat(focused).contains("Fragestellung: Welche Java-Version wird gebraucht?\n\nÜberblick 2")
                .doesNotContain("keine passende Stelle");
        assertThat(prompts.get(1)).contains("Java 21 ist Mindestversion",
                        "Answer this question using only the page text: Welche Java-Version wird gebraucht?")
                .doesNotContain("Weitere Details folgen");
        assertThat(requests).containsExactly("/release");

        String unrelated = tools.fetch(base + "/release", "Kubernetes Operator?", null);
        assertThat(unrelated).contains("keine passende Stelle gefunden, allgemeine Zusammenfassung");
    }

    @Test
    void refreshAndExpiryReload() {
        WebTools tools = tools(Map.of(WebModule.CACHE_HOURS, "1"));
        tools.fetch(base + "/release", null, null);
        tools.fetch(base + "/release", null, true);
        assertThat(requests).hasSize(2);
        assertThat(prompts).hasSize(2);

        clock.advance(Duration.ofMinutes(61));
        assertThat(tools.fetch(base + "/release", null, null)).doesNotContain("aus dem Cache");
        assertThat(requests).hasSize(3);
    }

    @Test
    void cacheCanBeDisabled() {
        WebTools tools = tools(Map.of(WebModule.CACHE_HOURS, "0"));
        tools.fetch(base + "/release", null, null);
        tools.fetch(base + "/release", null, null);
        assertThat(requests).hasSize(2);
        assertThat(module.cache(ModuleConfig.of(module.configSchema(), Map.of()), clock).size()).containsExactly(0, 0);
    }

    @Test
    void longPagesSendOnlyTheBestSentences() {
        StringBuilder sb = new StringBuilder("<html><body><main><h1>Handbuch</h1>");
        for (int i = 0; i < 200; i++) {
            sb.append("<p>Absatz ").append(i).append(" beschreibt die Option opt").append(i)
                    .append(" des Werkzeugs und ihre Wirkung auf den Build.</p>");
        }
        pages.put("/long", sb.append("</main></body></html>").toString());
        WebTools tools = tools(Map.of(WebModule.CONTEXT_CHARS, "1000", WebModule.KEY_POINTS, "3"));

        String out = tools.fetch(base + "/long", null, null);

        assertThat(prompts).hasSize(1);
        String page = prompts.getFirst().substring(prompts.getFirst().indexOf("Absatz"),
                prompts.getFirst().indexOf("Summarize"));
        assertThat(page.length()).isBetween(500, 1100);
        assertThat(page).startsWith("Absatz 0 ");
        assertThat(out.lines().filter(l -> l.startsWith("- Absatz"))).hasSize(3);
    }

    @Test
    void followsRedirectsAndReadsPlainText() {
        pages.put("/notes.txt", "Zeile 1\nZeile 2");
        WebTools tools = tools(Map.of());

        assertThat(tools.fetch(base + "/old", null, null)).contains("URL: " + base + "/release");
        assertThat(requests).containsExactly("/old", "/release");
        assertThat(tools.page(base + "/notes.txt", null, null)).contains("Zeile 1\nZeile 2");
    }

    @Test
    void pageIsPaged() {
        pages.put("/big.txt", "a".repeat(WebTools.PAGE_CHARS + 10));
        WebTools tools = tools(Map.of());

        String first = tools.page(base + "/big.txt", null, null);
        assertThat(first).contains("Zeichen 0–" + WebTools.PAGE_CHARS + " von " + (WebTools.PAGE_CHARS + 10))
                .endsWith("… weiter mit offset=" + WebTools.PAGE_CHARS);
        String second = tools.page(base + "/big.txt", WebTools.PAGE_CHARS, null);
        assertThat(second).endsWith("\n\naaaaaaaaaa");
        assertThat(requests).containsExactly("/big.txt");
        assertThat(prompts).isEmpty();
    }

    @Test
    void rejectsErrorsUnsupportedTypesAndSchemes() {
        WebTools tools = tools(Map.of());
        assertThatThrownBy(() -> tools.fetch(base + "/fehlt", null, null)).hasMessageContaining("HTTP 404");
        assertThatThrownBy(() -> tools.fetch(base + "/image.png", null, null))
                .hasMessageContaining("image/png wird nicht unterstützt");
        assertThatThrownBy(() -> tools.fetch("file:///etc/passwd", null, null))
                .hasMessageContaining("Nur http- und https-Adressen");
        assertThat(prompts).isEmpty();
    }

    @Test
    void blocksLocalNetworkUnlessAllowed() {
        WebTools tools = tools(Map.of(WebModule.ALLOW_PRIVATE, "false"));
        assertThatThrownBy(() -> tools.fetch(base + "/release", null, null))
                .hasMessageContaining("Adresse im lokalen Netz gesperrt");
        assertThat(requests).isEmpty();
    }

    @Test
    void normalizesUrls() {
        assertThat(PageFetcher.normalize("example.org/docs?a=1&b=%26#top").toString())
                .isEqualTo("https://example.org/docs?a=1&b=%26");
        assertThat(PageFetcher.normalize("localhost:8080/x").toString()).isEqualTo("https://localhost:8080/x");
        assertThat(PageFetcher.normalize("HTTP://Example.org").getScheme()).isEqualToIgnoringCase("http");
    }

    @Test
    void connectionTestReportsModelAndCache() {
        ModuleConfig config = ModuleConfig.of(module.configSchema(), Map.of());
        assertThat(module.testConnection(config).message())
                .contains("noch nicht geladen", "nicht gestartet", "0 Seite(n)");
    }

    /** Uhr, die der Test vorstellen kann. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
