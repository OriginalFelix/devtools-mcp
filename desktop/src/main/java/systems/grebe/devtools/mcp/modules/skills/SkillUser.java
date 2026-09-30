package systems.grebe.devtools.mcp.modules.skills;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.eclipse.jgit.util.SystemReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.api.Me;
import systems.grebe.devtools.mcp.config.ModuleSettings;
import systems.grebe.devtools.mcp.config.SettingsStore;
import systems.grebe.devtools.mcp.remote.TeamServer;

/**
 * Wer die Skills gerade benutzt: Jede App-Instanz arbeitet für genau einen Benutzer, erkannt an der Git-E-Mail
 * ({@code git config --global user.email}) oder – falls gesetzt – an der E-Mail aus den Modul-Einstellungen. Auf einer
 * gemeinsamen Datenbank sieht und ändert jeder Benutzer nur seine eigenen Skills plus die globalen Vorlagen.
 *
 * <p>Die Werte werden bei jedem Aufruf frisch gelesen, damit Änderungen in der UI sofort gelten; die Git-E-Mail wird
 * einmal ermittelt.
 *
 * <p>Mit einem Team-Server verbunden gilt dessen Konto (E-Mail, Administrator-Rolle) – die Skills liegen dann auf dem
 * Server ({@link SkillStore}); Modul-Einstellung und Git-E-Mail spielen keine Rolle.
 */
@Component
public class SkillUser implements SkillOwner {

    /** Eigentümer globaler Vorlagen, siehe {@link SkillOwner#GLOBAL}. */
    public static final String GLOBAL = SkillOwner.GLOBAL;

    static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+");
    static final int MAX_EMAIL = SkillOwner.MAX_EMAIL;

    private static final Logger LOG = LoggerFactory.getLogger(SkillUser.class);

    private final SettingsStore store;
    private final Supplier<Optional<String>> gitEmail;
    /** Konto beim Team-Server, falls verbunden; in Tests ohne Spring immer leer. */
    private Supplier<Optional<Me>> account = Optional::empty;

    @Autowired
    public SkillUser(SettingsStore store) {
        this(store, SkillUser::readGitEmail);
    }

    /** Für Tests: Git-E-Mail vorgeben statt aus {@code ~/.gitconfig} zu lesen. */
    SkillUser(SettingsStore store, Supplier<Optional<String>> gitEmail) {
        this.store = store;
        this.gitEmail = memoize(gitEmail);
    }

    @Autowired
    void team(ObjectProvider<TeamServer> team) {
        this.account = () -> Optional.ofNullable(team.getIfAvailable()).filter(TeamServer::active)
                .flatMap(TeamServer::me);
    }

    /** Aktueller Benutzer; wirft mit Hinweis, wenn weder Einstellung noch Git-E-Mail vorhanden sind. */
    @Override
    public String email() {
        return emailIfKnown().orElseThrow(() -> account.get().isPresent()
                ? new IllegalStateException("Kein Benutzer für die Skills: im Konto von '"
                        + account.get().get().username() + "' ist keine E-Mail hinterlegt (Web-UI → Mein Konto).")
                : new IllegalStateException("Kein Benutzer für die Skills: weder im "
                        + "Modul „Skills“ eine E-Mail eingetragen noch eine Git-E-Mail gesetzt (git config --global "
                        + "user.email …)."));
    }

    @Override
    public Optional<String> emailIfKnown() {
        Optional<Me> me = account.get();
        if (me.isPresent()) {
            return Optional.ofNullable(me.get().email()).map(SkillUser::normalize);
        }
        String configured = values().get(SkillsModule.USER_EMAIL);
        if (configured != null && !configured.isBlank()) {
            return Optional.of(normalize(configured));
        }
        return gitEmail.get().map(SkillUser::normalize);
    }

    /** Darf in der App globale Vorlagen veröffentlichen und löschen. */
    @Override
    public boolean admin() {
        Optional<Me> me = account.get();
        if (me.isPresent()) {
            return me.get().admin();
        }
        return Boolean.parseBoolean(values().getOrDefault(SkillsModule.ADMIN, "false"));
    }

    /** Woher der Benutzer stammt – für Anzeige und Log. */
    public String source() {
        if (account.get().isPresent()) {
            return "Team-Server";
        }
        String configured = values().get(SkillsModule.USER_EMAIL);
        return configured != null && !configured.isBlank() ? "Modul-Einstellung" : "Git (user.email)";
    }

    static String normalize(String email) {
        String e = email.strip().toLowerCase(Locale.ROOT);
        if (!EMAIL.matcher(e).matches() || e.length() > MAX_EMAIL) {
            throw new IllegalStateException("Ungültige Benutzer-E-Mail '" + email + "' für die Skills.");
        }
        return e;
    }

    private Map<String, String> values() {
        return store.module(SkillsModule.ID).map(ModuleSettings::values).orElse(Map.of());
    }

    static Optional<String> readGitEmail() {
        try {
            String email = SystemReader.getInstance().getUserConfig().getString("user", null, "email");
            return Optional.ofNullable(email).filter(s -> !s.isBlank());
        } catch (Exception e) {
            LOG.warn("Git-Konfiguration nicht lesbar: {}", e.toString());
            return Optional.empty();
        }
    }

    private static <T> Supplier<T> memoize(Supplier<T> s) {
        return new Supplier<>() {
            private volatile T value;

            @Override
            public T get() {
                T v = value;
                if (v == null) {
                    synchronized (this) {
                        if (value == null) {
                            value = s.get();
                        }
                        v = value;
                    }
                }
                return v;
            }
        };
    }
}
