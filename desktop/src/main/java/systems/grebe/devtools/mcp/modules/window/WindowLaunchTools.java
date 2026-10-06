package systems.grebe.devtools.mcp.modules.window;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.Executor;
import java.util.function.LongPredicate;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.ToolHints;
import systems.grebe.devtools.mcp.modules.window.platform.NativeWindow;
import systems.grebe.devtools.mcp.modules.window.platform.ProgramLauncher;

/**
 * Startet freigegebene Programme im Hintergrund und bindet sie (Schalter „Programme starten erlauben“). Das Fenster
 * bekommt keinen Fokus: Eingaben des Nutzers landen erst dort, wenn er es selbst anklickt – die KI bedient es mit
 * eigenem Zeiger und eigener Tastatur ohnehin im Hintergrund.
 */
@ToolHints(readOnly = false, destructive = false, openWorld = false)
public class WindowLaunchTools {

    static final int WINDOW_WAIT_POLLS = 200;
    private static final int POLL_MILLIS = 100;

    private final WindowSupport support;
    private final ProgramLauncher launcher;
    private final Executor background;
    private final WindowSupport.Sleeper sleeper;

    /** @param background führt den {@link FocusGuard} nach dem Start aus */
    WindowLaunchTools(WindowSupport support, ProgramLauncher launcher, Executor background,
                      WindowSupport.Sleeper sleeper) {
        this.support = support;
        this.launcher = launcher;
        this.background = background;
        this.sleeper = sleeper;
    }

    @Tool(name = "launch", description = "Startet ein Programm im Hintergrund (ohne ihm den Fokus zu geben) und bindet "
            + "es – danach window_screenshot. Nur Programme, die zu „Nur diese Prozesse“ passen. Unter Windows reicht "
            + "oft der Name (\"winword\", \"notepad\", \"charmap\"), sonst der volle Pfad." + ShellHints.WINDOW)
    public String launch(
            @ToolParam(description = "Programm: Name oder Pfad, z.B. \"winword\" oder \"C:\\\\Tools\\\\app.exe\"") String program,
            @ToolParam(required = false, description = "Argumente, z.B. [\"/w\"] für ein leeres Word-Dokument") List<String> arguments) {
        if (program == null || program.isBlank()) {
            throw new IllegalArgumentException("Kein Programm angegeben.");
        }
        List<String> args = arguments == null ? List.of() : List.copyOf(arguments);
        String name = ProcessFilter.name(program.strip());
        String commandLine = program.strip() + (args.isEmpty() ? "" : " " + String.join(" ", args));
        // nur ein absoluter Pfad kann in einem freigegebenen Ordner liegen ("winword" nicht)
        String executable = absolutePath(program.strip());
        support.filter().rejection(new ProcessFilter.Info(0, name, commandLine, executable)).ifPresent(reason -> {
            throw new IllegalArgumentException(reason + " Starten nicht erlaubt.");
        });

        OptionalLong userWindow = support.windows().foreground();
        ProgramLauncher.Launched launched = launcher.launch(program.strip(), args);
        if (launched.pid() == 0) {
            throw new IllegalStateException("„" + program + "“ wurde gestartet, der Prozess ließ sich aber nicht "
                    + "ermitteln (z.B. an eine laufende Instanz weitergereicht) – mit window_list nachsehen und binden.");
        }
        ProcessHandle process = ProcessHandle.of(launched.pid()).orElseThrow(() -> new IllegalStateException(
                "„" + program + "“ (PID " + launched.pid() + ") hat sich sofort wieder beendet."));
        support.filter().launched(process.pid()); // Kind dieser App, aber steuerbar
        LongPredicate ours = pid -> pid == process.pid()
                || ProcessHandle.of(pid).flatMap(ProcessHandle::parent).map(p -> p.pid() == process.pid()).orElse(false);
        if (launcher.needsFocusGuard()) {
            FocusGuard guard = new FocusGuard(support.windows(), ours, launcher::mouseButtonDown,
                    System::currentTimeMillis, sleeper);
            background.execute(() -> guard.guard(userWindow, FocusGuard.DEFAULT_DURATION));
        }

        Optional<NativeWindow> first = Optional.empty();
        for (int i = 0; i < WINDOW_WAIT_POLLS && first.isEmpty(); i++) {
            first = support.windows().windows().stream().filter(w -> ours.test(w.pid())).findFirst();
            if (first.isEmpty()) {
                sleeper.sleep(POLL_MILLIS);
            }
        }
        WindowSession.Binding binding = new WindowSession.Binding(process, name, true);
        support.session().bind(binding, support.settings().allowSiblings());
        return "Gestartet im Hintergrund und gebunden: " + binding.describe() + "\n"
                + first.map(w -> "Fenster: " + WindowReadTools.describe(w))
                .orElse("Noch kein Fenster sichtbar – gleich mit window_windows nachsehen.")
                + "\nDas Fenster hat keinen Fokus; Eingaben des Nutzers landen erst dort, wenn er es anklickt.";
    }

    private static String absolutePath(String program) {
        try {
            return Path.of(program).isAbsolute() ? program : null;
        } catch (InvalidPathException e) {
            return null;
        }
    }
}
