package systems.grebe.devtools.mcp.modules.skills;

import java.nio.file.Path;
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

    /**
     * Legt eine Zusatzdatei mit beliebigem Inhalt (auch binär, ohne Größengrenze) als Anhang in der Dateiablage des
     * Backends an oder ersetzt sie. Anhänge lassen sich nicht patchen, nur ersetzen.
     *
     * @param source    lokale Datei mit dem Inhalt
     * @param mediaType z.B. {@code image/png}; {@code null} = aus dem Pfad raten
     */
    String attachFile(String name, String filePath, Path source, String mediaType, String note);

    /** Metadaten einer Zusatzdatei (Text oder Anhang); leer, wenn es Skill oder Datei nicht gibt. */
    Optional<SkillViews.File> file(String name, String filePath);

    /** Schreibt den Inhalt einer Zusatzdatei (Text oder Anhang) nach {@code target}; vorhandene wird ersetzt. */
    String exportFile(String name, String filePath, Path target);

    String removeFile(String name, String filePath, String note);

    String delete(String name);
}
