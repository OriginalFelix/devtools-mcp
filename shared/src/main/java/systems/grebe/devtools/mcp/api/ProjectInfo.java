package systems.grebe.devtools.mcp.api;

import java.util.Map;

/**
 * Ein für den Benutzer sichtbares Projekt ({@code GET /api/projects}). Das Verzeichnis ordnet die Desktop-App zu.
 *
 * @param toolName Name, unter dem Tools das Projekt kennen: eigene unter ihrem Namen, fremde als
 *                 {@code name@eigentümer}
 * @param writable ob schreibende Tools (Commit, Build, Graph-Aufbau) erlaubt sind
 */
public record ProjectInfo(long id, String name, String owner, String toolName, boolean writable, String access,
                          String description, String sonarKey, String ticketProject) {

    /** Modul → Feld, das die Desktop-App aus den zugeordneten Projektverzeichnissen füllt. */
    public static final Map<String, String> DIRECTORY_FIELDS = Map.of("git", "repositories", "build", "projects",
            "graph", "projects", "pr", "repositories");

    /** Modul → Feld mit dem Standardprojekt. */
    public static final Map<String, String> DEFAULT_FIELDS = Map.of("git", "defaultRepository",
            "build", "defaultProject", "graph", "defaultProject", "pr", "defaultRepository");

    /** Ob die Desktop-App das Feld bei Server-Anbindung aus den Projekten füllt (Überschreiben wirkungslos). */
    public static boolean projectField(String moduleId, String key) {
        return key.equals(DIRECTORY_FIELDS.get(moduleId));
    }
}
