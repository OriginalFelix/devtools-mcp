package systems.grebe.devtools.mcp.profile;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import systems.grebe.devtools.mcp.config.ModuleSettings;
import systems.grebe.devtools.mcp.config.SettingsStore;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.ToolModule;
import systems.grebe.devtools.mcp.core.ToolScope;

/**
 * Profile der Benutzer und die Einstellungs-Ebenen <b>Global → Benutzer → Profil</b>.
 *
 * <p>Global sind die Einstellungen aus {@code settings.json} (Desktop-App bzw. Administrator). Benutzer und Profil
 * speichern nur, was sie überschreiben; alles andere erben sie. Vom Administrator gesperrte Felder gelten nur global –
 * Überschreibungen dafür werden beim Speichern abgelehnt und beim Auflösen ignoriert (falls die Sperre später kam).
 *
 * <p>Jeder Benutzer hat mindestens ein Profil („Standard“, beim ersten Zugriff angelegt) und genau ein aktives; seine
 * MCP-Clients arbeiten mit dem aktiven. Änderungen melden {@link SettingsChangedEvent} bzw.
 * {@link ProfileSwitchedEvent}, damit die Runtime des Benutzers ihre Tools neu aufbaut.
 */
@Service
public class ProfileService {

    public static final String DEFAULT_PROFILE = "Standard";
    private static final int MAX_NAME = 64;

    private final ProfileRepository repo;
    private final SettingsStore store;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;
    private final Clock clock = Clock.systemUTC();

    public ProfileService(ProfileRepository repo, SettingsStore store,
                          @Qualifier("coreTransactions") TransactionTemplate tx, ApplicationEventPublisher events) {
        this.repo = repo;
        this.store = store;
        this.tx = tx;
        this.events = events;
    }

    // ---------------------------------------------------------------- Profile

    /** Profile des Benutzers; legt „Standard“ an, falls er noch keins hat. */
    public List<Profile> profiles(long userId) {
        List<Profile> list = repo.profiles(userId);
        if (!list.isEmpty()) {
            return list;
        }
        activeProfile(userId);
        return repo.profiles(userId);
    }

    /** Aktives Profil; fehlt es (neuer Benutzer, Profil gelöscht), wird das erste bzw. „Standard“ aktiv. */
    public Profile activeProfile(long userId) {
        return tx.execute(s -> {
            Optional<Profile> active = repo.activeProfileId(userId).flatMap(repo::profile)
                    .filter(p -> p.userId() == userId);
            if (active.isPresent()) {
                return active.get();
            }
            List<Profile> existing = repo.profiles(userId);
            Profile p = existing.isEmpty()
                    ? repo.profile(repo.insertProfile(userId, DEFAULT_PROFILE, null, clock.instant())).orElseThrow()
                    : existing.getFirst();
            repo.setActiveProfile(userId, p.id());
            return p;
        });
    }

    public Profile create(long userId, String name, String description) {
        String n = name(name);
        long id = tx.execute(s -> {
            if (repo.profiles(userId).stream().anyMatch(p -> p.name().equalsIgnoreCase(n))) {
                throw new IllegalArgumentException("Profil „" + n + "“ gibt es schon.");
            }
            return repo.insertProfile(userId, n, blankToNull(description), clock.instant());
        });
        return repo.profile(id).orElseThrow();
    }

    /**
     * Legt ein Profil als Kopie an – mit allen Überschreibungen des Vorbilds.
     */
    public Profile copy(long userId, long sourceId, String name) {
        Profile source = own(userId, sourceId);
        Profile copy = create(userId, name, source.description());
        tx.executeWithoutResult(s -> repo.overriddenModules(Overrides.Level.PROFILE, source.id()).forEach(m ->
                repo.replaceOverrides(Overrides.Level.PROFILE, copy.id(), m,
                        repo.overrides(Overrides.Level.PROFILE, source.id(), m))));
        return copy;
    }

