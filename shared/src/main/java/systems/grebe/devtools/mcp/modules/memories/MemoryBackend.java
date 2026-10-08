package systems.grebe.devtools.mcp.modules.memories;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Memory-Speicher aus Sicht von Tools und Oberfläche: im Backend (eingebettet oder auf dem Team-Server). Texte sind
 * für das LLM formuliert; fachliche Fehler kommen als {@link IllegalArgumentException} mit einem Hinweis auf den
 * nächsten sinnvollen Schritt.
 *
 * <p>{@code temporaryOnly}: Der Aufrufer hat keine Freigabe für dauerhafte Memories – geändert bzw. gelöscht
 * werden dürfen dann nur temporäre ({@link MemoryViews.Type#TEMPORARY}), und keine wird dauerhaft gemacht.
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

    /** @param type nur Memories dieses Typs, {@code null} = alle */
    String search(String query, String project, String skill, String tag, MemoryViews.Type type, Integer days,
                  Integer limit);

    String view(long id);

    /** @param type {@code null} = {@link MemoryViews.Type#PERMANENT} */
    String save(String title, String content, MemoryViews.Type type, String project, String skill, String reference,
                List<String> tags, int maxContentChars);

    /** @param type neuer Typ, {@code null} = unverändert */
    String update(long id, String title, String content, String append, MemoryViews.Type type, String project,
                  String skill, String reference, List<String> tags, boolean temporaryOnly, int maxContentChars);

    String delete(long id, boolean temporaryOnly);
}
