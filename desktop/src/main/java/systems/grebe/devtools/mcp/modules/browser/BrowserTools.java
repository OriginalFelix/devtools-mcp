package systems.grebe.devtools.mcp.modules.browser;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebElement;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.Text;
import systems.grebe.devtools.mcp.core.ToolHints;

/** Lesende Browser-Tools: offene Browser, Screenshot, Seitenstruktur, Text, Suche, Konsole, Netzwerk. */
@ToolHints(readOnly = true)
public class BrowserTools {

    static final String SESSION = "Browser-Sitzung (z.B. b1, siehe browser_sessions); leer = zuletzt verwendete";
    static final String REF = "Referenz eines Elements aus browser_read_page/browser_find, z.B. e12";
    static final String SELECTOR = "Statt ref: CSS-Selektor, XPath (//… oder xpath=…) oder text=<sichtbarer Text>";

    private final BrowserEnvironment env;

    BrowserTools(BrowserEnvironment env) {
        this.env = env;
    }

    @Tool(name = "sessions", description = "Listet die offenen, von der DevTools-App gesteuerten Browser: Sitzung, "
            + "Browser und Version, aktiver Tab (Titel, Adresse), Anzahl Tabs. Dazu die startbaren Browser."
            + ShellHints.BROWSER)
    public String sessions() {
        StringBuilder sb = new StringBuilder();
        List<BrowserSession> all = env.sessions().list();
        BrowserSession current = env.sessions().current();
        if (all.isEmpty()) {
            sb.append("Kein Browser offen.\n");
        }
        for (BrowserSession s : all) {
            String line;
            try {
                line = env.with(s, d -> BrowserEnvironment.status(s, d) + "  [" + BrowserEnvironment.browserVersion(d)
                        + ", " + d.getWindowHandles().size() + " Tab(s)" + (s.attached ? ", angehängt" : "") + "]");
            } catch (RuntimeException e) {
                line = s.id + " (" + s.browser + "): " + e.getMessage();
            }
            sb.append(s == current ? "* " : "  ").append(line).append('\n');
        }
        sb.append("Startbar: ").append(String.join(", ", Drivers.ENGINES));
        env.customBrowsers().forEach(c -> sb.append(", ").append(c.name()).append(" (").append(c.engine()).append(')'));
        sb.append(" – Standard ").append(env.defaultBrowser());
        return sb.toString();
    }

    @Tool(name = "screenshot", description = "Screenshot des aktiven Tabs als Bild: sichtbarer Ausschnitt (Standard), "
            + "ein einzelnes Element oder die ganze Seite (fullPage, Chrome/Edge/Firefox). Das Bild wird auf die "
            + "eingestellte Kantenlänge verkleinert. x/y für browser_click, browser_hover, browser_scroll und "
            + "browser_drag beziehen sich auf die Pixel des letzten Screenshots des sichtbaren Ausschnitts. Für Inhalte "
            + "und Elemente ist browser_read_page meist genauer und günstiger als ein Bild." + ShellHints.BROWSER)
    public String screenshot(
            @ToolParam(required = false, description = SESSION) String session,
            @ToolParam(required = false, description = "Nur dieses Element: " + REF) String ref,
            @ToolParam(required = false, description = SELECTOR) String selector,
            @ToolParam(required = false, description = "Ganze Seite statt sichtbarem Ausschnitt (Standard false)") Boolean fullPage,
            @ToolParam(required = false, description = "Zusätzlich im Ablageordner des Moduls speichern (Standard false)") Boolean save) {
        BrowserSession s = env.session(session);
        return env.with(s, d -> {
            WebElement el = (ref != null && !ref.isBlank()) || (selector != null && !selector.isBlank())
                    ? BrowserEnvironment.element(d, ref, selector) : null;
            boolean full = el == null && Boolean.TRUE.equals(fullPage);
            Screenshots.Shot shot = Screenshots.capture(d, el, full, env.maxImageEdge());
            if (el == null && !full) {
                Object w = ((JavascriptExecutor) d).executeScript("return window.innerWidth");
                s.cssPerImagePixel = w instanceof Number n ? n.doubleValue() / shot.width() : 0;
            }
            String kind = el != null ? "Element" : full && shot.note().isEmpty() ? "ganze Seite" : "sichtbarer Ausschnitt";
            return "Screenshot (" + kind + ", " + env.deliver(s, shot, Boolean.TRUE.equals(save)) + ")\n"
                    + BrowserEnvironment.status(s, d);
        });
    }