    public void update(long userId, long profileId, String name, String description) {
        own(userId, profileId);
        String n = name(name);
        tx.executeWithoutResult(s -> {
            if (repo.profiles(userId).stream().anyMatch(p -> p.id() != profileId && p.name().equalsIgnoreCase(n))) {
                throw new IllegalArgumentException("Profil „" + n + "“ gibt es schon.");
            }
            repo.updateProfile(profileId, n, blankToNull(description));
        });
    }

    /** Löscht ein Profil samt Überschreibungen; das letzte bleibt. War es aktiv, wird ein anderes aktiv. */
    public void delete(long userId, long profileId) {
        own(userId, profileId);
        boolean wasActive = tx.execute(s -> {
            if (repo.profiles(userId).size() <= 1) {
                throw new IllegalStateException("Das letzte Profil kann nicht gelöscht werden.");
            }
            boolean active = repo.activeProfileId(userId).map(id -> id == profileId).orElse(false);
            repo.deleteProfile(profileId);
            return active;
        });
        if (wasActive) {
            activeProfile(userId);
            events.publishEvent(new ProfileSwitchedEvent(userId));
        }
    }

    /** Macht ein Profil aktiv; die MCP-Clients des Benutzers bekommen dessen Tools und Einstellungen. */
    public void activate(long userId, long profileId) {
        own(userId, profileId);
        repo.setActiveProfile(userId, profileId);
        events.publishEvent(new ProfileSwitchedEvent(userId));
    }

    private Profile own(long userId, long profileId) {
        return repo.profile(profileId).filter(p -> p.userId() == userId)
                .orElseThrow(() -> new IllegalArgumentException("Unbekanntes Profil"));
    }

    // ---------------------------------------------------------------- Überschreibungen

    /**
     * Überschreibungen eines Moduls auf einer Ebene.
     *
     * @param levelId Benutzer- bzw. Profil-ID
     */
    public Overrides overrides(Overrides.Level level, long levelId, String moduleId) {
        Boolean enabled = null;
        Map<String, Boolean> tools = new LinkedHashMap<>();
        Map<String, String> values = new LinkedHashMap<>();
        for (ProfileRepository.OverrideRow r : repo.overrides(level, levelId, moduleId)) {
            String v = r.secret() ? store.decrypt(r.value()) : r.value();
            if (Overrides.ENABLED.equals(r.key())) {
                enabled = Boolean.parseBoolean(v);
            } else if (r.key().startsWith(Overrides.TOOL_PREFIX)) {
                tools.put(r.key().substring(Overrides.TOOL_PREFIX.length()), Boolean.parseBoolean(v));
            } else {
                values.put(r.key(), v == null ? "" : v);
            }
        }
        return new Overrides(enabled, tools, values);
    }

    /**
     * Speichert die Überschreibungen eines Moduls für einen Benutzer ({@code USER}) oder eines seiner Profile
     * ({@code PROFILE}); ersetzt die bisherigen. Gesperrte oder unbekannte Felder werden abgelehnt.
     */
    public void saveOverrides(long userId, Overrides.Level level, long levelId, ToolModule module, Overrides o) {
        if (level == Overrides.Level.USER && levelId != userId) {
            throw new IllegalArgumentException("Fremde Benutzereinstellungen");
        }
        if (level == Overrides.Level.PROFILE) {
            own(userId, levelId);
        }
        Set<String> locks = repo.locks(module.id());
        Map<String, ConfigField> fields = module.configSchema().stream()
                .collect(Collectors.toMap(ConfigField::key, Function.identity()));
        List<ProfileRepository.OverrideRow> rows = new ArrayList<>();
        if (o.enabled() != null) {
            requireUnlocked(locks, Overrides.ENABLED, "Modul an/aus");
            rows.add(new ProfileRepository.OverrideRow(Overrides.ENABLED, o.enabled().toString(), false));
        }
        if (!o.tools().isEmpty()) {
            requireUnlocked(locks, Overrides.TOOLS, "Tool-Schalter");
        }
        o.tools().forEach((t, on) -> rows.add(new ProfileRepository.OverrideRow(Overrides.TOOL_PREFIX + t,
                on.toString(), false)));
        o.values().forEach((k, v) -> {
            ConfigField f = fields.get(k);
            if (f == null) {
                throw new IllegalArgumentException("Unbekanntes Feld " + k + " in " + module.id());
            }
            requireUnlocked(locks, k, f.label());
            rows.add(new ProfileRepository.OverrideRow(k, f.secret() ? store.encrypt(v) : v, f.secret()));
        });
        tx.executeWithoutResult(s -> repo.replaceOverrides(level, levelId, module.id(), rows));
        events.publishEvent(new SettingsChangedEvent(userId));
    }

