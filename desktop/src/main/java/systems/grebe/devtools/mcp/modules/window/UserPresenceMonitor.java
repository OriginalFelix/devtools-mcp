package systems.grebe.devtools.mcp.modules.window;

import java.awt.Point;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.LongSupplier;

/**
 * Not-Aus: Bewegt der Nutzer die Maus, während die KI steuert, hört die KI sofort auf. Erkannt wird das an einer
 * Mausposition, die von der zuletzt per Robot gesetzten abweicht – vor jedem Schritt einer Aktion und auch zwischen
 * zwei Aufrufen. Danach sind Eingaben für die Abkühlzeit gesperrt; die nächste Aktion danach misst neu.
 */
final class UserPresenceMonitor {

    /** Toleranz in Bildschirmpunkten (Rundung bei HiDPI, Zittern der Maus). */
    static final int TOLERANCE = 8;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final LongSupplier clock;
    private Point expected;
    private long lockedUntil;
    private String lockReason = "der Nutzer die Maus bewegt hat";

    UserPresenceMonitor(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * Prüft, ob der Nutzer eingegriffen hat, und wirft dann. Ohne Erwartung (erste Aktion, nach der Sperre) wird die
     * aktuelle Position zur Erwartung.
     *
     * @param enabled {@code false} = Mausbewegungen nicht überwachen (nur eine laufende Sperre gilt noch)
     */
    synchronized void check(Point actual, Duration cooldown, boolean enabled) {
        long now = clock.getAsLong();
        if (now < lockedUntil) {
            throw new UserInterventionException("Eingaben sind gesperrt, weil " + lockReason + " – wieder "
                    + "möglich ab " + time(lockedUntil) + ". Nicht sofort erneut versuchen, sondern den Nutzer fragen, "
                    + "ob du weitermachen sollst.");
        }
        if (enabled && expected != null && actual != null && actual.distance(expected) > TOLERANCE) {
            lockedUntil = now + cooldown.toMillis();
            lockReason = "der Nutzer die Maus bewegt hat";
            expected = null;
            throw new UserInterventionException("Abgebrochen: Der Nutzer hat die Maus bewegt. Eingaben sind bis "
                    + time(lockedUntil) + " gesperrt. Den Nutzer fragen, ob du weitermachen sollst.");
        }
        if (expected == null) {
            expected = actual;
        }
    }

    /**
     * Der Nutzer nimmt der KI die Kontrolle (z.B. per Tastenkombination): laufende und folgende Eingaben werden bis
     * zum Ende der Abkühlzeit abgelehnt.
     */
    synchronized void trip(String reason, Duration cooldown) {
        lockedUntil = clock.getAsLong() + cooldown.toMillis();
        lockReason = reason;
        expected = null;
    }

    /** Merkt die Position, die Robot gerade gesetzt hat. */
    synchronized void expect(Point position) {
        expected = position;
    }

    private static String time(long epochMillis) {
        return LocalTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault()).format(TIME);
    }

    /** Der Nutzer hat eingegriffen. */
    static final class UserInterventionException extends IllegalStateException {
        UserInterventionException(String message) {
            super(message);
        }
    }
}
