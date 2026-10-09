package systems.grebe.devtools.mcp.modules.window;

import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongPredicate;
import java.util.function.LongSupplier;

import systems.grebe.devtools.mcp.modules.window.platform.NativeWindow;
import systems.grebe.devtools.mcp.modules.window.platform.WindowSystem;

/**
 * Hält ein von der KI gestartetes Programm im Hintergrund: Holt es sich während des Hochfahrens den Vordergrund, geht
 * er sofort an das Fenster zurück, in dem der Nutzer gerade arbeitet – so landen Tastatur-Eingaben des Nutzers nie im
 * Fenster der KI. Klickt der Nutzer das Fenster selbst an (Maustaste gedrückt, während es nach vorn kommt), will er es
 * haben: dann hört der Wächter auf.
 */
final class FocusGuard {

    static final Duration DEFAULT_DURATION = Duration.ofSeconds(20);
    private static final int POLL_MILLIS = 30;

    private final WindowSystem windows;
    private final LongPredicate launched;
    private final BooleanSupplier userClicking;
    private final LongSupplier clock;
    private final WindowSupport.Sleeper sleeper;

    /**
     * @param launched     ob eine PID zum gestarteten Programm gehört
     * @param userClicking ob der Nutzer gerade eine Maustaste drückt
     */
    FocusGuard(WindowSystem windows, LongPredicate launched, BooleanSupplier userClicking, LongSupplier clock,
               WindowSupport.Sleeper sleeper) {
        this.windows = windows;
        this.launched = launched;
        this.userClicking = userClicking;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    /**
     * Wacht {@code duration} lang (blockierend – Aufrufer startet dafür einen Thread).
     *
     * @param userWindow Fenster im Vordergrund vor dem Start
     * @return wie oft der Vordergrund zurückgegeben wurde; -1, wenn der Nutzer das Fenster übernommen hat
     */
    int guard(OptionalLong userWindow, Duration duration) {
        long deadline = clock.getAsLong() + duration.toMillis();
        long user = userWindow.orElse(0);
        int restored = 0;
        while (clock.getAsLong() < deadline) {
            OptionalLong fg = windows.foreground();
            if (fg.isPresent() && fg.getAsLong() != user) {
                Optional<NativeWindow> w = windows.window(fg.getAsLong());
                boolean ours = w.isPresent() && launched.test(w.get().pid());
                if (ours && userClicking.getAsBoolean()) {
                    return -1; // der Nutzer hat es angeklickt
                }
                if (ours && user != 0) {
                    windows.activate(user);
                    restored++;
                } else if (!ours) {
                    user = fg.getAsLong(); // der Nutzer ist selbst in ein anderes Fenster gewechselt
                }
            }
            sleeper.sleep(POLL_MILLIS);
        }
        return restored;
    }
}
