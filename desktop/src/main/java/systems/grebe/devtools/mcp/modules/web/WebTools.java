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

/**
 * Webseiten abrufen: zusammengefasst bzw. eine Frage beantwortet ({@code web_fetch}) oder als Text ({@code web_page}),
 * beides mit Cache. Eine Seite gilt so lange, wie ihre HTTP-Header sagen, ohne Angabe unbegrenzt; abgelaufene Seiten mit
 * ETag/Last-Modified werden per bedingter Anfrage bestätigt. {@code refresh="force"} übergeht den Cache.
 */
@ToolHints(readOnly = true, openWorld = true)
class WebTools {

    /** Zeichen je Seite von {@code web_page}. */
    static final int PAGE_CHARS = 20_000;
    static final String FORCE = "force";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** Woher die Seite kam. */
    enum Source { CACHE, REVALIDATED, NETWORK }

    record Loaded(PageFetcher.Page page, Source source) {
    }

    private final PageFetcher fetcher;
    private final WebCache cache;
    private final PageSummarizer summarizer;
    private final Duration budget;
    private final Clock clock;

    /** @param budget Zeit für einen Aufruf von web_fetch ohne Cache-Treffer: Abruf plus lokales Modell */
    WebTools(PageFetcher fetcher, WebCache cache, PageSummarizer summarizer, Duration budget, Clock clock) {
        this.fetcher = fetcher;
        this.cache = cache;
        this.summarizer = summarizer;
        this.budget = budget;
        this.clock = clock;
    }

    @Tool(name = "fetch", description = "Ruft eine Webseite ab. Ohne 'prompt': kompakte Zusammenfassung von einem "
            + "lokalen LLM (Jlama, Llama 3.2 1B) aus einem Auszug der Seite. Mit 'prompt': Antwort auf die Frage von "
            + "Claude Haiku über die Claude API mit dem ganzen Seitentext (sofern eingerichtet, sonst lokal). Dazu die "
            + "wichtigsten Sätze der Seite wörtlich – spart Kontext gegenüber dem vollständigen Seiteninhalt. Seiten "
            + "und Antworten werden zwischengespeichert, so lange die HTTP-Header der Seite es erlauben, ohne Angabe "
            + "dauerhaft; refresh=\"force\" lädt neu. Für exakten Wortlaut (Code, Konfiguration, Zitate) web_page "
            + "verwenden. Nur HTML und Text, kein JavaScript-Rendering.")
    public String fetch(
            @ToolParam(description = "URL der Seite (http/https; ohne Schema wird https angenommen)") String url,
            @ToolParam(required = false, description = "Frage an die Seite (z.B. 'Welche Breaking Changes gibt "
                    + "es?') – beantwortet von Claude Haiku mit dem ganzen Seitentext. Leer = allgemeine "
                    + "Zusammenfassung vom lokalen Modell.") String prompt,
            @ToolParam(required = false, description = "\"force\" = Cache übergehen, Seite neu laden und neu "
                    + "beantworten. Leer = Cache nutzen, solange die Seite laut HTTP-Headern gültig ist.")
            String refresh) {
        Instant deadline = Instant.now().plus(budget);
        String key = PageFetcher.normalize(url).toString();
        String focus = prompt == null || prompt.isBlank() ? "" : prompt.strip();
        boolean force = force(refresh);
        String model = summarizer.modelFor(focus);
        // Lokales Modell startet schon, während die Seite lädt
        Loaded loaded = load(key, force, summarizer.usesLocal(focus) ? summarizer::prepare : () -> { });
        PageFetcher.Page page = loaded.page();
        String hash = page.contentHash();
        if (!force) {
            Optional<WebCache.Summary> cached = cache.summary(key, model, focus, hash);
            if (cached.isPresent()) {
                return format(cached.get(), page, loaded.source(), true, null);
            }
        }
        if (loaded.source() == Source.CACHE && summarizer.usesLocal(focus)) {
            summarizer.prepare(); // ohne Netzabruf noch nicht gestartet
        }
        PageSummarizer.Result r = summarizer.summarize(page, focus, deadline);
        WebCache.Summary s = new WebCache.Summary(key, page.finalUrl(), page.title(), r.model(), r.via(), focus,
                r.text(), r.keyPoints(), r.focusMatched(), page.truncated(), r.millis(), page.fetchedAt(),
                clock.instant(), hash, r.inputTokens(), r.outputTokens(), r.contextChars());
        cache.putSummary(s, page);
        return format(s, page, loaded.source(), false, r.note());
    }

