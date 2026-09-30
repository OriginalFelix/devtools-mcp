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

    String create(String name, String description, String content, String category, List<String> tags,
                  int maxContentChars);

    String update(String name, String description, String content, String category, List<String> tags, String note,
                  Integer expectedRevision, int maxContentChars);

    String patch(String name, String oldString, String newString, Boolean replaceAll, String filePath, String note,
                 Integer expectedRevision, int maxContentChars);

    String writeFile(String name, String filePath, String content, String note, int maxContentChars);

    String removeFile(String name, String filePath, String note);

    String delete(String name);
}
