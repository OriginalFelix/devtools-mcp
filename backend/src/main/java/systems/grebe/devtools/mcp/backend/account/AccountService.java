package systems.grebe.devtools.mcp.backend.account;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
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
import systems.grebe.devtools.mcp.api.Permission;
import systems.grebe.devtools.mcp.backend.BackendChanged;

/**
 * Benutzerverwaltung: anlegen, ändern, Rollen zuordnen, Passwörter, Anmeldung und der erste Administrator. Die
 * Formular-Anmeldung der Web-UI (Spring Security) sitzt im Server und liest über {@link #login}; Desktop-Apps melden
 * sich über {@link #authenticate} an (GraphQL-Mutation {@code login}).
 *
 * <p>Es bleibt immer mindestens ein aktiver Benutzer mit dem Recht {@link Permission#USERS_MANAGE} – Löschen,
 * Sperren oder Entzug der Rolle des letzten wird abgelehnt (siehe auch {@link RoleService}). Jede Änderung meldet ein
 * {@link AccountChangedEvent}, damit z.B. die Tokens eines gesperrten Benutzers sofort ungültig werden, und
 * {@link BackendChanged} an seine Desktop-Apps (Rollen und Rechte neu laden).
 */
@Service
public class AccountService {

    /** Anmeldename des Administrators beim ersten Start. */
    public static final String INITIAL_ADMIN = "admin";
    static final int MIN_PASSWORD = 8;
    static final int MAX_PASSWORD = 256;
    private static final Pattern USERNAME = Pattern.compile("[a-z0-9][a-z0-9._-]{1,63}");
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+");
    private static final String LOGIN_FAILED = "Benutzername oder Passwort falsch.";
    private static final Logger LOG = LoggerFactory.getLogger(AccountService.class);

    private final AccountRepository repo;
    private final Sha3Pbkdf2PasswordEncoder encoder;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;
    private final LoginThrottle throttle;
    private final Clock clock;
    private final String initialAdminPassword;
    private volatile String dummyHash;
    private boolean createInitialAdmin = true;

    @Autowired
    public AccountService(AccountRepository repo, Sha3Pbkdf2PasswordEncoder encoder,
                          @Qualifier("coreTransactions") TransactionTemplate tx, ApplicationEventPublisher events,
                          LoginThrottle throttle,
                          @Value("${devtools.admin.initial-password:${DEVTOOLS_MCP_ADMIN_PASSWORD:}}")
                          String initialAdminPassword) {
        this(repo, encoder, tx, events, throttle, Clock.systemUTC(), initialAdminPassword);
    }

