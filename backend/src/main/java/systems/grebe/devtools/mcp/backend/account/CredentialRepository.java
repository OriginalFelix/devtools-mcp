package systems.grebe.devtools.mcp.backend.account;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Zugangsdaten der Benutzer in der Core-Datenbank (Tabelle {@code credential}); {@code secret} verschlüsselt. */
@Repository
public class CredentialRepository {

    /** Gespeicherte Zeile; {@code secret} so, wie es in der Datenbank steht (verschlüsselt). */
    public record Row(long id, String name, String url, String username, String secret, String description) {
    }

    private final JdbcClient jdbc;

    public CredentialRepository(@Qualifier("coreJdbc") JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Row> list(long userId) {
        return jdbc.sql("""
                        SELECT id, name, url, username, secret, description FROM credential
                        WHERE user_id = ? ORDER BY sort_order, id""")
                .param(userId)
                .query((rs, row) -> new Row(rs.getLong("id"), rs.getString("name"), rs.getString("url"),
                        rs.getString("username"), rs.getString("secret"), rs.getString("description")))
                .list();
    }

    /** Ersetzt alle Zugangsdaten des Benutzers (im Aufrufer in einer Transaktion). */
    public void replace(long userId, List<Row> rows, Instant now) {
        jdbc.sql("DELETE FROM credential WHERE user_id = ?").param(userId).update();
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            jdbc.sql("""
                            INSERT INTO credential (user_id, sort_order, name, url, username, secret, description, updated_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(userId, i, r.name(), r.url(), r.username(), r.secret(), r.description(), Timestamp.from(now))
                    .update();
        }
    }
}
