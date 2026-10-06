package systems.grebe.devtools.mcp.modules.dolt;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.modules.dolt.DoltDatabase.Kind;
import systems.grebe.devtools.mcp.modules.jdbc.JdbcModule;

/**
 * Gleicht den Branch einer Datenbank mit dem Git-Branch ihres Arbeitsverzeichnisses ab: fehlt der Datenbank-Branch,
 * wird er gleichnamig angelegt (vom Branch, auf dem die Datenbank gerade steht, oder vom eingetragenen Startpunkt),
 * dann landen neue Verbindungen auf ihm. Je Datenbank läuft höchstens ein Abgleich; das letzte Ergebnis bleibt für
 * {@code dolt_status} und die Hinweise an das LLM erhalten.
 */
@Component
public class DoltBranches {

    private static final Logger LOG = LoggerFactory.getLogger(DoltBranches.class);

    /** Öffnet eine Datenbank für einen Abgleich. */
    @FunctionalInterface
    interface Backends {
        DoltBackend open(DoltDatabase db, Settings settings);
    }

    /** Einstellungen des Moduls, die jeder Abgleich braucht. */
    record Settings(int timeoutSeconds, String doltlite) {
    }

    enum Status {
        /** Neue Verbindungen landen jetzt auf dem Git-Branch (ggf. frisch angelegt). */
        SWITCHED,
        /** Stand schon auf dem Git-Branch. */
        IN_SYNC,
        /** Git-HEAD ist losgelöst – nichts geändert. */
        DETACHED,
        /** Branch vorhanden, aber die Datenbank kann den Standard-Branch nicht umstellen (Doltgres). */
        NOT_SWITCHABLE,
        FAILED
    }

    /**
     * Ergebnis eines Abgleichs.
     *
     * @param gitBranch {@code null} bei losgelöstem HEAD oder unlesbarem Repository
     * @param from      Startpunkt, falls der Branch angelegt wurde, sonst {@code null}
     */
    record Outcome(DoltDatabase database, String gitBranch, Status status, String from, String message, Instant at) {

        boolean ok() {
            return status != Status.FAILED;
        }

        /** Für Nutzer und LLM, z.B. „app (Dolt): Branch feature/x von main angelegt und eingestellt.“ */
        String describe() {
            return database.name() + " (" + database.kind().label + "): " + message;
        }
    }

    private final Backends backends;
    private final Consumer<DoltDatabase> afterSwitch;
    private final Map<String, Outcome> last = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    /** Änderungen und Fehler, die das LLM noch nicht gesehen hat (je Datenbank das neueste). */
    private final Map<String, Outcome> unreported = new ConcurrentHashMap<>();

    @Autowired
    public DoltBranches(JdbcModule jdbc) {
        this((db, s) -> db.kind() == Kind.DOLTLITE
                        ? DoltliteBackend.open(db, s.doltlite(), s.timeoutSeconds())
                        : DoltServerBackend.open(db, jdbc, s.timeoutSeconds()),
                db -> {
                    // gepoolte jdbc_*-Verbindungen säßen sonst noch auf dem alten Branch
                    int closed = jdbc.disconnectIdle(c -> pointsTo(c.url(), db));
                    if (closed > 0) {
                        LOG.debug("{} freie JDBC-Verbindung(en) zu {} geschlossen", closed, db.name());
                    }
                });
    }

    DoltBranches(Backends backends, Consumer<DoltDatabase> afterSwitch) {
        this.backends = backends;
        this.afterSwitch = afterSwitch;
    }

