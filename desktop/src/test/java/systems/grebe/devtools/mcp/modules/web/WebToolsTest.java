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
    /** Zusätzliche Antwort-Header je Pfad, z.B. Cache-Control. */
    final Map<String, Map<String, String>> headers = new HashMap<>();
    /** Bedingte Anfragen: Pfad → If-None-Match bzw. If-Modified-Since der Anfrage. */
    final List<String> conditionals = new CopyOnWriteArrayList<>();
    final List<String> questions = new CopyOnWriteArrayList<>();
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
        Map<String, String> extra = headers.getOrDefault(path, Map.of());
        extra.forEach((k, v) -> ex.getResponseHeaders().add(k, v));
        String inm = ex.getRequestHeaders().getFirst("If-None-Match");
        String ims = ex.getRequestHeaders().getFirst("If-Modified-Since");
        if (inm != null || ims != null) {
            conditionals.add(path + " " + (inm != null ? inm : ims));
        }
        if (inm != null && inm.equals(extra.get("ETag")) || ims != null && ims.equals(extra.get("Last-Modified"))) {
            ex.sendResponseHeaders(304, -1);
            ex.close();
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
        return tools(values, null);
    }

    /** Fake für Claude: merkt sich die Frage, antwortet mit „Claude: …“ und der Länge des Dokuments. */
    PageSummarizer.Remote claude(boolean fail) {
        return new PageSummarizer.Remote() {
            @Override
            public String model() {
                return "claude-haiku-4-5";
            }

            @Override
            public PageSummarizer.Answer answer(String system, String document, String question) {
                questions.add(document + "\n?" + question);
                if (fail) {
                    throw new IllegalStateException("Kein Claude-API-Key");
                }
                return new PageSummarizer.Answer("Claude: " + question, 4321, 12, 800);
            }
        };
    }

    WebTools tools(Map<String, String> values, PageSummarizer.Remote remote) {
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
        }, remote, clock);
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

        String second = tools(Map.of()).fetch(base + "/release", "", "");
        assertThat(second).contains("aus dem Cache", "Antwort aus dem Cache", "Überblick 1",
                "- Java 21 ist Mindestversion", "Cache dauerhaft (keine HTTP-Angabe)");
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
    void withoutHttpHeadersPagesStayUntilForced() {
        WebTools tools = tools(Map.of());
        tools.fetch(base + "/release", null, null);
        clock.advance(Duration.ofDays(1000));
        assertThat(tools.fetch(base + "/release", null, null)).contains("Antwort aus dem Cache");
        assertThat(tools.page(base + "/release", null, null)).contains("aus dem Cache");
        assertThat(requests).hasSize(1);

        assertThat(tools.fetch(base + "/release", null, "force")).doesNotContain("aus dem Cache");
        assertThat(tools.page(base + "/release", null, "force")).doesNotContain("aus dem Cache");
        assertThat(requests).hasSize(3);
        assertThat(prompts).hasSize(2);
        assertThat(conditionals).isEmpty();
    }

    @Test
    void refreshAcceptsOnlyForce() {
        assertThat(WebTools.force("force")).isTrue();
        assertThat(WebTools.force(" TRUE ")).isTrue();
        assertThat(WebTools.force(null)).isFalse();
        assertThat(WebTools.force("false")).isFalse();
        assertThatThrownBy(() -> WebTools.force("immer")).hasMessageContaining("\"force\"");
    }

    @Test
    void maxAgeExpiresAndReusesSummaryForSameText() {
        headers.put("/release", Map.of("Cache-Control", "public, max-age=600"));
        WebTools tools = tools(Map.of());
        assertThat(tools.fetch(base + "/release", null, null)).contains("Cache gültig bis 2026-10-05 10:10 (max-age=600)");

        clock.advance(Duration.ofMinutes(9));
        assertThat(tools.fetch(base + "/release", null, null)).contains("aus dem Cache");
        assertThat(requests).hasSize(1);

        clock.advance(Duration.ofMinutes(2));
        String reloaded = tools.fetch(base + "/release", null, null);
        assertThat(requests).hasSize(2);
        // Gleicher Text: die Zusammenfassung gilt weiter, das Modell läuft nicht noch einmal
        assertThat(reloaded).contains("Antwort aus dem Cache", "Cache gültig bis 2026-10-05 10:21");
        assertThat(prompts).hasSize(1);

        pages.put("/release", ARTICLE.replace("Java 21", "Java 25"));
        clock.advance(Duration.ofMinutes(11));
        assertThat(tools.fetch(base + "/release", null, null)).doesNotContain("Antwort aus dem Cache");
        assertThat(prompts).hasSize(2);
    }

    @Test
    void noCacheRevalidatesWithEtag() {
        headers.put("/release", Map.of("Cache-Control", "no-cache", "ETag", "\"v1\""));
        WebTools tools = tools(Map.of());
        tools.fetch(base + "/release", null, null);

        String second = tools.fetch(base + "/release", null, null);
        assertThat(second).contains("unverändert (HTTP 304)", "Antwort aus dem Cache");
        assertThat(conditionals).containsExactly("/release \"v1\"");
        assertThat(requests).hasSize(2);
        assertThat(prompts).hasSize(1);
    }

    @Test
    void lastModifiedRevalidates() {
        headers.put("/release", Map.of("Expires", "0", "Last-Modified", "Mon, 05 Oct 2026 08:00:00 GMT"));
        WebTools tools = tools(Map.of());
        tools.page(base + "/release", null, null);
        assertThat(tools.page(base + "/release", null, null)).contains("unverändert (HTTP 304)");
        assertThat(conditionals).containsExactly("/release Mon, 05 Oct 2026 08:00:00 GMT");
    }

    @Test
    void noStoreIsNeverCached() {
        headers.put("/release", Map.of("Cache-Control", "no-store"));
        WebTools tools = tools(Map.of());
        assertThat(tools.fetch(base + "/release", null, null)).contains("nicht gespeichert (no-store)");
        tools.fetch(base + "/release", null, null);
        assertThat(requests).hasSize(2);
        assertThat(module.cache(ModuleConfig.of(module.configSchema(), Map.of()), clock).size()).containsExactly(0, 0);
    }

    @Test
    void httpHeadersCanBeIgnored() {
        headers.put("/release", Map.of("Cache-Control", "no-cache"));
        WebTools tools = tools(Map.of(WebModule.HTTP_CACHING, "false"));
        tools.fetch(base + "/release", null, null);
        assertThat(tools.fetch(base + "/release", null, null)).contains("Cache dauerhaft");
        assertThat(requests).hasSize(1);
    }

    @Test
    void questionsGoToClaudeWithTheWholePage() {
        WebTools tools = tools(Map.of(), claude(false));
        String out = tools.fetch(base + "/release", "Welche Java-Version?", null);

        assertThat(out).contains("Antwort von claude-haiku-4-5 (Claude API, 0.8 s, 4321 Token ein / 12 aus)",
                "Claude: Welche Java-Version?", "- Java 21 ist Mindestversion");
        assertThat(questions).hasSize(1);
        assertThat(questions.getFirst()).startsWith("<page>\nTitle: Release 2.0\n")
                .contains("Weitere Details folgen", "Java 21 ist Mindestversion")
                .endsWith("</page>\n?Welche Java-Version?");
        assertThat(prompts).isEmpty();

        // Zweite gleiche Frage: Cache; Zusammenfassung ohne Frage: lokal
        assertThat(tools.fetch(base + "/release", "Welche Java-Version?", null)).contains("Antwort aus dem Cache");
        assertThat(tools.fetch(base + "/release", null, null)).contains("Zusammenfassung von " + WebModule.DEFAULT_MODEL);
        assertThat(questions).hasSize(1);
        assertThat(prompts).hasSize(1);
    }

    @Test
    void longPagesAreCutForClaudeAndSaySo() {
        pages.put("/big.txt", "x".repeat(5000));
        WebTools tools = tools(Map.of(WebModule.CLAUDE_CONTEXT_CHARS, "1000"), claude(false));
        String out = tools.fetch(base + "/big.txt", "Was steht da?", null);
        assertThat(questions.getFirst()).contains("[… Seite nach 1000 von 5000 Zeichen gekürzt]");
        assertThat(out).contains("(nur die ersten 1000 von 5000 Zeichen)");
    }

    @Test
    void failingClaudeFallsBackToLocalAndRetriesLater() {
        WebTools tools = tools(Map.of(), claude(true));
        String out = tools.fetch(base + "/release", "Welche Java-Version?", null);
        assertThat(out).contains("Hinweis: Kein Claude-API-Key – stattdessen lokales Modell",
                "Zusammenfassung von " + WebModule.DEFAULT_MODEL, "Überblick 1");

        tools.fetch(base + "/release", "Welche Java-Version?", null);
        assertThat(questions).hasSize(2);
    }

    @Test
    void cacheCanBeDisabled() {
        WebTools tools = tools(Map.of(WebModule.CACHE_ENABLED, "false"));
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
