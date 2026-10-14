package systems.grebe.devtools.mcp.modules.browser;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.openqa.selenium.By;
import org.openqa.selenium.Capabilities;
import org.openqa.selenium.HasCapabilities;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.NoAlertPresentException;
import org.openqa.selenium.NoSuchSessionException;
import org.openqa.selenium.NoSuchWindowException;
import org.openqa.selenium.UnhandledAlertException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.bidi.module.LogInspector;
import org.openqa.selenium.bidi.module.Network;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import systems.grebe.devtools.mcp.core.LocalFiles;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolImages;

/**
 * Einstellungen und offene Browser für die Tools: Sitzungen auflösen, Browser starten, Adressen prüfen, Elemente
 * finden, Screenshots aufnehmen. Fehler von WebDriver werden zu kurzen Meldungen ohne Stacktrace und Build-Info.
 */
final class BrowserEnvironment {

    private static final Logger LOG = LoggerFactory.getLogger(BrowserEnvironment.class);
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    /** Eingetragener weiterer Browser (z.B. Brave über die Chromium-Engine). */
    record CustomBrowser(String name, String engine, String binary, List<String> args) {
    }

    private final ModuleConfig config;
    private final BrowserSessions sessions;

    BrowserEnvironment(ModuleConfig config, BrowserSessions sessions) {
        this.config = config;
        this.sessions = sessions;
        sessions.idleMinutes(config.getInt(BrowserModule.IDLE_MINUTES, 30));
    }

    BrowserSessions sessions() {
        return sessions;
    }

    int maxLines() {
        return Math.max(20, config.getInt(BrowserModule.MAX_LINES, 400));
    }

    int maxImageEdge() {
        return Math.max(200, config.getInt(BrowserModule.MAX_IMAGE_EDGE, 1568));
    }

    int waitSeconds() {
        return Math.max(1, config.getInt(BrowserModule.WAIT_SECONDS, 10));
    }

    String defaultBrowser() {
        return config.getString(BrowserModule.DEFAULT_BROWSER, "chrome");
    }

    List<CustomBrowser> customBrowsers() {
        List<CustomBrowser> out = new ArrayList<>();
        for (Map<String, String> r : config.getRecords(BrowserModule.CUSTOM_BROWSERS)) {
            String name = r.getOrDefault("name", "").trim();
            if (!name.isEmpty()) {
                out.add(new CustomBrowser(name, r.getOrDefault("engine", "chrome").trim().toLowerCase(Locale.ROOT),
                        r.getOrDefault("binary", "").trim(), splitArgs(r.getOrDefault("args", ""))));
            }
        }
        return out;
    }

    private static List<String> splitArgs(String s) {
        return s == null || s.isBlank() ? List.of() : List.of(s.trim().split("\\s+"));
    }

    // ---- Sitzungen ---------------------------------------------------------------------------------------------

    /** Sitzung nach ID; leer = zuletzt verwendete. */
    BrowserSession session(String id) {
        if (id != null && !id.isBlank()) {
            BrowserSession s = sessions.get(id.trim());
            if (s == null) {
                throw new IllegalArgumentException("Kein offener Browser '" + id.trim() + "' – offen: " + ids()
                        + ". browser_sessions zeigt alle, browser_open startet einen.");
            }
            return s;
        }
        BrowserSession s = sessions.current();
        if (s == null) {
            throw new IllegalStateException("Kein Browser offen – zuerst browser_open (oder browser_navigate mit url).");
        }
        return s;
    }

    private List<String> ids() {
        return sessions.list().stream().map(s -> s.id).toList();
    }

