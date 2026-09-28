package systems.grebe.devtools.mcp.modules.skills;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import systems.grebe.devtools.mcp.config.ModuleSettings;
import systems.grebe.devtools.mcp.config.SettingsStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Service und Spring-Data-Repositories gegen eine echte H2-Datei in einem schlanken Spring-Kontext – mit genau der
 * Persistenz-Konfiguration der App ({@link SkillsPersistenceConfig}) und ohne Test-Transaktion, damit Commit,
 * Rollback und Bulk-Updates so wirken wie im Betrieb.
 */
class SkillServiceTest {

    @SpringBootConfiguration
    @ImportAutoConfiguration({HibernateJpaAutoConfiguration.class, DataJpaRepositoriesAutoConfiguration.class,
            TransactionAutoConfiguration.class})
    @Import({SkillsPersistenceConfig.class, SkillService.class})
    static class SkillsOnly {
    }

    @TempDir
    Path home;

    ConfigurableApplicationContext context;
    SkillService service;
    JdbcTemplate jdbc;

    static ConfigurableApplicationContext start(Path home, Map<String, String> moduleValues) {
        SettingsStore store = new SettingsStore(home);
        if (!moduleValues.isEmpty()) {
            store.saveModule(SkillsModule.ID, new ModuleSettings(true, Set.of(), moduleValues), Set.of());
        }
        return new SpringApplicationBuilder(SkillsOnly.class)
                .web(WebApplicationType.NONE)
                .initializers(ctx -> ctx.getBeanFactory().registerSingleton("settingsStore", store))
                .run();
    }

    @BeforeEach
    void setUp() {
        context = start(home, Map.of());
        service = context.getBean(SkillService.class);
        jdbc = new JdbcTemplate(context.getBean(javax.sql.DataSource.class));
    }

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    private void createHeapSkill() {
        service.create("wildfly-heap-leak", "Verwenden, wenn der WildFly-Heap wächst.",
                "## Schritte\n1. jvm_heap zweimal vergleichen\n2. visualvm_heap_analyze\n", "software-development",
                List.of("WildFly", "heap", "heap", " Leak Suche "), 5_000);
    }

