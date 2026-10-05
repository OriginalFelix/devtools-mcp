package systems.grebe.devtools.mcp.modules.web;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ToolHints;
import systems.grebe.devtools.mcp.core.ToolProgress;

/** Webseiten abrufen: lokal zusammengefasst ({@code web_fetch}) oder als Text ({@code web_page}), beides mit Cache. */
@ToolHints(readOnly = true, openWorld = true)
class WebTools {

    /** Zeichen je Seite von {@code web_page}. */
    static final int PAGE_CHARS = 20_000;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final PageFetcher fetcher;
    private final WebCache cache;
    private final PageSummarizer summarizer;
    private final String model;
    private final Duration budget;
    private final Clock clock;

    /** @param budget Zeit für einen Aufruf von web_fetch ohne Cache-Treffer: Abruf plus Modell */
    WebTools(PageFetcher fetcher, WebCache cache, PageSummarizer summarizer, String model, Duration budget, Clock clock) {
        this.fetcher = fetcher;
        this.cache = cache;
        this.summarizer = summarizer;
        this.model = model;
        this.budget = budget;
        this.clock = clock;
    }

    @Tool(name = "fetch", description = "Ruft eine Webseite ab und liefert eine kompakte Zusammenfassung, erstellt in "
            + "wenigen Sekunden von einem lokalen LLM (Jlama, Standard Llama 3.2 1B), dazu die wichtigsten Sätze der "
            + "Seite wörtlich – spart Kontext gegenüber dem vollständigen Seiteninhalt. Mit 'prompt' gezielt nach etwas "
            + "fragen (z.B. 'Welche Breaking Changes gibt es?'), dann werden die passenden Stellen gesucht. Ergebnisse "
            + "werden zwischengespeichert; 'refresh' lädt neu. Das Modell sieht nur einen Auszug und kann ungenau "
            + "sein – für Vollständigkeit oder exakten Wortlaut (Code, Konfiguration, Zitate) web_page verwenden. Nur "
            + "HTML und Text, kein JavaScript-Rendering.")
    public String fetch(
            @ToolParam(description = "URL der Seite (http/https; ohne Schema wird https angenommen)") String url,
            @ToolParam(required = false, description = "Worauf die Zusammenfassung achten soll – Frage oder Thema. "
                    + "Leer = allgemeine Zusammenfassung.") String prompt,
            @ToolParam(required = false, description = "true = Cache übergehen, Seite neu laden und neu zusammenfassen")
            Boolean refresh) {
        Instant deadline = Instant.now().plus(budget);
        String key = PageFetcher.normalize(url).toString();
        String focus = prompt == null || prompt.isBlank() ? "" : prompt.strip();
        boolean fresh = Boolean.TRUE.equals(refresh);
        if (!fresh) {
            Optional<WebCache.Summary> cached = cache.summary(key, model, focus);
            if (cached.isPresent()) {
                return format(cached.get(), true);
            }
        }
        summarizer.prepare(); // Modell startet, während die Seite lädt
        PageFetcher.Page page = load(key, fresh);
        PageSummarizer.Result r = summarizer.summarize(page, focus, deadline);
        WebCache.Summary s = new WebCache.Summary(key, page.finalUrl(), page.title(), model, focus, r.text(),
                r.keyPoints(), r.focusMatched(), page.truncated(), r.millis(), page.fetchedAt(), clock.instant());
        cache.putSummary(s);
        return format(s, false);
    }

    @Tool(name = "page", description = "Liefert den lesbaren Text einer Webseite als Markdown (ohne Navigation, "
            + "Skripte, Kopf-/Fußzeilen), seitenweise zu " + PAGE_CHARS + " Zeichen – für den genauen Wortlaut, wenn "
            + "die Zusammenfassung von web_fetch nicht reicht. Nutzt denselben Cache wie web_fetch.")
    public String page(
            @ToolParam(description = "URL der Seite (http/https)") String url,
            @ToolParam(required = false, description = "Ab diesem Zeichen lesen (Standard 0); der nächste Wert steht "
                    + "am Ende der Ausgabe") Integer offset,
            @ToolParam(required = false, description = "true = Cache übergehen und neu laden") Boolean refresh) {
        String key = PageFetcher.normalize(url).toString();
        PageFetcher.Page page = load(key, Boolean.TRUE.equals(refresh));
        String text = page.text();
        int from = Math.max(0, offset == null ? 0 : offset);
        if (from > 0 && from >= text.length()) {
            throw new IllegalArgumentException("offset " + from + " liegt hinter dem Ende des Textes (" + text.length()
                    + " Zeichen).");
        }
        int to = Math.min(text.length(), from + PAGE_CHARS);
        StringBuilder sb = new StringBuilder(heading(page.title(), page.finalUrl()));
        sb.append("Abgerufen ").append(time(page.fetchedAt())).append(" · Zeichen ").append(from).append('–').append(to)
                .append(" von ").append(text.length());
        if (page.truncated()) {
            sb.append(" · Download gekürzt (Größenlimit)");
        }
        sb.append("\n\n").append(text.isEmpty() ? "(kein lesbarer Text – Inhalt wird vermutlich per JavaScript geladen)"
                : text.substring(from, to));
        if (to < text.length()) {
            sb.append("\n\n… weiter mit offset=").append(to);
        }
        return sb.toString();
    }

    private PageFetcher.Page load(String key, boolean refresh) {
        if (!refresh) {
            Optional<PageFetcher.Page> cached = cache.page(key);
            if (cached.isPresent()) {
                return cached.get();
            }
        }
        ToolProgress.report("Lade " + key + " …");
        PageFetcher.Page page = fetcher.fetch(key);
        cache.putPage(page);
        return page;
    }

    private String format(WebCache.Summary s, boolean cached) {
        StringBuilder sb = new StringBuilder(heading(s.title(), s.finalUrl()));
        sb.append("Zusammenfassung von ").append(s.model()).append(" (lokal, ")
                .append(String.format(Locale.ROOT, "%.1f s", s.millis() / 1000.0)).append(") · Seite abgerufen ")
                .append(time(s.fetchedAt()));
        if (cached) {
            sb.append(" · aus dem Cache");
        }
        if (s.truncated()) {
            sb.append(" · Download am Größenlimit abgeschnitten");
        }
        if (s.focus() != null && !s.focus().isBlank()) {
            sb.append("\nFragestellung: ").append(s.focus());
            if (!s.focusMatched()) {
                sb.append(" – keine passende Stelle gefunden, allgemeine Zusammenfassung");
            }
        }
        sb.append("\n\n").append(s.text().isBlank() ? "(keine Antwort des Modells)" : s.text()).append('\n');
        if (!s.keyPoints().isEmpty()) {
            sb.append("\nKernaussagen (wörtlich von der Seite):\n");
            s.keyPoints().forEach(k -> sb.append("- ").append(k).append('\n'));
        }
        sb.append("\n---\nZusammenfassung von einem kleinen lokalen Modell aus einem Auszug der Seite – kann ungenau "
                + "sein. Vollständiger Text: web_page.");
        return sb.toString();
    }

    private static String heading(String title, String url) {
        return "# " + (title == null || title.isBlank() ? url : title.strip()) + "\nURL: " + url + "\n";
    }

    private String time(Instant t) {
        return t == null ? "?" : TIME.format(t.atZone(clock.getZone()));
    }
}
