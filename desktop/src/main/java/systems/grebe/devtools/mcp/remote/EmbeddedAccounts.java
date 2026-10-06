package systems.grebe.devtools.mcp.remote;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.eclipse.jgit.util.SystemReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import systems.grebe.devtools.mcp.backend.account.AccountService;
import systems.grebe.devtools.mcp.backend.account.Role;
import systems.grebe.devtools.mcp.backend.account.UserAccount;
import systems.grebe.devtools.mcp.config.ModuleSettings;
import systems.grebe.devtools.mcp.config.SettingsStore;
import systems.grebe.devtools.mcp.modules.skills.SkillsModule;

/**
 * Konten des eingebetteten Backends: Beim ersten Start richtet der Benutzer am Rechner das erste Konto ein
 * (Administrator, mit Passwort). Lief die App vorher ohne Anmeldung als {@value #LEGACY_USERNAME}, übernimmt die
 * Einrichtung dieses Konto – Profile, Einstellungen, Projekte, Skills und Memories bleiben (sie hängen an der ID bzw.
 * der E-Mail). Danach meldet sich jeder beim Start an; weitere Benutzer und Rollen verwaltet der Tab „Benutzer“.
 *
 * <p>Eingerichtet wird direkt über die Dienste des Backends (gleicher Prozess), nicht über GraphQL – die API kennt
 * keine Anmeldung ohne Konto.
 */
public class EmbeddedAccounts {

    /** Benutzer, unter dem die App vor der Anmeldung lief. */
    public static final String LEGACY_USERNAME = "local";
    private static final String MARKER = "account-setup.done";
    private static final Logger LOG = LoggerFactory.getLogger(EmbeddedAccounts.class);

    private final AccountService accounts;
    private final SettingsStore store;
    private final String fixedEmail;

    public EmbeddedAccounts(AccountService accounts, SettingsStore store, String fixedEmail) {
        this.accounts = accounts;
        this.store = store;
        this.fixedEmail = fixedEmail == null ? "" : fixedEmail.strip();
    }

    /** Noch kein Konto eingerichtet: erster Start oder das Konto {@value #LEGACY_USERNAME} ohne bekanntes Passwort. */
    public boolean setupRequired() {
        return !accounts.hasUsers() || legacyAccount().isPresent();
    }

    /** Das bisherige Konto {@value #LEGACY_USERNAME}, solange es nicht eingerichtet ist. */
    public Optional<UserAccount> legacyAccount() {
        if (Files.exists(marker())) {
            return Optional.empty();
        }
        return accounts.userByName(LEGACY_USERNAME);
    }

    /** Vorschlag für den Benutzernamen: der Anmeldename am Rechner, passend gemacht. */
    public static String suggestedUsername() {
        String os = System.getProperty("user.name", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "");
        os = os.replaceFirst("^[._-]+", "");
        return os.length() >= 2 ? os : "admin";
    }

    /** Vorschlag für die E-Mail (Eigentümer der Skills): fest eingestellt, frühere Modul-Einstellung oder Git. */
    public String suggestedEmail() {
        if (!fixedEmail.isEmpty()) {
            return fixedEmail;
        }
        return legacyAccount().map(UserAccount::email).or(() -> email(store)).orElse("");
    }

    /**
     * Richtet das erste Konto ein: übernimmt {@value #LEGACY_USERNAME} (neuer Name, Passwort, Rolle
     * {@value Role#ADMINISTRATOR}) oder legt einen Administrator an.
     *
     * @throws IllegalStateException wenn schon ein Konto eingerichtet ist
     */
    public synchronized UserAccount setup(String username, String displayName, String email, String password) {
        if (!setupRequired()) {
            throw new IllegalStateException("Es ist schon ein Konto eingerichtet – bitte anmelden.");
        }
        String mail = email == null || email.isBlank() ? (fixedEmail.isEmpty() ? null : fixedEmail) : email;
        Optional<UserAccount> legacy = legacyAccount();
        UserAccount account;
        if (legacy.isPresent()) {
            UserAccount old = legacy.get();
            account = accounts.rename(old.id(), username);
            Set<String> roles = new LinkedHashSet<>(old.roles());
            roles.add(Role.ADMINISTRATOR);
            accounts.update(old.id(), displayName, mail == null ? old.email() : mail, roles, true);
            accounts.resetPassword(old.id(), password, false);
            account = accounts.user(old.id()).orElseThrow();
            LOG.info("Bisheriges Konto {} als {} eingerichtet", LEGACY_USERNAME, account.username());
        } else {
            account = accounts.create(username, displayName, mail, Set.of(Role.ADMINISTRATOR), password, false);
            LOG.info("Erstes Konto {} eingerichtet", account.username());
        }
        try {
            Files.writeString(marker(), Instant.now().toString());
        } catch (IOException e) {
            LOG.warn("Marker {} nicht schreibbar", marker(), e);
        }
        return account;
    }

    private Path marker() {
        return store.dir().resolve(MARKER);
    }

    /**
     * Frühere Modul-Einstellung „Benutzer-E-Mail“, sonst Git-E-Mail. Auch Eigentümer alter Skills ohne Eigentümer
     * ({@code devtools.skills.legacy-owner}).
     */
    public static Optional<String> email(SettingsStore store) {
        String configured = store.module(SkillsModule.ID).map(ModuleSettings::values).orElse(Map.of())
                .get(SkillsModule.LEGACY_USER_EMAIL);
        if (configured != null && !configured.isBlank()) {
            return Optional.of(configured.strip());
        }
        return gitEmail();
    }

    static Optional<String> gitEmail() {
        try {
            String email = SystemReader.getInstance().getUserConfig().getString("user", null, "email");
            return Optional.ofNullable(email).filter(s -> !s.isBlank()).map(String::strip);
        } catch (Exception e) {
            LOG.warn("Git-Konfiguration nicht lesbar: {}", e.toString());
            return Optional.empty();
        }
    }
}