    private long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }

    @Test
    void defaultIsH2FileInSettingsFolder() {
        SkillsPersistenceConfig.Status status = context.getBean(SkillsPersistenceConfig.Status.class);
        assertThat(status.available()).isTrue();
        assertThat(status.effective().jdbcUrl()).isEqualTo("jdbc:h2:file:" + home.toAbsolutePath().resolve("skills"));
        createHeapSkill();
        assertThat(home.resolve("skills.mv.db")).exists();
    }

    @Test
    void createListAndView() {
        assertThat(service.list(null, null)).contains("Noch keine Skills", "skills_create");

        createHeapSkill();

        assertThat(service.list(null, null))
                .contains("software-development:", "wildfly-heap-leak: Verwenden, wenn der WildFly-Heap wächst.",
                        "[wildfly, heap, leak-suche]");
        // Suche über Name, Beschreibung, Tags und Inhalt, unabhängig von Groß-/Kleinschreibung
        assertThat(service.list("VISUALVM", null)).contains("wildfly-heap-leak");
        assertThat(service.list("leak-suche", null)).contains("wildfly-heap-leak");
        assertThat(service.list("heap", "software-development")).contains("wildfly-heap-leak");
        assertThat(service.list("gradle", null)).contains("Keine Skills gefunden für 'gradle'");
        assertThat(service.list(null, "devops")).contains("Keine Skills gefunden");

        assertThat(service.view("wildfly-heap-leak", null))
                .startsWith("---\nname: wildfly-heap-leak\n")
                .contains("category: software-development", "revision: 1", "1. jvm_heap zweimal vergleichen")
                .doesNotContain("Zusatzdateien");
    }

    @Test
    void createRejectsDuplicatesAndInvalidInput() {
        createHeapSkill();
        assertThatThrownBy(() -> service.create("wildfly-heap-leak", "x", "y", null, null, 5_000))
                .hasMessageContaining("existiert bereits").hasMessageContaining("skills_patch");
        assertThatThrownBy(() -> service.create("Mit Leerzeichen", "x", "y", null, null, 5_000))
                .hasMessageContaining("Ungültiger Skill-Name");
        assertThatThrownBy(() -> service.create("ok", " ", "y", null, null, 5_000))
                .hasMessageContaining("'description' fehlt");
        assertThatThrownBy(() -> service.create("ok", "x", "y".repeat(5_001), null, null, 5_000))
                .hasMessageContaining("max. 5000").hasMessageContaining("skills_write_file");
        assertThatThrownBy(() -> service.create("ok", "x", "y", "Kein Slug!", null, 5_000))
                .hasMessageContaining("Ungültige Kategorie");
        assertThat(count("skill")).isEqualTo(1);
    }

    @Test
    void patchReplacesExactlyOnceAndRecordsHistory() {
        createHeapSkill();

        assertThat(service.patch("wildfly-heap-leak", "2. visualvm_heap_analyze\n",
                "2. visualvm_heap_analyze\n3. Pfad zur GC-Wurzel prüfen\n", null, null, "GC-Wurzel ergänzt", null, 5_000))
                .contains("1 Stelle(n) ersetzt, Revision 2");
        assertThat(service.view("wildfly-heap-leak", null)).contains("3. Pfad zur GC-Wurzel prüfen", "revision: 2");

        assertThatThrownBy(() -> service.patch("wildfly-heap-leak", "gibt es nicht", "x", null, null, null, null, 5_000))
                .hasMessageContaining("nicht vor").hasMessageContaining("skills_view");
        // "heap" steht in jvm_heap und visualvm_heap_analyze
        assertThatThrownBy(() -> service.patch("wildfly-heap-leak", "heap", "Heap", null, null, null, null, 5_000))
                .hasMessageContaining("2-mal").hasMessageContaining("replace_all");
        assertThat(service.patch("wildfly-heap-leak", "heap", "Heap", true, null, null, null, 5_000))
                .contains("2 Stelle(n)");
        assertThat(service.view("wildfly-heap-leak", null)).contains("jvm_Heap", "visualvm_Heap_analyze");

        assertThat(service.history("wildfly-heap-leak", null))
                .contains("1  ", "create", "2  ", "patch  – GC-Wurzel ergänzt", "3  ");
        assertThat(service.history("wildfly-heap-leak", 1)).contains("Revision 1 (create").doesNotContain("GC-Wurzel");
        assertThat(service.history("wildfly-heap-leak", 2)).contains("GC-Wurzel");
        assertThatThrownBy(() -> service.history("wildfly-heap-leak", 9)).hasMessageContaining("keine Revision 9");
    }

    @Test
    void replaceAllCountsEveryOccurrence() {
        service.create("dups", "d", "a a a", null, null, 5_000);
        assertThat(service.patch("dups", "a", "b", true, null, null, null, 5_000)).contains("3 Stelle(n)");
        assertThat(service.view("dups", null)).endsWith("b b b");
    }

    @Test
    void failedChangeRollsBackCompletely() {
        createHeapSkill();
        // Patch ergäbe einen zu langen Inhalt: weder Inhalt noch Revision dürfen sich ändern
        assertThatThrownBy(() -> service.patch("wildfly-heap-leak", "Schritte", "x".repeat(5_000), null, null, null,
                null, 5_000)).hasMessageContaining("max. 5000");
        assertThat(service.view("wildfly-heap-leak", null)).contains("revision: 1", "## Schritte");
        assertThat(count("skill_revision")).isEqualTo(1);
    }

    @Test
    void expectedRevisionPreventsLostUpdates() {
        createHeapSkill();
        service.patch("wildfly-heap-leak", "Schritte", "Ablauf", null, null, null, 1, 5_000);
        assertThatThrownBy(() -> service.patch("wildfly-heap-leak", "Ablauf", "Schritte", null, null, null, 1, 5_000))
                .hasMessageContaining("inzwischen geändert (Revision 2 statt 1)");
        assertThatThrownBy(() -> service.update("wildfly-heap-leak", "neu", null, null, null, null, 1, 5_000))
                .hasMessageContaining("inzwischen geändert");
    }

    @Test
    void updateChangesOnlyGivenFields() {
        createHeapSkill();
        assertThat(service.update("wildfly-heap-leak", null, null, null, null, null, null, 5_000))
                .contains("Keine Änderung");
        assertThat(service.update("wildfly-heap-leak", "Neue Beschreibung", null, "", List.of(), "aufgeräumt", null,
                5_000)).contains("Revision 2");
        String view = service.view("wildfly-heap-leak", null);
        assertThat(view).contains("description: Neue Beschreibung", "1. jvm_heap zweimal vergleichen")
                .doesNotContain("category:", "tags:");
        assertThat(service.list(null, null)).contains("(ohne Kategorie):");
    }

    @Test
    void supportingFiles() {
        createHeapSkill();
        assertThat(service.writeFile("wildfly-heap-leak", "references/jcmd.md", "GC.class_histogram", null, 5_000))
                .contains("angelegt (Revision 2)");
        assertThat(service.view("wildfly-heap-leak", null)).contains("Zusatzdateien", "references/jcmd.md");
        assertThat(service.view("wildfly-heap-leak", "references/jcmd.md")).endsWith("GC.class_histogram");

        service.patch("wildfly-heap-leak", "GC.class_histogram", "GC.class_histogram -all", null,
                "references/jcmd.md", null, null, 5_000);
        assertThat(service.view("wildfly-heap-leak", "references/jcmd.md")).endsWith("-all");
        // Hauptinhalt bleibt unberührt
        assertThat(service.view("wildfly-heap-leak", null)).doesNotContain("-all");

        assertThat(service.writeFile("wildfly-heap-leak", "references/jcmd.md", "neu", null, 5_000))
                .contains("überschrieben");
        assertThat(count("skill_file")).isEqualTo(1);
        assertThat(service.removeFile("wildfly-heap-leak", "references/jcmd.md", null)).contains("entfernt");
        assertThat(count("skill_file")).isZero();
        assertThatThrownBy(() -> service.view("wildfly-heap-leak", "references/jcmd.md"))
                .hasMessageContaining("keine Datei").hasMessageContaining("Vorhanden: keine");

        for (String bad : List.of("../x.md", "references/../../etc/passwd", "notes.md", "/references/a.md",
                "references/", "references/a b.md")) {
            assertThatThrownBy(() -> service.writeFile("wildfly-heap-leak", bad, "x", null, 5_000))
                    .as(bad).hasMessageContaining("Ungültiger Dateipfad");
        }
    }

    @Test
    void deleteRemovesSkillWithFilesAndHistory() {
        createHeapSkill();
        service.writeFile("wildfly-heap-leak", "templates/a.txt", "x", null, 5_000);
        assertThat(service.delete("wildfly-heap-leak")).contains("samt 1 Datei(en)");
        assertThatThrownBy(() -> service.view("wildfly-heap-leak", null))
                .hasMessageContaining("gibt es nicht").hasMessageContaining("skills_list");
        assertThat(count("skill") + count("skill_file") + count("skill_revision")).isZero();
    }

    @Test
    void viewCountsUsageWithoutBumpingRevision() {
        createHeapSkill();
        service.view("wildfly-heap-leak", null);
        service.view("wildfly-heap-leak", null);
        assertThat(jdbc.queryForObject("select use_count from skill where name = 'wildfly-heap-leak'", Long.class))
                .isEqualTo(2);
        assertThat(service.view("wildfly-heap-leak", null)).contains("revision: 1");
    }

    @Test
    void skillsSurviveRestartOfTheApp() {
        createHeapSkill();
        context.close();

        context = start(home, Map.of());
        SkillService again = context.getBean(SkillService.class);
        assertThat(again.view("wildfly-heap-leak", null)).contains("1. jvm_heap zweimal vergleichen");
        assertThat(again.count()).isEqualTo(1);
    }

    @Test
    void configuredJdbcUrlIsUsed() {
        context.close();
        Path other = home.resolve("anderswo");
        context = start(home, Map.of(SkillsModule.JDBC_URL, "jdbc:h2:file:" + other.resolve("db")));
        context.getBean(SkillService.class).create("x", "d", "c", null, null, 5_000);
        assertThat(other.resolve("db.mv.db")).exists();
        // Die Standard-Datei aus setUp() bleibt leer – der Skill landete in der konfigurierten Datenbank.
        // (settings.json im selben Ordner behält die URL, deshalb die Standard-URL explizit zurücksetzen.)
        context.close();
        context = start(home, Map.of(SkillsModule.JDBC_URL, SkillsModule.defaultJdbcUrl(home.toAbsolutePath())));
        assertThat(context.getBean(SkillService.class).count()).isZero();
    }

    @Test
    void unreachableDatabaseDoesNotPreventStartup() {
        context.close();
        context = start(home, Map.of(SkillsModule.JDBC_URL, "jdbc:gibtsnicht:x"));
        SkillsPersistenceConfig.Status status = context.getBean(SkillsPersistenceConfig.Status.class);
        assertThat(status.available()).isFalse();
        assertThat(status.configured().jdbcUrl()).isEqualTo("jdbc:gibtsnicht:x");
        assertThat(status.effective().jdbcUrl()).isEqualTo(SkillsPersistenceConfig.UNAVAILABLE_URL);
        assertThat(status.error()).isNotBlank();
    }
}
