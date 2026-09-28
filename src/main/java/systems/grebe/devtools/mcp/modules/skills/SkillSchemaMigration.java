package systems.grebe.devtools.mcp.modules.skills;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hebt eine Skill-Datenbank aus der Zeit vor dem User-Scoping an: Spalte {@code owner} ergänzen, alle vorhandenen
 * Skills dem aktuellen Benutzer zuordnen und die alte, global eindeutige Namensregel {@code uk_skill_name} entfernen.
 *
 * <p>Läuft vor Hibernate über reines JDBC, weil {@code hbm2ddl=update} eine neue Pflichtspalte bei vorhandenen Zeilen
 * nicht befüllen und alte Unique-Constraints nicht entfernen kann. Die neue Regel (Name je Eigentümer eindeutig) legt
 * Hibernate anschließend selbst an. Idempotent: Hat die Tabelle {@code owner} schon, passiert nichts.
 */
final class SkillSchemaMigration {

    private static final Logger LOG = LoggerFactory.getLogger(SkillSchemaMigration.class);

    private SkillSchemaMigration() {
    }

    /** @return Beschreibung der Migration oder leer, wenn nichts zu tun war. */
    static Optional<String> migrate(Connection con, Supplier<Optional<String>> owner) throws SQLException {
        DatabaseMetaData meta = con.getMetaData();
        Optional<String> table = findTable(meta, "skill");
        if (table.isEmpty() || hasColumn(meta, table.get(), "owner")) {
            return Optional.empty();
        }
        String email = owner.get().orElseThrow(() -> new IllegalStateException("Die Skill-Datenbank enthält Skills "
                + "ohne Eigentümer (Stand vor dem User-Scoping). Für die Übernahme wird die Benutzer-E-Mail "
                + "gebraucht: im Modul „Skills“ eintragen oder git config --global user.email setzen."));
        String t = table.get();
        boolean autoCommit = con.getAutoCommit();
        con.setAutoCommit(false);
        try (Statement st = con.createStatement()) {
            st.executeUpdate("alter table " + t + " add owner varchar(" + SkillUser.MAX_EMAIL + ")");
            int rows;
            try (PreparedStatement ps = con.prepareStatement("update " + t + " set owner = ?")) {
                ps.setString(1, email);
                rows = ps.executeUpdate();
            }
            st.executeUpdate(notNull(meta, t));
            // Alte Regel „Name global eindeutig“ – würde verhindern, dass zwei Benutzer gleichnamige Skills haben
            st.executeUpdate("alter table " + t + " drop constraint uk_skill_name");
            con.commit();
            String msg = rows + " vorhandene Skill(s) dem Benutzer " + email + " zugeordnet";
            LOG.info("Skill-Datenbank auf User-Scoping migriert: {}", msg);
            return Optional.of(msg);
        } catch (SQLException | RuntimeException e) {
            con.rollback();
            throw e;
        } finally {
            con.setAutoCommit(autoCommit);
        }
    }

    private static String notNull(DatabaseMetaData meta, String table) throws SQLException {
        String product = meta.getDatabaseProductName().toLowerCase(Locale.ROOT);
        String type = "varchar(" + SkillUser.MAX_EMAIL + ")";
        if (product.contains("microsoft")) {
            return "alter table " + table + " alter column owner " + type + " not null";
        }
        if (product.contains("mysql") || product.contains("mariadb") || product.contains("oracle")) {
            return "alter table " + table + " modify owner " + type + " not null";
        }
        return "alter table " + table + " alter column owner set not null"; // H2, PostgreSQL
    }

    /** Tabellenname in der Schreibweise der Datenbank (H2 groß, PostgreSQL klein). */
    static Optional<String> findTable(DatabaseMetaData meta, String name) throws SQLException {
        try (ResultSet rs = meta.getTables(null, null, "%", new String[] {"TABLE"})) {
            while (rs.next()) {
                String schema = rs.getString("TABLE_SCHEM");
                if (name.equalsIgnoreCase(rs.getString("TABLE_NAME"))
                        && (schema == null || !schema.equalsIgnoreCase("INFORMATION_SCHEMA"))) {
                    return Optional.of(rs.getString("TABLE_NAME"));
                }
            }
        }
        return Optional.empty();
    }

    static boolean hasColumn(DatabaseMetaData meta, String table, String column) throws SQLException {
        try (ResultSet rs = meta.getColumns(null, null, table, "%")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("COLUMN_NAME"))) {
                    return true;
                }
            }
        }
        return false;
    }
}
