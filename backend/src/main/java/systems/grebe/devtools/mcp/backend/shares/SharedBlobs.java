package systems.grebe.devtools.mcp.backend.shares;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import systems.grebe.devtools.mcp.backend.memories.MemoryRepository;
import systems.grebe.devtools.mcp.backend.skills.SkillRepository;

/**
 * Anhänge geteilter Skills und Memories liegen in der Dateiablage ihres Eigentümers: Wer sie sehen darf, darf sie auch
 * über {@code GET /blobs/<sha256>} laden.
 */
@Component
public class SharedBlobs {

    private final ShareStore shares;
    private final SkillRepository skills;
    private final MemoryRepository memories;

    public SharedBlobs(ShareStore shares, SkillRepository skills, MemoryRepository memories) {
        this.shares = shares;
        this.skills = skills;
        this.memories = memories;
    }

    /** Eigentümer eines für den aktuellen Benutzer freigegebenen Skills bzw. einer Memory mit diesem Anhang. */
    @Transactional(readOnly = true)
    public Optional<String> owner(String blob) {
        List<Long> sharedSkills = shares.visible(ItemShare.Kind.SKILL);
        if (!sharedSkills.isEmpty()) {
            Optional<String> owner = skills.ownersOfBlob(sharedSkills, blob).stream().findFirst();
            if (owner.isPresent()) {
                return owner;
            }
        }
        List<Long> sharedMemories = shares.visible(ItemShare.Kind.MEMORY);
        return sharedMemories.isEmpty() ? Optional.empty()
                : memories.ownersOfBlob(sharedMemories, blob).stream().findFirst();
    }
}
