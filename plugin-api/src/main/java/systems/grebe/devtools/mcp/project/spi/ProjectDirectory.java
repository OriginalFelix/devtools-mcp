package systems.grebe.devtools.mcp.project.spi;

import java.nio.file.Path;

/**
 * Ein freigegebenes Projektverzeichnis, wie es {@link ProjectProvider} liefert.
 *
 * @param name     Name, unter dem Tools das Projekt ansprechen (Backend-Projekte wie in {@code projects_list}, sonst
 *                 der Ordnername)
 * @param path     absolutes, normalisiertes Verzeichnis
 * @param writable ob der Benutzer dort schreiben und Code des Projekts ausführen darf (nicht bei „nur lesen“
 *                 freigegebenen Projekten)
 */
public record ProjectDirectory(String name, Path path, boolean writable) {
}
