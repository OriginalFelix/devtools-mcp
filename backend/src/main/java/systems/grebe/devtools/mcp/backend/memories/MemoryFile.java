package systems.grebe.devtools.mcp.backend.memories;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import systems.grebe.devtools.mcp.api.MediaTypes;
import systems.grebe.devtools.mcp.backend.blobs.BlobStore;

/**
 * An eine Memory angehängte Datei mit beliebigem Inhalt (Screenshot, Log, Export …). Der Inhalt liegt in der
 * {@link BlobStore Dateiablage}, hier nur Name, Größe, Medientyp und Hash.
 */
@Entity
@Table(name = "memory_file",
        uniqueConstraints = @UniqueConstraint(name = "uk_memory_file_path", columnNames = {"memory_id", "path"}))
public class MemoryFile {

    @Id
    @GeneratedValue
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "memory_id", foreignKey = @ForeignKey(name = "fk_memory_file_memory"))
    private Memory memory;

    @Column(nullable = false, length = 200)
    private String path;

    /** SHA-256 des Inhalts in der Dateiablage. */
    @Column(nullable = false, length = 64)
    private String blob;

    @Column(name = "byte_size", nullable = false)
    private long size;

    @Column(nullable = false, length = MediaTypes.MAX_LENGTH)
    private String mediaType;

    @Column(nullable = false)
    private Instant updatedAt;

    protected MemoryFile() {
        // JPA
    }

    MemoryFile(Memory memory, String path, BlobStore.Blob blob, String mediaType, Instant now) {
        this.memory = memory;
        this.path = path;
        replace(blob, mediaType, now);
    }

    void replace(BlobStore.Blob blob, String mediaType, Instant now) {
        this.blob = blob.sha();
        this.size = blob.size();
        this.mediaType = mediaType;
        this.updatedAt = now;
    }

    public String getPath() {
        return path;
    }

    public String getBlob() {
        return blob;
    }

    public long getSize() {
        return size;
    }

    public String getMediaType() {
        return mediaType;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
