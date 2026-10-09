package systems.grebe.devtools.mcp.backend.blobs;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import systems.grebe.devtools.mcp.backend.memories.MemoryRepository;
import systems.grebe.devtools.mcp.backend.skills.SkillRepository;

/**
 * Wer welchen Inhalt der {@link BlobStore Dateiablage} verwendet: Zusatzdateien von Skills und Anhänge von Memories,
 * jeweils im Verzeichnis ihres Eigentümers. Gibt Inhalte frei, auf die nach einer Änderung nichts mehr verweist.
 */
@Component
public class BlobReferences {

    /** So lange bleibt ein frisch geschriebener Inhalt liegen – ein laufender Upload gleichen Inhalts braucht ihn. */
    static final Duration GRACE = Duration.ofMinutes(10);

    private final BlobStore store;
    private final SkillRepository skills;
    private final MemoryRepository memories;

    public BlobReferences(BlobStore store, SkillRepository skills, MemoryRepository memories) {
        this.store = store;
        this.skills = skills;
        this.memories = memories;
    }

    /**
     * In der laufenden Transaktion aufrufen, nachdem Verweise entfernt wurden: Inhalte ohne Verweis (gezählt nach dem
     * Flush) werden nach dem Commit gelöscht, bei Rollback nicht.
     */
    public void release(String owner, Collection<String> shas) {
        List<String> unused = shas.stream().filter(sha -> sha != null).distinct()
                .filter(sha -> skills.countFileReferences(owner, sha) + memories.countFileReferences(owner, sha) == 0)
                .toList();
        if (unused.isEmpty()) {
            return;
        }
        Runnable delete = () -> unused.forEach(sha -> store.deleteUnlessRecent(owner, sha,
                Instant.now().minus(GRACE)));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    delete.run();
                }
            });
        } else {
            delete.run();
        }
    }

    /** Alle verwendeten Inhalte als Schlüssel für {@link BlobStore#sweep}. */
    public Set<String> all() {
        Set<String> keys = new HashSet<>();
        for (Object[] row : skills.fileReferences()) {
            keys.add(BlobStore.key((String) row[0], (String) row[1]));
        }
        for (Object[] row : memories.fileReferences()) {
            keys.add(BlobStore.key((String) row[0], (String) row[1]));
        }
        return keys;
    }
}
