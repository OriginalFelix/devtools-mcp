package systems.grebe.devtools.mcp.backend.memories;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;
import systems.grebe.devtools.mcp.backend.skills.SkillTestSupport;
import systems.grebe.devtools.mcp.modules.memories.MemoryViews;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Memory-Service gegen eine echte H2-Datei ({@link SkillTestSupport}), ohne Test-Transaktion. */
class MemoryServiceTest {

    @TempDir
    Path home;

    ConfigurableApplicationContext context;
    MemoryService service;

    @BeforeEach
    void setUp() {
        context = SkillTestSupport.start(home);
        service = context.getBean(MemoryService.class);
    }

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    private static long idOf(String saved) {
        return Long.parseLong(saved.replaceAll("^Memory #(\\d+) .*$", "$1"));
    }

    private long saveReview() {
        return idOf(service.save("Ticket ABC-123 reviewt: Akzeptanzkriterien fehlen",
                "## Ergebnis\nZurück an den PO, weil die Akzeptanzkriterien fehlen.\n", null, "egecko",
                "Ticket-Review", "ABC-123", List.of("Review", "abgelehnt"), 5_000));
    }

    @Test
    void saveSearchAndView() {
        assertThat(service.search(null, null, null, null, null, null, null))
                .contains("Noch keine Memories", "memories_save");

        long id = saveReview();
        service.save("Heap-Leck in WildFly analysiert", "Cache ohne Obergrenze in OrderService gefunden.", null,
                "egecko", null, null, List.of("heap"), 5_000);

        // genau ein Treffer: direkt vollständig, kein zweiter Aufruf nötig
        assertThat(service.search("abc-123", null, null, null, null, null, null))
                .startsWith("1 Memory für 'abc-123' – direkt geladen:")
                .contains("# #" + id + " Ticket ABC-123 reviewt",
                        "Projekt egecko · Skill ticket-review · Bezug ABC-123 · Tags review, abgelehnt", "## Ergebnis");
        assertThat(service.search(null, null, null, null, null, null, null))
                .contains("2 Memories (neueste zuerst; laden mit memories_view(id))", "Heap-Leck",
                        "[egecko · ticket-review · ABC-123]")
                .doesNotContain("direkt geladen");
        assertThat(service.view(id)).startsWith("# #" + id + " Ticket ABC-123 reviewt: Akzeptanzkriterien fehlen\nProjekt egecko")
                .contains("## Ergebnis");
        assertThat(service.count()).isEqualTo(2);
    }

    @Test
    void searchWeighsTermsAndFilters() {
        long toolchain = idOf(service.save("Gradle-Toolchain fehlte", "Build-Agent ohne JDK 25.", null, "egecko", null,
                null, null, 5_000));
        long wrapper = idOf(service.save("Gradle aktualisiert", "Wrapper gehoben.", null, "egecko", null, null, null,
                5_000));
        long review = saveReview();
        service.save("Review-Notiz", "Ticket ABC-999 kurz angesehen.", null, "other", "ticket-review", "ABC-999",
                null, 5_000);

        // ohne Suchtext die neuesten zuerst …
        String newest = service.search(null, null, null, null, null, null, null);
        assertThat(newest.indexOf("#" + wrapper)).isLessThan(newest.indexOf("#" + toolchain));
        // … mit Suchtext gewichtet: beide Begriffe im Titel schlagen einen Begriff, auch wenn älter
        String found = service.search("gradle toolchain", null, null, null, null, null, null);
        assertThat(found).contains("2 Memories für 'gradle toolchain' (beste Treffer zuerst;");
        assertThat(found.indexOf("#" + toolchain)).isLessThan(found.indexOf("#" + wrapper));

        assertThat(service.search(null, null, "ticket-review", null, null, null, null))
                .contains("2 Memories zu Skill 'ticket-review'").doesNotContain("Build-Fehler");
        assertThat(service.search(null, "OTHER", null, null, null, null, null))
                .contains("1 Memory in Projekt 'OTHER'", "Review-Notiz");
        assertThat(service.search(null, null, null, "abgelehnt", null, null, null)).contains("#" + review)
                .doesNotContain("Review-Notiz");
        assertThat(service.search(null, null, null, "abge", null, null, null)).contains("Keine Memories gefunden");
        assertThat(service.search(null, null, null, null, null, 1, 1)).contains("1 Memory der letzten 1 Tag");
        assertThat(service.search("gibtsnicht", null, null, null, null, null, null))
                .contains("Keine Memories gefunden für 'gibtsnicht'");
        assertThatThrownBy(() -> service.search(null, null, null, null, null, 0, null)).hasMessageContaining("'days'");
    }

