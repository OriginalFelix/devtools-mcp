package systems.grebe.devtools.mcp.modules.browser;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.openqa.selenium.Alert;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.NoAlertPresentException;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WindowType;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.ToolHints;

/** Browser starten und schließen, navigieren, Tabs, Fenstergröße, Warten, Dialoge. */
@ToolHints(destructive = false)
public class BrowserNavigationTools {

    private final BrowserEnvironment env;

    BrowserNavigationTools(BrowserEnvironment env) {
        this.env = env;
    }

    @Tool(name = "open", description = "Startet einen Browser, den die DevTools-App steuert (Chrome, Firefox, Edge, "
            + "Safari oder ein in der App eingetragener weiterer Browser), optional ohne Fenster (headless) oder über "
            + "das eingestellte Selenium Grid, und öffnet optional eine Adresse. Treiber lädt Selenium Manager "
            + "automatisch. Liefert die Sitzung (b1, b2 …) für die übrigen browser_*-Tools. Mit attach=localhost:9222 "
            + "wird stattdessen ein schon laufender Chrome/Edge mit --remote-debugging-port übernommen (nur wenn in der "
            + "App erlaubt)." + ShellHints.BROWSER)
    public String open(
            @ToolParam(required = false, description = "chrome, firefox, edge, safari oder Name eines eingetragenen Browsers; leer = Standard") String browser,
            @ToolParam(required = false, description = "Adresse, die geöffnet wird") String url,
            @ToolParam(required = false, description = "Ohne sichtbares Fenster (Standard: Einstellung im Modul)") Boolean headless,
            @ToolParam(required = false, description = "Über das eingestellte Selenium Grid starten (Standard false)") Boolean remote,
            @ToolParam(required = false, description = "localhost:<port> eines laufenden Chrome/Edge mit Remote-Debugging übernehmen") String attach) {
        String target = url == null || url.isBlank() ? null : env.checkUrl(url);
        BrowserSession s = env.open(browser, headless, Boolean.TRUE.equals(remote), attach);
        return env.with(s, d -> {
            if (target != null) {
                d.get(target);
            }
            return "Browser " + s.id + " gestartet: " + BrowserEnvironment.browserVersion(d)
                    + (s.attached ? " (angehängt)" : "") + "\n" + env.after(s, d)
                    + (s.bidiUnavailable != null ? "\nKonsole/Netzwerk: ohne WebDriver BiDi (" + s.bidiUnavailable
                    + "), eingeschränkt per Skript" : "");
        });
    }

    @Tool(name = "close", description = "Beendet einen gesteuerten Browser (alle Tabs). Bei einem angehängten Browser "
            + "endet nur die Steuerung, der Browser bleibt offen." + ShellHints.BROWSER)
    public String close(
            @ToolParam(required = false, description = BrowserTools.SESSION + "; all = alle") String session) {
        if ("all".equalsIgnoreCase(session == null ? "" : session.trim())) {
            int n = env.sessions().size();
            env.sessions().closeAll();
            return n + " Browser beendet.";
        }
        BrowserSession s = env.session(session);
        env.sessions().remove(s.id);
        return s.attached ? "Steuerung von " + s.id + " beendet, der Browser bleibt offen." : "Browser " + s.id + " beendet.";
    }

    @Tool(name = "navigate", description = "Öffnet eine Adresse im aktiven Tab oder geht zurück, vor oder lädt neu "
            + "und wartet, bis die Seite geladen ist. Ist noch kein Browser offen, wird der Standard-Browser gestartet."
            + ShellHints.BROWSER)
    public String navigate(
            @ToolParam(required = false, description = BrowserTools.SESSION) String session,
            @ToolParam(required = false, description = "Adresse (ohne Schema wird https:// ergänzt)") String url,
            @ToolParam(required = false, description = "Statt url: back, forward oder reload") String action) {
        boolean byUrl = url != null && !url.isBlank();
        String a = action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
        if (!byUrl && a.isEmpty()) {
            throw new IllegalArgumentException("'url' oder 'action' (back, forward, reload) angeben.");
        }
        String target = byUrl ? env.checkUrl(url) : null;
        BrowserSession s = (session == null || session.isBlank()) && env.sessions().current() == null && byUrl
                ? env.open(null, null, false, null) : env.session(session);
        return env.with(s, d -> {
            switch (byUrl ? "url" : a) {
                case "url" -> d.get(target);
                case "back" -> d.navigate().back();
                case "forward" -> d.navigate().forward();
                case "reload", "refresh" -> d.navigate().refresh();
                default -> throw new IllegalArgumentException("Unbekannte action '" + action + "' – back, forward, reload.");
            }
            return env.after(s, d);
        });
    }

