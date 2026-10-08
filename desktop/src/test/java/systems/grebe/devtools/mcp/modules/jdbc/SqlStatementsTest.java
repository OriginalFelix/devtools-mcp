package systems.grebe.devtools.mcp.modules.jdbc;

import java.util.EnumSet;

import org.junit.jupiter.api.Test;
import systems.grebe.devtools.mcp.modules.jdbc.SqlStatements.Analysis;
import systems.grebe.devtools.mcp.modules.jdbc.SqlStatements.Kind;

import static org.assertj.core.api.Assertions.assertThat;

class SqlStatementsTest {

    private static Analysis a(String sql) {
        return SqlStatements.analyze(sql);
    }

    @Test
    void classifiesByFirstKeyword() {
        assertThat(a("select * from t").primary()).isEqualTo(Kind.QUERY);
        assertThat(a("  (SELECT 1) UNION (SELECT 2)").primary()).isEqualTo(Kind.QUERY);
        assertThat(a("VALUES (1, 2)").primary()).isEqualTo(Kind.QUERY);
        assertThat(a("SHOW TABLES").primary()).isEqualTo(Kind.QUERY);
        assertThat(a("insert into t values (1)").kinds()).containsExactly(Kind.INSERT);
        assertThat(a("UPDATE t SET a = 1 WHERE id = 2").kinds()).containsExactly(Kind.UPDATE);
        assertThat(a("delete from t where id = ?").kinds()).containsExactly(Kind.DELETE);
        assertThat(a("ALTER TABLE t ADD COLUMN c INT").primary()).isEqualTo(Kind.DDL);
        assertThat(a("drop table t").primary()).isEqualTo(Kind.DDL);
        assertThat(a("TRUNCATE TABLE t").primary()).isEqualTo(Kind.DDL);
        assertThat(a("CALL proc(1)").primary()).isEqualTo(Kind.OTHER);
        assertThat(a("GRANT SELECT ON t TO bob").primary()).isEqualTo(Kind.OTHER);
        assertThat(a("BEGIN NULL; END;").primary()).isEqualTo(Kind.OTHER);
    }

    @Test
    void readOnlyQueriesStayReadOnly() {
        assertThat(a("SELECT 'DELETE FROM t; DROP TABLE x' AS s FROM dual").readOnly()).isTrue();
        assertThat(a("SELECT \"update\" FROM t -- delete everything\n WHERE x = 1").readOnly()).isTrue();
        assertThat(a("SELECT * FROM t /* ; DROP TABLE t; */ WHERE a = 1").readOnly()).isTrue();
        assertThat(a("SELECT * FROM t FOR UPDATE").readOnly()).isTrue();
        assertThat(a("SELECT * FROM t FOR NO KEY UPDATE SKIP LOCKED").readOnly()).isTrue();
        assertThat(a("SELECT * FROM V$SESSION").readOnly()).isTrue();
        assertThat(a("WITH x AS (SELECT 1) SELECT * FROM x").readOnly()).isTrue();
        assertThat(a("SELECT q'[it's]' FROM dual").readOnly()).isTrue();
    }

    @Test
    void trailingSemicolonsAndCommentsAreRemoved() {
        Analysis x = a("SELECT 1;  ;\n-- fertig\n");
        assertThat(x.statements()).isEqualTo(1);
        assertThat(x.sql()).isEqualTo("SELECT 1");
        assertThat(a("SELECT ';'").sql()).isEqualTo("SELECT ';'");
    }

    @Test
    void detectsSeveralStatements() {
        assertThat(a("SELECT 1; DROP TABLE t").statements()).isEqualTo(2);
        assertThat(a("SELECT 1; DROP TABLE t").kinds()).contains(Kind.DDL);
        assertThat(a("").statements()).isZero();
        assertThat(a("-- nur Kommentar").statements()).isZero();
    }