    @Test
    void updateAppendsAndChangesFields() {
        long id = saveReview();
        assertThat(service.update(id, null, null, "PO hat Kriterien ergänzt, Ticket freigegeben.", null, null, "",
                null, List.of("freigegeben"), false, 5_000)).contains("Nachtrag", "Skill", "Tags");
        MemoryViews.Entry e = service.details(id).orElseThrow();
        assertThat(e.content()).contains("Zurück an den PO", "**Nachtrag ", "Ticket freigegeben.");
        assertThat(e.skill()).isNull();
        assertThat(e.tags()).containsExactly("freigegeben");
        assertThat(e.updatedAt()).isAfterOrEqualTo(e.createdAt());

        assertThat(service.update(id, null, null, null, null, null, null, null, null, false, 5_000))
                .contains("Keine Änderung");
        assertThatThrownBy(() -> service.update(id, null, "neu", "auch", null, null, null, null, null, false, 5_000))
                .hasMessageContaining("nicht beides");
        assertThatThrownBy(() -> service.update(id, null, null, "x".repeat(5_000), null, null, null, null, null,
                false, 5_000))
                .hasMessageContaining("max. 5000");
    }

    @Test
    void sameReferenceIsPointedOut() {
        long first = saveReview();
        assertThat(service.save("Nochmal ABC-123", "Erneut angesehen.", null, null, null, "abc-123", null, 5_000))
                .contains("Zu 'abc-123' gibt es außerdem #" + first, "memories_update");
    }

    @Test
    void validation() {
        assertThatThrownBy(() -> service.save(" ", "x", null, null, null, null, null, 5_000))
                .hasMessageContaining("'title'");
        assertThatThrownBy(() -> service.save("t", " ", null, null, null, null, null, 5_000))
                .hasMessageContaining("'content'");
        assertThatThrownBy(() -> service.save("t", "x", null, null, "kein skill!", null, null, 5_000))
                .hasMessageContaining("Skill-Name");
        assertThatThrownBy(() -> service.view(4711)).hasMessageContainingAll("#4711 gibt es nicht", "memories_search");
    }

    @Test
    void memoriesBelongToTheirOwner() {
        long id = saveReview();
        context.close();
        context = SkillTestSupport.start(home, null, "other@example.com", false, null);
        MemoryService other = context.getBean(MemoryService.class);

        assertThat(other.count()).isZero();
        assertThat(other.search("ABC-123", null, null, null, null, null, null)).contains("Keine Memories");
        assertThatThrownBy(() -> other.view(id)).hasMessageContaining("anderen Benutzer");
        assertThatThrownBy(() -> other.delete(id, false)).hasMessageContaining("gibt es nicht");
    }

