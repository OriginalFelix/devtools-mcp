package systems.grebe.devtools.mcp.config;

import java.util.Map;
import java.util.TreeMap;

/**
 * Anbindung an das Backend: ein Team-Server (optional, sonst eingebettet) und der zuletzt angemeldete Benutzer.
 *
 * @param url          Adresse des Servers, z.B. {@code https://devtools.example.com}; leer = eingebettetes Backend
 * @param token        persönliches Desktop-Token aus „Mein Konto“ (JWT), verschlüsselt gespeichert – nur für den Start
 *                     ohne Fenster ({@code --headless}); mit Fenster meldet sich jeder beim Start an
 * @param username     zuletzt angemeldeter Benutzer (Vorbelegung des Anmeldedialogs)
 * @param projectPaths lokales Verzeichnis je Server-Projekt (Projekt-ID → Pfad)
 */
public record TeamSettings(String url, String token, String username, Map<Long, String> projectPaths) {

    public static TeamSettings none() {
        return new TeamSettings("", "", "", Map.of());
    }

    public TeamSettings {
        url = url == null ? "" : url.strip().replaceAll("/+$", "");
        token = token == null ? "" : token.strip();
        username = username == null ? "" : username.strip();
        projectPaths = projectPaths == null ? Map.of() : Map.copyOf(projectPaths);
    }

    /** Ein Team-Server ist eingetragen. */
    public boolean configured() {
        return !url.isEmpty();
    }

    public TeamSettings withServer(String value) {
        return new TeamSettings(value, value == null || value.isBlank() ? "" : token, username, projectPaths);
    }

    public TeamSettings withUsername(String value) {
        return new TeamSettings(url, token, value, projectPaths);
    }

    public TeamSettings withProjectPath(long projectId, String path) {
        Map<Long, String> paths = new TreeMap<>(projectPaths);
        if (path == null || path.isBlank()) {
            paths.remove(projectId);
        } else {
            paths.put(projectId, path.strip());
        }
        return new TeamSettings(url, token, username, paths);
    }
}
