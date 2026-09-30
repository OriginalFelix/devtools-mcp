package systems.grebe.devtools.mcp.config;

import java.util.Map;
import java.util.TreeMap;

/**
 * Anbindung an einen Team-Server (optional).
 *
 * @param url          Adresse des Servers, z.B. {@code https://devtools.example.com}; leer = keine Anbindung
 * @param token        Desktop-Token aus „Mein Konto“ (JWT), verschlüsselt gespeichert
 * @param projectPaths lokales Verzeichnis je Server-Projekt (Projekt-ID → Pfad)
 */
public record TeamSettings(String url, String token, Map<Long, String> projectPaths) {

    public static TeamSettings none() {
        return new TeamSettings("", "", Map.of());
    }

    public TeamSettings {
        url = url == null ? "" : url.strip().replaceAll("/+$", "");
        token = token == null ? "" : token.strip();
        projectPaths = projectPaths == null ? Map.of() : Map.copyOf(projectPaths);
    }

    public boolean configured() {
        return !url.isEmpty() && !token.isEmpty();
    }

    public TeamSettings withProjectPath(long projectId, String path) {
        Map<Long, String> paths = new TreeMap<>(projectPaths);
        if (path == null || path.isBlank()) {
            paths.remove(projectId);
        } else {
            paths.put(projectId, path.strip());
        }
        return new TeamSettings(url, token, paths);
    }
}
