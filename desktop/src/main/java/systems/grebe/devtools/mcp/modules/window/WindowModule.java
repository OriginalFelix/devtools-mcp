package systems.grebe.devtools.mcp.modules.window;

import java.awt.GraphicsEnvironment;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.ConnectionTestResult;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolBeans;
import systems.grebe.devtools.mcp.core.ToolModule;
import systems.grebe.devtools.mcp.core.ToolScope;
import systems.grebe.devtools.mcp.modules.window.cursor.CursorProvider;
import systems.grebe.devtools.mcp.modules.window.platform.NativeWindow;
import systems.grebe.devtools.mcp.modules.window.platform.ProgramLauncher;
import systems.grebe.devtools.mcp.modules.window.platform.ScreenMapper;
import systems.grebe.devtools.mcp.modules.window.platform.WindowSystem;

/**
 * Fenstersteuerung: Die KI bindet einen Prozess und bedient dessen Fenster – Screenshots, Klicks, Tastatur. Fenster
 * und Prozess/Thread kommen per FFM aus der nativen Fenster-API (Windows, macOS, X11), Eingaben von
 * {@link java.awt.Robot}.
 */
@Component
public class WindowModule implements ToolModule {

    static final String INCLUDE = "includeProcesses";
    static final String EXCLUDE = "excludeProcesses";
    /** Global freigegebene Ordner (Modul „Freigaben“), von der ToolRegistry eingefügt – nicht im Formular. */
    static final String SHARED = "sharedDirectories";
    private static final Pattern NAMED = Pattern.compile("([A-Za-z0-9._@ -]+)=(.+)");
    static final String ALLOW_INPUT = "allowInput";
    static final String ALLOW_KEYBOARD = "allowKeyboard";
    static final String ALLOW_LAUNCH = "allowLaunch";
    static final String ALLOW_SIBLINGS = "allowSiblings";
    static final String ABORT_ON_MOUSE = "abortOnMouseMove";
    static final String COOLDOWN = "cooldownSeconds";
    static final String MAX_IMAGE = "maxImageSize";
    static final String POINTER_MODE = "pointerMode";
    /** Eigener, zweiter Zeiger: die Maus des Nutzers bleibt frei. */
    static final String POINTER_OWN = "eigener-zeiger";
    /** Die echte Maus (java.awt.Robot). */
    static final String POINTER_MOUSE = "maus";
    static final String KEYBOARD_MODE = "keyboardMode";
    /** Eigene Tastatur des Zeigers: die Tastatur des Nutzers bleibt frei. */
    static final String KEYBOARD_OWN = "eigene-tastatur";
    /** Die echte Tastatur (java.awt.Robot). */
    static final String KEYBOARD_REAL = "tastatur";

