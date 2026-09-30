package systems.grebe.devtools.mcp.profile;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** Profile, Überschreibungen und Sperren in der Core-Datenbank (Tabellen {@code profile}, {@code module_override}, {@code field_lock}). */
@Repository
public class ProfileRepository {

    /** Gespeicherte Zeile von {@code module_override}; {@code value} wie in der Tabelle (evtl. verschlüsselt). */
    public record OverrideRow(String key, String value, boolean secret) {
    }

    private final JdbcClient jdbc;

    public ProfileRepository(@Qualifier("coreJdbc") JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- Profile

    public List<Profile> profiles(long userId) {
        return jdbc.sql("SELECT id, user_id, name, description, created_at FROM profile WHERE user_id = ? ORDER BY name")
                .param(userId).query(ProfileRepository::profile).list();
    }

    public Optional<Profile> profile(long id) {
        return jdbc.sql("SELECT id, user_id, name, description, created_at FROM profile WHERE id = ?").param(id)
                .query(ProfileRepository::profile).optional();
    }

    public long insertProfile(long userId, String name, String description, Instant createdAt) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO profile (user_id, name, description, created_at) VALUES (?, ?, ?, ?)")
                .params(userId, name, description, Timestamp.from(createdAt)).update(keys, "id");
        return keys.getKeyAs(Number.class).longValue();
    }

    public void updateProfile(long id, String name, String description) {
        jdbc.sql("UPDATE profile SET name = ?, description = ? WHERE id = ?").params(name, description, id).update();
    }

    public void deleteProfile(long id) {
        jdbc.sql("DELETE FROM module_override WHERE level = 'PROFILE' AND level_id = ?").param(id).update();
        jdbc.sql("DELETE FROM profile WHERE id = ?").param(id).update();
    }

    public Optional<Long> activeProfileId(long userId) {
        return jdbc.sql("SELECT active_profile_id FROM app_user WHERE id = ?").param(userId)
                .query((rs, row) -> {
                    long v = rs.getLong(1);
                    return rs.wasNull() ? null : v;
                }).optional();
    }

    public void setActiveProfile(long userId, long profileId) {
        jdbc.sql("UPDATE app_user SET active_profile_id = ? WHERE id = ?").params(profileId, userId).update();
    }

    private static Profile profile(ResultSet rs, int row) throws SQLException {
        return new Profile(rs.getLong("id"), rs.getLong("user_id"), rs.getString("name"),
                rs.getString("description"), rs.getTimestamp("created_at").toInstant());
    }

    // ---------------------------------------------------------------- Überschreibungen

    public List<OverrideRow> overrides(Overrides.Level level, long levelId, String moduleId) {
        return jdbc.sql("""
                        SELECT setting_key, setting_value, secret FROM module_override
                        WHERE level = ? AND level_id = ? AND module_id = ? ORDER BY setting_key""")
                .params(level.name(), levelId, moduleId)
                .query((rs, row) -> new OverrideRow(rs.getString(1), rs.getString(2), rs.getBoolean(3))).list();
    }

    /** Ersetzt alle Überschreibungen des Moduls auf dieser Ebene. */
    public void replaceOverrides(Overrides.Level level, long levelId, String moduleId, List<OverrideRow> rows) {
        jdbc.sql("DELETE FROM module_override WHERE level = ? AND level_id = ? AND module_id = ?")
                .params(level.name(), levelId, moduleId).update();
        for (OverrideRow r : rows) {
            jdbc.sql("""
                            INSERT INTO module_override (level, level_id, module_id, setting_key, setting_value, secret)
                            VALUES (?, ?, ?, ?, ?, ?)""")
                    .params(level.name(), levelId, moduleId, r.key(), r.value(), r.secret()).update();
        }
    }

    /** Module mit Überschreibungen auf dieser Ebene (für die Übersicht). */
    public Set<String> overriddenModules(Overrides.Level level, long levelId) {
        return new LinkedHashSet<>(jdbc.sql("""
                        SELECT DISTINCT module_id FROM module_override WHERE level = ? AND level_id = ?
                        ORDER BY module_id""")
                .params(level.name(), levelId).query(String.class).list());
    }

    // ---------------------------------------------------------------- Sperren

    public Set<String> locks(String moduleId) {
        return new LinkedHashSet<>(jdbc.sql("SELECT field_key FROM field_lock WHERE module_id = ? ORDER BY field_key")
                .param(moduleId).query(String.class).list());
    }

    public Map<String, Set<String>> allLocks() {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        jdbc.sql("SELECT module_id, field_key FROM field_lock ORDER BY module_id, field_key")
                .query((rs, row) -> out.computeIfAbsent(rs.getString(1), k -> new LinkedHashSet<>())
                        .add(rs.getString(2)))
                .list();
        return out;
    }

    public void replaceLocks(String moduleId, Set<String> keys) {
        jdbc.sql("DELETE FROM field_lock WHERE module_id = ?").param(moduleId).update();
        for (String k : keys) {
            jdbc.sql("INSERT INTO field_lock (module_id, field_key) VALUES (?, ?)").params(moduleId, k).update();
        }
    }
}
