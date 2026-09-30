package systems.grebe.devtools.mcp.backend.skills;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import systems.grebe.devtools.mcp.modules.skills.SkillViews;

/**
 * Bestehende Datenbanken aus der Zeit vor dem User-Scoping (Fixture = Script-Export einer echten Datenbank der App):
 * Nach dem Start gehören alle Skills dem aktuellen Benutzer, gleichnamige Skills anderer Benutzer sind möglich.
 */
class SkillSchemaMigrationTest {

    @TempDir
    Path home;

    private String loadV1() throws Exception {
        String url = "jdbc:h2:file:" + home.resolve("skills").toAbsolutePath();
        String script = new String(getClass().getResourceAsStream("/skills/schema-v1.sql").readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        Path tmp = Files.writeString(home.resolve("v1.sql"), script);
        JdbcTemplate jdbc = jdbc(url);
        jdbc.execute("runscript from '" + tmp.toAbsolutePath() + "'");
        assertThat(jdbc.queryForObject("select count(*) from skill", Long.class)).isEqualTo(3);
        return url;
    }

    private static JdbcTemplate jdbc(String url) {
        SimpleDriverDataSource ds = DataSourceBuilder.create().type(SimpleDriverDataSource.class)
                .url(url).username("sa").password("").build();
        return new JdbcTemplate(ds);
    }

    @Test
    void existingSkillsAreAssignedToTheCurrentUser() throws Exception {
        String url = loadV1();
        try (var ctx = SkillTestSupport.start(home, null, "felix@example.com", false, "felix@example.com")) {
            SkillService service = ctx.getBean(SkillService.class);
            assertThat(service.overview()).extracting(SkillViews.Summary::name)
                    .containsExactlyInAnyOrder("wildfly-heap-leak", "gradle-toolchain-jdk", "antwortstil-felix");
            assertThat(service.view("wildfly-heap-leak", "references/jcmd.md")).contains("GC.class_histogram");
            assertThat(service.history("wildfly-heap-leak", null)).contains("write_file", "patch", "create");
            // Revisionen von vorher haben keinen Autor – wird ohne Klammer angezeigt
            assertThat(service.history("wildfly-heap-leak", null)).doesNotContain("(null)");
        }
        JdbcTemplate jdbc = jdbc(url);
        assertThat(jdbc.queryForList("select distinct owner from skill", String.class))
                .containsExactly("felix@example.com");

        // Neue Namensregel: gleicher Name für einen anderen Benutzer erlaubt
        try (var ctx = SkillTestSupport.start(home, null, "bernd@example.com", false, "bernd@example.com")) {
            SkillService bernd = ctx.getBean(SkillService.class);
            assertThat(bernd.overview()).isEmpty();
            bernd.create("wildfly-heap-leak", "Bernds Variante.", "x", null, null, 5_000);
        }
        List<Map<String, Object>> constraints = jdbc.queryForList("select constraint_name from "
                + "information_schema.table_constraints where table_name = 'SKILL' and constraint_type = 'UNIQUE'");
        assertThat(constraints).extracting(m -> m.get("CONSTRAINT_NAME"))
                .contains("UK_SKILL_OWNER_NAME").doesNotContain("UK_SKILL_NAME");
    }

    @Test
    void migrationRunsOnlyOnce() throws Exception {
        String url = loadV1();
        try (var ctx = SkillTestSupport.start(home, null, "felix@example.com", false, "felix@example.com")) {
            ctx.getBean(SkillService.class).create("neu", "d", "c", null, null, 5_000);
        }
        // Zweiter Start mit anderem Benutzer darf die Zuordnung nicht erneut umschreiben
        try (var ctx = SkillTestSupport.start(home, null, "bernd@example.com", false, "bernd@example.com")) {
            assertThat(ctx.getBean(SkillService.class).overview()).isEmpty();
        }
        assertThat(jdbc(url).queryForList("select distinct owner from skill", String.class))
                .containsExactly("felix@example.com");
    }

    @Test
    void withoutUserTheOldDatabaseIsNotTouched() throws Exception {
        String url = loadV1();
        assertThatThrownBy(() -> SkillTestSupport.start(home, null, null, false, null))
                .hasStackTraceContaining("Benutzer-E-Mail");
        JdbcTemplate jdbc = jdbc(url);
        assertThat(jdbc.queryForObject("select count(*) from information_schema.columns where table_name = 'SKILL' "
                + "and column_name = 'OWNER'", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from skill", Long.class)).isEqualTo(3);
    }
}