    /**
     * Startet einen Browser.
     *
     * @param browser chrome, firefox, edge, safari oder ein eingetragener weiterer Browser; leer = Standard
     * @param attach  {@code host:port} eines laufenden Chromium-Browsers, sonst leer
     */
    BrowserSession open(String browser, Boolean headless, boolean remote, String attach) {
        int max = Math.max(1, config.getInt(BrowserModule.MAX_SESSIONS, 3));
        if (sessions.size() >= max) {
            throw new IllegalStateException("Schon " + sessions.size() + " Browser offen (Grenze " + max
                    + ") – mit browser_close einen schließen: " + ids());
        }
        String name = browser == null || browser.isBlank() ? defaultBrowser() : browser.trim();
        String key = name.toLowerCase(Locale.ROOT);
        CustomBrowser custom = customBrowsers().stream().filter(c -> c.name().equalsIgnoreCase(name)).findFirst()
                .orElse(null);
        String engine = custom != null ? custom.engine() : switch (key) {
            case "chromium", "google-chrome" -> "chrome";
            case "msedge", "microsoft-edge" -> "edge";
            default -> key;
        };
        if (custom == null && !Drivers.ENGINES.contains(engine)) {
            List<String> known = new ArrayList<>(Drivers.ENGINES);
            customBrowsers().forEach(c -> known.add(c.name()));
            throw new IllegalArgumentException("Unbekannter Browser '" + name + "' – verfügbar: " + known
                    + ". Weitere Browser (Brave, Vivaldi, Opera, LibreWolf …) trägt der Nutzer in der DevTools-App "
                    + "unter Module → Browser ein.");
        }
        String attachTo = attach == null ? "" : attach.trim();
        if (!attachTo.isEmpty()) {
            if (!config.getBoolean(BrowserModule.ALLOW_ATTACH)) {
                throw new IllegalStateException("Anhängen an laufende Browser ist in der DevTools-App abgeschaltet "
                        + "(Module → Browser → „An laufende Browser anhängen erlauben“).");
            }
            if (!attachTo.matches("(localhost|127\\.0\\.0\\.1|\\[::1]):\\d{2,5}")) {
                throw new IllegalArgumentException("attach muss localhost:<port> sein, z.B. localhost:9222.");
            }
        }
        String grid = remote ? config.getString(BrowserModule.GRID_URL, "") : "";
        if (remote && grid.isBlank()) {
            throw new IllegalStateException("Keine Grid-URL eingetragen (Module → Browser → Selenium Grid).");
        }
        boolean head = headless != null ? headless : config.getBoolean(BrowserModule.HEADLESS);
        int[] size = windowSize();
        List<String> args = new ArrayList<>(config.getList(BrowserModule.BROWSER_ARGS));
        if (custom != null) {
            args.addAll(custom.args());
        }
        Drivers.Launch launch = new Drivers.Launch(engine, custom != null ? custom.binary() : "", args,
                config.getString(BrowserModule.PROFILE_DIR, ""), head, size[0], size[1], attachTo, grid,
                config.getInt(BrowserModule.PAGE_LOAD_SECONDS, 30));
        WebDriver driver;
        try {
            driver = Drivers.start(launch);
        } catch (WebDriverException e) {
            LOG.warn("Browser {} konnte nicht gestartet werden", name, e);
            throw new IllegalStateException(name + " konnte nicht gestartet werden: " + shortMessage(e)
                    + Drivers.hint(engine, e.getMessage()));
        }
        BrowserSession s = new BrowserSession(sessions.nextId(), custom != null ? custom.name() : engine,
                !attachTo.isEmpty(), engine.equals("safari"), driver);
        if (attachTo.isEmpty() && !engine.equals("safari") && !engine.equals("firefox") && size[0] > 0 && !head) {
            // Chromium übernimmt --window-size nicht immer (gespeicherte Fenstergröße im Profil)
            try {
                driver.manage().window().setSize(new org.openqa.selenium.Dimension(size[0], size[1]));
            } catch (WebDriverException e) {
                LOG.debug("Fenstergröße nicht gesetzt", e);
            }
        } else if (attachTo.isEmpty() && engine.equals("safari") && size[0] > 0) {
            try {
                driver.manage().window().setSize(new org.openqa.selenium.Dimension(size[0], size[1]));
            } catch (WebDriverException e) {
                LOG.debug("Fenstergröße nicht gesetzt", e);
            }
        }
        listen(s);
        sessions.add(s);
        return s;
    }

