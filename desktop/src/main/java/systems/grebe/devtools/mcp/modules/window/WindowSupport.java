package systems.grebe.devtools.mcp.modules.window;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.function.Function;
import java.util.function.Supplier;

import systems.grebe.devtools.mcp.modules.window.platform.NativeWindow;
import systems.grebe.devtools.mcp.modules.window.platform.WindowSystem;

/**
 * Gemeinsame Logik der window_*-Tools: Fenster des gebundenen Prozesses finden, in den Vordergrund holen und
 * Eingaben über {@link InputGuard} ausführen. Alle Robot-Aktionen laufen unter einem gemeinsamen Lock, damit sich
 * zwei Clients nicht gegenseitig dazwischen klicken.
 */
final class WindowSupport {

    /** Einstellungen aus der Modul-Konfiguration. */
    record Settings(int maxImageSize, boolean abortOnMouseMove, Duration cooldown, boolean allowSiblings) {
    }

    /** Wartet (Tests ersetzen es, um nicht zu schlafen). */
    interface Sleeper {
        void sleep(int millis);
    }

    static final int FOREGROUND_TIMEOUT_MILLIS = 500;
    private static final int POLL_MILLIS = 25;

    private final WindowSystem windows;
    private final ProcessFilter filter;
    private final Supplier<WindowSession> session;
    private final Supplier<InputDevice> device;
    private final UserPresenceMonitor presence;
    private final Lock lock;
    private final Settings settings;
    private final Sleeper sleeper;
    private final boolean mac;

    /** @param session Zustand der KI, die gerade aufruft (je MCP-Session, siehe {@link WindowSessions}) */
    WindowSupport(WindowSystem windows, ProcessFilter filter, Supplier<WindowSession> session, Supplier<InputDevice> device,
                  UserPresenceMonitor presence, Lock lock, Settings settings, Sleeper sleeper, boolean mac) {
        this.windows = windows;
        this.filter = filter;
        this.session = session;
        this.device = device;
        this.presence = presence;
        this.lock = lock;
        this.settings = settings;
        this.sleeper = sleeper;
        this.mac = mac;
    }

    WindowSystem windows() {
        return windows;
    }

    ProcessFilter filter() {
        return filter;
    }

    WindowSession session() {
        return session.get();
    }

    Settings settings() {
        return settings;
    }

    boolean mac() {
        return mac;
    }

    InputDevice device() {
        return device.get();
    }

    /**
     * Prozesse, auf deren Fenster zugegriffen werden darf: der gebundene, seine Nachfahren und – nur wenn erlaubt –
     * seine Geschwister. Nie der Elternprozess.
     */
    Set<Long> boundPids() {
        return session().require().pids(settings.allowSiblings());
    }

    /** Sichtbare Fenster der erlaubten Prozesse, vorderstes zuerst. */
    List<NativeWindow> boundWindows() {
        Set<Long> pids = boundPids();
        return windows.windows().stream().filter(w -> pids.contains(w.pid()) && filter.allowed(w.pid())).toList();
    }

