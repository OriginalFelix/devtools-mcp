package systems.grebe.devtools.mcp.project.spi;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;

/**
 * Die Projektverzeichnisse, die der Benutzer in der App freigegeben hat – für Plugins, die wie Git, Build und Code-Graph
 * in Projekten arbeiten. Dazu gehören Backend-Projekte mit lokalem Verzeichnis, die global freigegebenen Verzeichnisse
 * (Modul „Freigaben“) und die in Git, Build, Code-Graph und Pull Requests eingetragenen. Sammelordner werden zu ihren
 * Unterordnern aufgelöst, die {@code marker} erfüllen. Die App stellt genau eine Bean bereit:
 *
 * <pre>{@code
 * MyModule(ObjectProvider<ProjectProvider> projects) { … }
 *
 * Predicate<Path> gradle = dir -> Files.exists(dir.resolve("settings.gradle")) || …;
 * ProjectDirectory p = projects.resolve(workingDirectory, gradle);   // Name, Pfad oder Unterverzeichnis
 * }</pre>
 *
 * <p>Ist im Modul „Freigaben“ die Beschränkung aufgehoben, löst {@link #resolve} auch absolute Pfade außerhalb auf –
 * zum nächsten Verzeichnis darüber, das {@code marker} erfüllt. Schreiben oder Code des Projekts ausführen (Builds) nur,
 * wenn {@link ProjectDirectory#writable()} gilt.
 */
public interface ProjectProvider {

    /**
     * Alle freigegebenen Projekte, die {@code marker} erfüllen.
     *
     * @param marker z.B. „enthält {@code build.gradle}“; {@code null} = jedes Verzeichnis
     */
    List<ProjectDirectory> projects(Predicate<Path> marker);

    /**
     * Ein Projekt nach Name (wie in {@code projects_list}, Groß-/Kleinschreibung egal) oder Pfad – auch ein
     * Unterverzeichnis davon, etwa das Arbeitsverzeichnis des Clients. Ohne Angabe das einzige Projekt.
     *
     * @throws IllegalArgumentException wenn nichts oder mehreres passt bzw. der Pfad nicht freigegeben ist (mit den
     *                                  verfügbaren Projekten in der Meldung)
     * @throws IllegalStateException    wenn gar kein Projekt freigegeben ist
     */
    ProjectDirectory resolve(String nameOrPath, Predicate<Path> marker);
}