    /**
     * Gleicht ab, wenn sich Git-Branch oder Einstellungen der Datenbank seit dem letzten Abgleich geändert haben; ein
     * gescheiterter Abgleich wird frühestens nach {@code retryAfter} wiederholt.
     *
     * @return das Ergebnis oder leer, wenn nichts zu tun war
     */
    Optional<Outcome> syncIfNeeded(DoltDatabase db, Settings settings, Duration retryAfter) {
        String key = key(db);
        String git;
        try {
            git = gitBranch(db);
        } catch (IOException | IllegalStateException e) {
            synchronized (lock(key)) {
                Outcome prev = last.get(key);
                if (prev != null && prev.database().equals(db) && prev.status() == Status.FAILED
                        && age(prev).compareTo(retryAfter) < 0) {
                    return Optional.empty();
                }
                return Optional.of(record(new Outcome(db, null, Status.FAILED, null, message(e), Instant.now())));
            }
        }
        synchronized (lock(key)) {
            Outcome prev = last.get(key);
            if (prev != null && prev.database().equals(db) && Objects.equals(prev.gitBranch(), git)
                    && (prev.ok() || age(prev).compareTo(retryAfter) < 0)) {
                return Optional.empty();
            }
            return Optional.of(run(db, settings, git));
        }
    }

    /** Gleicht sofort ab, auch wenn sich nichts geändert hat (z.B. nachdem jemand den Standard-Branch verstellt hat). */
    Outcome sync(DoltDatabase db, Settings settings) {
        String key = key(db);
        synchronized (lock(key)) {
            String git;
            try {
                git = gitBranch(db);
            } catch (IOException | IllegalStateException e) {
                return record(new Outcome(db, null, Status.FAILED, null, message(e), Instant.now()));
            }
            return run(db, settings, git);
        }
    }

    /** Letztes Ergebnis für die Datenbank (gleiche Einstellungen), falls es eins gibt. */
    Optional<Outcome> last(DoltDatabase db) {
        Outcome o = last.get(key(db));
        return o != null && o.database().equals(db) ? Optional.of(o) : Optional.empty();
    }

    /** Öffnet die Datenbank (für {@code dolt_status} und den Verbindungstest). */
    DoltBackend open(DoltDatabase db, Settings settings) {
        return backends.open(db, settings);
    }

    /**
     * Nimmt die Änderungen und Fehler der letzten {@code maxAge}, die das LLM noch nicht gesehen hat – je Datenbank
     * das neueste Ergebnis, jedes nur einmal.
     */
    List<Outcome> takeUnreported(Collection<DoltDatabase> databases, Duration maxAge) {
        List<Outcome> out = new ArrayList<>();
        for (DoltDatabase db : databases) {
            Outcome o = unreported.remove(key(db));
            if (o != null && o.database().equals(db) && age(o).compareTo(maxAge) <= 0) {
                out.add(o);
            }
        }
        return out;
    }

    /** Vergisst den Zustand entfernter Datenbanken. */
    void retain(Collection<DoltDatabase> databases) {
        Set<String> keys = databases.stream().map(DoltBranches::key).collect(Collectors.toSet());
        last.keySet().retainAll(keys);
        unreported.keySet().retainAll(keys);
        locks.keySet().retainAll(keys);
    }

    // ------------------------------------------------------------------ intern

    private Outcome run(DoltDatabase db, Settings settings, String git) {
        if (git == null) {
            return record(new Outcome(db, null, Status.DETACHED, null, "Git-HEAD ist losgelöst (Rebase, Bisect, Tag …) "
                    + "– Datenbank-Branch bleibt, wie er ist.", Instant.now()));
        }
        try (DoltBackend b = backends.open(db, settings)) {
            String current = b.defaultBranch();
            if (git.equals(current)) {
                return record(new Outcome(db, git, Status.IN_SYNC, null, "steht auf " + git + ".", Instant.now()));
            }
            String from = null;
            if (!b.branches().contains(git)) {
                from = startPoint(db, current, b);
                b.create(git, from);
            }
            String created = from == null ? "" : "Branch " + git + " von " + from + " angelegt";
            if (!b.setDefault(git)) {
                return record(new Outcome(db, git, Status.NOT_SWITCHABLE, from, (created.isEmpty() ? "Branch " + git
                        + " vorhanden" : created) + "; " + db.kind().label + " kann den Standard-Branch für neue "
                        + "Verbindungen nicht umstellen (bleibt " + (current == null ? "unverändert" : current)
                        + ") – die Anwendung verbindet sich ausdrücklich über " + b.connectHint(git) + ".", Instant.now()));
            }
            afterSwitch.accept(db);
            return record(new Outcome(db, git, Status.SWITCHED, from, created.isEmpty()
                    ? "Standard-Branch von " + (current == null ? "(nicht auflösbar)" : current) + " auf " + git
                    + " umgestellt." : created + " und als Standard-Branch eingestellt.", Instant.now()));
        } catch (RuntimeException e) {
            return record(new Outcome(db, git, Status.FAILED, null, message(e), Instant.now()));
        }
    }

