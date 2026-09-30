package systems.grebe.devtools.mcp.backend.account;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Benutzerverwaltung: anlegen, ändern, Passwörter und der erste Administrator. Die Formular-Anmeldung der Web-UI
 * (Spring Security) sitzt im Server und liest über {@link #login}.
 *
 * <p>Es bleibt immer mindestens ein aktiver Administrator – Löschen, Sperren oder Herabstufen des letzten wird
 * abgelehnt. Jede Änderung meldet ein {@link AccountChangedEvent}, damit z.B. die Tokens eines gesperrten
 * Benutzers sofort ungültig werden.
 */
@Service
public class AccountService {

    /** Anmeldename des Administrators beim ersten Start. */
    public static final String INITIAL_ADMIN = "admin";
    static final int MIN_PASSWORD = 8;
    private static final Pattern USERNAME = Pattern.compile("[a-z0-9][a-z0-9._-]{1,63}");
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+");
    private static final Logger LOG = LoggerFactory.getLogger(AccountService.class);

    private final AccountRepository repo;
    private final Sha3Pbkdf2PasswordEncoder encoder;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final String initialAdminPassword;

    @Autowired
    public AccountService(AccountRepository repo, Sha3Pbkdf2PasswordEncoder encoder,
                          @Qualifier("coreTransactions") TransactionTemplate tx, ApplicationEventPublisher events,
                          @Value("${devtools.admin.initial-password:${DEVTOOLS_MCP_ADMIN_PASSWORD:}}")
                          String initialAdminPassword) {
        this(repo, encoder, tx, events, Clock.systemUTC(), initialAdminPassword);
    }

    AccountService(AccountRepository repo, Sha3Pbkdf2PasswordEncoder encoder, TransactionTemplate tx,
                   ApplicationEventPublisher events, Clock clock, String initialAdminPassword) {
        this.repo = repo;
        this.encoder = encoder;
        this.tx = tx;
        this.events = events;
        this.clock = clock;
        this.initialAdminPassword = initialAdminPassword == null ? "" : initialAdminPassword;
    }

    // ---------------------------------------------------------------- Lesen

    public List<UserAccount> users() {
        return repo.users();
    }

    public Optional<UserAccount> user(long id) {
        return repo.user(id);
    }

    public Optional<UserAccount> userByName(String username) {
        return repo.userByName(normalizeUsername(username));
    }

    // ---------------------------------------------------------------- Ändern

    public UserAccount create(String username, String displayName, String email, Role role, String password) {
        String name = normalizeUsername(username);
        if (!USERNAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Benutzername: 2–64 Zeichen a-z, 0-9, Punkt, Unterstrich, Bindestrich; "
                    + "beginnt mit Buchstabe oder Ziffer.");
        }
        checkPassword(password);
        long id = tx.execute(s -> {
            if (repo.userByName(name).isPresent()) {
                throw new IllegalArgumentException("Benutzer '" + name + "' gibt es schon.");
            }
            return repo.insertUser(name, blankToNull(displayName), email(email), role, true, encoder.encode(password),
                    clock.instant());
        });
        LOG.info("Benutzer {} angelegt ({})", name, role);
        events.publishEvent(new AccountChangedEvent(id));
        return repo.user(id).orElseThrow();
    }

    public UserAccount update(long id, String displayName, String email, Role role, boolean enabled) {
        tx.executeWithoutResult(s -> {
            UserAccount before = repo.user(id).orElseThrow(() -> unknown(id));
            if (before.admin() && before.enabled() && (role != Role.ADMIN || !enabled)) {
                requireAnotherAdmin();
            }
            repo.updateUser(id, blankToNull(displayName), email(email), role, enabled);
        });
        events.publishEvent(new AccountChangedEvent(id));
        return repo.user(id).orElseThrow();
    }

    public void delete(long id) {
        tx.executeWithoutResult(s -> {
            UserAccount u = repo.user(id).orElseThrow(() -> unknown(id));
            if (u.admin() && u.enabled()) {
                requireAnotherAdmin();
            }
            repo.deleteUser(id); // Tokens per ON DELETE CASCADE
        });
        LOG.info("Benutzer {} gelöscht", id);
        events.publishEvent(new AccountChangedEvent(id));
    }

    /** Eigenes Passwort ändern – nur mit dem bisherigen. */
    public void changePassword(long id, String current, String next) {
        String hash = repo.passwordHash(id).orElseThrow(() -> unknown(id));
        if (!encoder.matches(current, hash)) {
            throw new IllegalArgumentException("Bisheriges Passwort stimmt nicht.");
        }
        checkPassword(next);
        repo.updatePassword(id, encoder.encode(next));
    }

    /** Passwort durch einen Administrator setzen. */
    public void resetPassword(long id, String next) {
        repo.user(id).orElseThrow(() -> unknown(id));
        checkPassword(next);
        repo.updatePassword(id, encoder.encode(next));
    }

    // ---------------------------------------------------------------- Anmeldung

    /** Benutzer mit Passwort-Hash zum Anmeldenamen – für die Formular-Anmeldung der Web-UI. */
    public Optional<Login> login(String username) {
        return repo.userByName(normalizeUsername(username))
                .flatMap(u -> repo.passwordHash(u.id()).map(h -> new Login(u, h)));
    }

    /** Speichert einen neu berechneten Hash (höhere Iterationszahl, siehe {@link Sha3Pbkdf2PasswordEncoder}). */
    public void storeRehashedPassword(long id, String passwordHash) {
        repo.updatePassword(id, passwordHash);
    }

    /** Benutzer und Passwort-Hash. */
    public record Login(UserAccount user, String passwordHash) {
    }

    // ---------------------------------------------------------------- Erster Start

    /**
     * Legt beim ersten Start den Administrator {@value #INITIAL_ADMIN} an. Passwort aus
     * {@code DEVTOOLS_MCP_ADMIN_PASSWORD} bzw. {@code devtools.admin.initial-password}, sonst zufällig – dann steht es
     * einmalig im Log und muss nach der Anmeldung geändert werden.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void ensureAdmin() {
        if (repo.countUsers() > 0) {
            return;
        }
        boolean generated = initialAdminPassword.isBlank();
        String password = generated ? randomPassword() : initialAdminPassword;
        create(INITIAL_ADMIN, "Administrator", null, Role.ADMIN, password);
        if (generated) {
            LOG.warn("""

                    ================================================================
                     Erster Start: Administrator angelegt
                       Benutzer: {}
                       Passwort: {}
                     Bitte nach der Anmeldung unter „Mein Konto“ ändern.
                    ================================================================""", INITIAL_ADMIN, password);
        } else {
            LOG.info("Erster Start: Administrator {} mit vorgegebenem Passwort angelegt", INITIAL_ADMIN);
        }
    }

    static String randomPassword() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            sb.append(alphabet.charAt(r.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- intern

    private void requireAnotherAdmin() {
        if (repo.countEnabledAdmins() <= 1) {
            throw new IllegalStateException("Es muss mindestens ein aktiver Administrator bleiben.");
        }
    }

    static String normalizeUsername(String username) {
        return username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
    }

    private static void checkPassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD) {
            throw new IllegalArgumentException("Passwort: mindestens " + MIN_PASSWORD + " Zeichen.");
        }
    }

    private static String email(String email) {
        String e = blankToNull(email);
        if (e == null) {
            return null;
        }
        e = e.toLowerCase(Locale.ROOT);
        if (!EMAIL.matcher(e).matches() || e.length() > 320) {
            throw new IllegalArgumentException("Ungültige E-Mail: " + email);
        }
        return e;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static IllegalArgumentException unknown(long id) {
        return new IllegalArgumentException("Unbekannter Benutzer " + id);
    }

    /** Ein Benutzer wurde angelegt, geändert oder gelöscht. */
    public record AccountChangedEvent(long userId) {
    }
}