    @Tool(name = "tabs", description = "Tabs des Browsers: list (Standard) zeigt alle mit Nummer, Titel und Adresse; "
            + "new öffnet einen Tab (optional mit url) und wechselt hinein; switch wechselt zu Tab Nummer index; close "
            + "schließt Tab index (Standard: den aktiven). Die übrigen Tools wirken immer auf den aktiven Tab."
            + ShellHints.BROWSER)
    public String tabs(
            @ToolParam(required = false, description = BrowserTools.SESSION) String session,
            @ToolParam(required = false, description = "list, new, switch oder close") String action,
            @ToolParam(required = false, description = "Nummer des Tabs aus list (ab 1)") Integer index,
            @ToolParam(required = false, description = "Für new: Adresse") String url) {
        String a = action == null || action.isBlank() ? "list" : action.trim().toLowerCase(Locale.ROOT);
        String target = a.equals("new") && url != null && !url.isBlank() ? env.checkUrl(url) : null;
        BrowserSession s = env.session(session);
        return env.with(s, d -> {
            List<String> handles = new ArrayList<>(d.getWindowHandles());
            switch (a) {
                case "list" -> {
                    return list(s, d, handles);
                }
                case "new" -> {
                    d.switchTo().newWindow(WindowType.TAB);
                    if (target != null) {
                        d.get(target);
                    }
                    return "Neuer Tab: " + env.after(s, d);
                }
                case "switch" -> {
                    d.switchTo().window(handle(handles, index));
                    return BrowserEnvironment.status(s, d);
                }
                case "close" -> {
                    String h = index == null ? d.getWindowHandle() : handle(handles, index);
                    if (handles.size() == 1) {
                        throw new IllegalArgumentException("Das ist der letzte Tab – browser_close beendet den Browser.");
                    }
                    d.switchTo().window(h).close();
                    handles.remove(h);
                    d.switchTo().window(handles.getLast());
                    return "Tab geschlossen, aktiv: " + BrowserEnvironment.status(s, d);
                }
                default -> throw new IllegalArgumentException("Unbekannte action '" + action + "' – list, new, switch, close.");
            }
        });
    }

