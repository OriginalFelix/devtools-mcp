package systems.grebe.devtools.mcp.modules.skills;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.eclipse.jgit.util.SystemReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.config.ModuleSettings;
import systems.grebe.devtools.mcp.config.SettingsStore;

/**
 * Wer die Skills gerade benutzt: Jede App-Instanz arbeitet für genau einen Benutzer, erkannt an der Git-E-Mail
 * ({@code git config --global user.email}) oder – falls gesetzt – an der E-Mail aus den Modul-Einstellungen. Auf einer
 * gemeinsamen Datenbank sieht und ändert jeder Benutzer nur seine eigenen Skills plus die globalen Vorlagen.
 *
 * <p>Die Werte werden bei jedem Aufruf frisch gelesen, damit Änderungen in der UI sofort gelten; die Git-E-Mail wird
 * einmal ermittelt.
 */
@Component
public class SkillUser {

    /** Eigentümer globaler Vorlagen – bewusst keine gültige E-Mail, damit kein Benutzer so heißen kann. */
    public static final String GLOBAL = "@global";

    static final Pattern EMAIL = Pattern.compile("[^@\\s]+@[^@\\s]+");
    static final int MAX_EMAIL = 320;

    private static final Logger LOG = LoggerFactory.getLogger(SkillUser.class);

    private final SettingsStore store;
    private final Supplier<Optional<String>> gitEmail;

    @Autowired
    public SkillUser(SettingsStore store) {
        this(store, SkillUser::readGitEmail);
    }

    /** Für Tests: Git-E-Mail vorgeben statt aus {@code ~/.gitconfig} zu lesen. */
    SkillUser(SettingsStore store, Supplier<Optional<String>> gitEmail) {
        this.store = store;
        this.gitEmail = memoize(gitEmail);
    }

    /** Aktueller Benutzer; wirft mit Hinweis, wenn weder Einstellung noch Git-E-Mail vorhanden sind. */
    public String email() {
        return emailIfKnown().orElseThrow(() -> new IllegalStateException("Kein Benutzer für die Skills: weder im "
                + "Modul „Skills“ eine E-Mail eingetragen noch eine Git-E-Mail gesetzt (git config --global "
                + "user.email …)."));
    }

    public Optional<String> emailIfKnown() {
        String configured = values().get(SkillsModule.USER_EMAIL);
        if (configured != null && !configured.isBlank()) {
            return Optional.of(normalize(configured));
        }
        return gitEmail.get().map(SkillUser::normalize);
    }

    /** Darf in der App globale Vorlagen veröffentlichen und löschen. */
    public boolean admin() {
        return Boolean.parseBoolean(values().getOrDefault(SkillsModule.ADMIN, "false"));
    }

    /** Woher der Benutzer stammt – für Anzeige und Log. */
    public String source() {
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