    @Test
    void deleteAndChangeListenerAfterCommit() {
        AtomicInteger changes = new AtomicInteger();
        service.addChangeListener(changes::incrementAndGet);
        long id = saveReview();
        assertThatThrownBy(() -> service.save("", "x", null, null, null, null, null, 5_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(service.delete(id, false)).contains("#" + id, "gelöscht");
        assertThat(service.count()).isZero();
        assertThat(changes).hasValue(2); // Anlegen und Löschen – der Fehlversuch zählt nicht
    }

    @Test
    void temporaryMemoriesWithoutPermission() {
        long permanent = saveReview();
        String saved = service.save("Zwischenstand Heap-Analyse", "Dump liegt unter /tmp.", MemoryViews.Type.TEMPORARY,
                null, null, null, null, 5_000);
        assertThat(saved).matches("Memory #\\d+ \\(temporär\\) gespeichert\\.");
        long temp = idOf(saved);

        assertThat(service.details(permanent).orElseThrow().type()).isEqualTo(MemoryViews.Type.PERMANENT);
        assertThat(service.details(temp).orElseThrow().temporary()).isTrue();
        assertThat(service.temporary(temp)).isTrue();
        assertThat(service.view(temp)).contains("temporär · ");
        assertThat(service.search(null, null, null, null, MemoryViews.Type.TEMPORARY, null, null))
                .contains("(nur temporäre)", "Zwischenstand").doesNotContain("ABC-123");
        assertThat(service.search(null, null, null, null, MemoryViews.Type.PERMANENT, null, null))
                .contains("ABC-123").doesNotContain("Zwischenstand");

        // ohne Freigabe (temporaryOnly): temporäre ändern und löschen, dauerhafte nicht
        assertThat(service.update(temp, null, null, "Analyse fertig.", null, null, null, null, null, true, 5_000))
                .contains("Nachtrag");
        assertThatThrownBy(() -> service.update(temp, null, null, null, MemoryViews.Type.PERMANENT, null, null,
                null, null, true, 5_000)).hasMessageContaining("dauerhaft zu machen");
        assertThatThrownBy(() -> service.update(permanent, null, null, "x", null, null, null, null, null, true,
                5_000)).hasMessageContainingAll("ist dauerhaft", "permissions_request");
        assertThatThrownBy(() -> service.delete(permanent, true)).hasMessageContaining("ist dauerhaft");
        assertThat(service.delete(temp, true)).contains("gelöscht");

        // mit Freigabe lässt sich der Typ in beide Richtungen ändern
        assertThat(service.update(permanent, null, null, null, MemoryViews.Type.TEMPORARY, null, null, null, null,
                false, 5_000)).contains("jetzt temporär");
        assertThat(service.delete(permanent, true)).contains("gelöscht");
        assertThat(service.count()).isZero();
    }

    @Test
    void snippetCentersOnFirstHit() {
        String content = "a".repeat(300) + " TREFFER " + "b".repeat(300);
        assertThat(MemoryService.snippet(content, List.of("treffer"))).startsWith("…").endsWith("…")
                .contains("TREFFER").hasSizeLessThanOrEqualTo(162);
        assertThat(MemoryService.snippet("kurz\n\nund  knapp", List.of())).isEqualTo("kurz und knapp");
        assertThat(MemoryService.terms("  Heap heap  WildFly ")).containsExactly("heap", "wildfly");
    }

    @Test
    void referencesAndRelatedForHints() {
        long review = saveReview();
        service.save("Andere Sache", "x", null, null, "ticket-review", "#77", null, 5_000);
        service.save("Ohne Bezug", "y", null, null, null, null, null, 5_000);

        assertThat(service.references()).containsExactlyInAnyOrder("abc-123", "#77");
        assertThat(service.related(List.of("ABC-123"), null, 3)).extracting(MemoryViews.Entry::id)
                .containsExactly(review);
        assertThat(service.related(null, "ticket-review", 3)).extracting(MemoryViews.Entry::title)
                .containsExactly("Andere Sache", "Ticket ABC-123 reviewt: Akzeptanzkriterien fehlen");
        assertThat(service.related(null, "ticket-review", 3)).allSatisfy(e -> assertThat(e.content()).isNull());
        assertThat(service.related(List.of(), null, 3)).isEmpty();
    }
}
