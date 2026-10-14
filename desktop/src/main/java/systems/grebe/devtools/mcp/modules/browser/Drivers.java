package systems.grebe.devtools.mcp.modules.browser;

import java.net.MalformedURLException;
import java.net.URI;
import java.util.List;
import java.util.Locale;

import org.openqa.selenium.Capabilities;
import org.openqa.selenium.PageLoadStrategy;
import org.openqa.selenium.UnexpectedAlertBehaviour;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.chromium.ChromiumOptions;
import org.openqa.selenium.edge.EdgeDriver;
import org.openqa.selenium.edge.EdgeOptions;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;
import org.openqa.selenium.remote.AbstractDriverOptions;
import org.openqa.selenium.remote.Augmenter;
import org.openqa.selenium.remote.LocalFileDetector;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.safari.SafariDriver;
import org.openqa.selenium.safari.SafariOptions;

/**
 * Startet Browser über WebDriver. Treiber (chromedriver, geckodriver, msedgedriver) und – fehlt er – der Browser selbst
 * kommen über Selenium Manager; Safari bringt {@code safaridriver} mit. Weitere Browser auf Basis von Chromium (Brave,
 * Vivaldi, Opera …) oder Firefox (LibreWolf …) laufen über den Treiber ihrer Engine mit eigenem Programm.
 */
final class Drivers {

    /** Engines, die WebDriver ansteuern kann. */
    static final List<String> ENGINES = List.of("chrome", "firefox", "edge", "safari");

    /**
     * Was gestartet werden soll.
     *
     * @param engine   chrome, firefox, edge oder safari
     * @param binary   Programm des Browsers (leer = installierter Standard bzw. Selenium Manager)
     * @param args     zusätzliche Kommandozeilen-Argumente
     * @param profile  Profilverzeichnis (leer = frisches, temporäres Profil)
     * @param attach   {@code host:port} eines laufenden Chromium-Browsers mit Remote-Debugging, sonst leer
     * @param gridUrl  Selenium Grid / Remote-WebDriver, sonst leer
     */
    record Launch(String engine, String binary, List<String> args, String profile, boolean headless, int width,
                  int height, String attach, String gridUrl, int pageLoadSeconds) {
    }

    private Drivers() {
    }

    static WebDriver start(Launch l) {
        Capabilities options = options(l);
        if (!l.gridUrl().isBlank()) {
            RemoteWebDriver remote;
            try {
                remote = new RemoteWebDriver(URI.create(l.gridUrl().trim()).toURL(), options);
            } catch (MalformedURLException | IllegalArgumentException e) {
                throw new IllegalArgumentException("Ungültige Grid-URL: " + l.gridUrl());
            }
            remote.setFileDetector(new LocalFileDetector());
            // Augmenter: BiDi (Konsole, Netzwerk) und CDP auch über das Grid
            return new Augmenter().augment(remote);
        }
        return switch (l.engine()) {
            case "chrome" -> new ChromeDriver((ChromeOptions) options);
            case "edge" -> new EdgeDriver((EdgeOptions) options);
            case "firefox" -> new FirefoxDriver((FirefoxOptions) options);
            case "safari" -> new SafariDriver((SafariOptions) options);
            default -> throw new IllegalArgumentException("Unbekannter Browser: " + l.engine());
        };
    }