    AccountService(AccountRepository repo, Sha3Pbkdf2PasswordEncoder encoder, TransactionTemplate tx,
                   ApplicationEventPublisher events, LoginThrottle throttle, Clock clock,
                   String initialAdminPassword) {
        this.repo = repo;
        this.encoder = encoder;
        this.tx = tx;
        this.events = events;
        this.throttle = throttle;
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

    public boolean hasUsers() {
        return repo.countUsers() > 0;
    }

    // ---------------------------------------------------------------- Ändern

    /**
     * Legt einen Benutzer an.
     *
     * @param roles                  Namen der Rollen
     * @param passwordChangeRequired muss das Passwort bei der ersten Anmeldung ändern
     */
    public UserAccount create(String username, String displayName, String email, Collection<String> roles,
                              String password, boolean passwordChangeRequired) {
        String name = checkUsername(username);
        checkPassword(password);
        long id = tx.execute(s -> {
            if (repo.userByName(name).isPresent()) {
                throw new IllegalArgumentException("Benutzer '" + name + "' gibt es schon.");
            }
            Set<Long> roleIds = roleIds(roles);
            long userId = repo.insertUser(name, blankToNull(displayName), email(email), true, encoder.encode(password),
                    passwordChangeRequired, clock.instant());
            repo.setUserRoles(userId, roleIds);
            return userId;
        });
        LOG.info("Benutzer {} angelegt (Rollen {})", name, roles);
        changed(id);
        return repo.user(id).orElseThrow();
    }

    /** Ändert Stammdaten, Rollen und Status; {@code roles = null} lässt die Rollen unverändert. */
    public UserAccount update(long id, String displayName, String email, Collection<String> roles, boolean enabled) {
        guardUserManagers(() -> {
            repo.user(id).orElseThrow(() -> unknown(id));
            repo.updateUser(id, blankToNull(displayName), email(email), enabled);
            if (roles != null) {
                repo.setUserRoles(id, roleIds(roles));
            }
        });
        changed(id);
        return repo.user(id).orElseThrow();
    }

    /** Neuer Anmeldename; Profile, Projekte und Tokens bleiben (sie hängen an der ID). */
    public UserAccount rename(long id, String username) {
        String name = checkUsername(username);
        tx.executeWithoutResult(s -> {
            UserAccount u = repo.user(id).orElseThrow(() -> unknown(id));
            if (u.username().equals(name)) {
                return;
            }
            if (repo.userByName(name).isPresent()) {
                throw new IllegalArgumentException("Benutzer '" + name + "' gibt es schon.");
            }
            repo.rename(id, name);
        });
        changed(id);
        return repo.user(id).orElseThrow();
    }

    public void delete(long id) {
        guardUserManagers(() -> {
            repo.user(id).orElseThrow(() -> unknown(id));
            repo.deleteUser(id); // Tokens, Rollen per ON DELETE CASCADE
        });
        LOG.info("Benutzer {} gelöscht", id);
        changed(id);
    }

    /** Eigenes Passwort ändern – nur mit dem bisherigen. Hebt die Pflicht zum Ändern auf. */
    public void changePassword(long id, String current, String next) {
        String hash = repo.passwordHash(id).orElseThrow(() -> unknown(id));
        if (current == null || !encoder.matches(current, hash)) {
            throw new IllegalArgumentException("Bisheriges Passwort stimmt nicht.");
        }
        checkPassword(next);
        if (next.equals(current)) {
            throw new IllegalArgumentException("Das neue Passwort muss sich vom bisherigen unterscheiden.");
        }
        repo.updatePassword(id, encoder.encode(next), false);
        changed(id);
    }

    /**
     * Passwort durch einen Administrator (oder die Einrichtung der Desktop-App) setzen.
     *
     * @param changeRequired der Benutzer muss es bei der nächsten Anmeldung ändern
     */
    public void resetPassword(long id, String next, boolean changeRequired) {
        repo.user(id).orElseThrow(() -> unknown(id));
        checkPassword(next);
        repo.updatePassword(id, encoder.encode(next), changeRequired);
        changed(id);
    }

    // ---------------------------------------------------------------- Anmeldung

    /**
     * Anmeldung mit Benutzername und Passwort (Desktop-App). Fehlversuche bremst {@link LoginThrottle}; die Meldung
     * verrät nicht, ob es den Benutzer gibt. Gesperrte Benutzer erfahren es erst mit dem richtigen Passwort.
     *
     * @throws IllegalArgumentException bei falschen Angaben, Sperre oder zu vielen Fehlversuchen
     */
    public UserAccount authenticate(String username, String password) {
        String name = normalizeUsername(username);
        Optional<Duration> blocked = throttle.blocked(name);
        if (blocked.isPresent()) {
            throw new IllegalArgumentException(LoginThrottle.message(blocked.get()));
        }
        Optional<Login> login = login(name);
        // gleicher Aufwand mit und ohne Benutzer – die Antwortzeit verrät nicht, ob es ihn gibt
        String hash = login.map(Login::passwordHash).orElseGet(this::dummyHash);
        boolean ok = password != null && encoder.matches(password, hash) && login.isPresent();
        if (!ok) {
            throttle.failed(name);
            throw new IllegalArgumentException(LOGIN_FAILED);
        }
        throttle.succeeded(name);
        UserAccount u = login.get().user();
        if (!u.enabled()) {
            throw new IllegalArgumentException("Benutzer '" + u.username() + "' ist gesperrt.");
        }
        if (encoder.upgradeEncoding(hash)) {
            repo.rehashPassword(u.id(), encoder.encode(password));
        }
        recordLogin(u.id());
        return u;
    }

    /** Benutzer mit Passwort-Hash zum Anmeldenamen – für die Formular-Anmeldung der Web-UI. */
    public Optional<Login> login(String username) {
        return repo.userByName(normalizeUsername(username))
                .flatMap(u -> repo.passwordHash(u.id()).map(h -> new Login(u, h)));
    }

    /** Hash eines zufälligen Passworts – Vergleich für unbekannte Benutzer (beim ersten Bedarf berechnet). */
    private String dummyHash() {
        String h = dummyHash;
        if (h == null) {
            h = encoder.encode(randomPassword());
            dummyHash = h;
        }
        return h;
    }

    /** Merkt den Zeitpunkt der letzten Anmeldung. */
    public void recordLogin(long id) {
        repo.touchLogin(id, clock.instant());
    }

    /** Speichert einen neu berechneten Hash (höhere Iterationszahl, siehe {@link Sha3Pbkdf2PasswordEncoder}). */
    public void storeRehashedPassword(long id, String passwordHash) {
        repo.rehashPassword(id, passwordHash);
    }

    /** Benutzer und Passwort-Hash. */
    public record Login(UserAccount user, String passwordHash) {
    }

    // ---------------------------------------------------------------- Erster Start

    /**
     * Legt beim ersten Start den Administrator {@value #INITIAL_ADMIN} an. Passwort aus
     * {@code DEVTOOLS_MCP_ADMIN_PASSWORD} bzw. {@code devtools.admin.initial-password}, sonst zufällig – dann steht es
     * einmalig im Log und muss bei der ersten Anmeldung geändert werden.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void ensureAdmin() {
        if (!createInitialAdmin || repo.countUsers() > 0) {
            return;
        }
        boolean generated = initialAdminPassword.isBlank();
        String password = generated ? randomPassword() : initialAdminPassword;
        create(INITIAL_ADMIN, "Administrator", null, List.of(Role.ADMINISTRATOR), password, generated);
        if (generated) {
            LOG.warn("""

                    ================================================================
                     Erster Start: Administrator angelegt
                       Benutzer: {}
                       Passwort: {}
                     Das Passwort muss bei der ersten Anmeldung geändert werden.
                    ================================================================""", INITIAL_ADMIN, password);
        } else {
            LOG.info("Erster Start: Administrator {} mit vorgegebenem Passwort angelegt", INITIAL_ADMIN);
        }
    }

    public static String randomPassword() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            sb.append(alphabet.charAt(r.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    /** Eingebettet in der Desktop-App richtet der Benutzer das erste Konto selbst ein. */
    @Autowired(required = false)
    void createInitialAdmin(@Value("${devtools.admin.create-initial:true}") boolean value) {
        this.createInitialAdmin = value;
    }

    // ---------------------------------------------------------------- intern

    /**
     * Führt eine Änderung in einer Transaktion aus und rollt sie zurück, wenn danach kein aktiver Benutzer mehr
     * Benutzer und Rollen verwalten darf (vorher aber einer konnte).
     */
    void guardUserManagers(Runnable change) {
        tx.executeWithoutResult(s -> {
            long before = repo.countEnabledUsersWith(Permission.USERS_MANAGE.key());
            change.run();
            if (before > 0 && repo.countEnabledUsersWith(Permission.USERS_MANAGE.key()) == 0) {
                throw new IllegalStateException("Es muss mindestens ein aktiver Benutzer mit dem Recht „"
                        + Permission.USERS_MANAGE.label() + "“ bleiben.");
            }
        });
    }

    private Set<Long> roleIds(Collection<String> roles) {
        Set<Long> ids = new LinkedHashSet<>();
        if (roles != null) {
            for (String r : roles) {
                if (r != null && !r.isBlank()) {
                    ids.add(repo.roleByName(r.strip()).orElseThrow(() ->
                            new IllegalArgumentException("Unbekannte Rolle '" + r.strip() + "'")).id());
                }
            }
        }
        return ids;
    }

    private void changed(long id) {
        events.publishEvent(new AccountChangedEvent(id));
        events.publishEvent(BackendChanged.of(BackendChanged.Topic.SETTINGS, id));
    }

    static String normalizeUsername(String username) {
        return username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
    }

    private static String checkUsername(String username) {
        String name = normalizeUsername(username);
        if (!USERNAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Benutzername: 2–64 Zeichen a-z, 0-9, Punkt, Unterstrich, Bindestrich; "
                    + "beginnt mit Buchstabe oder Ziffer.");
        }
        return name;
    }

    private static void checkPassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD) {
            throw new IllegalArgumentException("Passwort: mindestens " + MIN_PASSWORD + " Zeichen.");
        }
        if (password.length() > MAX_PASSWORD) {
            throw new IllegalArgumentException("Passwort: höchstens " + MAX_PASSWORD + " Zeichen.");
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

    /**
     * Ein Benutzer wurde angelegt, geändert oder gelöscht bzw. eine Rolle geändert.
     *
     * @param userId betroffener Benutzer, {@code null} = möglicherweise alle (Rolle geändert)
     */
    public record AccountChangedEvent(Long userId) {
    }
}