    @Tool(name = "read_page", description = "Struktur des aktiven Tabs als Liste mit Referenzen, ähnlich einem "
            + "Accessibility-Baum: je Element [ref] Rolle \"Name\" und Zustand (Wert, checked, disabled, href …). Mit "
            + "mode=interactive (Standard) nur bedienbare Elemente (Links, Buttons, Felder …), mit mode=all zusätzlich "
            + "Überschriften, Texte, Bilder, Tabellenzellen. Die Referenzen (e12) nehmen browser_click, browser_type, "
            + "browser_select usw. an; sie gelten bis zur nächsten Navigation. Inhalte von iframes werden nicht "
            + "aufgelistet." + ShellHints.BROWSER)
    public String readPage(
            @ToolParam(required = false, description = SESSION) String session,
            @ToolParam(required = false, description = "interactive (Standard) oder all") String mode,
            @ToolParam(required = false, description = "Nur Elemente im sichtbaren Ausschnitt (Standard false)") Boolean viewportOnly,
            @ToolParam(required = false, description = "Nur innerhalb dieses Elements: " + REF) String ref,
            @ToolParam(required = false, description = "Max. Elemente (Standard: Einstellung Max. Ausgabezeilen)") Integer maxItems) {
        BrowserSession s = env.session(session);
        String m = mode == null || mode.isBlank() ? "interactive" : mode.trim().toLowerCase(Locale.ROOT);
        if (!m.equals("interactive") && !m.equals("all")) {
            throw new IllegalArgumentException("mode muss interactive oder all sein.");
        }
        int max = maxItems == null || maxItems <= 0 ? env.maxLines() : Math.min(maxItems, env.maxLines() * 3);
        return env.with(s, d -> {
            Object r = ((JavascriptExecutor) d).executeScript(PageScripts.SNAPSHOT, m, max,
                    Boolean.TRUE.equals(viewportOnly), ref == null ? "" : ref.trim());
            if (r == null) {
                throw new IllegalArgumentException("Element " + ref + " gibt es nicht (mehr).");
            }
            return format(s, (Map<?, ?>) r, "Keine " + (m.equals("all") ? "" : "bedienbaren ") + "Elemente gefunden.",
                    "mit viewportOnly, ref oder maxItems eingrenzen bzw. nach browser_scroll erneut lesen");
        });
    }

    @Tool(name = "text", description = "Sichtbarer Text des aktiven Tabs (oder eines Elements) als Klartext, "
            + "zeilenweise mit Nachladen über fromLine – für Artikel, Tabellen, Meldungen. Für bedienbare Elemente "
            + "browser_read_page verwenden." + ShellHints.BROWSER)
    public String text(
            @ToolParam(required = false, description = SESSION) String session,
            @ToolParam(required = false, description = "Nur dieses Element: " + REF) String ref,
            @ToolParam(required = false, description = "Erste Zeile (Standard 1)") Integer fromLine) {
        BrowserSession s = env.session(session);
        return env.with(s, d -> {
            Object r = ((JavascriptExecutor) d).executeScript(PageScripts.TEXT, ref == null ? "" : ref.trim());
            if (r == null) {
                throw new IllegalArgumentException("Element " + ref + " gibt es nicht (mehr).");
            }
            Map<?, ?> page = (Map<?, ?>) r;
            List<String> lines = String.valueOf(page.get("text")).lines().map(String::strip)
                    .filter(l -> !l.isEmpty()).toList();
            int start = fromLine == null ? 1 : Math.max(1, fromLine);
            int end = Math.min(lines.size(), start + env.maxLines() - 1);
            StringBuilder sb = new StringBuilder(BrowserEnvironment.status(s, d)).append(" – ").append(lines.size())
                    .append(" Zeilen\n");
            for (int i = start; i <= end; i++) {
                sb.append(BrowserEnvironment.clip(lines.get(i - 1), 2000)).append('\n');
            }
            if (end < lines.size()) {
                sb.append("… [weitere Zeilen ab fromLine=").append(end + 1).append(']');
            }
            return sb.toString().strip();
        });
    }

