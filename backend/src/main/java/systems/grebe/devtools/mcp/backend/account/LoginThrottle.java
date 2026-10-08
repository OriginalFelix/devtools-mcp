package systems.grebe.devtools.mcp.backend.account;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Bremst das Raten von Passwörtern: nach {@value #FREE_ATTEMPTS} Fehlversuchen für einen Benutzernamen ist er
 * gesperrt – erst 30 Sekunden, dann jeweils doppelt so lange, höchstens 15 Minuten. Eine erfolgreiche Anmeldung oder
 * 15 Minuten ohne Fehlversuch setzen den Zähler zurück. Gilt für Desktop-Apps (GraphQL) und die Web-UI; nur im
 * Speicher, ein Neustart beginnt von vorn.
 */
@Component
public class LoginThrottle {

    static final int FREE_ATTEMPTS = 5;
    private static final Duration FIRST_BLOCK = Duration.ofSeconds(30);
    private static final Duration MAX_BLOCK = Duration.ofMinutes(15);
    private static final Duration FORGET = Duration.ofMinutes(15);
    private static final int MAX_TRACKED = 10_000;

    private record Failures(int count, Instant last, Instant blockedUntil) {
    }

    private final Clock clock;
    private final Map<String, Failures> failures = new ConcurrentHashMap<>();

    @Autowired
    public LoginThrottle() {
        this(Clock.systemUTC());
    }

    LoginThrottle(Clock clock) {
        this.clock = clock;
    }

    /** Restdauer der Sperre, leer = Anmeldung erlaubt. */
    public Optional<Duration> blocked(String username) {
        Failures f = failures.get(key(username));
        Instant now = clock.instant();
        if (f == null || f.blockedUntil() == null || !now.isBefore(f.blockedUntil())) {
            return Optional.empty();
        }
        return Optional.of(Duration.between(now, f.blockedUntil()));
    }

    public void failed(String username) {
        Instant now = clock.instant();
        if (failures.size() > MAX_TRACKED) {
            failures.values().removeIf(f -> f.last().plus(FORGET).isBefore(now));
        }
        failures.compute(key(username), (k, f) -> {
            int count = f == null || f.last().plus(FORGET).isBefore(now) ? 1 : f.count() + 1;
            Instant until = null;
            if (count >= FREE_ATTEMPTS) {
                long factor = 1L << Math.min(count - FREE_ATTEMPTS, 10);
                Duration block = FIRST_BLOCK.multipliedBy(factor);
                until = now.plus(block.compareTo(MAX_BLOCK) > 0 ? MAX_BLOCK : block);
            }
            return new Failures(count, now, until);
        });
    }

    public void succeeded(String username) {
        failures.remove(key(username));
    }

    /** Meldung für eine Sperre, z.B. „… in 30 s erneut versuchen“. */
    public static String message(Duration remaining) {
        long s = Math.max(1, remaining.toSeconds());
        return "Zu viele Fehlversuche – in " + (s < 120 ? s + " s" : (s + 59) / 60 + " min") + " erneut versuchen.";
    }

    private static String key(String username) {
        return username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
    }
}
