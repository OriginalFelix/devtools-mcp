package systems.grebe.devtools.mcp.backend.account;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import systems.grebe.devtools.mcp.api.Grants;

/**
 * Benutzer, Rollen und Tokens in der Core-Datenbank (Tabellen {@code app_user}, {@code app_role},
 * {@code role_permission}, {@code user_role}, {@code api_token}).
 */
@Repository
public class AccountRepository {

    private static final String USER_COLUMNS = "id, username, display_name, email, enabled, password_change_required, "
            + "created_at, last_login_at";
    private static final String ROLE_COLUMNS = "r.id, r.name, r.description, r.builtin, r.created_at, "
            + "(SELECT COUNT(*) FROM user_role ur WHERE ur.role_id = r.id) AS users";
    private static final String TOKEN_COLUMNS = "id, user_id, name, created_at, expires_at, last_used_at, revoked_at, "
            + "kind";

    private final JdbcClient jdbc;

    public AccountRepository(@Qualifier("coreJdbc") JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- Benutzer

    public long countUsers() {
        return jdbc.sql("SELECT COUNT(*) FROM app_user").query(Long.class).single();
    }

    /** Aktive Benutzer, die das Recht über eine ihrer Rollen haben (oder alle Rechte). */
    public long countEnabledUsersWith(String permission) {
        return jdbc.sql("""
                        SELECT COUNT(DISTINCT u.id) FROM app_user u
                        JOIN user_role ur ON ur.user_id = u.id
                        JOIN role_permission rp ON rp.role_id = ur.role_id
                        WHERE u.enabled = TRUE AND rp.permission IN (?, ?)""")
                .params(Grants.ALL, permission).query(Long.class).single();
    }

    public List<UserAccount> users() {
        return withRoles(jdbc.sql("SELECT " + USER_COLUMNS + " FROM app_user ORDER BY username")
                .query(AccountRepository::userRow).list(), null);
    }

    public Optional<UserAccount> user(long id) {
        return one(jdbc.sql("SELECT " + USER_COLUMNS + " FROM app_user WHERE id = ?").param(id)
                .query(AccountRepository::userRow).optional());
    }

    public Optional<UserAccount> userByName(String username) {
        return one(jdbc.sql("SELECT " + USER_COLUMNS + " FROM app_user WHERE username = ?").param(username)
                .query(AccountRepository::userRow).optional());
    }

    public Optional<String> passwordHash(long id) {
        return jdbc.sql("SELECT password_hash FROM app_user WHERE id = ?").param(id).query(String.class).optional();
    }

    public long insertUser(String username, String displayName, String email, boolean enabled, String passwordHash,
                           boolean passwordChangeRequired, Instant createdAt) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO app_user (username, display_name, email, password_hash, enabled,
                                              password_change_required, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)""")
                .params(username, displayName, email, passwordHash, enabled, passwordChangeRequired,
                        Timestamp.from(createdAt))
                .update(keys, "id");
        return keys.getKeyAs(Number.class).longValue();
    }

    public void updateUser(long id, String displayName, String email, boolean enabled) {
        jdbc.sql("UPDATE app_user SET display_name = ?, email = ?, enabled = ? WHERE id = ?")
                .params(displayName, email, enabled, id).update();
    }

    public void rename(long id, String username) {
        jdbc.sql("UPDATE app_user SET username = ? WHERE id = ?").params(username, id).update();
    }

    public void updatePassword(long id, String passwordHash, boolean changeRequired) {
        jdbc.sql("UPDATE app_user SET password_hash = ?, password_change_required = ? WHERE id = ?")
                .params(passwordHash, changeRequired, id).update();
    }

    /** Nur den Hash ersetzen (höhere Iterationszahl), Pflicht zum Ändern bleibt. */
    public void rehashPassword(long id, String passwordHash) {
        jdbc.sql("UPDATE app_user SET password_hash = ? WHERE id = ?").params(passwordHash, id).update();
    }

    public void touchLogin(long id, Instant at) {
        jdbc.sql("UPDATE app_user SET last_login_at = ? WHERE id = ?").params(Timestamp.from(at), id).update();
    }

    public void deleteUser(long id) {
        jdbc.sql("DELETE FROM app_user WHERE id = ?").param(id).update();
    }

    public void setUserRoles(long userId, Collection<Long> roleIds) {
        jdbc.sql("DELETE FROM user_role WHERE user_id = ?").param(userId).update();
        for (long roleId : new TreeSet<>(roleIds)) {
            jdbc.sql("INSERT INTO user_role (user_id, role_id) VALUES (?, ?)").params(userId, roleId).update();
        }
    }

    private record UserRow(long id, String username, String displayName, String email, boolean enabled,
                           boolean changeRequired, Instant createdAt, Instant lastLoginAt) {
    }

    private static UserRow userRow(ResultSet rs, int row) throws SQLException {
        return new UserRow(rs.getLong("id"), rs.getString("username"), rs.getString("display_name"),
                rs.getString("email"), rs.getBoolean("enabled"), rs.getBoolean("password_change_required"),
                instant(rs, "created_at"), instant(rs, "last_login_at"));
    }

    private Optional<UserAccount> one(Optional<UserRow> row) {
        return row.map(r -> withRoles(List.of(r), r.id()).getFirst());
    }

    /** Rollen und Rechte dazuladen; {@code userId} = nur dieser Benutzer, {@code null} = alle. */
    private List<UserAccount> withRoles(List<UserRow> rows, Long userId) {
        Map<Long, Set<String>> roles = new HashMap<>();
        Map<Long, Set<String>> permissions = new HashMap<>();
        String sql = """
                SELECT ur.user_id, r.name, rp.permission FROM user_role ur
                JOIN app_role r ON r.id = ur.role_id
                LEFT JOIN role_permission rp ON rp.role_id = r.id""" + (userId == null ? "" : " WHERE ur.user_id = ?");
        JdbcClient.StatementSpec spec = jdbc.sql(sql);
        if (userId != null) {
            spec = spec.param(userId);
        }
        spec.query(rs -> {
            long id = rs.getLong("user_id");
            roles.computeIfAbsent(id, k -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER)).add(rs.getString("name"));
            String p = rs.getString("permission");
            if (p != null) {
                permissions.computeIfAbsent(id, k -> new HashSet<>()).add(p);
            }
        });
        List<UserAccount> out = new ArrayList<>(rows.size());
        for (UserRow r : rows) {
            out.add(new UserAccount(r.id(), r.username(), r.displayName(), r.email(), r.enabled(), r.changeRequired(),
                    r.createdAt(), r.lastLoginAt(), List.copyOf(roles.getOrDefault(r.id(), Set.of())),
                    new Grants(permissions.getOrDefault(r.id(), Set.of()))));
        }
        return out;
    }

    // ---------------------------------------------------------------- Rollen

    public List<Role> roles() {
        return withPermissions(jdbc.sql("SELECT " + ROLE_COLUMNS + " FROM app_role r ORDER BY r.builtin DESC, r.name")
                .query(AccountRepository::roleRow).list());
    }

    public Optional<Role> role(long id) {
        return jdbc.sql("SELECT " + ROLE_COLUMNS + " FROM app_role r WHERE r.id = ?").param(id)
                .query(AccountRepository::roleRow).optional().map(r -> withPermissions(List.of(r)).getFirst());
    }

    /** Ohne Beachtung der Groß-/Kleinschreibung. */
    public Optional<Role> roleByName(String name) {
        return jdbc.sql("SELECT " + ROLE_COLUMNS + " FROM app_role r WHERE LOWER(r.name) = LOWER(?)").param(name)
                .query(AccountRepository::roleRow).optional().map(r -> withPermissions(List.of(r)).getFirst());
    }

    public long insertRole(String name, String description, boolean builtin, Instant createdAt) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO app_role (name, description, builtin, created_at) VALUES (?, ?, ?, ?)")
                .params(name, description, builtin, Timestamp.from(createdAt)).update(keys, "id");
        return keys.getKeyAs(Number.class).longValue();
    }

    public void updateRole(long id, String name, String description) {
        jdbc.sql("UPDATE app_role SET name = ?, description = ? WHERE id = ?").params(name, description, id).update();
    }

    public void replacePermissions(long roleId, Collection<String> permissions) {
        jdbc.sql("DELETE FROM role_permission WHERE role_id = ?").param(roleId).update();
        for (String p : new TreeSet<>(permissions)) {
            jdbc.sql("INSERT INTO role_permission (role_id, permission) VALUES (?, ?)").params(roleId, p).update();
        }
    }

    public void deleteRole(long id) {
        jdbc.sql("DELETE FROM app_role WHERE id = ?").param(id).update();
    }

    /** Benutzer mit dieser Rolle. */
    public List<Long> roleUsers(long roleId) {
        return jdbc.sql("SELECT user_id FROM user_role WHERE role_id = ?").param(roleId).query(Long.class).list();
    }

    private static Role roleRow(ResultSet rs, int row) throws SQLException {
        return new Role(rs.getLong("id"), rs.getString("name"), rs.getString("description"),
                rs.getBoolean("builtin"), Set.of(), instant(rs, "created_at"), rs.getInt("users"));
    }

    private List<Role> withPermissions(List<Role> roles) {
        Map<Long, Set<String>> permissions = new HashMap<>();
        jdbc.sql("SELECT role_id, permission FROM role_permission").query(rs -> {
            permissions.computeIfAbsent(rs.getLong("role_id"), k -> new HashSet<>()).add(rs.getString("permission"));
        });
        return roles.stream().map(r -> new Role(r.id(), r.name(), r.description(), r.builtin(),
                permissions.getOrDefault(r.id(), Set.of()), r.createdAt(), r.users())).toList();
    }

    // ---------------------------------------------------------------- Tokens

    public void insertToken(ApiToken t) {
        jdbc.sql("INSERT INTO api_token (id, user_id, name, created_at, expires_at, kind) VALUES (?, ?, ?, ?, ?, ?)")
                .params(t.id(), t.userId(), t.name(), Timestamp.from(t.createdAt()), timestamp(t.expiresAt()),
                        t.kind().name())
                .update();
    }

    public List<ApiToken> tokens(long userId) {
        return jdbc.sql("SELECT " + TOKEN_COLUMNS + " FROM api_token WHERE user_id = ? ORDER BY created_at DESC")
                .param(userId).query(AccountRepository::token).list();
    }

    public Optional<ApiToken> token(String id) {
        return jdbc.sql("SELECT " + TOKEN_COLUMNS + " FROM api_token WHERE id = ?").param(id)
                .query(AccountRepository::token).optional();
    }

    /** @return ob ein noch gültiges Token widerrufen wurde */
    public boolean revokeToken(String id, Instant at) {
        return jdbc.sql("UPDATE api_token SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL")
                .params(Timestamp.from(at), id).update() > 0;
    }

    public void revokeAllTokens(long userId, Instant at) {
        jdbc.sql("UPDATE api_token SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL")
                .params(Timestamp.from(at), userId).update();
    }

    public void deleteToken(String id) {
        jdbc.sql("DELETE FROM api_token WHERE id = ?").param(id).update();
    }

    /** Beendete und abgelaufene Anmeldungen eines Benutzers entfernen. */
    public void deleteEndedSessions(long userId, Instant now) {
        jdbc.sql("DELETE FROM api_token WHERE user_id = ? AND kind = 'SESSION' AND (revoked_at IS NOT NULL "
                + "OR expires_at <= ?)").params(userId, Timestamp.from(now)).update();
    }

    public void touchToken(String id, Instant at) {
        jdbc.sql("UPDATE api_token SET last_used_at = ? WHERE id = ?").params(Timestamp.from(at), id).update();
    }

    private static ApiToken token(ResultSet rs, int row) throws SQLException {
        return new ApiToken(rs.getString("id"), rs.getLong("user_id"), rs.getString("name"),
                instant(rs, "created_at"), instant(rs, "expires_at"), instant(rs, "last_used_at"),
                instant(rs, "revoked_at"), ApiToken.Kind.valueOf(rs.getString("kind")));
    }

    // ---------------------------------------------------------------- intern

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }

    private static Timestamp timestamp(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }
}