    @Tool(name = "find", description = "Sucht Elemente im aktiven Tab nach sichtbarem Text (auch Beschriftung, "
            + "Platzhalter, Wert) oder nach CSS-Selektor/XPath und liefert sie mit Referenzen wie browser_read_page."
            + ShellHints.BROWSER)
    public String find(
            @ToolParam(required = false, description = SESSION) String session,
            @ToolParam(required = false, description = "Gesuchter Text (Groß-/Kleinschreibung egal)") String text,
            @ToolParam(required = false, description = "Statt text: CSS-Selektor oder XPath (//… bzw. xpath=…)") String selector,
            @ToolParam(required = false, description = "Max. Treffer (Standard 30)") Integer max) {
        boolean byText = text != null && !text.isBlank();
        if (!byText && (selector == null || selector.isBlank())) {
            throw new IllegalArgumentException("'text' oder 'selector' angeben.");
        }
        int limit = max == null || max <= 0 ? 30 : Math.min(max, 200);
        BrowserSession s = env.session(session);
        return env.with(s, d -> {
            JavascriptExecutor js = (JavascriptExecutor) d;
            Object r = byText ? js.executeScript(PageScripts.FIND_TEXT, text.strip(), limit)
                    : js.executeScript(PageScripts.DESCRIBE, BrowserEnvironment.find(d, selector.trim(), limit));
            return format(s, (Map<?, ?>) r, "Nichts gefunden.", "mit max mehr Treffer anfordern");
        });
    }

    @Tool(name = "console", description = "Konsolenausgaben (console.log/warn/error) und JavaScript-Fehler des Browsers "
            + "seit dem Start bzw. dem letzten clear, neueste zuletzt. Über WebDriver BiDi (Chrome, Edge, Firefox); in Safari "
            + "und angehängten Browsern per Skript ab der ersten Aktion auf einer Seite." + ShellHints.BROWSER)
    public String console(
            @ToolParam(required = false, description = SESSION) String session,
            @ToolParam(required = false, description = "Nur diese Stufen: debug, info (auch log), warn, error, exception (JS-Fehler), z.B. \"error,warn\"") String levels,
            @ToolParam(required = false, description = "Nur Einträge, die diesen regulären Ausdruck enthalten") String pattern,
            @ToolParam(required = false, description = "Danach leeren (Standard false)") Boolean clear,
            @ToolParam(required = false, description = "Max. Einträge, die neuesten (Standard 100)") Integer limit) {
        BrowserSession s = env.session(session);
        if (s.bidiUnavailable != null) {
            env.with(s, d -> {
                BrowserEnvironment.pollConsole(s, d);
                return null;
            });
        }
        Predicate<BrowserSession.Entry> filter = levelFilter(levels).and(patternFilter(pattern));
        String out = entries(s.console(filter, Boolean.TRUE.equals(clear)), limit, "Keine Konsolenausgaben.");
        return s.bidiUnavailable == null ? out : out + "\n(Ohne WebDriver BiDi – " + s.bidiUnavailable + " – per Skript "
                + "erfasst: nur Meldungen ab der ersten Aktion auf einer Seite, nicht während des Ladens.)";
    }

    @Tool(name = "network", description = "Abgeschlossene Netzwerkanfragen des Browsers (Methode, Status, Typ, Größe, "
            + "URL) und fehlgeschlagene Anfragen seit dem Start bzw. dem letzten clear, neueste zuletzt. Über WebDriver "
            + "BiDi (Chrome, Edge, Firefox); in Safari und angehängten Browsern nur die Ressourcen der aktuellen Seite "
            + "(Resource Timing)." + ShellHints.BROWSER)
    public String network(
            @ToolParam(required = false, description = SESSION) String session,
            @ToolParam(required = false, description = "Nur Fehler (Status ≥ 400, Abbrüche)") Boolean onlyErrors,
            @ToolParam(required = false, description = "Nur Einträge, die diesen regulären Ausdruck enthalten (z.B. /api/)") String pattern,
            @ToolParam(required = false, description = "Danach leeren (Standard false)") Boolean clear,
            @ToolParam(required = false, description = "Max. Einträge, die neuesten (Standard 100)") Integer limit) {
        BrowserSession s = env.session(session);
        Predicate<BrowserSession.Entry> filter = patternFilter(pattern);
        if (s.bidiUnavailable != null) {
            Predicate<BrowserSession.Entry> f = Boolean.TRUE.equals(onlyErrors) ? filter.and(e -> e.level().equals("error"))
                    : filter;
            List<BrowserSession.Entry> timing = env.with(s, BrowserTools::resourceTiming).stream().filter(f).toList();
            return entries(timing, limit, "Keine Anfragen.") + "\n(Ohne WebDriver BiDi – " + s.bidiUnavailable
                    + " – aus der Resource Timing API: nur die aktuelle Seite, ohne Methode und fehlgeschlagene "
                    + "Anfragen; Status 0 = vom Browser nicht gemeldet.)";
        }
        if (Boolean.TRUE.equals(onlyErrors)) {
            filter = filter.and(e -> e.level().equals("error"));
        }
        return entries(s.network(filter, Boolean.TRUE.equals(clear)), limit, "Keine Anfragen.");
    }