    /** Eingetragener Startpunkt, sonst der aktuelle Standard-Branch, notfalls main/master. */
    private static String startPoint(DoltDatabase db, String current, DoltBackend b) {
        if (!db.baseBranch().isEmpty()) {
            return db.baseBranch();
        }
        if (current != null) {
            return current;
        }
        List<String> branches = b.branches();
        for (String candidate : List.of("main", "master")) {
            if (branches.contains(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Der Standard-Branch der Datenbank ist nicht auflösbar und es gibt weder main "
                + "noch master – in der DevTools-App einen Startpunkt für neue Branches eintragen.");
    }

    private Outcome record(Outcome o) {
        String key = key(o.database());
        Outcome prev = last.put(key, o);
        switch (o.status()) {
            case SWITCHED, NOT_SWITCHABLE -> LOG.info("Datenbank-Branch: {}", o.describe());
            case FAILED -> {
                if (prev == null || prev.status() != Status.FAILED || !prev.message().equals(o.message())) {
                    LOG.warn("Datenbank-Branch nicht abgeglichen: {}", o.describe());
                }
            }
            default -> LOG.debug("Datenbank-Branch: {}", o.describe());
        }
        boolean news = switch (o.status()) {
            case SWITCHED, NOT_SWITCHABLE -> true;
            // denselben Fehler nicht bei jedem Git-Aufruf wiederholen
            case FAILED -> prev == null || prev.status() != Status.FAILED || !prev.message().equals(o.message());
            default -> false;
        };
        if (news) {
            unreported.put(key, o);
        } else if (o.status() == Status.IN_SYNC) {
            unreported.remove(key);
        }
        return o;
    }

    private static String gitBranch(DoltDatabase db) throws IOException {
        return GitHead.branch(GitHead.gitDir(db.repositoryPath()));
    }

    private Object lock(String key) {
        return locks.computeIfAbsent(key, k -> new Object());
    }

    private static String key(DoltDatabase db) {
        return db.name().toLowerCase(Locale.ROOT);
    }

    private static Duration age(Outcome o) {
        return Duration.between(o.at(), Instant.now());
    }

    private static String message(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
    }

    /**
     * Ob eine JDBC-URL auf dieselbe Dolt-Datenbank ohne Branch-Angabe zeigt ({@code jdbc:mysql://host:port/db} bzw.
     * {@code jdbc:mariadb:…}) – nur solche Verbindungen hängen am Standard-Branch.
     */
    static boolean pointsTo(String jdbcUrl, DoltDatabase db) {
        if (db.kind() != Kind.DOLT || jdbcUrl == null) {
            return false;
        }
        String u = jdbcUrl.strip();
        String lower = u.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("jdbc:mysql://") && !lower.startsWith("jdbc:mariadb://")) {
            return false;
        }
        DoltDatabase.Server target;
        URI uri;
        try {
            target = db.server();
            uri = URI.create(u.substring(5));
        } catch (RuntimeException e) {
            return false;
        }
        String path = uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/", "");
        int port = uri.getPort() < 0 ? Kind.DOLT.defaultPort : uri.getPort();
        return port == target.port() && sameHost(uri.getHost(), target.host())
                && path.equalsIgnoreCase(target.database());
    }

    private static boolean sameHost(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        Set<String> local = Set.of("localhost", "127.0.0.1", "[::1]", "::1");
        String x = a.toLowerCase(Locale.ROOT);
        String y = b.toLowerCase(Locale.ROOT);
        return x.equals(y) || local.contains(x) && local.contains(y);
    }
}