    static Capabilities options(Launch l) {
        String engine = l.engine().toLowerCase(Locale.ROOT);
        AbstractDriverOptions<?> options = switch (engine) {
            case "chrome" -> chromium(new ChromeOptions(), l);
            case "edge" -> chromium(new EdgeOptions(), l);
            case "firefox" -> firefox(l);
            case "safari" -> new SafariOptions();
            default -> throw new IllegalArgumentException("Unbekannter Browser '" + l.engine() + "' – erlaubt: "
                    + String.join(", ", ENGINES));
        };
        if (l.attach().isBlank()) {
            options.setPageLoadStrategy(PageLoadStrategy.NORMAL);
            // Dialoge (alert/confirm) nicht automatisch wegklicken – browser_dialog entscheidet
            options.setUnhandledPromptBehaviour(UnexpectedAlertBehaviour.IGNORE);
            if (!engine.equals("safari")) {
                // WebDriver BiDi: Konsole und Netzwerk mitschneiden
                options.setCapability("webSocketUrl", true);
            }
        }
        if (l.pageLoadSeconds() > 0) {
            options.setPageLoadTimeout(java.time.Duration.ofSeconds(l.pageLoadSeconds()));
        }
        return options;
    }

    private static <T extends ChromiumOptions<T>> T chromium(T o, Launch l) {
        if (!l.binary().isBlank()) {
            o.setBinary(l.binary().trim());
        }
        if (!l.attach().isBlank()) {
            o.setExperimentalOption("debuggerAddress", l.attach().trim());
            return o;
        }
        if (l.headless()) {
            o.addArguments("--headless=new");
        }
        if (l.width() > 0 && l.height() > 0) {
            o.addArguments("--window-size=" + l.width() + "," + l.height());
        }
        if (!l.profile().isBlank()) {
            o.addArguments("--user-data-dir=" + l.profile().trim());
        }
        o.addArguments("--no-first-run", "--no-default-browser-check");
        o.addArguments(l.args());
        return o;
    }

    private static FirefoxOptions firefox(Launch l) {
        if (!l.attach().isBlank()) {
            throw new IllegalArgumentException("Anhängen an einen laufenden Browser geht nur mit Chrome und Edge "
                    + "(Chromium mit --remote-debugging-port).");
        }
        FirefoxOptions o = new FirefoxOptions();
        if (!l.binary().isBlank()) {
            o.setBinary(l.binary().trim());
        }
        if (l.headless()) {
            o.addArguments("-headless");
        }
        if (l.width() > 0 && l.height() > 0) {
            o.addArguments("-width", String.valueOf(l.width()), "-height", String.valueOf(l.height()));
        }
        if (!l.profile().isBlank()) {
            o.addArguments("-profile", l.profile().trim());
        }
        o.addArguments(l.args());
        return o;
    }

    /** Für Meldungen: was ein Start braucht, wenn er scheitert. */
    static String hint(String engine, String message) {
        String m = message == null ? "" : message;
        if (engine.equals("safari") && (m.contains("Allow Remote Automation") || m.contains("remote automation"))) {
            return " – der Nutzer muss einmalig in Safari → Einstellungen → Entwickler „Entfernte Automation erlauben“ "
                    + "aktivieren (Menü Entwickler ggf. erst unter Erweitert einblenden) oder `safaridriver --enable` im "
                    + "Terminal ausführen.";
        }
        if (m.contains("Unable to obtain") || m.contains("Selenium Manager")) {
            return " – Selenium Manager konnte Treiber oder Browser nicht beschaffen (Netzwerk/Proxy?). Abhilfe: Browser "
                    + "installieren oder in der DevTools-App unter Module → Browser ein Programm eintragen.";
        }
        if (m.contains("user data directory is already in use") || m.contains("DevToolsActivePort")) {
            return " – das Profilverzeichnis wird schon von einem laufenden Browser verwendet; anderes Profil wählen oder "
                    + "den Browser schließen.";
        }
        if (m.contains("cannot connect to chrome") || m.contains("debuggerAddress")) {
            return " – an der Adresse lauscht kein Chromium-Browser; Browser mit --remote-debugging-port=9222 und eigenem "
                    + "--user-data-dir starten (Chrome erlaubt Remote-Debugging nicht mit dem Standardprofil).";
        }
        return "";
    }

    /** Browser und Version für Meldungen. */
    static String describe(Capabilities c) {
        return c.getBrowserName() + " " + c.getBrowserVersion();
    }
}