    private static List<BrowserSession.Entry> resourceTiming(org.openqa.selenium.WebDriver d) {
        Object r = ((JavascriptExecutor) d).executeScript(PageScripts.RESOURCES);
        List<BrowserSession.Entry> out = new java.util.ArrayList<>();
        if (r instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    int status = ((Number) m.get("status")).intValue();
                    long bytes = ((Number) m.get("bytes")).longValue();
                    out.add(new BrowserSession.Entry(((Number) m.get("t")).longValue(), status >= 400 ? "error" : "ok",
                            status + " " + m.get("type") + " " + bytes + " B " + m.get("ms") + " ms " + m.get("url")));
                }
            }
        }
        return out;
    }

    private String entries(List<BrowserSession.Entry> list, Integer limit, String empty) {
        if (list.isEmpty()) {
            return empty;
        }
        int n = limit == null || limit <= 0 ? 100 : limit;
        List<BrowserSession.Entry> tail = list.size() > n ? list.subList(list.size() - n, list.size()) : list;
        StringBuilder sb = new StringBuilder();
        if (tail.size() < list.size()) {
            sb.append("(").append(list.size() - tail.size()).append(" ältere ausgelassen)\n");
        }
        tail.forEach(e -> sb.append(BrowserEnvironment.clip(e.format(), 1000)).append('\n'));
        return Text.limitLines(sb.toString().strip(), env.maxLines());
    }

    private static Predicate<BrowserSession.Entry> levelFilter(String levels) {
        if (levels == null || levels.isBlank()) {
            return e -> true;
        }
        List<String> wanted = List.of(levels.toLowerCase(Locale.ROOT).split("[,\\s]+")).stream()
                .map(l -> switch (l) {
                    case "warning" -> "warn";
                    case "log" -> "info";
                    default -> l;
                }).toList();
        return e -> wanted.contains(e.level());
    }

    private static Predicate<BrowserSession.Entry> patternFilter(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return e -> true;
        }
        Pattern p;
        try {
            p = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            p = Pattern.compile(Pattern.quote(pattern), Pattern.CASE_INSENSITIVE);
        }
        Pattern compiled = p;
        return e -> compiled.matcher(e.text()).find();
    }

    /** Ergebnis von {@link PageScripts}: Kopf mit Adresse und Scrollposition, dann die Zeilen. */
    static String format(BrowserSession s, Map<?, ?> page, String empty, String moreHint) {
        @SuppressWarnings("unchecked")
        List<String> lines = (List<String>) page.get("lines");
        StringBuilder sb = new StringBuilder();
        sb.append(s.id).append(" (").append(s.browser).append("): „").append(page.get("title")).append("“ ")
                .append(page.get("url")).append('\n');
        sb.append("Ausschnitt ").append(page.get("innerWidth")).append('×').append(page.get("innerHeight"))
                .append(" bei y=").append(page.get("scrollY")).append(" von ").append(page.get("scrollHeight"))
                .append(" Seitenhöhe\n");
        if (lines.isEmpty()) {
            sb.append(empty);
        }
        lines.forEach(l -> sb.append(l).append('\n'));
        if (Boolean.TRUE.equals(page.get("truncated"))) {
            sb.append("… [weitere Elemente – ").append(moreHint).append(']');
        }
        return sb.toString().strip();
    }
}
