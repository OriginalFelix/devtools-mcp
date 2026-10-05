package systems.grebe.devtools.mcp.backend.skills;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import systems.grebe.devtools.mcp.modules.skills.SkillViews;

/**
 * Mehrere Benutzer auf einer gemeinsamen Datenbank: Jeder sieht nur seine Skills plus die globalen Vorlagen;
 * Vorlagen sind schreibgeschützt, eine Änderung erzeugt eine persönliche Kopie. Zwei App-Instanzen (je ein
 * Spring-Kontext mit eigenem Benutzer) greifen per {@code AUTO_SERVER} auf dieselbe H2-Datei zu – wie zwei Entwickler
 * auf eine gemeinsame Datenbank.
 */
class SkillScopingTest {

    static final String ANNA = "anna@example.com";
    static final String BERND = "bernd@example.com";

    @TempDir
    Path home;

    private final List<ConfigurableApplicationContext> contexts = new java.util.ArrayList<>();

    @AfterEach
    void close() {
        contexts.forEach(ConfigurableApplicationContext::close);
    }

    /** Backend-Instanz eines Benutzers auf einer gemeinsamen Datenbank; {@code admin} = Administrator. */
    private SkillService app(String user, boolean admin) {
        Path dir = home.resolve(user);
        String url = "jdbc:h2:file:" + home.resolve("shared").toAbsolutePath() + ";AUTO_SERVER=TRUE";
        ConfigurableApplicationContext ctx = SkillTestSupport.start(dir, url, user, admin, null);
        contexts.add(ctx);
        return ctx.getBean(SkillService.class);
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(contexts.getFirst().getBean(javax.sql.DataSource.class));
    }

    @Test
    void usersSeeOnlyTheirOwnSkills() {
        SkillService anna = app(ANNA, false);
        SkillService bernd = app(BERND, false);

        anna.create("gradle-jdk", "Verwenden, wenn Gradle am JDK scheitert.", "Annas Weg", null, null, 5_000);
        // Gleicher Name bei einem anderen Benutzer ist erlaubt
        bernd.create("gradle-jdk", "Verwenden, wenn Gradle am JDK scheitert.", "Bernds Weg", null, null, 5_000);
        bernd.create("nur-bernd", "Nur für Bernd.", "geheim", null, null, 5_000);

        assertThat(anna.view("gradle-jdk", null)).contains("Annas Weg").doesNotContain("Bernds Weg");
        assertThat(bernd.view("gradle-jdk", null)).contains("Bernds Weg");
        assertThat(anna.list(null, null)).contains("gradle-jdk").doesNotContain("nur-bernd");
        assertThat(anna.list("geheim", null)).contains("Keine Skills für 'geheim'");
        assertThat(anna.overview()).extracting(SkillViews.Summary::name).containsExactly("gradle-jdk");

        // Fremde Skills sind für alle Operationen unsichtbar
        assertThatThrownBy(() -> anna.view("nur-bernd", null)).hasMessageContaining("gibt es nicht");
        assertThatThrownBy(() -> anna.history("nur-bernd", null)).hasMessageContaining("gibt es nicht");
        assertThatThrownBy(() -> anna.patch("nur-bernd", "geheim", "x", null, null, null, null, 5_000))
                .hasMessageContaining("gibt es nicht");
        assertThatThrownBy(() -> anna.delete("nur-bernd")).hasMessageContaining("gibt es nicht");
        assertThat(anna.details("nur-bernd")).isEmpty();

        assertThat(bernd.view("nur-bernd", null)).contains("geheim");
        assertThat(jdbc().queryForList("select owner from skill order by owner", String.class))
                .containsExactly(ANNA, BERND, BERND);
        assertThat(bernd.history("nur-bernd", null)).contains("create  (" + BERND + ")");
    }

