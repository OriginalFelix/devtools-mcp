package systems.grebe.devtools.mcp.remote;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

import org.eclipse.jgit.util.SystemReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import systems.grebe.devtools.mcp.backend.account.AccountService;
import systems.grebe.devtools.mcp.backend.account.ApiToken;
import systems.grebe.devtools.mcp.backend.account.Role;
import systems.grebe.devtools.mcp.backend.account.TokenService;
import systems.grebe.devtools.mcp.backend.account.UserAccount;
import systems.grebe.devtools.mcp.config.ModuleSettings;
import systems.grebe.devtools.mcp.config.SettingsStore;
import systems.grebe.devtools.mcp.modules.skills.SkillsModule;

/**
 * Der Benutzer der Desktop-App am eingebetteten Backend: {@value #USERNAME}, Administrator, ohne Anmeldung. Seine
 * E-Mail (Eigentümer der Skills) ist die frühere Einstellung „Benutzer-E-Mail“ des Skill-Moduls oder die Git-E-Mail
 * ({@code git config --global user.email}). Bei jedem Start bekommt die App ein frisches Token; ältere werden
 * gelöscht.
 */
public class LocalUser {

    public static final String USERNAME = "local";
    private static final Duration VALIDITY = Duration.ofDays(30);
    private static final Logger LOG = LoggerFactory.getLogger(LocalUser.class);

    private final AccountService accounts;
    private final TokenService tokens;
    private final SettingsStore store;
    private final String fixedEmail;
    private volatile String token;

    public LocalUser(AccountService accounts, TokenService tokens, SettingsStore store, String fixedEmail) {
        this.accounts = accounts;
        this.tokens = tokens;
        this.store = store;
        this.fixedEmail = fixedEmail == null ? "" : fixedEmail.strip();
    }

    /** Token für die GraphQL-API des eingebetteten Backends (legt den Benutzer beim ersten Aufruf an). */
    public synchronized String token() {
        if (token == null) {
            UserAccount user = ensureUser();
            for (ApiToken old : tokens.tokens(user.id())) {
                if (old.revokedAt() == null) {
                    tokens.revoke(user.id(), old.id());
                }
                tokens.delete(user.id(), old.id());
            }
            token = tokens.issue(user, "Desktop-App", VALIDITY).jwt();
        }
        return token;
    }

    private UserAccount ensureUser() {
        String email = fixedEmail.isEmpty() ? email(store).orElse(null) : fixedEmail;
        Optional<UserAccount> existing = accounts.userByName(USERNAME);
        if (existing.isPresent()) {
            UserAccount u = existing.get();
            if (u.email() == null && email != null) {
                return accounts.update(u.id(), u.displayName(), email, Role.ADMIN, true);
            }
            return u;
        }
        byte[] random = new byte[24];
        new SecureRandom().nextBytes(random);
        LOG.info("Lokaler Benutzer {} angelegt ({})", USERNAME, email == null ? "ohne E-Mail" : email);
        return accounts.create(USERNAME, "Lokal", email, Role.ADMIN, HexFormat.of().formatHex(random));
    }

    /**
     * E-Mail des lokalen Benutzers: frühere Modul-Einstellung „Benutzer-E-Mail“, sonst Git-E-Mail. Auch Eigentümer
     * alter Skills ohne Eigentümer ({@code devtools.skills.legacy-owner}).
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