    @Tool(name = "page", description = "Liefert den lesbaren Text einer Webseite als Markdown (ohne Navigation, "
            + "Skripte, Kopf-/Fußzeilen), seitenweise zu " + PAGE_CHARS + " Zeichen – für den genauen Wortlaut, wenn "
            + "die Antwort von web_fetch nicht reicht. Nutzt denselben Cache wie web_fetch.")
    public String page(
            @ToolParam(description = "URL der Seite (http/https)") String url,
            @ToolParam(required = false, description = "Ab diesem Zeichen lesen (Standard 0); der nächste Wert steht "
                    + "am Ende der Ausgabe") Integer offset,
            @ToolParam(required = false, description = "\"force\" = Cache übergehen und neu laden. Leer = Cache "
                    + "nutzen, solange die Seite laut HTTP-Headern gültig ist.") String refresh) {
        String key = PageFetcher.normalize(url).toString();
        Loaded loaded = load(key, force(refresh), () -> { });
        PageFetcher.Page page = loaded.page();
        String text = page.text();
        int from = Math.max(0, offset == null ? 0 : offset);
        if (from > 0 && from >= text.length()) {
            throw new IllegalArgumentException("offset " + from + " liegt hinter dem Ende des Textes (" + text.length()
                    + " Zeichen).");
        }
        int to = Math.min(text.length(), from + PAGE_CHARS);
        StringBuilder sb = new StringBuilder(heading(page.title(), page.finalUrl()));
        sb.append("Abgerufen ").append(time(page.fetchedAt())).append(source(loaded.source())).append(" · ")
                .append(cacheInfo(page)).append(" · Zeichen ").append(from).append('–').append(to).append(" von ")
                .append(text.length());
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

    /** „force“ (auch „true“) = Cache übergehen; leer bzw. „false“ = Cache nutzen. */
    static boolean force(String refresh) {
        String r = refresh == null ? "" : refresh.strip().toLowerCase(Locale.ROOT);
        return switch (r) {
            case FORCE, "true" -> true;
            case "", "false" -> false;
            default -> throw new IllegalArgumentException("refresh kennt nur \"force\" (Cache übergehen) oder leer, "
                    + "nicht \"" + refresh + "\".");
        };
    }

    /**
     * Seite aus dem Cache, solange sie gilt; abgelaufen mit Validator per bedingter Anfrage, sonst neu geladen.
     *
     * @param beforeNetwork läuft, bevor das Netz gefragt wird
     */
    private Loaded load(String key, boolean force, Runnable beforeNetwork) {
        Optional<PageFetcher.Page> cached = force ? Optional.empty() : cache.page(key);
        if (cached.isPresent() && cached.get().fresh(clock.instant())) {
            return new Loaded(cached.get(), Source.CACHE);
        }
        beforeNetwork.run();
        ToolProgress.report((cached.isPresent() && cached.get().revalidatable() ? "Prüfe " : "Lade ") + key + " …");
        PageFetcher.Fetched fetched = fetcher.fetch(key, cached.orElse(null));
        cache.putPage(fetched.page());
        return new Loaded(fetched.page(), fetched.notModified() ? Source.REVALIDATED : Source.NETWORK);
    }

    private String format(WebCache.Summary s, PageFetcher.Page page, Source source, boolean cached, String note) {
        boolean api = PageSummarizer.API.equals(s.via());
        StringBuilder sb = new StringBuilder(heading(s.title(), s.finalUrl()));
        sb.append(api ? "Antwort von " : "Zusammenfassung von ").append(s.model()).append(" (")
                .append(api ? PageSummarizer.API : PageSummarizer.LOCAL).append(", ")
                .append(String.format(Locale.ROOT, "%.1f s", s.millis() / 1000.0));
        if (api && s.inputTokens() >= 0) {
            sb.append(", ").append(s.inputTokens()).append(" Token ein / ").append(s.outputTokens()).append(" aus");
        }
        sb.append(") · Seite abgerufen ").append(time(page.fetchedAt())).append(source(source));
        if (cached) {
            sb.append(" · Antwort aus dem Cache");
        }
        sb.append(" · ").append(cacheInfo(page));
        if (s.truncated()) {
            sb.append(" · Download am Größenlimit abgeschnitten");
        }
        if (s.focus() != null && !s.focus().isBlank()) {
            sb.append("\nFragestellung: ").append(s.focus());
            if (!s.focusMatched()) {
                sb.append(" – keine passende Stelle gefunden, allgemeine Zusammenfassung");
            }
        }
        if (note != null) {
            sb.append("\nHinweis: ").append(note);
        }
        sb.append("\n\n").append(s.text().isBlank() ? "(keine Antwort des Modells)" : s.text()).append('\n');
        if (!s.keyPoints().isEmpty()) {
            sb.append("\nKernaussagen (wörtlich von der Seite):\n");
            s.keyPoints().forEach(k -> sb.append("- ").append(k).append('\n'));
        }
        sb.append("\n---\n");
        if (api) {
            sb.append("Antwort von ").append(s.model()).append(" aus dem Seitentext");
            if (s.contextChars() >= 0 && s.contextChars() < page.text().length()) {
                sb.append(" (nur die ersten ").append(s.contextChars()).append(" von ").append(page.text().length())
                        .append(" Zeichen)");
            }
            sb.append(". Exakter Wortlaut: web_page.");
        } else {
            sb.append("Zusammenfassung von einem kleinen lokalen Modell aus einem Auszug der Seite – kann ungenau "
                    + "sein. Vollständiger Text: web_page.");
        }
        return sb.toString();
    }

    private static String source(Source source) {
        return switch (source) {
            case CACHE -> " · aus dem Cache";
            case REVALIDATED -> " · unverändert (HTTP 304)";
            case NETWORK -> "";
        };
    }

    /** Wie lange die Seite im Cache gilt. */
    private String cacheInfo(PageFetcher.Page page) {
        if (!cache.enabled()) {
            return "Cache aus";
        }
        if (page.noStore()) {
            return "nicht gespeichert (no-store)";
        }
        if (page.expiresAt() == null) {
            return "Cache dauerhaft (keine HTTP-Angabe)";
        }
        String rule = page.cacheRule() == null || page.cacheRule().isBlank() ? "" : " (" + page.cacheRule() + ")";
        if (!page.fresh(clock.instant())) {
            return "Cache abgelaufen, wird beim nächsten Abruf geprüft" + rule;
        }
        return "Cache gültig bis " + time(page.expiresAt()) + rule;
    }

    private static String heading(String title, String url) {
        return "# " + (title == null || title.isBlank() ? url : title.strip()) + "\nURL: " + url + "\n";
    }

    private String time(Instant t) {
        return t == null ? "?" : TIME.format(t.atZone(clock.getZone()));
    }
}
