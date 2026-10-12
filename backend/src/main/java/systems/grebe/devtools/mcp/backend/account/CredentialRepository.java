package systems.grebe.devtools.mcp.backend.account;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Zugangsdaten der Benutzer in der Core-Datenbank (Tabelle {@code credential}); {@code secret}, {@code client_secret}
 * und {@code refresh_token} verschlüsselt.
 */
@Repository
public class CredentialRepository {

    private static final String COLUMNS = "id, name, url, username, secret, description, auth, issuer, client_id, "
            + "client_secret, scopes, refresh_token, expires_at";

    /** Gespeicherte Zeile; Geheimnisse so, wie sie in der Datenbank stehen (verschlüsselt). */
    public record Row(long id, String name, String url, String username, String secret, String description,
                      String auth, String issuer, String clientId, String clientSecret, String scopes,
                      String refreshToken, Instant expiresAt) {
    }

    private final JdbcClient jdbc;

    public CredentialRepository(@Qualifier("coreJdbc") JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Row> list(long userId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM credential WHERE user_id = ? ORDER BY sort_order, id")
                .param(userId).query(CredentialRepository::row).list();
    }

    public Optional<Row> find(long userId, long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM credential WHERE user_id = ? AND id = ?")
                .params(userId, id).query(CredentialRepository::row).optional();
    }

    /** Ersetzt alle Zugangsdaten des Benutzers (im Aufrufer in einer Transaktion). */
    public void replace(long userId, List<Row> rows, Instant now) {
        jdbc.sql("DELETE FROM credential WHERE user_id = ?").param(userId).update();
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            jdbc.sql("""
                            INSERT INTO credential (user_id, sort_order, name, url, username, secret, description, auth,
                                                    issuer, client_id, client_secret, scopes, refresh_token, expires_at,
                                                    updated_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(userId, i, r.name(), r.url(), r.username(), r.secret(), r.description(), r.auth(),
                            r.issuer(), r.clientId(), r.clientSecret(), r.scopes(), r.refreshToken(),
                            timestamp(r.expiresAt()), Timestamp.from(now))
                    .update();
        }
    }

    /** Neue Tokens nach dem Erneuern (Geheimnisse verschlüsselt). */
    public void updateTokens(long id, String secret, String refreshToken, Instant expiresAt, Instant now) {
        jdbc.sql("UPDATE credential SET secret = ?, refresh_token = ?, expires_at = ?, updated_at = ? WHERE id = ?")
                .params(secret, refreshToken, timestamp(expiresAt), Timestamp.from(now), id).update();
    }

    private static Row row(ResultSet rs, int n) throws SQLException {
        Timestamp expires = rs.getTimestamp("expires_at");
        return new Row(rs.getLong("id"), rs.getString("name"), rs.getString("url"), rs.getString("username"),
                rs.getString("secret"), rs.getString("description"), rs.getString("auth"), rs.getString("issuer"),
                rs.getString("client_id"), rs.getString("client_secret"), rs.getString("scopes"),
                rs.getString("refresh_token"), expires == null ? null : expires.toInstant());
    }

    private static Timestamp timestamp(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }
}
