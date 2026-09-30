package systems.grebe.devtools.mcp.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Für wen Tools gebaut werden und laufen: ein Benutzer mit seinem aktiven Profil. Jede {@link McpRuntime} hat genau
 * einen Scope; der Desktop-Betrieb kennt nur {@link #LOCAL}.
 *
 * <p>Zustandsbehaftete Module (offene SSH-Sitzungen, Debugger-Verbindungen …) legen ihren Zustand über
 * {@link #state(String, Supplier)} hier ab statt im Modul-Singleton – so teilen sich Benutzer nichts, und beim
 * Profilwechsel oder Ende der Runtime schließt {@link #close()} alles, was {@link AutoCloseable} ist.
 *
 * <p>Während eines Tool-Aufrufs ist der Scope über {@link #current()} erreichbar (gleicher Thread wie der Handler,
 * wie bei {@link ToolProgress}). Code, der zur Laufzeit Einstellungen oder den Benutzer braucht, fragt dort nach.
 */
public final class ToolScope implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(ToolScope.class);

    /** Einzelplatz: der Benutzer am Rechner, Einstellungen aus {@code settings.json}. */
    public static final ToolScope LOCAL = new ToolScope("local", null, null, null, null, true);

    private static final ThreadLocal<ToolScope> CURRENT = new ThreadLocal<>();

    private final String id;
    private final String userId;
    private final String userName;
    private final String email;
    private final String profileId;
    private final boolean admin;
    private volatile Predicate<Path> writable;
    private final Map<String, Object> state = new LinkedHashMap<>();

    public ToolScope(String id, String userId, String userName, String email, String profileId, boolean admin) {
        this(id, userId, userName, email, profileId, admin, root -> true);
    }

    /**
     * @param writable ob der Benutzer in einem Projektverzeichnis schreiben darf (Commit, Build …); wird bei jedem
     *                 Aufruf gefragt, Freigaben gelten also sofort
     */
    public ToolScope(String id, String userId, String userName, String email, String profileId, boolean admin,
                     Predicate<Path> writable) {
        this.writable = writable;
        this.id = id;
        this.userId = userId;
        this.userName = userName;
        this.email = email;
        this.profileId = profileId;
        this.admin = admin;
    }

    /** Stabile ID, z.B. {@code local} oder {@code user:42/profile:7}. */
    public String id() {
        return id;
    }

    /** Benutzer-ID; leer im Einzelplatz-Betrieb. */
    public Optional<String> userId() {
        return Optional.ofNullable(userId);
    }

    public Optional<String> userName() {
        return Optional.ofNullable(userName);
    }

    /** E-Mail des Benutzers (z.B. Eigentümer seiner Skills); leer im Einzelplatz-Betrieb oder wenn nicht gepflegt. */
    public Optional<String> email() {
        return Optional.ofNullable(email);
    }

    public Optional<String> profileId() {
        return Optional.ofNullable(profileId);
    }

    public boolean admin() {
        return admin;
    }

    /** Ob in diesem Projektverzeichnis geschrieben werden darf (siehe {@link Workspaces#requireWritable}). */
    public boolean canWrite(Path root) {
        return writable.test(root);
    }

    /**
     * Legt fest, in welchen Projektverzeichnissen schreibende Tools arbeiten dürfen – z.B. nicht in Projekten, die der
     * Team-Server nur zum Lesen freigibt.
     */
    public void restrictWrites(Predicate<Path> value) {
        this.writable = value == null ? root -> true : value;
    }

    /**
     * Zustand dieses Scopes unter {@code key}; wird beim ersten Zugriff mit {@code factory} erzeugt. Werte, die
     * {@link AutoCloseable} sind, schließt {@link #close()} in umgekehrter Reihenfolge der Anlage.
     */
    @SuppressWarnings("unchecked")
    public synchronized <T> T state(String key, Supplier<T> factory) {
        return (T) state.computeIfAbsent(key, k -> factory.get());
    }

    /**
     * Schließt allen Zustand. Der Scope bleibt verwendbar – ein späterer Zugriff legt neuen Zustand an (z.B. ein
     * Aufruf, der beim Profilwechsel noch lief); SSH-Sitzungen u.ä. schließen sich zusätzlich nach Leerlauf.
     */
    @Override
    public void close() {
        List<Object> values;
        synchronized (this) {
            values = new ArrayList<>(state.values());
            state.clear();
        }
        Collections.reverse(values);
        for (Object v : values) {
            if (v instanceof AutoCloseable c) {
                try {
                    c.close();
                } catch (Exception e) {
                    LOG.warn("Zustand {} in Scope {} nicht sauber geschlossen", v.getClass().getSimpleName(), id, e);
                }
            }
        }
    }

    /** Scope des laufenden Tool-Aufrufs, außerhalb eines Aufrufs (UI, Aktionen) {@link #LOCAL}. */
    public static ToolScope current() {
        ToolScope s = CURRENT.get();
        return s == null ? LOCAL : s;
    }

    /** Führt {@code body} mit {@code scope} als {@link #current()} aus. */
    public static <T> T callIn(ToolScope scope, Supplier<T> body) {
        ToolScope previous = CURRENT.get();
        CURRENT.set(scope);
        try {
            return body.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    @Override
    public String toString() {
        return id;
    }
}