    @Test
    void stringsThatEndDifferentlyPerDatabaseCountWithMostRights() {
        // MySQL: \' maskiert – SQL Server/PostgreSQL: Zeichenkette endet beim Backslash, danach folgt DROP
        Analysis x = a("SELECT 'a\\'; DROP TABLE t; -- '");
        assertThat(x.statements()).isEqualTo(2);
        assertThat(x.readOnly()).isFalse();
        // MySQL: # beginnt einen Kommentar, das Hochkomma dahinter öffnet dort keine Zeichenkette
        Analysis y = a("SELECT a #'\n; DROP TABLE t; -- '");
        assertThat(y.statements()).isEqualTo(2);
        // MySQL führt /*! … */ aus
        assertThat(a("SELECT 1 /*! ; DELETE FROM t */").kinds()).contains(Kind.DELETE);
        // MySQL: --x ist kein Kommentar
        assertThat(a("SELECT 1--1; DELETE FROM t").statements()).isEqualTo(2);
        // H2: // beginnt einen Kommentar
        assertThat(a("SELECT 1 // '\n; DROP TABLE t").statements()).isEqualTo(2);
    }

    @Test
    void postgresDollarQuotesAndEscapeStrings() {
        assertThat(a("SELECT $$it's; DROP TABLE t$$").readOnly()).isTrue(); // $$ kennen PostgreSQL und H2
        assertThat(a("SELECT E'it\\'s ok' FROM t").readOnly()).isTrue();
        assertThat(a("SELECT * FROM t WHERE id = $1").readOnly()).isTrue();
        // ; in $tag$ oder E'…' lesen andere Datenbanken als Trenner – vorsichtig zwei Anweisungen
        assertThat(a("SELECT $body$ ; DELETE FROM t $body$").statements()).isEqualTo(2);
        assertThat(a("SELECT E'it\\'s; ok' FROM t").statements()).isEqualTo(2);
    }

    @Test
    void embeddedChangesNeedTheirOwnRights() {
        assertThat(a("WITH d AS (DELETE FROM t RETURNING *) SELECT * FROM d").kinds())
                .isEqualTo(EnumSet.of(Kind.QUERY, Kind.DELETE));
        assertThat(a("WITH x AS (SELECT id FROM a) DELETE FROM t WHERE id IN (SELECT id FROM x)").primary())
                .isEqualTo(Kind.DELETE);
        assertThat(a("INSERT INTO t (id, n) VALUES (1, 'x') ON CONFLICT (id) DO UPDATE SET n = excluded.n").kinds())
                .isEqualTo(EnumSet.of(Kind.INSERT, Kind.UPDATE));
        assertThat(a("INSERT INTO t VALUES (1) ON DUPLICATE KEY UPDATE n = 2").kinds()).contains(Kind.UPDATE);
        assertThat(a("INSERT IGNORE INTO t VALUES (1)").kinds()).containsExactly(Kind.INSERT);
        assertThat(a("MERGE INTO t USING s ON t.id = s.id WHEN MATCHED THEN UPDATE SET a = s.a "
                + "WHEN NOT MATCHED THEN INSERT (id) VALUES (s.id)").kinds()).isEqualTo(EnumSet.of(Kind.UPDATE, Kind.INSERT));
        assertThat(a("EXPLAIN ANALYZE DELETE FROM t").kinds()).contains(Kind.DELETE);
        assertThat(a("SELECT * INTO neu FROM t").kinds()).contains(Kind.OTHER);
        assertThat(a("SELECT * FROM t INTO OUTFILE '/tmp/x'").kinds()).contains(Kind.OTHER);
    }

    @Test
    void ddlIsNotScannedForForeignKeyActions() {
        assertThat(a("CREATE TABLE b (id INT, a_id INT REFERENCES a(id) ON DELETE CASCADE ON UPDATE CASCADE)").kinds())
                .containsExactly(Kind.DDL);
    }

    @Test
    void whereIsDetected() {
        assertThat(a("DELETE FROM t").where()).isFalse();
        assertThat(a("DELETE FROM t WHERE id = 1").where()).isTrue();
        assertThat(a("UPDATE t SET a = 'where'").where()).isFalse();
        // Backslash in der Zeichenkette: eine Lesart sieht das WHERE – das genügt für den Schutz
        assertThat(a("UPDATE t SET p = 'C:\\dir\\' WHERE id = 1").where()).isTrue();
    }
}