    @Test
    void globalTemplatesAreReadOnlyAndPatchingCreatesAPersonalCopy() {
        SkillService admin = app(ANNA, true);
        SkillService bernd = app(BERND, false);

        admin.create("heap-leak", "Verwenden, wenn der Heap wächst.", "1. jvm_heap\n2. heap dump\n", "jvm",
                List.of("heap"), 5_000);
        admin.writeFile("heap-leak", "references/jcmd.md", "GC.class_histogram", null, 5_000);
        assertThat(admin.publish("heap-leak")).contains("veröffentlicht", "Revision 1");

        // Bernd sieht die Vorlage als global markiert und kann sie laden
        assertThat(bernd.list(null, null)).contains("heap-leak* – Verwenden, wenn der Heap wächst.", "* globale Vorlage");
        assertThat(bernd.view("heap-leak", null)).contains("globale Vorlage", "1. jvm_heap");
        assertThat(bernd.view("heap-leak", "references/jcmd.md")).contains("GC.class_histogram");
        assertThat(bernd.overview()).singleElement().extracting(SkillViews.Summary::scope)
                .isEqualTo(SkillViews.Scope.GLOBAL);

        // Anlegen unter dem Namen einer Vorlage wird abgelehnt – stattdessen patchen
        assertThatThrownBy(() -> bernd.create("heap-leak", "x", "y", null, null, 5_000))
                .hasMessageContaining("globale Vorlage").hasMessageContaining("skills_patch");
        // Löschen einer Vorlage ist nicht möglich
        assertThatThrownBy(() -> bernd.delete("heap-leak")).hasMessageContaining("schreibgeschützt");

        // Patchen: Kopie entsteht, Vorlage bleibt unverändert
        assertThat(bernd.patch("heap-leak", "2. heap dump\n", "2. heap dump\n3. GC-Wurzel\n", null, null,
                "Bernds Ergänzung", null, 5_000))
                .startsWith("Globale Vorlage 'heap-leak' ist schreibgeschützt – persönliche Kopie angelegt")
                .contains("Revision 2");
        assertThat(bernd.view("heap-leak", null)).contains("3. GC-Wurzel", "Kopie der Vorlage Rev. 1");
        assertThat(bernd.view("heap-leak", "references/jcmd.md")).contains("GC.class_histogram"); // Dateien mitkopiert
        assertThat(bernd.history("heap-leak", null)).contains("patch", "adopt", "Kopie der globalen Vorlage");
        assertThat(bernd.list(null, null)).doesNotContain("heap-leak*"); // Kopie verdeckt die Vorlage
        assertThat(bernd.overview()).singleElement().satisfies(s -> {
            assertThat(s.scope()).isEqualTo(SkillViews.Scope.COPY);
            assertThat(s.templateRevision()).isEqualTo(1);
            assertThat(s.templateUpdated()).isFalse();
        });

        long templates = jdbc().queryForObject("select count(*) from skill where owner = ?", Long.class,
                SkillOwner.GLOBAL);
        assertThat(templates).isEqualTo(1);
        assertThat(jdbc().queryForObject("select content from skill where owner = ?", String.class, SkillOwner.GLOBAL))
                .doesNotContain("GC-Wurzel");

        // Löscht Bernd seine Kopie, gilt wieder die Vorlage
        assertThat(bernd.delete("heap-leak")).contains("globale Vorlage 'heap-leak' ist wieder sichtbar");
        assertThat(bernd.view("heap-leak", null)).contains("globale Vorlage").doesNotContain("GC-Wurzel");
    }

    @Test
    void failedPatchOnATemplateLeavesNoCopyBehind() {
        SkillService admin = app(ANNA, true);
        SkillService bernd = app(BERND, false);
        admin.create("vorlage", "d", "Inhalt", null, null, 5_000);
        admin.publish("vorlage");

        assertThatThrownBy(() -> bernd.patch("vorlage", "gibt es nicht", "x", null, null, null, null, 5_000))
                .hasMessageContaining("kommt in Skill 'vorlage' nicht vor");
        assertThat(jdbc().queryForObject("select count(*) from skill where owner = ?", Long.class, BERND)).isZero();
        // Update ohne tatsächliche Änderung legt ebenfalls keine Kopie an
        assertThat(bernd.update("vorlage", "d", null, null, null, null, null, 5_000)).contains("Keine Änderung");
        assertThat(jdbc().queryForObject("select count(*) from skill where owner = ?", Long.class, BERND)).isZero();
    }

