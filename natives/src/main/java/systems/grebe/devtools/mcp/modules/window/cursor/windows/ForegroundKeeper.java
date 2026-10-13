package systems.grebe.devtools.mcp.modules.window.cursor.windows;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.function.LongUnaryOperator;

/**
 * Hält ein Java-Fenster, das die KI bedient, im Hintergrund. Glaubt sich Java/AWT aktiv (siehe
 * {@link WindowsCursorController}), holt es sich beim nächsten Fokuswechsel selbst nach vorn
 * ({@code SetForegroundWindow} in {@code AwtWindow::AwtSetActiveWindow}) – Windows lässt das zu, wenn der Nutzer eine
 * Weile nichts eingegeben hat (Vordergrundsperre, Standard 200 s). Nach jeder Eingabe der KI wacht dieser Wächter
 * {@link #WATCH_MILLIS} lang: Kommt ein Fenster des Programms nach vorn, bekommt das Fenster, in dem der Nutzer zuletzt
 * war, den Vordergrund sofort zurück – so landen seine Tastatureingaben nie im Fenster der KI. Klickt der Nutzer gerade
 * selbst, will er das Programm haben: dann bleibt es.
 */
final class ForegroundKeeper {

    static final long WATCH_MILLIS = 2_000;
    private static final long POLL_MILLIS = 15;

    private final LongSupplier foreground;
    private final LongUnaryOperator pid;
    private final LongConsumer activate;
    private final BooleanSupplier userClicking;
    private final LongSupplier clock;
    private final ScheduledExecutorService timer;

    private long javaPid;
    private long user;
    private long deadline;
    private boolean restored;
    private ScheduledFuture<?> task;

    /**
     * @param foreground   Vordergrundfenster, 0 wenn keins
     * @param pid          Prozess eines Fensters
     * @param activate     holt ein Fenster nach vorn
     * @param userClicking ob der Nutzer gerade eine Maustaste drückt
     * @param timer        prüft im Hintergrund; {@code null} = nur über {@link #poll()} (Tests)
     */
    ForegroundKeeper(LongSupplier foreground, LongUnaryOperator pid, LongConsumer activate, BooleanSupplier userClicking,
                     LongSupplier clock, ScheduledExecutorService timer) {
        this.foreground = foreground;
        this.pid = pid;
        this.activate = activate;
        this.userClicking = userClicking;
        this.clock = clock;
        this.timer = timer;
    }

    /** Mit eigenem Hintergrund-Thread. */
    static ForegroundKeeper start(LongSupplier foreground, LongUnaryOperator pid, LongConsumer activate,
                                  BooleanSupplier userClicking) {
        return new ForegroundKeeper(foreground, pid, activate, userClicking, System::currentTimeMillis,
                Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "java-foreground-keeper");
                    t.setDaemon(true);
                    return t;
                }));
    }

    /**
     * Die KI gibt gleich etwas in das Java-Fenster {@code javaWindow} ein: ab jetzt wachen. Das Fenster des Nutzers ist
     * das aktuelle Vordergrundfenster, sofern es nicht zum selben Programm gehört.
     */
    synchronized void watch(long javaWindow) {
        long p = pid.applyAsLong(javaWindow);
        long fg = foreground.getAsLong();
        if (fg != 0 && pid.applyAsLong(fg) != p) {
            user = fg;
        } else if (p != javaPid) {
            user = 0; // anderes Programm, der Nutzer arbeitet darin – nichts zurückzugeben
        }
        javaPid = p;
        deadline = clock.getAsLong() + WATCH_MILLIS;
        if (timer != null && (task == null || task.isDone())) {
            task = timer.scheduleWithFixedDelay(this::poll, POLL_MILLIS, POLL_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    /** Ein Blick auf den Vordergrund; gibt ihn zurück, wenn das Java-Programm ihn sich genommen hat. */
    synchronized void poll() {
        if (clock.getAsLong() >= deadline) {
            if (task != null) {
                task.cancel(false);
            }
            return;
        }
        long fg = foreground.getAsLong();
        if (fg == 0 || fg == user) {
            return;
        }
        if (pid.applyAsLong(fg) != javaPid) {
            user = fg; // der Nutzer ist selbst in ein anderes Fenster gewechselt
            return;
        }
        if (user == 0) {
            return;
        }
        if (userClicking.getAsBoolean()) {
            deadline = 0; // er hat das Programm selbst angeklickt – es gehört ihm
            return;
        }
        activate.accept(user);
        restored = true;
    }

    /**
     * Ob seit dem letzten Aufruf der Vordergrund zurückgegeben wurde. Das Java-Fenster war dann wirklich aktiv und ist
     * es nicht mehr – es hält sich nicht mehr für aktiv und muss vor der nächsten Eingabe erneut so gemeldet werden.
     */
    synchronized boolean takeRestored() {
        boolean r = restored;
        restored = false;
        return r;
    }
}
