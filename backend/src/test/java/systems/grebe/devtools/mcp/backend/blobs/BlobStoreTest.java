package systems.grebe.devtools.mcp.backend.blobs;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import systems.grebe.devtools.mcp.backend.skills.SkillOwner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Dateiablage: Verzeichnis je Eigentümer, Inhalt unter seinem Hash, Upload in Teilen, Aufräumen. */
class BlobStoreTest {

    static final String USER = "Felix@Example.com";

    @TempDir
    Path root;

    @Test
    void ownerDirectories() {
        assertThat(BlobStore.dirName(USER)).isEqualTo("felix@example.com");
        assertThat(BlobStore.dirName(SkillOwner.GLOBAL)).isEqualTo("GLOBAL");
        assertThat(BlobStore.dirName("../x@y")).isEqualTo("_.._x@y");
        assertThat(BlobStore.dirName("global")).isEqualTo("_global");
    }

    @Test
    void putStoresUnderHashOncePerOwner() throws Exception {
        BlobStore store = new BlobStore(root);
        byte[] data = "Hallo Anhang".getBytes();
        BlobStore.Blob a = store.put(USER, new ByteArrayInputStream(data));
        BlobStore.Blob b = store.put(USER, new ByteArrayInputStream(data));

        assertThat(a).isEqualTo(b);
        assertThat(a.sha()).isEqualTo(sha(data));
        assertThat(a.size()).isEqualTo(data.length);
        assertThat(root.resolve("felix@example.com").resolve(a.sha())).hasBinaryContent(data);
        try (var files = Files.list(root.resolve("felix@example.com"))) {
            assertThat(files).hasSize(1); // keine temporären Reste
        }
        // fremde Benutzer sehen den Inhalt nicht
        assertThat(store.find("other@example.com", a.sha())).isEmpty();
        assertThatThrownBy(() -> store.require("other@example.com", a.sha())).hasMessageContaining("erneut hochladen");
    }

    @Test
    void chunkedUploadToleratesRepeatedPart() throws Exception {
        BlobStore store = new BlobStore(root);
        String id = store.startUpload(USER);
        assertThat(store.append(USER, id, 0, new ByteArrayInputStream("Teil 1|".getBytes()))).isEqualTo(7);
        assertThat(store.append(USER, id, 7, new ByteArrayInputStream("Teil X".getBytes()))).isEqualTo(13);
        // zweiter Teil wird nach einem Abbruch wiederholt – ab offset überschrieben
        assertThat(store.append(USER, id, 7, new ByteArrayInputStream("Teil 2".getBytes()))).isEqualTo(13);
        assertThatThrownBy(() -> store.append(USER, id, 99, new ByteArrayInputStream(new byte[1])))
                .hasMessageContaining("Position 99");

        BlobStore.Blob blob = store.complete(USER, id);
        assertThat(blob.sha()).isEqualTo(sha("Teil 1|Teil 2".getBytes()));
        assertThat(store.require(USER, blob.sha())).hasContent("Teil 1|Teil 2");
        assertThatThrownBy(() -> store.complete(USER, id)).hasMessageContaining("gibt es nicht");
        assertThatThrownBy(() -> store.append(USER, "../../etc", 0, new ByteArrayInputStream(new byte[0])))
                .hasMessageContaining("Ungültige Upload-Kennung");
    }

    @Test
    void copyAndSweep() throws Exception {
        BlobStore store = new BlobStore(root);
        BlobStore.Blob used = store.put(USER, new ByteArrayInputStream("benutzt".getBytes()));
        BlobStore.Blob orphan = store.put(USER, new ByteArrayInputStream("verwaist".getBytes()));
        BlobStore.Blob fresh = store.put(USER, new ByteArrayInputStream("frisch".getBytes()));
        store.copy(USER, SkillOwner.GLOBAL, used.sha());
        assertThat(store.find(SkillOwner.GLOBAL, used.sha())).isPresent();
        String upload = store.startUpload(USER);

        Instant old = Instant.now().minus(Duration.ofDays(2));
        for (Path p : new Path[] {store.require(USER, used.sha()), store.require(USER, orphan.sha()),
                root.resolve("felix@example.com/.uploads").resolve(upload)}) {
            Files.setLastModifiedTime(p, FileTime.from(old));
        }
        int deleted = store.sweep(Set.of(BlobStore.key(USER, used.sha()), BlobStore.key(SkillOwner.GLOBAL,
                used.sha())), Instant.now().minus(Duration.ofDays(1)));

        assertThat(deleted).isEqualTo(2); // verwaister Inhalt und abgebrochener Upload
        assertThat(store.find(USER, used.sha())).isPresent();
        assertThat(store.find(USER, orphan.sha())).isEmpty();
        assertThat(store.find(USER, fresh.sha())).isPresent(); // zu jung
        assertThat(store.find(SkillOwner.GLOBAL, used.sha())).isPresent();
    }

    static String sha(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }
}
