package systems.grebe.devtools.mcp.modules.browser;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Predicate;

import org.openqa.selenium.WebDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ein gesteuerter Browser: WebDriver samt Puffern für Konsole und Netzwerk (WebDriver BiDi) und dem Maßstab des
 * letzten Screenshots (für Klicks auf Bildkoordinaten). Alle Zugriffe auf den Treiber laufen unter dem Monitor der
 * Sitzung – WebDriver ist nicht thread-sicher.
 */
final class BrowserSession {

    private static final Logger LOG = LoggerFactory.getLogger(BrowserSession.class);
    private static final int MAX_ENTRIES = 1000;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    /** Eine Zeile aus Konsole oder Netzwerk. */
    record Entry(long timestamp, String level, String text) {
        String format() {
            LocalTime t = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalTime();
            return TIME.format(t) + " " + level + " " + text;
        }
    }

    final String id;
    final String browser;
    final boolean attached;
    /**
     * Safari: Eingaben kommen nur an, solange das Fenster den Fokus hat, und ein Klick kehrt zurück, bevor eine
     * ausgelöste Navigation beginnt.
     */
    final boolean safari;
    final WebDriver driver;
    private final Deque<Entry> console = new ArrayDeque<>();
    private final Deque<Entry> network = new ArrayDeque<>();
    private final List<AutoCloseable> listeners = new ArrayList<>();
    /** Grund, warum Konsole/Netzwerk nicht mitgeschnitten werden, oder {@code null}. */
    volatile String bidiUnavailable;
    /** CSS-Pixel je Bildpixel des letzten Viewport-Screenshots; 0 = noch keiner. */
    volatile double cssPerImagePixel;
    /** Aktionen im Browser anzeigen ({@link Overlay}); aus bei headless. */
    volatile boolean showActions;
    volatile int animationMillis = 400;
    volatile String overlayLabel = "";
    /** Position des angezeigten Zeigers (CSS-Pixel), -1 = noch keine. */
    volatile int cursorX = -1;
    volatile int cursorY = -1;
    /** Der Nutzer hat im Browser „Stopp“ gedrückt; Eingaben werden abgelehnt, bis er „Fortsetzen“ drückt. */
    volatile boolean stoppedByUser;
    /** Letzte freigegebene Adresse des aktiven Tabs – Rückfallziel, wenn ein Seitenwechsel gesperrt ist. */
    volatile String lastAllowedUrl;
    volatile long lastUsed = System.currentTimeMillis();

    BrowserSession(String id, String browser, boolean attached, boolean safari, WebDriver driver) {
        this.id = id;
        this.browser = browser;
        this.attached = attached;
        this.safari = safari;
        this.driver = driver;
    }

    void touch() {
        lastUsed = System.currentTimeMillis();
    }

    void addListener(AutoCloseable listener) {
        listeners.add(listener);
    }

    void console(Entry e) {
        add(console, e);
    }

    void network(Entry e) {
        add(network, e);
    }

    private static void add(Deque<Entry> buffer, Entry e) {
        synchronized (buffer) {
            buffer.addLast(e);
            while (buffer.size() > MAX_ENTRIES) {
                buffer.removeFirst();
            }
        }
    }

    List<Entry> console(Predicate<Entry> filter, boolean clear) {
        return read(console, filter, clear);
    }

    List<Entry> network(Predicate<Entry> filter, boolean clear) {
        return read(network, filter, clear);
    }

    private static List<Entry> read(Deque<Entry> buffer, Predicate<Entry> filter, boolean clear) {
        synchronized (buffer) {
            List<Entry> out = buffer.stream().filter(filter).toList();
            if (clear) {
                buffer.clear();
            }
            return out;
        }
    }

    /** Beendet den Browser; ein angehängter (fremder) Browser bleibt offen, nur die Steuerung endet. */
    void close() {
        for (AutoCloseable l : listeners) {
            try {
                l.close();
            } catch (Exception e) {
                LOG.debug("BiDi-Listener von {} nicht geschlossen", id, e);
            }
        }
        try {
            driver.quit();
        } catch (RuntimeException e) {
            LOG.debug("Browser {} nicht sauber beendet", id, e);
        }
    }
}
