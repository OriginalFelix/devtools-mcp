package systems.grebe.devtools.mcp.backend.skills;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import systems.grebe.devtools.mcp.modules.skills.SkillViews;

/**
 * Service und Spring-Data-Repositories gegen eine echte H2-Datei ({@link SkillTestSupport}) – ohne Test-Transaktion,
 * damit Commit, Rollback und Bulk-Updates so wirken wie im Betrieb.
 */
class SkillServiceTest {

    static final String USER = SkillTestSupport.USER;

    @TempDir
    Path home;

    ConfigurableApplicationContext context;
    SkillService service;
    JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        context = SkillTestSupport.start(home);
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
        assertThat(context.getBean(com.zaxxer.hikari.HikariDataSource.class).getJdbcUrl())
                .isEqualTo("jdbc:h2:file:" + home.toAbsolutePath().resolve("skills").toString().replace('\\', '/'));
        createHeapSkill();
        assertThat(home.resolve("skills.mv.db")).exists();
    }

    @Test
    void createListAndView() {
        assertThat(service.list(null, null)).contains("Noch keine Skills", "skills_create");

        createHeapSkill();

        assertThat(service.list(null, null))
                .contains("software-development:", "wildfly-heap-leak – Verwenden, wenn der WildFly-Heap wächst.")
                .doesNotContain("leak-suche"); // Tags nur für die Suche, nicht in der Liste
        // Suche über Name, Beschreibung, Tags und Inhalt, unabhängig von Groß-/Kleinschreibung
        assertThat(service.list("VISUALVM", null)).contains("wildfly-heap-leak");
        assertThat(service.list("leak-suche", null)).contains("wildfly-heap-leak");
        assertThat(service.list("heap", "software-development")).contains("wildfly-heap-leak");
        assertThat(service.list("gradle", null)).contains("Keine Skills für 'gradle'");
        assertThat(service.list(null, "devops")).contains("Keine Skills in Kategorie 'devops'");

        assertThat(service.view("wildfly-heap-leak", null))
                .startsWith("# wildfly-heap-leak · Revision 1\n\n## Schritte")
                .contains("# wildfly-heap-leak · Revision 1", "1. jvm_heap zweimal vergleichen")
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
        assertThat(service.view("wildfly-heap-leak", null)).contains("3. Pfad zur GC-Wurzel prüfen", "Revision 2");

        assertThatThrownBy(() -> service.patch("wildfly-heap-leak", "gibt es nicht", "x", null, null, null, null, 5_000))
                .hasMessageContaining("nicht vor").hasMessageContaining("skills_view");
        // "heap" steht in jvm_heap und visualvm_heap_analyze
        assertThatThrownBy(() -> service.patch("wildfly-heap-leak", "heap", "Heap", null, null, null, null, 5_000))
                .hasMessageContaining("2-mal").hasMessageContaining("replace_all");
        assertThat(service.patch("wildfly-heap-leak", "heap", "Heap", true, null, null, null, 5_000))
                .contains("2 Stelle(n)");
        assertThat(service.view("wildfly-heap-leak", null)).contains("jvm_Heap", "visualvm_Heap_analyze");

        assertThat(service.history("wildfly-heap-leak", null))
                .contains("1  ", "create", "2  ", "patch  (" + USER + ")  – GC-Wurzel ergänzt", "3  ");
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
        assertThat(service.view("wildfly-heap-leak", null)).contains("Revision 1", "## Schritte");
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
        assertThat(view).contains("Revision 2", "1. jvm_heap zweimal vergleichen");
        assertThat(service.details("wildfly-heap-leak").orElseThrow().summary())
                .satisfies(s -> assertThat(s.description()).isEqualTo("Neue Beschreibung"))
                .satisfies(s -> assertThat(s.category()).isNull())
                .satisfies(s -> assertThat(s.tags()).isEmpty());
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
        assertThat(service.view("wildfly-heap-leak", null)).contains("Revision 1");
    }

    @Test
    void skillsSurviveRestartOfTheApp() {
        createHeapSkill();
        context.close();

        context = SkillTestSupport.start(home);
        SkillService again = context.getBean(SkillService.class);
        assertThat(again.view("wildfly-heap-leak", null)).contains("1. jvm_heap zweimal vergleichen");
        assertThat(again.countText()).startsWith("1 eigene Skill(s) von " + USER);
    }

    @Test
    void configuredJdbcUrlIsUsed() {
        context.close();
        Path other = home.resolve("anderswo");
        context = SkillTestSupport.start(home, "jdbc:h2:file:" + other.resolve("db"), USER, false, null);
        context.getBean(SkillService.class).create("x", "d", "c", null, null, 5_000);
        assertThat(other.resolve("db.mv.db")).exists();
        // Die Standard-Datei aus setUp() bleibt leer – der Skill landete in der konfigurierten Datenbank.
        context.close();
        context = SkillTestSupport.start(home);
        assertThat(context.getBean(SkillService.class).countText()).startsWith("0 eigene Skill(s)");
    }

    @Test
    void unreachableDatabaseFailsTheStart() {
        context.close();
        context = null;
        assertThatThrownBy(() -> SkillTestSupport.start(home, "jdbc:gibtsnicht:x", USER, false, null))
                .hasStackTraceContaining("jdbc:gibtsnicht:x");
    }

    @Test
    void overviewAndDetailsForTheGui() {
        createHeapSkill();
        service.create("ohne-kat", "Ohne Kategorie.", "x", null, null, 5_000);
        service.writeFile("wildfly-heap-leak", "references/jcmd.md", "GC.class_histogram", "Referenz", 5_000);
        service.view("wildfly-heap-leak", null);

        List<SkillViews.Summary> overview = service.overview();
        // wie skills_list: Kategorie (ohne zuerst), dann Name
        assertThat(overview).extracting(SkillViews.Summary::name).containsExactly("ohne-kat", "wildfly-heap-leak");
        SkillViews.Summary heap = overview.get(1);
        assertThat(heap.category()).isEqualTo("software-development");
        assertThat(heap.tags()).containsExactly("wildfly", "heap", "leak-suche");
        assertThat(heap.revision()).isEqualTo(2);
        assertThat(heap.useCount()).isEqualTo(1);
        assertThat(heap.lastUsedAt()).isNotNull();
        assertThat(heap.fileCount()).isEqualTo(1);

        SkillViews.Details d = service.details("wildfly-heap-leak").orElseThrow();
        assertThat(d.content()).contains("1. jvm_heap zweimal vergleichen");
        assertThat(d.files()).extracting(SkillViews.File::path).containsExactly("references/jcmd.md");
        assertThat(d.revisions()).extracting(SkillViews.Revision::revision).containsExactly(2, 1);
        assertThat(d.revisions().getFirst().action()).isEqualTo("write_file");
        assertThat(d.revisions().getFirst().note()).isEqualTo("Referenz");
        assertThat(service.details("gibt-es-nicht")).isEmpty();
    }

    @Test
    void changeListenerFiresAfterCommitOnly() {
        java.util.concurrent.atomic.AtomicInteger events = new java.util.concurrent.atomic.AtomicInteger();
        service.addChangeListener(events::incrementAndGet);

        createHeapSkill();
        assertThat(events).hasValue(1);
        service.patch("wildfly-heap-leak", "Schritte", "Ablauf", null, null, null, null, 5_000);
        service.writeFile("wildfly-heap-leak", "references/a.md", "a", null, 5_000);
        service.removeFile("wildfly-heap-leak", "references/a.md", null);
        service.update("wildfly-heap-leak", "neu", null, null, null, null, null, 5_000);
        assertThat(events).hasValue(5);

        // Lesen und Nutzung zählen lösen nichts aus
        service.view("wildfly-heap-leak", null);
        service.list(null, null);
        service.overview();
        assertThat(events).hasValue(5);

        // Rollback: kein Ereignis
        assertThatThrownBy(() -> service.patch("wildfly-heap-leak", "Ablauf", "x".repeat(5_000), null, null, null,
                null, 5_000)).hasMessageContaining("max. 5000");
        assertThatThrownBy(() -> service.create("wildfly-heap-leak", "x", "y", null, null, 5_000))
                .hasMessageContaining("existiert bereits");
        assertThat(events).hasValue(5);

        service.delete("wildfly-heap-leak");
        assertThat(events).hasValue(6);

        // Rollback, nachdem die Änderung schon angemeldet war (äußere Transaktion): ebenfalls kein Ereignis
        var tx = new org.springframework.transaction.support.TransactionTemplate(
                context.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        tx.executeWithoutResult(status -> {
            service.create("verworfen", "d", "c", null, null, 5_000);
            status.setRollbackOnly();
        });
        assertThat(service.details("verworfen")).isEmpty();
        assertThat(events).hasValue(6);
    }

    @Test
    void triggersRegisterTheSkillForTools() {
        assertThat(service.create("ticket-review", "Verwenden, wenn ein Ticket geprüft wird.", "1. ticket_get",
                null, null, List.of("ticket_get", " PR_* ", "ticket_get"), 5_000))
                .contains("registriert für ticket_get,pr_*");
        assertThat(service.view("ticket-review", null)).contains("Registriert für: ticket_get, pr_*");
        SkillViews.Summary s = service.overview().getFirst();
        assertThat(s.triggers()).containsExactly("ticket_get", "pr_*");
        assertThat(s.triggeredBy("ticket_get")).isTrue();
        assertThat(s.triggeredBy("pr_diff")).isTrue();
        assertThat(s.triggeredBy("ticket_search")).isFalse();

        assertThat(service.update("ticket-review", null, null, null, null, List.of(), null, null, 5_000))
                .contains("Revision 2");
        assertThat(service.overview().getFirst().triggers()).isEmpty();
        assertThatThrownBy(() -> service.update("ticket-review", null, null, null, null, List.of("git status"), null,
                null, 5_000)).hasMessageContaining("Ungültiger Trigger");
    }

    @Test
    void listLoadsASingleHitDirectlyAndShortensDescriptions() {
        createHeapSkill();
        service.create("long-one", "x".repeat(400), "inhalt", null, null, 5_000);

        assertThat(service.list("visualvm", null)).startsWith("1 Skill für 'visualvm' – direkt geladen:")
                .contains("# wildfly-heap-leak · Revision 1", "2. visualvm_heap_analyze");
        assertThat(jdbc.queryForObject("select use_count from skill where name = 'wildfly-heap-leak'", Long.class))
                .isEqualTo(1);
        String all = service.list(null, null);
        assertThat(all).contains("long-one – " + "x".repeat(150)).doesNotContain("x".repeat(160))
                .doesNotContain("direkt geladen");
    }
}