    /** Maus und Tastatur gibt es einmal – geteilt von allen Benutzern und Clients. */
    private final UserPresenceMonitor presence = new UserPresenceMonitor(System::currentTimeMillis);
    private final ReentrantLock inputLock = new ReentrantLock();
    /** Zustand je KI (MCP-Session): Bindung, Farbe, Rahmen und eigener Zeiger. */
    private final WindowSessions sessions = WindowSessions.start(new AiColors());
    private InputDevice robot;
    /** Per window_launch gestartete Programme – Kindprozesse dieser App, die trotzdem gesteuert werden dürfen. */
    private final java.util.Set<Long> launched = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Wächter nach dem Start von Programmen (FocusGuard) – kurzlebige Hintergrund-Threads. */
    private final java.util.concurrent.ExecutorService background = java.util.concurrent.Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "window-launch-guard");
        t.setDaemon(true);
        return t;
    });

    @Override
    public String id() {
        return "window";
    }

    @Override
    public String displayName() {
        return "Fenstersteuerung";
    }

    @Override
    public String description() {
        return "Fenster anderer Anwendungen sehen und bedienen: Prozess binden, Screenshots, je Schalter Klicken, "
                + "Tippen, Tastenkombinationen, Scrollen und Ziehen (Windows, macOS, Linux/X11).";
    }

    @Override
    public String instructions() {
        return """
                Wenn window_* angeboten wird, bedienst du darüber Fenster anderer Anwendungen auf dem Rechner des \
                Nutzers – NICHT per PowerShell, AppleScript, xdotool oder Skripten.

                Ablauf:
                1. `window_list` → Prozess finden, `window_bind` (pid oder process) → nur dessen Fenster sind ansprechbar. \
                Programme selbst starten nur mit `window_launch` (startet im Hintergrund und bindet) – nie per Shell, \
                sonst bekommt das Fenster den Fokus und die Eingaben des Nutzers landen darin.
                2. `window_screenshot` → Bild ansehen. Koordinaten für `window_click`, `window_scroll` und \
                `window_drag` sind Pixel dieses Bildes.
                3. Eingabe (`window_click`, `window_scroll`, `window_drag`; mit Tastatur `window_type`, `window_key`), \
                danach erneut `window_screenshot` und das Ergebnis prüfen, bevor es weitergeht. Neue Dialoge findet \
                `window_windows`.
                4. Tippen geht in das Element, das du zuletzt angeklickt hast – vor dem Tippen also in das Eingabefeld \
                klicken.

                Regeln:
                - Fehlen die Maus-Tools, ist „Eingaben erlauben“ in der DevTools-App aus; fehlen `window_type` und \
                `window_key`, ist „Tastatur erlauben“ aus – den Nutzer fragen, statt per Klick auf eine \
                Bildschirmtastatur auszuweichen.
                - Meldet ein Tool, dass der Nutzer die Maus bewegt hat, NICHT sofort wiederholen: der Nutzer arbeitet \
                gerade selbst. Fragen, ob du weitermachen sollst.
                - Fokusverlust bricht ab: erst nachsehen (Screenshot), was sich geöffnet hat.
                - Fertig? Immer `window_unbind` aufrufen – das blendet den Zeiger der KI, Rahmen und Hinweis aus.
                - Keine Passwörter, Zugangsdaten oder Zahlungsdaten eintippen; nichts Unumkehrbares (Löschen, Senden, \
                Bezahlen) ohne ausdrückliche Bestätigung des Nutzers.""";
    }

    @Override
    public String briefInstructions() {
        return "Fenster anderer Programme nur über window_*, nie per Shell/PowerShell: window_list → window_bind "
                + "(Programme starten mit window_launch), nach jeder Eingabe window_screenshot prüfen, am Ende "
                + "window_unbind. Keine Passwörter eintippen.";
    }

    @Override
    public int order() {
        return 150;
    }

    @Override
    public Set<String> sharedDirectoryFields() {
        return Set.of(SHARED);
    }

    /** Freigegebene Ordner; Einträge {@code name=pfad} werden auf den Pfad reduziert, ungültige übersprungen. */
    static List<Path> sharedDirectories(ModuleConfig config) {
        List<Path> out = new ArrayList<>();
        for (String line : config.getList(SHARED)) {
            Matcher named = NAMED.matcher(line);
            String path = named.matches() && !absolute(line) ? named.group(2) : line;
            try {
                out.add(Path.of(path.strip()));
            } catch (InvalidPathException e) {
                // ungültiger Eintrag: ignorieren
            }
        }
        return out;
    }

    /**
     * Warnt, wenn Programme in global freigegebenen Ordnern unter „Prozesse ausschließen“ fallen – die Freigabe hebt
     * den Ausschluss auf. Sucht dafür die Ordner rekursiv ab (siehe {@link SharedProgramScan}).
     */
    @Override
    public List<String> saveWarnings(ModuleConfig config) {
        List<Path> shared = sharedDirectories(config);
        Pattern exclude;
        try {
            exclude = pattern(config, EXCLUDE);
        } catch (IllegalStateException e) {
            return List.of(); // ungültiger Ausdruck: meldet die Prüfung beim Aufruf
        }
        if (shared.isEmpty() || exclude == null) {
            return List.of();
        }
        SharedProgramScan.Result scan = SharedProgramScan.scan(shared);
        ProcessFilter plain = new ProcessFilter(null, null, -1);
        List<Path> overridden = new ArrayList<>();
        int locked = 0;
        for (Path program : scan.programs()) {
            ProcessFilter.Info info = ProcessFilter.Info.ofExecutable(program);
            if (plain.rejection(info).isPresent()) {
                locked++;
            } else if (exclude.matcher(info.name() + " " + info.commandLine()).find()) {
                overridden.add(program);
            }
        }
        if (overridden.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        out.add(overridden.size() == 1
                ? "1 Programm fällt unter „Prozesse ausschließen“, liegt aber in einem global freigegebenen Ordner "
                        + "(Freigaben) und darf deshalb trotzdem gesteuert werden:"
                : overridden.size() + " Programme fallen unter „Prozesse ausschließen“, liegen aber in global "
                        + "freigegebenen Ordnern (Freigaben) und dürfen deshalb trotzdem gesteuert werden:");
        out.add("");
        out.addAll(SharedProgramScan.describe(shared, overridden));
        if (locked > 0 || !scan.complete()) {
            out.add("");
        }
        if (locked > 0) {
            out.add(locked + " Programm(e) in den freigegebenen Ordnern bleiben gesperrt (Anmeldung, "
                    + "Berechtigungsdialog oder Passwortmanager).");
        }
        if (!scan.complete()) {
            out.add("Die Suche wurde nach " + SharedProgramScan.LIMIT + " Einträgen abgebrochen – weitere Programme "
                    + "können betroffen sein.");
        }
        return out;
    }

    private static boolean absolute(String path) {
        try {
            return Path.of(path.strip()).isAbsolute();
        } catch (InvalidPathException e) {
            return false;
        }
    }

    @Override
    public List<ConfigField> configSchema() {
        return List.of(
                ConfigField.of(INCLUDE, "Nur diese Prozesse", FieldType.PROCESS_PATTERN)
                        .withHelp("Regulärer Ausdruck auf Prozessname und Kommandozeile; „Fenster wählen…“ fügt ein "
                                + "Programm hinzu. Leer = alle Prozesse des Benutzers mit Fenstern. Programme aus "
                                + "Ordnern unter „Freigaben“ sind immer freigegeben."),
                ConfigField.of(EXCLUDE, "Prozesse ausschließen", FieldType.PROCESS_PATTERN)
                        .withHelp("Regulärer Ausdruck; „Fenster wählen…“ fügt ein Programm hinzu. Gilt nicht für "
                                + "Programme aus Ordnern unter „Freigaben“. Immer ausgeschlossen: diese App, "
                                + "Anmelde-/Berechtigungsdialoge des Systems und gängige Passwortmanager."),
                ConfigField.of(ALLOW_INPUT, "Eingaben erlauben (Klicken, Scrollen, Ziehen)", FieldType.BOOLEAN)
                        .withDefault("false").withHelp("Ohne diesen Schalter kann die KI Fenster nur ansehen. Mit "
                                + "eigenem Zeiger bleibt deine Maus frei, im Modus „maus“ bewegt die KI die echte Maus."),
                ConfigField.of(ALLOW_KEYBOARD, "Tastatur erlauben (Tippen, Tastenkombinationen)", FieldType.BOOLEAN)
                        .withDefault("false").withHelp("Nur zusammen mit „Eingaben erlauben“. Ob die KI dafür eine "
                                + "eigene Tastatur hat oder deine nutzt, stellt „Tastatur der KI“ ein."),
                ConfigField.of(ALLOW_LAUNCH, "Programme starten erlauben", FieldType.BOOLEAN)
                        .withDefault("false").withHelp("Die KI darf Programme starten, die zu „Nur diese Prozesse“ "
                                + "passen (window_launch) – im Hintergrund: Das Fenster bekommt keinen Fokus, deine "
                                + "Eingaben landen erst dort, wenn du es anklickst. Unter Windows wird ein Programm, das "
                                + "sich beim Start trotzdem nach vorn holt, sofort wieder hinter dein Fenster gesetzt."),
                ConfigField.of(ALLOW_SIBLINGS, "Geschwisterprozesse erlauben", FieldType.BOOLEAN)
                        .withDefault("false").withHelp("Neben dem gebundenen Prozess und seinen Kindprozessen auch "
                                + "Fenster der anderen Kinder seines Elternprozesses – nur mit Fenster-ID, ohne ID "
                                + "bleibt es beim gebundenen Prozess. Der Elternprozess selbst ist nie erreichbar. Ist "
                                + "er ein Shell- oder Systemprozess (Explorer, Finder/launchd, systemd), gibt es keine "
                                + "Geschwister – sonst wären das alle anderen Anwendungen des Benutzers."),
                ConfigField.of(POINTER_MODE, "Maus der KI", FieldType.ENUM).withDefault(POINTER_OWN)
                        .withOptions(POINTER_OWN, POINTER_MOUSE).withHelp("eigener-zeiger: die KI klickt mit einem "
                                + "zweiten Zeiger, deine Maus bleibt frei und das Fenster wird nicht nach vorn geholt "
                                + "(Windows: Nachrichten an das Fenster, bei UWP-Apps UI Automation bzw. Touch zum "
                                + "Ziehen; macOS: Ereignisse an den Prozess; X11: zweiter Master-Zeiger). maus: die KI "
                                + "bewegt deine echte Maus."),
                ConfigField.of(KEYBOARD_MODE, "Tastatur der KI", FieldType.ENUM).withDefault(KEYBOARD_OWN)
                        .withOptions(KEYBOARD_OWN, KEYBOARD_REAL).withHelp("eigene-tastatur: Text und Tasten gehen an "
                                + "das Element, das der Zeiger der KI zuletzt angeklickt hat – deine Tastatur bleibt "
                                + "frei, das Fenster wird nicht nach vorn geholt (Windows: Nachrichten an das Element; "
                                + "macOS: Ereignisse an den Prozess; X11: eigene Master-Tastatur). tastatur: die KI "
                                + "tippt auf deiner echten Tastatur und holt das Fenster dafür nach vorn. Nur wirksam, "
                                + "wenn „Tastatur erlauben“ an ist."),
                ConfigField.of(ABORT_ON_MOUSE, "Abbrechen, wenn ich die Maus bewege", FieldType.BOOLEAN)
                        .withDefault("true").withHelp("Not-Aus: Mausbewegung während oder zwischen Eingaben der KI "
                                + "bricht ab und sperrt Eingaben für die Abkühlzeit."),
                ConfigField.of(COOLDOWN, "Abkühlzeit nach Eingriff (Sekunden)", FieldType.INT).withDefault("10"),
                ConfigField.of(MAX_IMAGE, "Max. Bildgröße Screenshot (Pixel, längste Seite)", FieldType.INT)
                        .withDefault("1280").withHelp("Größere Fenster werden verkleinert – spart Kontext des LLM."));
    }

    @Override
    public List<ToolCallback> createTools(ModuleConfig config) {
        return createTools(config, ToolScope.LOCAL);
    }

    @Override
    public List<ToolCallback> createTools(ModuleConfig config, ToolScope scope) {
        WindowSystem ws = WindowSystem.current();
        ws.unsupportedReason().ifPresent(r -> {
            throw new IllegalStateException(r);
        });
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException("Die App läuft ohne Bildschirm (headless) – Fenstersteuerung nicht verfügbar.");
        }
        WindowSupport support = support(config, ws);
        List<Object> beans = new ArrayList<>(List.of(new WindowReadTools(support)));
        if (config.getBoolean(ALLOW_LAUNCH)) {
            beans.add(new WindowLaunchTools(support, ProgramLauncher.current(), background, WindowModule::sleep));
        }
        if (config.getBoolean(ALLOW_INPUT)) {
            boolean keyboard = config.getBoolean(ALLOW_KEYBOARD);
            beans.add(new WindowInputTools(support, keyboard));
            if (keyboard) {
                beans.add(new WindowKeyboardTools(support));
            }
        }
        return ToolBeans.callbacks(beans.toArray());
    }

    private WindowSupport support(ModuleConfig config, WindowSystem ws) {
        WindowSupport.Settings settings = new WindowSupport.Settings(Math.max(200, config.getInt(MAX_IMAGE, 1280)),
                config.getBoolean(ABORT_ON_MOUSE), Duration.ofSeconds(Math.max(0, config.getInt(COOLDOWN, 10))),
                config.getBoolean(ALLOW_SIBLINGS));
        ProcessFilter filter = new ProcessFilter(pattern(config, INCLUDE), pattern(config, EXCLUDE),
                ProcessHandle.current().pid(), launched, sharedDirectories(config));
        boolean ownPointer = !POINTER_MOUSE.equals(config.getString(POINTER_MODE, POINTER_OWN));
        boolean ownKeyboard = !KEYBOARD_REAL.equals(config.getString(KEYBOARD_MODE, KEYBOARD_OWN));
        String mode = ownPointer ? " mit eigenem Zeiger"
                : settings.abortOnMouseMove() ? " – Maus bewegen bricht ab" : "";
        boolean mac = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
        return new WindowSupport(ws, filter, sessions::current, () -> device(ownPointer, ownKeyboard, mode), presence,
                inputLock, settings, WindowModule::sleep, mac);
    }

    /**
     * Das Eingabegerät der KI, die gerade aufruft – je KI und Kombination aus eigener Maus/Tastatur einmal erzeugt, mit
     * Rahmen, Hinweis und eigenem Zeiger in ihrer Farbe. Robot (echte Maus und Tastatur, Bildschirm, Zwischenablage)
     * teilen sich alle.
     *
     * @param mode Ergänzung des Hinweises, z.B. „ mit eigenem Zeiger“
     */
    private synchronized InputDevice device(boolean ownPointer, boolean ownKeyboard, String mode) {
        if (robot == null) {
            robot = new RobotInputDevice();
        }
        WindowSession s = sessions.current();
        ScreenMapper.Mode screen = screenMode();
        ControlOverlay overlay = s.resource("overlay",
                () -> new ControlOverlay(() -> CursorProvider.current().controller(), screen, s.color(),
                        WindowSystem.current()));
        overlay.hint(s.client() + " steuert dieses Fenster" + mode);
        return s.resource("device/" + ownPointer + "/" + ownKeyboard, () -> {
            WindowSystem ws = WindowSystem.current();
            if (!ownPointer && !ownKeyboard) {
                return new OverlayInputDevice(robot, overlay, ws, true);
            }
            // mit eigener Maus ist deren Zeiger selbst die Anzeige; sonst zeichnet die Anzeige den KI-Zeiger
            return new OverlayInputDevice(new VirtualCursorInputDevice(robot, () -> CursorProvider.current().controller(),
                    p -> ScreenMapper.current(screen).toNative(p), ws, ownPointer, ownKeyboard,
                    VirtualCursorInputDevice.IDLE, s.color()), overlay, ws, !ownPointer);
        });
    }

    /** Wie native Zeigerkoordinaten aus Java-Bildschirmkoordinaten entstehen (siehe {@link ScreenMapper}). */
    private static ScreenMapper.Mode screenMode() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return ScreenMapper.Mode.ANCHORED;
        }
        return os.contains("mac") ? ScreenMapper.Mode.IDENTITY : ScreenMapper.Mode.SCALED;
    }

    private static void sleep(int millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Unterbrochen", e);
        }
    }

    /** Leeres Feld = kein Filter; ungültige Ausdrücke werden beim Aufruf gemeldet statt still ignoriert. */
    private static Pattern pattern(ModuleConfig config, String key) {
        String v = config.getString(key, "").strip();
        if (v.isEmpty()) {
            return null;
        }
        try {
            return Pattern.compile(v, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            throw new IllegalStateException("Ungültiger regulärer Ausdruck in „" + key + "“: " + e.getDescription());
        }
    }

    @Override
    public ConnectionTestResult testConnection(ModuleConfig config) {
        WindowSystem ws = WindowSystem.current();
        if (ws.unsupportedReason().isPresent()) {
            return ConnectionTestResult.failed(ws.unsupportedReason().get());
        }
        if (GraphicsEnvironment.isHeadless()) {
            return ConnectionTestResult.failed("Die App läuft ohne Bildschirm (headless).");
        }
        ProcessFilter filter;
        try {
            filter = new ProcessFilter(pattern(config, INCLUDE), pattern(config, EXCLUDE), ProcessHandle.current().pid(),
                    launched, sharedDirectories(config));
        } catch (IllegalStateException e) {
            return ConnectionTestResult.failed(e.getMessage());
        }
        List<NativeWindow> all = ws.windows();
        long allowed = all.stream().map(NativeWindow::pid).distinct().filter(filter::allowed).count();
        StringBuilder sb = new StringBuilder(ws.name()).append(": ").append(all.size()).append(" sichtbare Fenster, ")
                .append(allowed).append(" freigegebene Prozesse mit Fenstern.");
        ws.warnings().forEach(w -> sb.append('\n').append("Hinweis: ").append(w));
        return ws.warnings().isEmpty() ? ConnectionTestResult.ok(sb.toString()) : ConnectionTestResult.failed(sb.toString());
    }
}
