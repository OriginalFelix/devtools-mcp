package systems.grebe.devtools.mcp.modules.window;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Welche Prozesse gesteuert werden dürfen: Include/Exclude-Regex aus der Konfiguration auf Prozessname und
 * Kommandozeile. Immer ausgeschlossen sind diese App (samt Kindprozessen), Anmelde- und Berechtigungsdialoge des
 * Systems und Passwortmanager; ebenso Prozesse, deren Kommando nicht lesbar ist (fremder Benutzer, erhöhte Rechte).
 * Ausnahme: Programme, die die KI per {@code window_launch} gestartet hat ({@link #launched(long)}), sind zwar
 * Kindprozesse dieser App, gehören aber nicht zu ihr – für sie gelten die übrigen Regeln.
 */
final class ProcessFilter {

    /** Prozessnamen (ohne Endung, klein) bzw. Präfixe, die nie gesteuert werden. */
    static final List<String> ALWAYS_EXCLUDED = List.of(
            // Windows: Anmeldung, Sperrbildschirm, UAC, Anmeldedaten-Dialoge, Sicherheitsprozesse
            "winlogon", "logonui", "lockapp", "consent", "credentialuibroker", "lsass", "csrss", "securityhealth",
            // macOS: Anmeldung, Berechtigungs- und Passwortabfragen, Schlüsselbund
            "loginwindow", "securityagent", "coreautha", "coreauthd", "screensaverengine", "keychain access",
            // Linux: Passwortabfragen und Schlüsselbund
            "polkit", "pinentry", "gnome-keyring", "kwalletd", "ksecretd", "seahorse",
            // Passwortmanager
            "keepass", "1password", "bitwarden", "lastpass", "dashlane", "enpass", "keeper", "nordpass", "roboform",
            "proton pass");

    /** Ein Prozess mit Name und Kommandozeile. */
    record Info(long pid, String name, String commandLine) {
    }

    private final Pattern include;
    private final Pattern exclude;
    private final long self;
    private final Set<Long> launched;

    ProcessFilter(Pattern include, Pattern exclude) {
        this(include, exclude, ProcessHandle.current().pid());
    }

    ProcessFilter(Pattern include, Pattern exclude, long self) {
        this(include, exclude, self, ConcurrentHashMap.newKeySet());
    }

    /** @param launched von der KI gestartete Programme – geteilt, damit alle Filter des Moduls sie kennen */
    ProcessFilter(Pattern include, Pattern exclude, long self, Set<Long> launched) {
        this.include = include;
        this.exclude = exclude;
        this.self = self;
        this.launched = launched;
    }

    /** Merkt ein per {@code window_launch} gestartetes Programm: es zählt nicht als Teil dieser App. */
    void launched(long pid) {
        launched.add(pid);
    }

    static Optional<Info> info(long pid) {
        return ProcessHandle.of(pid).flatMap(ProcessFilter::info);
    }

    static Optional<Info> info(ProcessHandle p) {
        ProcessHandle.Info i = p.info();
        return i.command().map(cmd -> new Info(p.pid(), name(cmd), i.commandLine().orElse(cmd)));
    }

    /** Dateiname ohne {@code .exe}/{@code .app}-Endung. */
    static String name(String command) {
        String n = Path.of(command).getFileName().toString();
        String lower = n.toLowerCase(Locale.ROOT);
        return lower.endsWith(".exe") || lower.endsWith(".app") ? n.substring(0, n.length() - 4) : n;
    }

    /** Leer, wenn der Prozess gesteuert werden darf, sonst der Grund. */
    Optional<String> rejection(long pid) {
        if (isSelfOrChild(pid)) {
            return Optional.of("Die DevTools-App selbst kann nicht gesteuert werden.");
        }
        Optional<Info> info = info(pid);
        if (info.isEmpty()) {
            return Optional.of("Prozess " + pid + " ist nicht lesbar (beendet, anderer Benutzer oder erhöhte Rechte).");
        }
        return rejection(info.get());
    }

    Optional<String> rejection(Info p) {
        String name = p.name().toLowerCase(Locale.ROOT);
        for (String excluded : ALWAYS_EXCLUDED) {
            if (name.startsWith(excluded)) {
                return Optional.of(p.name() + " ist aus Sicherheitsgründen immer ausgeschlossen "
                        + "(Anmeldung, Berechtigungsdialog oder Passwortmanager).");
            }
        }
        String hay = p.name() + " " + p.commandLine();
        if (include != null && !include.matcher(hay).find()) {
            return Optional.of(p.name() + " ist nicht freigegeben (passt nicht zu „Nur diese Prozesse“).");
        }
        if (exclude != null && exclude.matcher(hay).find()) {
            return Optional.of(p.name() + " ist ausgeschlossen („Prozesse ausschließen“).");
        }
        return Optional.empty();
    }

    boolean allowed(long pid) {
        return rejection(pid).isEmpty();
    }

    private boolean isSelfOrChild(long pid) {
        if (pid == self) {
            return true;
        }
        Optional<ProcessHandle> p = ProcessHandle.of(pid);
        while (p.isPresent()) {
            if (launched.contains(p.get().pid())) {
                return false; // von der KI gestartet (oder dessen Kind) – nicht Teil dieser App
            }
            p = p.get().parent();
            if (p.isPresent() && p.get().pid() == self) {
                return true;
            }
        }
        return false;
    }
}
