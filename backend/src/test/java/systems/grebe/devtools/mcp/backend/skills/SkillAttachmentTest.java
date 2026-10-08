package systems.grebe.devtools.mcp.backend.skills;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;
import systems.grebe.devtools.mcp.backend.blobs.BlobJanitor;
import systems.grebe.devtools.mcp.backend.blobs.BlobReferences;
import systems.grebe.devtools.mcp.backend.blobs.BlobStore;
import systems.grebe.devtools.mcp.modules.skills.SkillViews;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Zusatzdateien als Anhang: beliebiger Inhalt in der Dateiablage unter {@code blobs/<e-mail>} bzw. {@code blobs/GLOBAL},
 * mitkopiert beim Übernehmen und Veröffentlichen von Vorlagen, freigegeben, sobald nichts mehr darauf verweist.
 */
class SkillAttachmentTest {

    static final String ANNA = "anna@example.com";
    static final String BERND = "bernd@example.com";
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0, 0, 0, 13, 'I', 'H', 'D', 'R'};

    @TempDir
    Path home;
    @TempDir
    Path work;

    private final List<ConfigurableApplicationContext> contexts = new ArrayList<>();

    @AfterEach
    void close() {
        contexts.forEach(ConfigurableApplicationContext::close);
    }

    /** Ein Backend (gemeinsame Datenbank und Dateiablage), angemeldet als {@code user}. */
    private SkillService app(String user, boolean admin) {
        String url = "jdbc:h2:file:" + home.resolve("skills").toAbsolutePath() + ";AUTO_SERVER=TRUE";
        ConfigurableApplicationContext ctx = SkillTestSupport.start(home, url, user, admin, null);
        contexts.add(ctx);
        return ctx.getBean(SkillService.class);
    }

    private BlobStore store() {
        return contexts.getFirst().getBean(BlobStore.class);
    }

    private Path blob(String owner, String sha) {
        return home.resolve("blobs").resolve(BlobStore.dirName(owner)).resolve(sha);
    }

    /** Älter als die Schonfrist für frische Uploads – sonst bleibt ein freigegebener Inhalt bis zum Aufräumen. */
    private static void age(Path p) throws Exception {
        Files.setLastModifiedTime(p, FileTime.from(Instant.now().minus(Duration.ofHours(1))));
    }

    private static void createSkill(SkillService s, String name) {
        s.create(name, "Verwenden zum Testen.", "Siehe assets/.", null, null, 5_000);
    }

    @Test
    void attachViewExportAndRemove() throws Exception {
        SkillService anna = app(ANNA, false);
        createSkill(anna, "release");
        Path png = Files.write(work.resolve("logo.png"), PNG);

        assertThat(anna.attachFile("release", "assets/logo.png", png, null, "Logo"))
                .contains("Anhang 'assets/logo.png' (image/png, 12 B)", "angelegt", "Revision 2");
        SkillViews.File f = anna.file("release", "assets/logo.png").orElseThrow();
        assertThat(f.inline()).isFalse();
        assertThat(f.size()).isEqualTo(12);
        assertThat(f.content()).isNull();
        assertThat(blob(ANNA, f.blob())).hasBinaryContent(PNG);

        assertThat(anna.view("release", null)).contains("assets/logo.png (Anhang, image/png, 12 B)");
        assertThat(anna.view("release", "assets/logo.png")).contains("Nicht als Text anzeigbar");
        assertThat(anna.details("release").orElseThrow().files()).singleElement()
                .satisfies(x -> assertThat(x.blob()).isEqualTo(f.blob()));
        assertThat(anna.history("release", null)).contains("attach_file", "Logo");

        Path out = work.resolve("out/logo.png");
        assertThat(anna.exportFile("release", "assets/logo.png", out)).contains(out.toString());
        assertThat(out).hasBinaryContent(PNG);

        assertThatThrownBy(() -> anna.patch("release", "PNG", "JPG", null, "assets/logo.png", null, null, 5_000))
                .hasMessageContaining("Anhang");

        // Textanhang (z.B. zu groß für den Skill) zeigt skills_view als Text
        Path md = Files.writeString(work.resolve("big.md"), "# Groß\n" + "x".repeat(3_000));
        anna.attachFile("release", "references/big.md", md, null, null);
        assertThat(anna.view("release", "references/big.md")).contains("Anhang (text/markdown", "# Groß");

        // Ersetzen durch Text und Entfernen geben den Inhalt frei
        age(blob(ANNA, f.blob()));
        anna.writeFile("release", "assets/logo.png", "jetzt Text", null, 5_000);
        assertThat(anna.file("release", "assets/logo.png").orElseThrow().inline()).isTrue();
        assertThat(blob(ANNA, f.blob())).doesNotExist();

        String mdBlob = anna.file("release", "references/big.md").orElseThrow().blob();
        age(blob(ANNA, mdBlob));
        anna.removeFile("release", "references/big.md", null);
        assertThat(blob(ANNA, mdBlob)).doesNotExist();
    }

    @Test
    void sameContentStaysWhileStillUsed() throws Exception {
        SkillService anna = app(ANNA, false);
        createSkill(anna, "a");
        createSkill(anna, "b");
        Path png = Files.write(work.resolve("logo.png"), PNG);
        anna.attachFile("a", "assets/logo.png", png, null, null);
        anna.attachFile("b", "assets/logo.png", png, null, null);
        String sha = anna.file("a", "assets/logo.png").orElseThrow().blob();
        age(blob(ANNA, sha));

        anna.delete("a");
        assertThat(blob(ANNA, sha)).exists(); // b verwendet ihn noch
        anna.delete("b");
        assertThat(blob(ANNA, sha)).doesNotExist();
    }

    @Test
    void attachmentsFollowTemplates() throws Exception {
        SkillService anna = app(ANNA, true);
        SkillService bernd = app(BERND, false);
        createSkill(anna, "vorlage");
        Path png = Files.write(work.resolve("logo.png"), PNG);
        anna.attachFile("vorlage", "assets/logo.png", png, null, null);
        String sha = anna.file("vorlage", "assets/logo.png").orElseThrow().blob();

        anna.publish("vorlage");
        assertThat(blob(SkillOwner.GLOBAL, sha)).hasBinaryContent(PNG);

        // Bernd liest die Vorlage, eine Änderung kopiert auch den Anhang in seine Ablage
        bernd.exportFile("vorlage", "assets/logo.png", work.resolve("bernd.png"));
        assertThat(work.resolve("bernd.png")).hasBinaryContent(PNG);
        bernd.writeFile("vorlage", "references/notiz.md", "Bernds Notiz", null, 5_000);
        assertThat(blob(BERND, sha)).hasBinaryContent(PNG);

        // erneut veröffentlichen mit neuem Inhalt unter gleichem Pfad: alter Inhalt der Vorlage wird frei
        Path png2 = Files.write(work.resolve("logo2.png"), new byte[] {1, 2, 3});
        age(blob(ANNA, sha));
        age(blob(SkillOwner.GLOBAL, sha));
        anna.attachFile("vorlage", "assets/logo.png", png2, null, null);
        String sha2 = anna.file("vorlage", "assets/logo.png").orElseThrow().blob();
        anna.publish("vorlage");
        assertThat(blob(SkillOwner.GLOBAL, sha2)).exists();
        assertThat(blob(SkillOwner.GLOBAL, sha)).doesNotExist();
        assertThat(blob(ANNA, sha)).doesNotExist(); // Annas Skill verweist auch nicht mehr darauf
        assertThat(blob(BERND, sha)).exists(); // Bernds Kopie schon

        // Zurückziehen gibt die Kopie der Vorlage frei, die eigenen bleiben
        age(blob(SkillOwner.GLOBAL, sha2));
        anna.unpublish("vorlage");
        assertThat(blob(SkillOwner.GLOBAL, sha2)).doesNotExist();
        assertThat(blob(ANNA, sha2)).exists();
        assertThat(blob(BERND, sha)).exists();
    }

    @Test
    void attachBlobNeedsUploadInOwnDirectory() throws Exception {
        SkillService anna = app(ANNA, false);
        createSkill(anna, "s");
        BlobStore.Blob foreign = store().put(BERND, new java.io.ByteArrayInputStream(PNG));
        assertThatThrownBy(() -> anna.attachBlob("s", "assets/x.png", foreign.sha(), null, null))
                .hasMessageContaining("erneut hochladen");
        assertThatThrownBy(() -> anna.attachBlob("s", "assets/x.png", "../../etc/passwd", null, null))
                .hasMessageContaining("Ungültige Datei-Kennung");
        assertThatThrownBy(() -> anna.attachBlob("s", "assets/x.png", foreign.sha(), "kein typ", null))
                .hasMessageContaining("Ungültiger Medientyp");
    }

    @Test
    void janitorKeepsReferencedContent() throws Exception {
        SkillService anna = app(ANNA, false);
        createSkill(anna, "s");
        Path png = Files.write(work.resolve("logo.png"), PNG);
        anna.attachFile("s", "assets/logo.png", png, null, null);
        String used = anna.file("s", "assets/logo.png").orElseThrow().blob();
        BlobStore.Blob orphan = store().put(ANNA, new java.io.ByteArrayInputStream("nie angehängt".getBytes()));
        Files.setLastModifiedTime(blob(ANNA, used), FileTime.from(Instant.now().minus(Duration.ofDays(3))));
        Files.setLastModifiedTime(blob(ANNA, orphan.sha()), FileTime.from(Instant.now().minus(Duration.ofDays(3))));

        BlobJanitor janitor = new BlobJanitor(store(), contexts.getFirst().getBean(BlobReferences.class));
        assertThat(janitor.sweep()).isEqualTo(1);
        assertThat(blob(ANNA, used)).exists();
        assertThat(blob(ANNA, orphan.sha())).doesNotExist();
    }
}
