package systems.grebe.devtools.mcp.modules.memories;

import java.util.List;
import java.util.Optional;

/**
 * Memory-Speicher aus Sicht von Tools und Oberfläche: im Backend (eingebettet oder auf dem Team-Server). Texte sind
 * für das LLM formuliert; fachliche Fehler kommen als {@link IllegalArgumentException} mit einem Hinweis auf den
 * nächsten sinnvollen Schritt.
 */
public interface MemoryBackend {

    /** Nach jeder Änderung aufgerufen (beliebiger Thread). */
    void addChangeListener(Runnable listener);

    /** Neueste bzw. passendste Memories des Benutzers für die Oberfläche. */
    List<MemoryViews.Entry> overview(String query, String project, String skill, int limit);

    Optional<MemoryViews.Entry> details(long id);

    int count();

    String search(String query, String project, String skill, String tag, Integer days, Integer limit);

    String view(long id);

    String save(String title, String content, String project, String skill, String reference, List<String> tags,
                int maxContentChars);

    String update(long id, String title, String content, String append, String project, String skill,
                  String reference, List<String> tags, int maxContentChars);

    String delete(long id);
}
