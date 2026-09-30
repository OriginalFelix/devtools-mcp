package systems.grebe.devtools.mcp.backend.account;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** Benutzer und Tokens in der Core-Datenbank (Tabellen {@code app_user}, {@code api_token}). */
@Repository
public class AccountRepository {

    private static final String USER_COLUMNS = "id, username, display_name, email, role, enabled, created_at";
    private static final String TOKEN_COLUMNS = "id, user_id, name, created_at, expires_at, last_used_at, revoked_at";

    private final JdbcClient jdbc;

    public AccountRepository(@Qualifier("coreJdbc") JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- Benutzer

    public long countUsers() {
        return jdbc.sql("SELECT COUNT(*) FROM app_user").query(Long.class).single();
    }

    public long countEnabledAdmins() {
        return jdbc.sql("SELECT COUNT(*) FROM app_user WHERE role = 'ADMIN' AND enabled = TRUE")
                .query(Long.class).single();
    }

    public List<UserAccount> users() {
        return jdbc.sql("SELECT " + USER_COLUMNS + " FROM app_user ORDER BY username").query(AccountRepository::user)
                .list();
    }

    public Optional<UserAccount> user(long id) {
        return jdbc.sql("SELECT " + USER_COLUMNS + " FROM app_user WHERE id = ?").param(id)
                .query(AccountRepository::user).optional();
    }

    public Optional<UserAccount> userByName(String username) {
        return jdbc.sql("SELECT " + USER_COLUMNS + " FROM app_user WHERE username = ?").param(username)
                .query(AccountRepository::user).optional();
    }

    public Optional<String> passwordHash(long id) {
        return jdbc.sql("SELECT password_hash FROM app_user WHERE id = ?").param(id).query(String.class).optional();
    }

    public long insertUser(String username, String displayName, String email, Role role, boolean enabled,
                           String passwordHash, Instant createdAt) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO app_user (username, display_name, email, password_hash, role, enabled, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)""")
                .params(username, displayName, email, passwordHash, role.name(), enabled, Timestamp.from(createdAt))
                .update(keys, "id");
        return keys.getKeyAs(Number.class).longValue();
    }

    public void updateUser(long id, String displayName, String email, Role role, boolean enabled) {
        jdbc.sql("UPDATE app_user SET display_name = ?, email = ?, role = ?, enabled = ? WHERE id = ?")
                .params(displayName, email, role.name(), enabled, id).update();
    }

    public void updatePassword(long id, String passwordHash) {
        jdbc.sql("UPDATE app_user SET password_hash = ? WHERE id = ?").params(passwordHash, id).update();
    }

    public void deleteUser(long id) {
        jdbc.sql("DELETE FROM app_user WHERE id = ?").param(id).update();
    }

    private static UserAccount user(ResultSet rs, int row) throws SQLException {
        return new UserAccount(rs.getLong("id"), rs.getString("username"), rs.getString("display_name"),
                rs.getString("email"), Role.valueOf(rs.getString("role")), rs.getBoolean("enabled"),
                instant(rs, "created_at"));
    }

    // ---------------------------------------------------------------- Tokens

    public void insertToken(ApiToken t) {
        jdbc.sql("INSERT INTO api_token (id, user_id, name, created_at, expires_at) VALUES (?, ?, ?, ?, ?)")
                .params(t.id(), t.userId(), t.name(), Timestamp.from(t.createdAt()), timestamp(t.expiresAt()))
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

    public void touchToken(String id, Instant at) {
        jdbc.sql("UPDATE api_token SET last_used_at = ? WHERE id = ?").params(Timestamp.from(at), id).update();
    }

    private static ApiToken token(ResultSet rs, int row) throws SQLException {
        return new ApiToken(rs.getString("id"), rs.getLong("user_id"), rs.getString("name"),
                instant(rs, "created_at"), instant(rs, "expires_at"), instant(rs, "last_used_at"),
                instant(rs, "revoked_at"));
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