    private static void requireUnlocked(Set<String> locks, String key, String label) {
        if (locks.contains(key)) {
            throw new IllegalArgumentException("„" + label + "“ ist vom Administrator gesperrt.");
        }
    }

    /** Module mit Überschreibungen auf dieser Ebene. */
    public Set<String> overriddenModules(Overrides.Level level, long levelId) {
        return repo.overriddenModules(level, levelId);
    }

    // ---------------------------------------------------------------- Sperren (Administrator)

    public Set<String> locks(String moduleId) {
        return repo.locks(moduleId);
    }

    public void setLocks(String moduleId, Set<String> keys) {
        tx.executeWithoutResult(s -> repo.replaceLocks(moduleId, new LinkedHashSet<>(keys)));
        events.publishEvent(new SettingsChangedEvent(null));
    }

    // ---------------------------------------------------------------- Auflösung

    /** Globale Einstellungen, überlagert von Benutzer und aktivem Profil des Scopes (siehe {@code ProjectSettings}). */
    public ModuleSettings effective(ToolScope scope, ToolModule module, ModuleSettings global) {
        Optional<Long> userId = scope.userId().map(Long::parseLong);
        if (userId.isEmpty()) {
            return global;
        }
        Set<String> locks = repo.locks(module.id());
        Set<String> schema = module.configSchema().stream().map(ConfigField::key).collect(Collectors.toSet());
        boolean enabled = global.enabled();
        Set<String> disabled = new LinkedHashSet<>(global.disabledTools());
        Map<String, String> values = new LinkedHashMap<>(global.values());

        List<Overrides> layers = new ArrayList<>();
        layers.add(overrides(Overrides.Level.USER, userId.get(), module.id()));
        scope.profileId().map(Long::parseLong)
                .ifPresent(p -> layers.add(overrides(Overrides.Level.PROFILE, p, module.id())));
        for (Overrides o : layers) {
            if (o.enabled() != null && !locks.contains(Overrides.ENABLED)) {
                enabled = o.enabled();
            }
            if (!locks.contains(Overrides.TOOLS)) {
                o.tools().forEach((t, on) -> {
                    if (on) {
                        disabled.remove(t);
                    } else {
                        disabled.add(t);
                    }
                });
            }
            o.values().forEach((k, v) -> {
                if (schema.contains(k) && !locks.contains(k)) {
                    values.put(k, v);
                }
            });
        }
        return new ModuleSettings(enabled, disabled, values);
    }

    // ---------------------------------------------------------------- intern

    private static String name(String name) {
        String n = name == null ? "" : name.strip();
        if (n.isEmpty() || n.length() > MAX_NAME) {
            throw new IllegalArgumentException("Profilname: 1–" + MAX_NAME + " Zeichen.");
        }
        return n;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    /** Einstellungen eines Benutzers ({@code userId}) oder – bei {@code null} – aller Benutzer haben sich geändert. */
    public record SettingsChangedEvent(Long userId) {
    }

    /** Ein Benutzer hat ein anderes Profil aktiv. */
    public record ProfileSwitchedEvent(long userId) {
    }
}
