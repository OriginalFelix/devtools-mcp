package systems.grebe.devtools.mcp.modules.window.platform;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.ShellAPI;
import com.sun.jna.platform.win32.Shell32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinNT;

/**
 * Startet ein Programm im Hintergrund – ohne es zu aktivieren, damit Eingaben des Nutzers nicht dort landen.
 *
 * <ul>
 *   <li>Windows: {@code ShellExecuteEx} mit {@code SW_SHOWNOACTIVATE} (findet auch Programme aus „App Paths“ wie
 *       {@code winword}); holt es sich trotzdem den Vordergrund, gibt ihn der Fokus-Wächter der Fenstersteuerung zurück.</li>
 *   <li>macOS: {@code open -g -a}.</li>
 *   <li>sonst: direkt gestartet – ob das Fenster den Fokus bekommt, entscheidet der Fenstermanager.</li>
 * </ul>
 */
public interface ProgramLauncher {

    /** Ein gestartetes Programm: PID (sofern bekannt) und Startzeit. */
    record Launched(long pid, Instant started) {
    }

    Launched launch(String program, List<String> arguments);

    /** Ob gerade eine Maustaste gedrückt ist – für den Fokus-Wächter der Fenstersteuerung; ohne Abfrage {@code false}. */
    default boolean mouseButtonDown() {
        return false;
    }

    /** Ob nach dem Start ein Fokus-Wächter der Fenstersteuerung nötig ist. */
    default boolean needsFocusGuard() {
        return false;
    }

    static ProgramLauncher current() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return new Windows();
        }
        if (os.contains("mac")) {
            return new Mac();
        }
        return new Plain();
    }

    /**
     * Wartet kurz auf den neuesten Prozess, dessen Programmname passt – für Starts, die keine PID liefern (Weitergabe an
     * eine laufende Instanz, {@code open}).
     */
    static Optional<Long> newestProcess(String program, Instant since) {
        String wanted = programName(program);
        for (int i = 0; i < 50; i++) {
            Optional<Long> pid = ProcessHandle.allProcesses()
                    .filter(p -> p.info().command().map(ProgramLauncher::programName)
                            .filter(wanted::equals).isPresent())
                    .filter(p -> p.info().startInstant().map(t -> !t.isBefore(since.minusSeconds(1))).orElse(false))
                    .max(Comparator.comparing(p -> p.info().startInstant().orElse(Instant.MIN)))
                    .map(ProcessHandle::pid);
            if (pid.isPresent()) {
                return pid;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return Optional.empty();
    }

    /** Dateiname ohne {@code .exe}/{@code .app}-Endung, klein geschrieben. */
    private static String programName(String command) {
        String n = Path.of(command).getFileName().toString().toLowerCase(Locale.ROOT);
        return n.endsWith(".exe") || n.endsWith(".app") ? n.substring(0, n.length() - 4) : n;
    }

    final class Windows implements ProgramLauncher {
        private static final int SEE_MASK_NOCLOSEPROCESS = 0x40;
        private static final int SEE_MASK_FLAG_NO_UI = 0x400;
        private static final int SW_SHOWNOACTIVATE = 4;
        private static final int VK_LBUTTON = 0x01;
        private static final int VK_RBUTTON = 0x02;

        @Override
        public Launched launch(String program, List<String> arguments) {
            Instant started = Instant.now();
            ShellAPI.SHELLEXECUTEINFO info = new ShellAPI.SHELLEXECUTEINFO();
            info.fMask = SEE_MASK_NOCLOSEPROCESS | SEE_MASK_FLAG_NO_UI;
            info.lpVerb = "open";
            info.lpFile = program;
            info.lpParameters = arguments.isEmpty() ? null : String.join(" ", arguments.stream().map(Windows::quote).toList());
            info.nShow = SW_SHOWNOACTIVATE;
            if (!Shell32.INSTANCE.ShellExecuteEx(info)) {
                throw new IllegalStateException("Programm „" + program + "“ ließ sich nicht starten (Fehler "
                        + Kernel32.INSTANCE.GetLastError() + ").");
            }
            WinNT.HANDLE process = info.hProcess;
            if (process != null) {
                try {
                    int pid = Kernel32.INSTANCE.GetProcessId(process);
                    if (pid != 0) {
                        return new Launched(pid, started);
                    }
                } finally {
                    Kernel32.INSTANCE.CloseHandle(process);
                }
            }
            return new Launched(newestProcess(program, started).orElse(0L), started);
        }

        private static String quote(String argument) {
            return argument.isEmpty() || argument.contains(" ") || argument.contains("\"")
                    ? "\"" + argument.replace("\"", "\\\"") + "\"" : argument;
        }

        @Override
        public boolean mouseButtonDown() {
            return (User32.INSTANCE.GetAsyncKeyState(VK_LBUTTON) & 0x8000) != 0
                    || (User32.INSTANCE.GetAsyncKeyState(VK_RBUTTON) & 0x8000) != 0;
        }

        @Override
        public boolean needsFocusGuard() {
            return true;
        }
    }

    final class Mac implements ProgramLauncher {
        @Override
        public Launched launch(String program, List<String> arguments) {
            Instant started = Instant.now();
            List<String> cmd = new ArrayList<>(List.of("open", "-g", "-a", program));
            if (!arguments.isEmpty()) {
                cmd.add("--args");
                cmd.addAll(arguments);
            }
            try {
                Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                if (p.waitFor() != 0) {
                    throw new IllegalStateException("„open -a " + program + "“ meldet: "
                            + new String(p.getInputStream().readAllBytes()).strip());
                }
            } catch (IOException e) {
                throw new IllegalStateException("Programm „" + program + "“ ließ sich nicht starten: " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Unterbrochen", e);
            }
            return new Launched(newestProcess(program, started).orElse(0L), started);
        }
    }

    final class Plain implements ProgramLauncher {
        @Override
        public Launched launch(String program, List<String> arguments) {
            List<String> cmd = new ArrayList<>(List.of(program));
            cmd.addAll(arguments);
            try {
                Process p = new ProcessBuilder(cmd).redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                return new Launched(p.pid(), Instant.now());
            } catch (IOException e) {
                throw new IllegalStateException("Programm „" + program + "“ ließ sich nicht starten: " + e.getMessage(), e);
            }
        }
    }
}
