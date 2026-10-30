package systems.grebe.devtools.mcp.modules.p6spy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import systems.grebe.devtools.mcp.core.LocalFiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** p6spy-Protokolle in den Standardformaten lesen und auswerten. */
class P6SpyToolsTest {

    @TempDir
    Path dir;

    @Test
    void parsesSingleLineFormatWithAndWithoutUrlAndCustomFormat() {
        var withUrl = P6SpyLog.parse(0, "1700000000000|3|statement|connection 7|url jdbc:h2:mem:t|"
                + "select a || b from t where id=?|select a || b from t where id=42");
        assertThat(withUrl.millis()).isEqualTo(3);
        assertThat(withUrl.connection()).isEqualTo("7");
        assertThat(withUrl.prepared()).isEqualTo("select a || b from t where id=?");
        assertThat(withUrl.sql()).isEqualTo("select a || b from t where id=42");

        var old = P6SpyLog.parse(0, "1700000000000|0|commit|connection 1||");
        assertThat(old.category()).isEqualTo("commit");
        assertThat(old.sql()).isEmpty();

        var custom = P6SpyLog.parse(0, "12:00:01|5|statement|connection3|update t set x='a|b' where id=1");
        assertThat(custom.connection()).isEqualTo("3");
        assertThat(custom.sql()).isEqualTo("update t set x='a|b' where id=1");

        assertThat(P6SpyLog.parse(0, "\tat com.example.Foo.bar(Foo.java:12)")).isNull();
        assertThat(P6SpyLog.normalize("select * from t1 where id in (1, 2, 3) and n = 'x''y'"))
                .isEqualTo("select * from t1 where id in (?) and n = ?");
    }

    @Test
    void statementsFiltersAndContinuesWithSince() throws IOException {
        Path log = dir.resolve("spy.log");
        Files.writeString(log, String.join("\n",
                line(1, 2, "select * from customer where id=?", "select * from customer where id=1"),
                "java.lang.Exception: stack",
                line(1, 250, "select * from orders", "select * from orders"),
                "1700000000100|0|commit|connection 1|url jdbc:h2:mem:t||", ""));
        P6SpyTools tools = new P6SpyTools(new LocalFiles(List.of(dir.toString()), "Test"), 1 << 20);

        String slow = tools.statements("spy.log", 100L, null, null, null, null, null);
        assertThat(slow).contains("1 passende von 3 Einträgen", "250 ms", "select * from orders")
                .doesNotContain("customer");
        assertThat(tools.statements("spy.log", null, "CUSTOMER", null, null, null, null))
                .contains("where id=1");

        long since = Long.parseLong(slow.substring(slow.lastIndexOf("since=") + 6));
        assertThat(since).isEqualTo(Files.size(log));
        Files.writeString(log, line(2, 9, "delete from t", "delete from t") + "\n", StandardOpenOption.APPEND);
        assertThat(tools.statements("spy.log", null, null, null, null, since, null))
                .contains("1 passende von 1 Einträgen", "delete from t").doesNotContain("orders");

        assertThatThrownBy(() -> tools.statements("/etc/hosts", null, null, null, null, null, null))
                .hasMessageContaining("nicht freigegeben");
    }

    @Test
    void analyzeGroupsByStatementAndFlagsNPlusOne() throws IOException {
        StringBuilder sb = new StringBuilder(line(1, 40, "select * from parent", "select * from parent")).append('\n');
        for (int i = 1; i <= 6; i++) {
            sb.append(line(1, 2, "select * from child where parent_id=?", "select * from child where parent_id=" + i))
                    .append('\n');
        }
        // abgeschnittene letzte Zeile wird noch geschrieben und zählt erst beim nächsten Aufruf
        sb.append("1700000000000|1|statement|conn");
        Path log = dir.resolve("spy.log");
        Files.writeString(log, sb);
        P6SpyTools tools = new P6SpyTools(new LocalFiles(List.of(dir.toString()), "Test"), 1 << 20);

        String result = tools.analyze(log.toString(), null, "count", null, null);
        assertThat(result).contains("7 Statements, 2 verschiedene, zusammen 52 ms",
                "6×  Summe 12 ms", "select * from child where parent_id=?", "N+1-Verdacht",
                "bis zu 6× in Folge, 6× insgesamt");
        assertThat(result.indexOf("6×  Summe")).isLessThan(result.indexOf("1×  Summe"));
        assertThat(result).endsWith("since=" + (Files.size(log) - "1700000000000|1|statement|conn".length()));
        assertThat(tools.analyze(log.toString(), null, null, 7, null)).doesNotContain("N+1-Verdacht");
    }

    @Test
    void readsOnlyTheTailOfLargeLogs() throws IOException {
        Path log = dir.resolve("spy.log");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append(line(1, 1, "select " + i, "select " + i)).append('\n');
        }
        Files.writeString(log, sb);
        P6SpyLog.Chunk chunk = P6SpyLog.read(log, null, 500);
        assertThat(chunk.skipped()).isTrue();
        assertThat(chunk.entries()).isNotEmpty().allSatisfy(e -> assertThat(e.offset()).isGreaterThan(0));
        assertThat(chunk.entries().getLast().sql()).isEqualTo("select 99");
        assertThat(P6SpyLog.read(log, Files.size(log) + 10, 1 << 20).rotated()).isTrue();
    }

    private static String line(int conn, long ms, String prepared, String sql) {
        return "1700000000000|" + ms + "|statement|connection " + conn + "|url jdbc:h2:mem:t|" + prepared + "|" + sql;
    }
}