    /**
     * Das angesprochene Fenster: mit ID genau dieses (muss zum gebundenen Prozess gehören), ohne ID das
     * Vordergrundfenster des Prozesses, sonst sein größtes.
     */
    NativeWindow resolve(String windowId) {
        List<NativeWindow> own = boundWindows();
        if (windowId != null && !windowId.isBlank()) {
            long id;
            try {
                id = NativeWindow.parseId(windowId);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Ungültige Fenster-ID \"" + windowId + "\" – IDs wie 0x1A2B aus "
                        + "window_windows verwenden.");
            }
            return own.stream().filter(w -> w.id() == id).findFirst().orElseThrow(() -> new IllegalArgumentException(
                    "Fenster " + windowId + " gehört nicht zum gebundenen Prozess oder ist nicht mehr sichtbar. "
                            + "window_windows zeigt die aktuellen Fenster."));
        }
        if (own.isEmpty()) {
            throw new IllegalStateException("Der gebundene Prozess " + session().require().describe()
                    + " hat kein sichtbares Fenster.");
        }
        OptionalLong fg = windows.foreground();
        if (fg.isPresent()) {
            Optional<NativeWindow> front = own.stream().filter(w -> w.id() == fg.getAsLong()).findFirst();
            if (front.isPresent()) {
                return front.get();
            }
        }
        return own.stream().filter(w -> !w.minimized())
                .max(Comparator.comparingLong(w -> (long) w.bounds().width * w.bounds().height))
                .orElse(own.getFirst());
    }

    /** Liest das Fenster neu (Position kann sich geändert haben); wirft, wenn es weg ist oder nicht mehr dazugehört. */
    NativeWindow refresh(NativeWindow w) {
        Set<Long> pids = boundPids();
        return windows.window(w.id()).filter(n -> pids.contains(n.pid()))
                .orElseThrow(() -> new IllegalStateException("Fenster " + w.hexId() + " („" + w.title()
                        + "“) ist geschlossen oder nicht mehr sichtbar."));
    }

    /**
     * Holt das Fenster nach vorn und wartet bis {@value #FOREGROUND_TIMEOUT_MILLIS} ms darauf.
     *
     * @return ob es im Vordergrund liegt
     */
    boolean bringToFront(NativeWindow w) {
        if (isForeground(w)) {
            return true;
        }
        windows.activate(w.id());
        for (int waited = 0; waited < FOREGROUND_TIMEOUT_MILLIS; waited += POLL_MILLIS) {
            if (isForeground(w)) {
                sleeper.sleep(POLL_MILLIS * 2); // Fenster zeichnet sich nach dem Aktivieren oft noch neu
                return true;
            }
            sleeper.sleep(POLL_MILLIS);
        }
        return isForeground(w);
    }

    boolean isForeground(NativeWindow w) {
        OptionalLong fg = windows.foreground();
        return fg.isPresent() && fg.getAsLong() == w.id();
    }

    /** Führt {@code body} exklusiv aus (ein Robot für alle Benutzer und Clients). */
    <T> T exclusive(Supplier<T> body) {
        lock.lock();
        try {
            return body.get();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Eingabe in ein Fenster des gebundenen Prozesses: Not-Aus prüfen, Fenster nach vorn holen (sonst Abbruch), dann
     * {@code action} mit einem {@link InputGuard}, der vor jedem Schritt erneut prüft.
     */
    <T> T input(String windowId, Function<InputGuard, T> action) {
        windows.requireInputPermission();
        return exclusive(() -> {
            InputDevice d = device.get();
            try {
                presence.check(d.pointer(), settings.cooldown(), settings.abortOnMouseMove());
                NativeWindow w = resolve(windowId);
                if (!bringToFront(w)) {
                    throw new IllegalStateException("Fenster " + w.hexId() + " („" + w.title() + "“) ließ sich nicht in "
                            + "den Vordergrund holen – Eingabe abgebrochen, damit nichts in einem fremden Fenster landet.");
                }
                NativeWindow target = refresh(w);
                d.target(target, boundPids());
                return action.apply(new InputGuard(this, d, presence, target, session().scale(w.id())));
            } catch (UserPresenceMonitor.UserInterventionException e) {
                d.release(); // der Nutzer hat übernommen – Anzeige sofort weg
                throw e;
            }
        });
    }

    /**
     * Mauseingabe: mit eigenem Zeiger ({@link InputDevice#independentPointer()}) ohne das Fenster nach vorn zu holen –
     * der Nutzer arbeitet ungestört weiter. Ob das Fenster an der Stelle verdeckt ist, prüft das Gerät beim Klicken.
     * Mit {@code keyboard} (z.B. gehaltene Strg-Taste) oder ohne eigenen Zeiger wie {@link #input}.
     */
    <T> T pointerInput(String windowId, boolean keyboard, Function<InputGuard, T> action) {
        InputDevice dev = device.get();
        if (!dev.independentPointer() || keyboard && !dev.independentKeyboard()) {
            return input(windowId, action);
        }
        return independent(windowId, action);
    }

    /** Tastatur-Eingabe: mit eigener Tastatur ohne das Fenster nach vorn zu holen, sonst wie {@link #input}. */
    <T> T keyboardInput(String windowId, Function<InputGuard, T> action) {
        return device.get().independentKeyboard() ? independent(windowId, action) : input(windowId, action);
    }

    /** Eingabe mit eigenem Zeiger bzw. eigener Tastatur: kein Vordergrund nötig, das Fenster darf nicht minimiert sein. */
    private <T> T independent(String windowId, Function<InputGuard, T> action) {
        windows.requireInputPermission();
        return exclusive(() -> {
            InputDevice d = device.get();
            try {
                presence.check(d.pointer(), settings.cooldown(), settings.abortOnMouseMove());
                NativeWindow w = resolve(windowId);
                if (w.minimized()) {
                    throw new IllegalStateException("Fenster " + w.hexId() + " („" + w.title() + "“) ist minimiert – "
                            + "erst mit window_screenshot wiederherstellen.");
                }
                NativeWindow target = refresh(w);
                d.target(target, boundPids());
                return action.apply(new InputGuard(this, d, presence, target, session().scale(w.id()), false));
            } catch (UserPresenceMonitor.UserInterventionException e) {
                d.release();
                throw e;
            }
        });
    }

    /** Die KI gibt die Kontrolle ab (Bindung aufgehoben): Anzeige des Geräts ausblenden. */
    void release() {
        try {
            device.get().release();
        } catch (RuntimeException e) {
            // ohne Gerät (headless) gibt es nichts auszublenden
        }
    }
}