    @Test
    void everyWriteOperationOnATemplateGoesToTheCopy() {
        SkillService admin = app(ANNA, true);
        admin.create("t", "d", "Inhalt", null, null, 5_000);
        admin.writeFile("t", "references/a.md", "A", null, 5_000);
        admin.publish("t");
        String template = jdbc().queryForObject("select content from skill where owner = ?", String.class,
                SkillOwner.GLOBAL);

        SkillService b1 = app(BERND, false);
        assertThat(b1.update("t", null, "neu", null, null, null, null, 5_000)).contains("persönliche Kopie angelegt");
        b1.delete("t");
        assertThat(b1.writeFile("t", "references/b.md", "B", null, 5_000)).contains("persönliche Kopie angelegt");
        b1.delete("t");
        assertThat(b1.removeFile("t", "references/a.md", null)).contains("persönliche Kopie angelegt");
        assertThat(b1.view("t", null)).doesNotContain("references/a.md");

        // Vorlage unverändert, Datei a.md weiterhin da
        assertThat(jdbc().queryForObject("select content from skill where owner = ?", String.class, SkillOwner.GLOBAL))
                .isEqualTo(template);
        assertThat(jdbc().queryForObject("select count(*) from skill_file f join skill k on f.skill_id = k.id "
                + "where k.owner = ?", Long.class, SkillOwner.GLOBAL)).isEqualTo(1);
    }

    @Test
    void republishUpdatesTemplateAndMarksOutdatedCopies() {
        SkillService admin = app(ANNA, true);
        SkillService bernd = app(BERND, false);
        admin.create("t", "d", "v1", null, null, 5_000);
        admin.publish("t");
        bernd.patch("t", "v1", "v1 + Bernd", null, null, null, null, 5_000);

        admin.patch("t", "v1", "v2", null, null, null, null, 5_000);
        assertThat(admin.publish("t")).contains("aktualisiert", "Vorlage Revision 2");

        assertThat(bernd.view("t", null)).contains("v1 + Bernd"); // Kopie bleibt, wie sie ist
        assertThat(bernd.overview()).singleElement().satisfies(s -> {
            assertThat(s.templateRevision()).isEqualTo(1);
            assertThat(s.currentTemplateRevision()).isEqualTo(2);
            assertThat(s.templateUpdated()).isTrue();
        });
        // Der Admin selbst sieht seinen Skill als Kopie der aktuellen Vorlage
        assertThat(admin.overview()).singleElement().satisfies(s -> {
            assertThat(s.scope()).isEqualTo(SkillViews.Scope.COPY);
            assertThat(s.templateUpdated()).isFalse();
        });

        assertThat(admin.unpublish("t")).contains("zurückgezogen");
        assertThat(bernd.view("t", null)).contains("v1 + Bernd");
        assertThat(bernd.overview()).singleElement().satisfies(s ->
                assertThat(s.currentTemplateRevision()).isNull());
    }

    @Test
    void managingTemplatesNeedsTheAdminSwitch() {
        SkillService bernd = app(BERND, false);
        bernd.create("x", "d", "c", null, null, 5_000);
        assertThatThrownBy(() -> bernd.publish("x")).hasMessageContaining("nicht freigegeben");
        assertThatThrownBy(() -> bernd.unpublish("x")).hasMessageContaining("nicht freigegeben");
        assertThat(jdbc().queryForObject("select count(*) from skill where owner = ?", Long.class,
                SkillOwner.GLOBAL)).isZero();

        SkillService anna = app(ANNA, true);
        assertThatThrownBy(() -> anna.publish("x")).hasMessageContaining("Nur eigene Skills");
    }

    @Test
    void missingUserIsReported() {
        ConfigurableApplicationContext noUser = SkillTestSupport.start(home.resolve("n"), null, null, false, null);
        contexts.add(noUser);
        assertThatThrownBy(() -> noUser.getBean(SkillService.class).list(null, null))
                .hasMessageContaining("Kein Benutzer");
    }
}
