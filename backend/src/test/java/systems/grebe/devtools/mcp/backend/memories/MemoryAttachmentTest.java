package systems.grebe.devtools.mcp.backend.memories;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;
import systems.grebe.devtools.mcp.backend.blobs.BlobStore;
import systems.grebe.devtools.mcp.backend.skills.SkillTestSupport;
import systems.grebe.devtools.mcp.modules.memories.MemoryViews;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Dateien an Memories: beliebiger Inhalt in der Dateiablage des Benutzers, Rechte wie beim Ändern der Memory. */
class MemoryAttachmentTest {

    static final byte[] PDF = {'%', 'P', 'D', 'F', '-', '1', '.', '7', 0, 1, 2, 3};

    @TempDir
    Path home;
    @TempDir
    Path work;

    ConfigurableApplicationContext context;
    MemoryService service;

    @BeforeEach
    void setUp() {
        context = SkillTestSupport.start(home);
        service = context.getBean(MemoryService.class);
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    private long save(MemoryViews.Type type) {
        String saved = service.save("Fehler analysiert", "Siehe Log.", type, null, null, null, List.of(), 5_000);
        return Long.parseLong(saved.replaceAll("^Memory #(\\d+).*$", "$1"));
    }

    private Path blob(String sha) {
        return home.resolve("blobs").resolve(BlobStore.dirName(SkillTestSupport.USER)).resolve(sha);
    }

    private static void age(Path p) throws Exception {
        Files.setLastModifiedTime(p, FileTime.from(Instant.now().minus(Duration.ofHours(1))));
    }

    @Test
    void attachViewExportReplaceAndRemove() throws Exception {
        long id = save(null);
        Path pdf = Files.write(work.resolve("Bericht 2026.pdf"), PDF);
        Path log = Files.writeString(work.resolve("server.log"), "ERROR boom");

        assertThat(service.attachFile(id, null, pdf, null, false))
                .contains("'Bericht_2026.pdf' (application/pdf, 12 B) an Memory #" + id + " angehängt");
        service.attachFile(id, "logs/server.log", log, null, false);

        MemoryViews.Entry e = service.details(id).orElseThrow();
        assertThat(e.files()).extracting(MemoryViews.File::path).containsExactly("Bericht_2026.pdf", "logs/server.log");
        assertThat(service.view(id)).contains("Dateien (memories_view mit file_path): Bericht_2026.pdf "
                + "(application/pdf, 12 B), logs/server.log (text/plain, 10 B)");
        assertThat(service.viewFile(id, "logs/server.log")).contains("ERROR boom");
        assertThat(service.viewFile(id, "Bericht_2026.pdf")).contains("Nicht als Text anzeigbar");

        Path out = work.resolve("out.pdf");
        service.exportFile(id, "Bericht_2026.pdf", out);
        assertThat(out).hasBinaryContent(PDF);

        // gleicher Pfad ersetzt und gibt den alten Inhalt frei
        String oldSha = service.file(id, "logs/server.log").orElseThrow().blob();
        age(blob(oldSha));
        assertThat(service.attachFile(id, "logs/server.log", Files.writeString(work.resolve("neu.log"), "OK"), null,
                false)).contains("ersetzt");
        assertThat(blob(oldSha)).doesNotExist();

        String logSha = service.file(id, "logs/server.log").orElseThrow().blob();
        age(blob(logSha));
        service.removeFile(id, "logs/server.log", false);
        assertThat(blob(logSha)).doesNotExist();
        assertThatThrownBy(() -> service.viewFile(id, "logs/server.log"))
                .hasMessageContaining("Vorhanden: Bericht_2026.pdf");

        // Löschen der Memory nimmt die Dateien mit
        String pdfSha = service.file(id, "Bericht_2026.pdf").orElseThrow().blob();
        age(blob(pdfSha));
        assertThat(service.delete(id, false)).contains("samt 1 Datei(en)");
        assertThat(blob(pdfSha)).doesNotExist();
    }

    @Test
    void temporaryOnlyProtectsPermanentMemories() throws Exception {
        long permanent = save(null);
        long temporary = save(MemoryViews.Type.TEMPORARY);
        Path log = Files.writeString(work.resolve("a.log"), "x");

        assertThatThrownBy(() -> service.attachFile(permanent, null, log, null, true))
                .hasMessageContaining("dauerhaft");
        assertThat(service.attachFile(temporary, null, log, null, true)).contains("angehängt");
        assertThat(service.removeFile(temporary, "a.log", true)).contains("entfernt");
    }

    @Test
    void pathsStayRelative() {
        assertThat(MemoryService.normalizeFilePath(" logs\\a.log ")).isEqualTo("logs/a.log");
        for (String bad : new String[] {"../x", "/etc/passwd", "a//b", "a b.txt", ""}) {
            assertThatThrownBy(() -> MemoryService.normalizeFilePath(bad)).hasMessageContaining("Ungültiger Dateipfad");
        }
        assertThat(MemoryService.fileName(Path.of("/tmp/Über uns.png"))).isEqualTo("_ber_uns.png");
        assertThat(MemoryService.fileName(Path.of("/tmp/.."))).isEqualTo("datei");
    }
}
