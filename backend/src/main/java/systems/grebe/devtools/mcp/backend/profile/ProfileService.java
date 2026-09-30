package systems.grebe.devtools.mcp.backend.profile;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import systems.grebe.devtools.mcp.api.ModuleDescriptor;
import systems.grebe.devtools.mcp.api.ModuleOverlay;
import systems.grebe.devtools.mcp.api.SettingsSnapshot;
import systems.grebe.devtools.mcp.backend.BackendChanged;
import systems.grebe.devtools.mcp.backend.catalog.ModuleCatalog;
import systems.grebe.devtools.mcp.config.SecretCipher;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.profile.Overrides;

/**
 * Profile der Benutzer und die Einstellungs-Ebenen <b>Global → Benutzer → Profil</b>.
 *
 * <p>Global sind die Einstellungen aus {@code settings.json} (Desktop-App bzw. Administrator). Benutzer und Profil
 * speichern nur, was sie überschreiben; alles andere erben sie. Vom Administrator gesperrte Felder gelten nur global –
 * Überschreibungen dafür werden beim Speichern abgelehnt und beim Auflösen ignoriert (falls die Sperre später kam).
 *
 * <p>Jeder Benutzer hat mindestens ein Profil („Standard“, beim ersten Zugriff angelegt) und genau ein aktives; seine
 * Desktop-Apps arbeiten mit dem aktiven und holen sich dessen Vorgaben über {@link #snapshot}.
 */
@Service
public class ProfileService {

    public static final String DEFAULT_PROFILE = "Standard";
    private static final int MAX_NAME = 64;

    private final ProfileRepository repo;
    private final SecretCipher cipher;
    private final ModuleCatalog catalog;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;
    private final Clock clock = Clock.systemUTC();

    public ProfileService(ProfileRepository repo, SecretCipher cipher, ModuleCatalog catalog,
                          @Qualifier("coreTransactions") TransactionTemplate tx, ApplicationEventPublisher events) {
        this.repo = repo;
        this.cipher = cipher;
        this.catalog = catalog;
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
        changed(userId);
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
        changed(userId);
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
        }
        changed(userId);
    }

    /** Macht ein Profil aktiv; verbundene Desktop-Apps des Benutzers übernehmen es sofort. */
    public void activate(long userId, long profileId) {
        own(userId, profileId);
        repo.setActiveProfile(userId, profileId);
        changed(userId);
    }

    private void changed(long userId) {
        events.publishEvent(BackendChanged.of(BackendChanged.Topic.SETTINGS, userId));
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
            String v = r.secret() ? cipher.decrypt(r.value()) : r.value();
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
    public void saveOverrides(long userId, Overrides.Level level, long levelId, String moduleId, Overrides o) {
        if (level == Overrides.Level.GLOBAL) {
            throw new IllegalArgumentException("Globale Einstellungen über saveGlobal");
        }
        if (level == Overrides.Level.USER && levelId != userId) {
            throw new IllegalArgumentException("Fremde Benutzereinstellungen");
        }
        if (level == Overrides.Level.PROFILE) {
            own(userId, levelId);
        }
        save(level, levelId, module(moduleId), o, repo.locks(moduleId));
        changed(userId);
    }

    /** Speichert die globalen Vorgaben eines Moduls (nur Administratoren – prüft der Aufrufer). */
    public void saveGlobal(String moduleId, Overrides o) {
        save(Overrides.Level.GLOBAL, 0, module(moduleId), o, Set.of());
        events.publishEvent(BackendChanged.all(BackendChanged.Topic.SETTINGS));
    }

    private void save(Overrides.Level level, long levelId, ModuleDescriptor module, Overrides o, Set<String> locks) {
        Map<String, ConfigField> fields = module.schema().stream()
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
            rows.add(new ProfileRepository.OverrideRow(k, f.secret() ? cipher.encrypt(v) : v, f.secret()));
        });
        tx.executeWithoutResult(s -> repo.replaceOverrides(level, levelId, module.id(), rows));
    }

    private ModuleDescriptor module(String moduleId) {
        return catalog.module(moduleId).orElseThrow(() -> new IllegalArgumentException("Unbekanntes Modul "
                + moduleId + " – noch von keiner Desktop-App gemeldet."));
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
        events.publishEvent(BackendChanged.all(BackendChanged.Topic.SETTINGS));
    }

    // ---------------------------------------------------------------- Auflösung

    /**
     * Vorgaben für die Desktop-Apps des Benutzers: alle Module mit Werten auf einer der Ebenen Global, Benutzer oder
     * aktives Profil – oder mit Sperren.
     */
    public SettingsSnapshot snapshot(long userId) {
        Profile profile = activeProfile(userId);
        Set<String> moduleIds = new TreeSet<>();
        moduleIds.addAll(repo.overriddenModules(Overrides.Level.GLOBAL, 0));
        moduleIds.addAll(repo.overriddenModules(Overrides.Level.USER, userId));
        moduleIds.addAll(repo.overriddenModules(Overrides.Level.PROFILE, profile.id()));
        moduleIds.addAll(repo.allLocks().keySet());
        List<ModuleOverlay> modules = new ArrayList<>();
        for (String id : moduleIds) {
            ModuleOverlay o = overlay(id, userId, profile.id());
            if (!o.isEmpty() || !o.locked().isEmpty()) {
                modules.add(o);
            }
        }
        return new SettingsSnapshot(profile.id(), profile.name(), modules);
    }

    /**
     * Die Ebenen Global → Benutzer → Profil eines Moduls zusammengefasst. Gesperrte Schlüssel gelten nur global.
     *
     * @param userId    {@code null} = nur Global
     * @param profileId {@code null} = ohne Profil-Ebene
     */
    public ModuleOverlay overlay(String moduleId, Long userId, Long profileId) {
        Set<String> locks = repo.locks(moduleId);
        Set<String> schema = catalog.module(moduleId)
                .map(m -> m.schema().stream().map(ConfigField::key).collect(Collectors.toSet())).orElse(null);
        Boolean enabled = null;
        Map<String, Boolean> tools = new LinkedHashMap<>();
        Map<String, String> values = new LinkedHashMap<>();
        List<Overrides> layers = new ArrayList<>();
        layers.add(overrides(Overrides.Level.GLOBAL, 0, moduleId));
        if (userId != null) {
            layers.add(overrides(Overrides.Level.USER, userId, moduleId));
        }
        if (profileId != null) {
            layers.add(overrides(Overrides.Level.PROFILE, profileId, moduleId));
        }
        boolean global = true;
        for (Overrides o : layers) {
            if (o.enabled() != null && (global || !locks.contains(Overrides.ENABLED))) {
                enabled = o.enabled();
            }
            if (global || !locks.contains(Overrides.TOOLS)) {
                tools.putAll(o.tools());
            }
            for (var e : o.values().entrySet()) {
                if ((schema == null || schema.contains(e.getKey())) && (global || !locks.contains(e.getKey()))) {
                    values.put(e.getKey(), e.getValue());
                }
            }
            global = false;
        }
        return ModuleOverlay.of(moduleId, enabled, tools, values, locks);
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
}
