package systems.grebe.devtools.mcp.modules.window;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import systems.grebe.devtools.mcp.modules.window.platform.NativeWindow;
import systems.grebe.devtools.mcp.modules.window.platform.WindowSystem;

/**
 * Fenster für den Auswahldialog der Einstellungen: alle sichtbaren Fenster mit Prozessname und Programmpfad – ohne
 * diese App und ohne immer ausgeschlossene Prozesse (Anmeldung, Passwortmanager), die ohnehin nicht steuerbar sind.
 */
public final class WindowCandidates {

    /** Ein wählbares Fenster. */
    public record Candidate(NativeWindow window, String processName, String executable) {

        public long pid() {
            return window.pid();
        }
    }

    private final WindowSystem windows;
    private final ProcessFilter filter;

    WindowCandidates(WindowSystem windows, long self) {
        this.windows = windows;
        this.filter = new ProcessFilter(null, null, self);
    }

    public static WindowCandidates current() {
        return new WindowCandidates(WindowSystem.current(), ProcessHandle.current().pid());
    }

    public Optional<String> unsupportedReason() {
        return windows.unsupportedReason();
    }

    public List<Candidate> list() {
        List<Candidate> out = new ArrayList<>();
        for (NativeWindow w : windows.windows()) {
            if (filter.rejection(w.pid()).isPresent()) {
                continue;
            }
            ProcessFilter.info(w.pid()).ifPresent(i -> out.add(new Candidate(w, i.name(), i.command())));
        }
        return out;
    }

    /** Vorschaubild, ohne das Fenster zu aktivieren; leer, wenn minimiert oder nicht erfassbar. */
    public Optional<BufferedImage> preview(Candidate c) {
        if (c.window().minimized()) {
            return Optional.empty();
        }
        try {
            return windows.captureInBackground(c.window());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
