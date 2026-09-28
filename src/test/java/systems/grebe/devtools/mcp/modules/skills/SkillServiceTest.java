package systems.grebe.devtools.mcp.modules.skills;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Gegen eine echte H2-Datei, so wie im Standardbetrieb. */
class SkillServiceTest {

    @TempDir
    Path dir;

    SkillDatabase database;
    SkillDatabase.Connection connection;
    SkillService service;

    @BeforeEach
    void setUp() {
        database = new SkillDatabase();
        connection = new SkillDatabase.Connection("jdbc:h2:file:" + dir.resolve("skills"), "sa", "", "update");
        service = new SkillService(database, connection, 5_000);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    private void createHeapSkill() {
        service.create("wildfly-heap-leak", "Verwenden, wenn der WildFly-Heap wächst.",
                "## Schritte\n1. jvm_heap zweimal vergleichen\n2. visualvm_heap_analyze\n", "software-development",
                List.of("WildFly", "heap", "heap", " Leak Suche "));
    }

    @Test
    void createListAndView() {
        assertThat(service.list(null, null)).contains("Noch keine Skills");
        assertThat(service.list(null, null)).contains("skills_create");

        createHeapSkill();

        assertThat(service.list(null, null))
                .contains("software-development:", "wildfly-heap-leak: Verwenden, wenn der WildFly-Heap wächst.",
                        "[wildfly, heap, leak-suche]");
        // Suche über Name, Beschreibung, Tags und Inhalt, unabhängig von Groß-/Kleinschreibung
        assertThat(service.list("VISUALVM", null)).contains("wildfly-heap-leak");
        assertThat(service.list("leak-suche", null)).contains("wildfly-heap-leak");
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
        assertThatThrownBy(() -> service.create("wildfly-heap-leak", "x", "y", null, null))
                .hasMessageContaining("existiert bereits").hasMessageContaining("skills_patch");
        assertThatThrownBy(() -> service.create("Mit Leerzeichen", "x", "y", null, null))
                .hasMessageContaining("Ungültiger Skill-Name");
        assertThatThrownBy(() -> service.create("ok", " ", "y", null, null)).hasMessageContaining("'description' fehlt");
        assertThatThrownBy(() -> service.create("ok", "x", "y".repeat(5_001), null, null))
                .hasMessageContaining("max. 5000").hasMessageContaining("skills_write_file");
        assertThatThrownBy(() -> service.create("ok", "x", "y", "Kein Slug!", null))
                .hasMessageContaining("Ungültige Kategorie");
    }

    @Test
    void patchReplacesExactlyOnceAndRecordsHistory() {
        createHeapSkill();

        assertThat(service.patch("wildfly-heap-leak", "2. visualvm_heap_analyze\n",
                "2. visualvm_heap_analyze\n3. Pfad zur GC-Wurzel prüfen\n", null, null, "GC-Wurzel ergänzt", null))
                .contains("1 Stelle(n) ersetzt, Revision 2");
        assertThat(service.view("wildfly-heap-leak", null)).contains("3. Pfad zur GC-Wurzel prüfen", "revision: 2");

        assertThatThrownBy(() -> service.patch("wildfly-heap-leak", "gibt es nicht", "x", null, null, null, null))
                .hasMessageContaining("nicht vor").hasMessageContaining("skills_view");
        // "heap" steht in jvm_heap und visualvm_heap_analyze
        assertThatThrownBy(() -> service.patch("wildfly-heap-leak", "heap", "Heap", null, null, null, null))
                .hasMessageContaining("2-mal").hasMessageContaining("replace_all");
        assertThat(service.patch("wildfly-heap-leak", "heap", "Heap", true, null, null, null))
                .contains("2 Stelle(n)");
        assertThat(service.view("wildfly-heap-leak", null)).contains("jvm_Heap", "visualvm_Heap_analyze");

        assertThat(service.history("wildfly-heap-leak", null))
                .contains("1  ", "create", "2  ", "patch  – GC-Wurzel ergänzt", "3  ");
        assertThat(service.history("wildfly-heap-leak", 1)).contains("Revision 1 (create").doesNotContain("GC-Wurzel");
        assertThat(service.history("wildfly-heap-leak", 2)).contains("GC-Wurzel");
    }

    @Test
    void replaceAllCountsEveryOccurrence() {
        service.create("dups", "d", "a a a", null, null);
        assertThat(service.patch("dups", "a", "b", true, null, null, null)).contains("3 Stelle(n)");
        assertThat(service.view("dups", null)).endsWith("b b b");
    }

    @Test
    void expectedRevisionPreventsLostUpdates() {
        createHeapSkill();
        service.patch("wildfly-heap-leak", "Schritte", "Ablauf", null, null, null, 1);
        assertThatThrownBy(() -> service.patch("wildfly-heap-leak", "Ablauf", "Schritte", null, null, null, 1))
                .hasMessageContaining("inzwischen geändert (Revision 2 statt 1)");
        assertThatThrownBy(() -> service.update("wildfly-heap-leak", "neu", null, null, null, null, 1))
                .hasMessageContaining("inzwischen geändert");
    }

    @Test
    void updateChangesOnlyGivenFields() {
        createHeapSkill();
        assertThat(service.update("wildfly-heap-leak", null, null, null, null, null, null)).contains("Keine Änderung");
        assertThat(service.update("wildfly-heap-leak", "Neue Beschreibung", null, "", List.of(), "aufgeräumt", null))
                .contains("Revision 2");
        String view = service.view("wildfly-heap-leak", null);
        assertThat(view).contains("description: Neue Beschreibung", "1. jvm_heap zweimal vergleichen")
                .doesNotContain("category:", "tags:");
        assertThat(service.list(null, null)).contains("(ohne Kategorie):");
    }

    @Test
    void supportingFiles() {
        createHeapSkill();
        assertThat(service.writeFile("wildfly-heap-leak", "references/jcmd.md", "GC.class_histogram", null))
                .contains("angelegt (Revision 2)");
        assertThat(service.view("wildfly-heap-leak", null)).contains("Zusatzdateien", "references/jcmd.md");
        assertThat(service.view("wildfly-heap-leak", "references/jcmd.md")).endsWith("GC.class_histogram");

        service.patch("wildfly-heap-leak", "GC.class_histogram", "GC.class_histogram -all", null,
                "references/jcmd.md", null, null);
        assertThat(service.view("wildfly-heap-leak", "references/jcmd.md")).endsWith("-all");
        // Hauptinhalt bleibt unberührt
        assertThat(service.view("wildfly-heap-leak", null)).doesNotContain("-all");

        assertThat(service.writeFile("wildfly-heap-leak", "references/jcmd.md", "neu", null)).contains("überschrieben");
        assertThat(service.removeFile("wildfly-heap-leak", "references/jcmd.md", null)).contains("entfernt");
        assertThatThrownBy(() -> service.view("wildfly-heap-leak", "references/jcmd.md"))
                .hasMessageContaining("keine Datei").hasMessageContaining("Vorhanden: keine");

        for (String bad : List.of("../x.md", "references/../../etc/passwd", "notes.md", "/references/a.md",
                "references/", "references/a b.md")) {
            assertThatThrownBy(() -> service.writeFile("wildfly-heap-leak", bad, "x", null))
                    .as(bad).hasMessageContaining("Ungültiger Dateipfad");
        }
    }

    @Test
    void deleteRemovesSkillWithFilesAndHistory() {
        createHeapSkill();
        service.writeFile("wildfly-heap-leak", "templates/a.txt", "x", null);
        assertThat(service.delete("wildfly-heap-leak")).contains("samt 1 Datei(en)");
        assertThatThrownBy(() -> service.view("wildfly-heap-leak", null))
                .hasMessageContaining("gibt es nicht").hasMessageContaining("skills_list");
        long orphans = database.inTransaction(connection, s -> s.createSelectionQuery(
                "select count(r) from SkillRevision r", Long.class).getSingleResult()
                + s.createSelectionQuery("select count(f) from SkillFile f", Long.class).getSingleResult());
        assertThat(orphans).isZero();
    }

    @Test
    void viewCountsUsageWithoutBumpingRevision() {
        createHeapSkill();
        service.view("wildfly-heap-leak", null);
        service.view("wildfly-heap-leak", null);
        long uses = database.inTransaction(connection, s -> s.createSelectionQuery(
                "select k.useCount from Skill k where k.name = 'wildfly-heap-leak'", Long.class).getSingleResult());
        assertThat(uses).isEqualTo(2);
        assertThat(service.view("wildfly-heap-leak", null)).contains("revision: 1");
    }

    @Test
    void skillsSurviveReopeningTheDatabase() {
        createHeapSkill();
        database.close();

        SkillDatabase reopened = new SkillDatabase();
        try {
            SkillService again = new SkillService(reopened, connection, 5_000);
            assertThat(again.view("wildfly-heap-leak", null)).contains("1. jvm_heap zweimal vergleichen");
            assertThat(SkillDatabase.withTemporary(connection, SkillService::count)).isEqualTo(1);
        } finally {
            reopened.close();
        }
    }

    @Test
    void changedConnectionOpensTheOtherDatabase() {
        createHeapSkill();
        SkillDatabase.Connection other = new SkillDatabase.Connection("jdbc:h2:file:" + dir.resolve("other"), "sa",
                "", "update");
        assertThat(new SkillService(database, other, 5_000).list(null, null)).contains("Noch keine Skills");
        assertThat(service.list(null, null)).contains("wildfly-heap-leak");
    }

    @Test
    void unreachableDatabaseGivesReadableError() {
        SkillDatabase.Connection broken = new SkillDatabase.Connection("jdbc:gibtsnicht:x", "sa", "", "update");
        assertThatThrownBy(() -> new SkillService(database, broken, 5_000).list(null, null))
                .hasMessageContaining("Skill-Datenbank konnte nicht geöffnet werden (jdbc:gibtsnicht:x)");
    }
}
