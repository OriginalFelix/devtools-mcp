package systems.grebe.devtools.mcp.modules.skills;

import java.util.List;
import java.util.Optional;

/**
 * Skill-Speicher aus Sicht von Tools und Oberfläche: lokal ({@link SkillService} auf der konfigurierten Datenbank) oder
 * – bei Anbindung an einen Team-Server – zentral auf dem Server. Texte sind für das LLM formuliert; fachliche Fehler
 * kommen als {@link IllegalArgumentException} mit einem Hinweis auf den nächsten sinnvollen Schritt.
 */
public interface SkillBackend {

    /** Nach jeder Änderung aufgerufen (beliebiger Thread). */
    void addChangeListener(Runnable listener);

    List<SkillViews.Summary> overview();

    Optional<SkillViews.Details> details(String name);

    String publish(String name);

    String unpublish(String name);

    String list(String query, String category);

    String view(String name, String filePath);

    String history(String name, Integer revision);

    int visibleCount();

    /**
     * @param triggers Registrierung: Tool-Namen oder Präfixe mit {@code *} (z.B. {@code ticket_get}, {@code pr_*}),
     *                 bei deren Aufruf der Server auf den Skill hinweist; {@code null} = keine
     */
    String create(String name, String description, String content, String category, List<String> tags,
                  List<String> triggers, int maxContentChars);

    default String create(String name, String description, String content, String category, List<String> tags,
                          int maxContentChars) {
        return create(name, description, content, category, tags, null, maxContentChars);
    }

    /** {@code triggers}: {@code null} = unverändert, leere Liste = entfernen. */
    String update(String name, String description, String content, String category, List<String> tags,
                  List<String> triggers, String note, Integer expectedRevision, int maxContentChars);

    default String update(String name, String description, String content, String category, List<String> tags,
                          String note, Integer expectedRevision, int maxContentChars) {
        return update(name, description, content, category, tags, null, note, expectedRevision, maxContentChars);
    }

    String patch(String name, String oldString, String newString, Boolean replaceAll, String filePath, String note,
                 Integer expectedRevision, int maxContentChars);

    String writeFile(String name, String filePath, String content, String note, int maxContentChars);

    String removeFile(String name, String filePath, String note);

    String delete(String name);
}