    private int[] windowSize() {
        String v = config.getString(BrowserModule.WINDOW_SIZE, "1280x800").trim().toLowerCase(Locale.ROOT);
        String[] p = v.split("[x×*, ]+");
        try {
            return new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1])};
        } catch (RuntimeException e) {
            return new int[]{0, 0};
        }
    }

    /** Konsole und Netzwerk über WebDriver BiDi mitschneiden, soweit der Browser es kann. */
    private static void listen(BrowserSession s) {
        if (s.attached) {
            s.bidiUnavailable = "an einen laufenden Browser angehängt";
        } else try {
            LogInspector logs = new LogInspector(s.driver);
            s.addListener(logs);
            logs.onConsoleEntry(e -> s.console(new BrowserSession.Entry(e.getTimestamp(),
                    String.valueOf(e.getLevel()).toLowerCase(Locale.ROOT), e.getText())));
            logs.onJavaScriptException(e -> s.console(new BrowserSession.Entry(e.getTimestamp(), "exception",
                    e.getText())));
            Network net = new Network(s.driver);
            s.addListener(net);
            net.onResponseCompleted(r -> s.network(new BrowserSession.Entry(r.getTimestamp(),
                    r.getResponseData().getStatus() >= 400 ? "error" : "ok",
                    r.getRequest().getMethod() + " " + r.getResponseData().getStatus() + " "
                            + Optional.ofNullable(r.getResponseData().getMimeType()).orElse("") + " "
                            + kb(r.getResponseData().getBytesReceived()) + " " + r.getRequest().getUrl())));
            net.onFetchError(f -> s.network(new BrowserSession.Entry(f.getTimestamp(), "error",
                    f.getRequest().getMethod() + " FEHLER " + f.getErrorText() + " " + f.getRequest().getUrl())));
        } catch (RuntimeException e) {
            LOG.debug("WebDriver BiDi für {} nicht verfügbar", s.browser, e);
            s.bidiUnavailable = s.browser + " bietet kein WebDriver BiDi an";
        }
        if (s.bidiUnavailable != null) {
            pollConsole(s, s.driver);
        }
    }

    private static String kb(long bytes) {
        return bytes < 1024 ? bytes + " B" : String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
    }

    /**
     * Führt {@code action} mit dem Treiber der Sitzung aus – exklusiv, mit aktualisierter Nutzungszeit und kurzen
     * Fehlermeldungen. Hat der Nutzer den Browser geschlossen, wird die Sitzung entfernt.
     */
    <T> T with(BrowserSession s, Function<WebDriver, T> action) {
        synchronized (s) {
            s.touch();
            try {
                return action.apply(s.driver);
            } catch (NoSuchSessionException e) {
                sessions.remove(s.id);
                throw new IllegalStateException("Browser " + s.id + " ist nicht mehr erreichbar (geschlossen?) – "
                        + "Sitzung entfernt, mit browser_open neu starten.");
            } catch (NoSuchWindowException e) {
                throw new IllegalStateException("Der aktive Tab von " + s.id + " wurde geschlossen – mit browser_tabs "
                        + "(action=switch) zu einem offenen wechseln.");
            } catch (UnhandledAlertException e) {
                throw new IllegalStateException("Ein Dialog ist offen" + (e.getAlertText() == null ? "" : ": „"
                        + e.getAlertText() + "“") + " – mit browser_dialog bestätigen oder abbrechen.");
            } catch (WebDriverException e) {
                LOG.debug("WebDriver-Fehler in {}", s.id, e);
                throw new IllegalStateException(shortMessage(e));
            }
        }
    }

    /**
     * Wie {@link #with}, für Eingaben (Klicks, Tippen, Tasten): Safari verwirft Eingaben an ein Fenster ohne Fokus,
     * deshalb wird es vorher nach vorn geholt.
     */
    <T> T interact(BrowserSession s, Function<WebDriver, T> action) {
        return with(s, d -> {
            if (s.safari) {
                d.switchTo().window(d.getWindowHandle());
            }
            return action.apply(d);
        });
    }

    /** Erste Zeile der WebDriver-Meldung, ohne Build- und Systeminfo. */
    static String shortMessage(WebDriverException e) {
        String m = e.getRawMessage();
        if (m == null || m.isBlank()) {
            m = e.getMessage();
        }
        if (m == null || m.isBlank()) {
            return e.getClass().getSimpleName();
        }
        int cut = m.indexOf("\nBuild info");
        m = cut >= 0 ? m.substring(0, cut) : m;
        m = m.lines().filter(l -> !l.isBlank()).limit(3).reduce((a, b) -> a + " " + b).orElse(m).strip();
        return e.getClass().getSimpleName().replace("Exception", "") + ": " + clip(m, 400);
    }

    // ---- Adressen ----------------------------------------------------------------------------------------------

    /** Prüft eine Adresse vor dem Aufruf; ohne Schema wird {@code https://} ergänzt. */
    String checkUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("'url' fehlt.");
        }
        String u = url.trim();
        if (u.equals("about:blank")) {
            return u;
        }
        if (!u.matches("(?i)^([a-z][a-z0-9+.-]*://|(about|data|javascript|blob|mailto|file|chrome|view-source):).*")) {
            u = (u.startsWith("localhost") || u.startsWith("127.") ? "http://" : "https://") + u;
        }
        String problem = urlProblem(u);
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
        return u;
    }

    /** Warum die Adresse nicht erlaubt ist, oder {@code null}. */
    String urlProblem(String url) {
        if (url == null || url.isBlank() || url.startsWith("about:") || url.startsWith("data:")
                || url.startsWith("chrome-error:") || url.startsWith("blob:")) {
            return null;
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return "Ungültige Adresse: " + url;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (scheme.equals("file")) {
            return config.getBoolean(BrowserModule.ALLOW_FILE_URLS) ? null
                    : "Lokale Dateien (file://) sind im Browser-Modul nicht freigegeben.";
        }
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return "Nur http(s)-Adressen sind erlaubt, nicht " + scheme + ":";
        }
        List<String> allowed = config.getList(BrowserModule.ALLOWED_HOSTS);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        String hostPort = uri.getPort() < 0 ? host : host + ":" + uri.getPort();
        for (String b : config.getList(BrowserModule.BLOCKED_HOSTS)) {
            if (hostMatches(b, host, hostPort)) {
                return host + " ist im Browser-Modul gesperrt.";
            }
        }
        if (allowed.isEmpty() || allowed.stream().anyMatch(a -> hostMatches(a, host, hostPort))) {
            return null;
        }
        return host + " ist im Browser-Modul nicht freigegeben (erlaubt: " + String.join(", ", allowed)
                + ") – den Nutzer bitten, den Host in der DevTools-App unter Module → Browser freizugeben.";
    }

    /** {@code *.firma.de} passt auf firma.de und alle Subdomains; mit Port nur auf diesen Port. */
    static boolean hostMatches(String pattern, String host, String hostPort) {
        String p = pattern.trim().toLowerCase(Locale.ROOT).replaceFirst("^[a-z][a-z0-9+.-]*://", "");
        int slash = p.indexOf('/');
        if (slash >= 0) {
            p = p.substring(0, slash);
        }
        if (p.isEmpty()) {
            return false;
        }
        String target = p.contains(":") && !p.startsWith("[") ? hostPort : host;
        if (p.startsWith("*.") && target.equals(p.substring(2))) {
            return true;
        }
        String regex = Pattern.quote(p).replace("*", "\\E.*\\Q");
        return target.matches(regex);
    }

    /**
     * Nach Navigationen, Klicks und Eingaben: hat die Seite auf eine nicht freigegebene Adresse gewechselt, geht es
     * zurück (notfalls wird die letzte erlaubte Adresse neu geladen) und das Tool meldet einen Fehler.
     */
    void checkCurrentUrl(BrowserSession s, WebDriver d) {
        String url = d.getCurrentUrl();
        String problem = urlProblem(url);
        if (problem == null) {
            s.lastAllowedUrl = url;
            return;
        }
        d.navigate().back();
        // Safari kehrt aus back() zurück, bevor die vorige Seite da ist – oder ignoriert es, solange die Seite lädt
        long end = System.currentTimeMillis() + 2000;
        while (urlProblem(d.getCurrentUrl()) != null && System.currentTimeMillis() < end) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        String back = "Zurück zur vorherigen Seite.";
        if (urlProblem(d.getCurrentUrl()) != null) {
            String fallback = s.lastAllowedUrl == null ? "about:blank" : s.lastAllowedUrl;
            d.get(fallback);
            back = "Vorherige Seite " + fallback + " neu geladen.";
        }
        throw new IllegalStateException("Die Seite wollte nach " + url + " wechseln: " + problem + " " + back);
    }

    // ---- Elemente ----------------------------------------------------------------------------------------------

    /**
     * Element über eine Referenz aus browser_read_page/browser_find oder einen Selektor: CSS, {@code xpath=…} bzw.
     * {@code //…}, {@code text=…} (sichtbarer Text, erster Treffer).
     */
    static WebElement element(WebDriver d, String ref, String selector) {
        if (ref != null && !ref.isBlank()) {
            String r = ref.trim().replaceAll("^\\[|]$", "");
            Object el = ((JavascriptExecutor) d).executeScript(PageScripts.LOOKUP, r);
            if (el instanceof WebElement w) {
                return w;
            }
            throw new IllegalArgumentException("Element " + r + " gibt es nicht (mehr) – Referenzen gelten bis zur "
                    + "nächsten Navigation; mit browser_read_page oder browser_find neu ermitteln.");
        }
        if (selector == null || selector.isBlank()) {
            throw new IllegalArgumentException("'ref' oder 'selector' angeben.");
        }
        List<WebElement> found = find(d, selector.trim(), 1);
        if (found.isEmpty()) {
            throw new IllegalArgumentException("Kein Element zu '" + selector.trim() + "'.");
        }
        return found.getFirst();
    }

    /** Elemente zu einem Selektor (CSS, XPath oder {@code text=…}). */
    @SuppressWarnings("unchecked")
    static List<WebElement> find(WebDriver d, String selector, int max) {
        if (selector.startsWith("text=")) {
            Object r = ((JavascriptExecutor) d).executeScript(PageScripts.FIND_TEXT + "", selector.substring(5), max);
            List<String> lines = r instanceof Map<?, ?> m ? (List<String>) m.get("lines") : List.of();
            List<WebElement> out = new ArrayList<>();
            for (String line : lines) {
                String ref = line.substring(1, line.indexOf(']'));
                out.add(element(d, ref, null));
            }
            return out;
        }
        By by = selector.startsWith("xpath=") ? By.xpath(selector.substring(6))
                : selector.startsWith("//") || selector.startsWith("(//") ? By.xpath(selector)
                : By.cssSelector(selector.startsWith("css=") ? selector.substring(4) : selector);
        List<WebElement> all = d.findElements(by);
        return all.size() > max ? all.subList(0, max) : all;
    }

    static void scrollIntoView(WebDriver d, WebElement el) {
        ((JavascriptExecutor) d).executeScript(
                "arguments[0].scrollIntoView({block: 'center', inline: 'center', behavior: 'instant'});", el);
    }

    /** Lokale Datei für browser_upload, nur aus den freigegebenen Verzeichnissen. */
    Path uploadFile(String path) {
        return new LocalFiles(config.getList(BrowserModule.UPLOAD_DIRS), "Module → Browser → Verzeichnisse für Uploads")
                .readable(path);
    }

    static Path expandHome(String path) {
        return Path.of(path.replaceFirst("^~(?=[/\\\\]|$)",
                java.util.regex.Matcher.quoteReplacement(System.getProperty("user.home"))));
    }

    static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    // ---- Ausgabe -----------------------------------------------------------------------------------------------

    /** Statuszeile: Sitzung, Titel, Adresse. */
    static String status(BrowserSession s, WebDriver d) {
        String title;
        String url;
        try {
            title = d.getTitle();
            url = d.getCurrentUrl();
        } catch (UnhandledAlertException e) {
            return s.id + ": Dialog offen – browser_dialog";
        }
        return s.id + " (" + s.browser + "): „" + clip(title == null ? "" : title, 120) + "“ " + url;
    }

    static String browserVersion(WebDriver d) {
        if (d instanceof HasCapabilities hc) {
            Capabilities c = hc.getCapabilities();
            return Drivers.describe(c);
        }
        return "";
    }

    /**
     * Nach einer Aktion: kurz auf eine ausgelöste Navigation warten, Adresse prüfen und Statuszeile liefern – oder
     * melden, dass die Aktion einen Dialog geöffnet hat.
     */
    String after(BrowserSession s, WebDriver d) {
        String dialog = openDialog(d);
        if (dialog == null) {
            settle(d, s.safari ? s.lastAllowedUrl : null);
            dialog = openDialog(d);
        }
        if (dialog != null) {
            return s.id + ": Dialog offen „" + clip(dialog, 300) + "“ – mit browser_dialog bestätigen oder abbrechen.";
        }
        checkCurrentUrl(s, d);
        pollConsole(s, d);
        return status(s, d);
    }

    /**
     * Ohne BiDi: Konsole der aktuellen Seite per Skript abholen und den Hook (neu) setzen – nach jeder Aktion, damit
     * auch Meldungen einer neu geladenen Seite ab dann erfasst werden.
     */
    static void pollConsole(BrowserSession s, WebDriver d) {
        if (s.bidiUnavailable == null) {
            return;
        }
        try {
            Object r = ((JavascriptExecutor) d).executeScript(PageScripts.CONSOLE_HOOK);
            if (r instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) {
                        s.console(new BrowserSession.Entry(((Number) m.get("t")).longValue(),
                                String.valueOf(m.get("level")), String.valueOf(m.get("text"))));
                    }
                }
            }
        } catch (WebDriverException | ClassCastException e) {
            LOG.debug("Konsole von {} nicht per Skript lesbar", s.id, e);
        }
    }

    /** Text eines offenen alert/confirm/prompt oder {@code null}. */
    static String openDialog(WebDriver d) {
        try {
            String text = d.switchTo().alert().getText();
            return text == null ? "" : text;
        } catch (NoAlertPresentException e) {
            return null;
        }
    }

    /**
     * Wartet kurz, bis eine durch Klick/Eingabe ausgelöste Navigation geladen ist.
     *
     * @param urlBefore Adresse vor der Aktion, wenn der Browser (Safari) erst verzögert zu navigieren beginnt – dann
     *                  wird bis zu 600 ms auf den Beginn gewartet; sonst {@code null}
     */
    static void settle(WebDriver d, String urlBefore) {
        JavascriptExecutor js = (JavascriptExecutor) d;
        long start = System.currentTimeMillis();
        try {
            Thread.sleep(150);
            if (urlBefore != null) {
                while (System.currentTimeMillis() - start < 600
                        && "complete".equals(js.executeScript("return document.readyState"))
                        && urlBefore.equals(d.getCurrentUrl())) {
                    Thread.sleep(50);
                }
            }
            long end = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < end) {
                if ("complete".equals(js.executeScript("return document.readyState"))) {
                    return;
                }
                Thread.sleep(100);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (WebDriverException e) {
            LOG.debug("readyState nicht lesbar", e);
        }
    }

    /**
     * Screenshot an das Ergebnis hängen; je nach Einstellung bzw. wenn kein Bild übertragen werden kann, zusätzlich
     * als Datei speichern.
     *
     * @return Beschreibung für das Ergebnis
     */
    String deliver(BrowserSession s, Screenshots.Shot shot, boolean save) {
        boolean sent = ToolImages.add(shot.data(), shot.mimeType());
        StringBuilder sb = new StringBuilder();
        sb.append(shot.width()).append('×').append(shot.height()).append(" Pixel");
        if (!shot.note().isEmpty()) {
            sb.append(" (").append(shot.note()).append(')');
        }
        String dir = config.getString(BrowserModule.SCREENSHOT_DIR, "");
        if (save || !sent) {
            if (dir.isBlank()) {
                if (!sent) {
                    sb.append(". Das Bild kann über diesen Aufruf nicht übertragen werden, und es ist kein Ablageordner "
                            + "eingetragen (Module → Browser → Ablageordner für Screenshots).");
                }
            } else {
                Path file = expandHome(dir.trim()).resolve(s.id + "-" + FILE_TIME.format(LocalDateTime.now())
                        + (shot.mimeType().equals("image/png") ? ".png" : ".jpg"));
                try {
                    Files.createDirectories(file.getParent());
                    Files.write(file, shot.data());
                    sb.append(", gespeichert unter ").append(file);
                } catch (java.io.IOException e) {
                    sb.append(", nicht gespeichert: ").append(e.getMessage());
                }
            }
        }
        return sb.toString();
    }
}