    private static String list(BrowserSession s, WebDriver d, List<String> handles) {
        String active = d.getWindowHandle();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < handles.size(); i++) {
            d.switchTo().window(handles.get(i));
            sb.append(handles.get(i).equals(active) ? "* " : "  ").append(i + 1).append(". „")
                    .append(BrowserEnvironment.clip(d.getTitle(), 100)).append("“ ").append(d.getCurrentUrl()).append('\n');
        }
        d.switchTo().window(active);
        return s.id + ": " + handles.size() + " Tab(s)\n" + sb.toString().strip();
    }

    private static String handle(List<String> handles, Integer index) {
        if (index == null || index < 1 || index > handles.size()) {
            throw new IllegalArgumentException("index zwischen 1 und " + handles.size() + " angeben (siehe action=list).");
        }
        return handles.get(index - 1);
    }

    @Tool(name = "resize", description = "Setzt die Fenstergröße des Browsers (z.B. 390×844 für ein Smartphone, "
            + "1920×1080 für Desktop) – für responsive Layouts." + ShellHints.BROWSER)
    public String resize(
            @ToolParam(required = false, description = BrowserTools.SESSION) String session,
            @ToolParam(description = "Breite in Pixeln") Integer width,
            @ToolParam(description = "Höhe in Pixeln") Integer height) {
        if (width == null || height == null || width < 100 || height < 100) {
            throw new IllegalArgumentException("width und height (mindestens 100) angeben.");
        }
        BrowserSession s = env.session(session);
        return env.with(s, d -> {
            d.manage().window().setSize(new Dimension(width, height));
            Object inner = ((JavascriptExecutor) d).executeScript("return [innerWidth, innerHeight]");
            s.cssPerImagePixel = 0;
            return "Fenster " + width + "×" + height + ", Ausschnitt " + inner + "\n" + BrowserEnvironment.status(s, d);
        });
    }

    @Tool(name = "wait", description = "Wartet, bis ein Element erscheint (selector) oder verschwindet (gone=true), ein "
            + "Text auf der Seite steht (text) oder die Adresse einen Teil enthält (urlContains) – oder einfach "
            + "seconds Sekunden. Für Seiten, die nachladen." + ShellHints.BROWSER)
    @ToolHints(readOnly = true)
    public String waitFor(
            @ToolParam(required = false, description = BrowserTools.SESSION) String session,
            @ToolParam(required = false, description = "CSS-Selektor oder XPath") String selector,
            @ToolParam(required = false, description = "Sichtbarer Text") String text,
            @ToolParam(required = false, description = "Teil der Adresse") String urlContains,
            @ToolParam(required = false, description = "Warten, bis selector/text verschwunden ist") Boolean gone,
            @ToolParam(required = false, description = "Nur warten (Sekunden, max. 60), ohne Bedingung") Integer seconds,
            @ToolParam(required = false, description = "Höchstens so lange warten (Sekunden, Standard: Einstellung)") Integer timeout) {
        BrowserSession s = env.session(session);
        boolean hasCondition = (selector != null && !selector.isBlank()) || (text != null && !text.isBlank())
                || (urlContains != null && !urlContains.isBlank());
        if (!hasCondition) {
            int sec = seconds == null ? 1 : Math.clamp(seconds, 0, 60);
            try {
                Thread.sleep(sec * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return env.with(s, d -> sec + " s gewartet. " + BrowserEnvironment.status(s, d));
        }
        int t = timeout == null || timeout <= 0 ? env.waitSeconds() : Math.min(timeout, 120);
        boolean disappear = Boolean.TRUE.equals(gone);
        long start = System.currentTimeMillis();
        return env.with(s, d -> {
            WebDriverWait w = new WebDriverWait(d, Duration.ofSeconds(t), Duration.ofMillis(200));
            try {
                w.until(drv -> {
                    boolean present;
                    if (urlContains != null && !urlContains.isBlank()) {
                        present = drv.getCurrentUrl().contains(urlContains.trim());
                    } else if (selector != null && !selector.isBlank()) {
                        present = BrowserEnvironment.find(drv, selector.trim(), 1).stream().anyMatch(e -> {
                            try {
                                return e.isDisplayed();
                            } catch (RuntimeException ex) {
                                return false;
                            }
                        });
                    } else {
                        Object r = ((JavascriptExecutor) drv).executeScript(
                                "return (document.body ? document.body.innerText : '').toLowerCase()"
                                        + ".includes(arguments[0].toLowerCase())", text.strip());
                        present = Boolean.TRUE.equals(r);
                    }
                    return present != disappear;
                });
            } catch (TimeoutException e) {
                throw new IllegalStateException("Nach " + t + " s nicht erfüllt. " + BrowserEnvironment.status(s, d));
            }
            return "Erfüllt nach " + (System.currentTimeMillis() - start) + " ms. " + BrowserEnvironment.status(s, d);
        });
    }

    @Tool(name = "dialog", description = "Liest, bestätigt oder schließt einen JavaScript-Dialog (alert, confirm, "
            + "prompt) bzw. eine „Seite verlassen?“-Abfrage. Solange einer offen ist, melden die anderen Tools das."
            + ShellHints.BROWSER)
    public String dialog(
            @ToolParam(required = false, description = BrowserTools.SESSION) String session,
            @ToolParam(required = false, description = "read (Standard), accept oder dismiss") String action,
            @ToolParam(required = false, description = "Für prompt: einzugebender Text (mit accept)") String text) {
        String a = action == null || action.isBlank() ? "read" : action.trim().toLowerCase(Locale.ROOT);
        BrowserSession s = env.session(session);
        String msg;
        synchronized (s) {
            s.touch();
            Alert alert;
            try {
                alert = s.driver.switchTo().alert();
            } catch (NoAlertPresentException e) {
                return "Kein Dialog offen.";
            }
            msg = alert.getText();
            switch (a) {
                case "read" -> {
                    return "Dialog: „" + msg + "“ – mit action=accept oder dismiss beantworten.";
                }
                case "accept" -> {
                    if (text != null) {
                        alert.sendKeys(text);
                    }
                    alert.accept();
                }
                case "dismiss" -> alert.dismiss();
                default -> throw new IllegalArgumentException("action: read, accept oder dismiss.");
            }
        }
        return env.with(s, d -> {
            return "Dialog „" + BrowserEnvironment.clip(msg == null ? "" : msg, 200) + "“ "
                    + (a.equals("accept") ? "bestätigt" : "abgebrochen") + ". " + env.after(s, d);
        });
    }
}
