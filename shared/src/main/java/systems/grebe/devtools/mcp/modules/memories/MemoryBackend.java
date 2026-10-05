package systems.grebe.devtools.mcp.modules.memories;

import java.util.List;
import java.util.Optional;
import java.util.Set;

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

    /** Bezüge (Ticket-Keys, PRs …) aller Memories des Benutzers, klein – für Hinweise bei Tool-Aufrufen. */
    Set<String> references();

    /**
     * Neueste Memories mit einem der Bezüge (Groß-/Kleinschreibung egal) oder zu einem Skill; {@code content} bleibt
     * dabei leer (nur für kurze Hinweise).
     */
    List<MemoryViews.Entry> related(List<String> references, String skill, int limit);

    String search(String query, String project, String skill, String tag, Integer days, Integer limit);

    String view(long id);

    String save(String title, String content, String project, String skill, String reference, List<String> tags,
                int maxContentChars);

    String update(long id, String title, String content, String append, String project, String skill,
                  String reference, List<String> tags, int maxContentChars);

    String delete(long id);
}
