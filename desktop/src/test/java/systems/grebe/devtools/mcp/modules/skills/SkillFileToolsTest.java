package systems.grebe.devtools.mcp.modules.skills;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import systems.grebe.devtools.mcp.core.LocalFiles;
import systems.grebe.devtools.mcp.core.ToolScope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Zusatzdateien über die Tools: Text bleibt im Skill, Binäres und Großes wird Anhang; nur aus Freigaben. */
class SkillFileToolsTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0, 0, 0, 13};

    @TempDir
    Path shared;
    @TempDir
    Path outside;
    @TempDir
    Path data;

    SkillBackend service = mock(SkillBackend.class);
    SkillWriteTools write;
    SkillReadTools read;

    @BeforeEach
    void setUp() {
        LocalFiles files = new LocalFiles(List.of(shared.toString()), "Module → Skills");
        write = new SkillWriteTools(service, 1_000, files);
        read = new SkillReadTools(service, files, data.resolve("attachments"));
    }

    @Test
    void shortTextStaysInline() {
        write.writeFile("deploy", "references/a.md", "# Ablauf", null, null, null, "n");
        verify(service).writeFile("deploy", "references/a.md", "# Ablauf", "n", 1_000);
    }

    @Test
    void binaryFromSharedDirectoryBecomesAttachment() throws Exception {
        Path png = Files.write(shared.resolve("logo.png"), PNG);
        write.writeFile("deploy", null, null, png.toString(), null, null, null);
        verify(service).attachFile("deploy", "assets/logo.png", png, "image/png", null);
    }

    @Test
    void textFileFromPathStaysPatchable() throws Exception {
        Path md = Files.writeString(shared.resolve("notes.md"), "Schritt 1");
        write.writeFile("deploy", "references/notes.md", null, md.toString(), null, null, null);
        verify(service).writeFile("deploy", "references/notes.md", "Schritt 1", null, 1_000);
        verify(service, never()).attachFile(any(), any(), any(), any(), any());
    }

    @Test
    void longTextBecomesAttachment() {
        AtomicReference<String> uploaded = new AtomicReference<>();
        when(service.attachFile(eq("deploy"), eq("references/big.md"), any(), eq("text/markdown"), isNull()))
                .thenAnswer(inv -> {
                    uploaded.set(Files.readString(inv.getArgument(2)));
                    return "ok";
                });
        String big = "x".repeat(5_000);
        write.writeFile("deploy", "references/big.md", big, null, null, null, null);
        assertThat(uploaded.get()).isEqualTo(big);
        verify(service, never()).writeFile(any(), any(), any(), any(), anyInt());
    }

    @Test
    void base64IsDecodedIntoTemporaryFile() {
        AtomicReference<byte[]> uploaded = new AtomicReference<>();
        AtomicReference<Path> temp = new AtomicReference<>();
        when(service.attachFile(eq("deploy"), eq("assets/x.png"), any(), eq("image/png"), isNull()))
                .thenAnswer(inv -> {
                    temp.set(inv.getArgument(2));
                    uploaded.set(Files.readAllBytes(temp.get()));
                    return "ok";
                });
        write.writeFile("deploy", "assets/x.png", null, null,
                "data:image/png;base64," + Base64.getEncoder().encodeToString(PNG), null, null);
        assertThat(uploaded.get()).isEqualTo(PNG);
        assertThat(temp.get()).doesNotExist(); // temporäre Datei wieder weg
    }

    @Test
    void sourceMustBeShared() throws Exception {
        Path secret = Files.writeString(outside.resolve("id_rsa"), "geheim");
        assertThatThrownBy(() -> write.writeFile("deploy", "assets/key", null, secret.toString(), null, null, null))
                .hasMessageContaining("nicht freigegeben");
        // Symlink aus der Freigabe heraus zählt nicht
        Path link = Files.createSymbolicLink(shared.resolve("link"), secret);
        assertThatThrownBy(() -> write.writeFile("deploy", "assets/key", null, link.toString(), null, null, null))
                .hasMessageContaining("nicht freigegeben");
        // ohne Beschränkung („Freigaben“ → Beschränkung aufheben) geht jeder Pfad
        ToolScope scope = new ToolScope("t", null, null, null, null, true);
        scope.setUnrestricted(true);
        ToolScope.callIn(scope, () -> write.writeFile("deploy", "assets/key.bin", null, secret.toString(), null,
                "application/octet-stream", null));
        verify(service).attachFile("deploy", "assets/key.bin", secret, "application/octet-stream", null);
    }

    @Test
    void exactlyOneSource() {
        assertThatThrownBy(() -> write.writeFile("deploy", "references/a.md", "x", "/tmp/a", null, null, null))
                .hasMessageContaining("Genau eines");
    }

    @Test
    void binaryViewIsSavedLocally() {
        when(service.file("deploy", "assets/logo.png")).thenReturn(Optional.of(new SkillViews.File(
                "assets/logo.png", null, Instant.now(), 8, "image/png", "a".repeat(64))));
        when(service.exportFile(eq("deploy"), eq("assets/logo.png"), any())).thenReturn("gespeichert");

        assertThat(read.view("deploy", "assets/logo.png", null)).contains("gespeichert", "Read-Tool");
        verify(service).exportFile("deploy", "assets/logo.png",
                data.resolve("attachments/skills/deploy/assets/logo.png"));

        read.view("deploy", "assets/logo.png", shared.toString());
        verify(service).exportFile("deploy", "assets/logo.png", shared.resolve("logo.png"));
        assertThatThrownBy(() -> read.view("deploy", "assets/logo.png", outside.toString()))
                .hasMessageContaining("nicht freigegeben");
    }

    @Test
    void textViewComesInline() {
        when(service.file("deploy", "references/a.md")).thenReturn(Optional.of(new SkillViews.File(
                "references/a.md", "# A", Instant.now())));
        when(service.view("deploy", "references/a.md")).thenReturn("# A");
        assertThat(read.view("deploy", "references/a.md", null)).isEqualTo("# A");
    }
}
