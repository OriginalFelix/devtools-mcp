package systems.grebe.devtools.mcp.modules.graph;

import java.nio.file.Path;

/**
 * Wer ein Projektverzeichnis ist – daran hängt der Graph in der Datenbank, nicht am Pfad: der Name aus dem
 * {@code ProjectProvider} und, wenn das Verzeichnis einem Backend-Projekt zugeordnet ist, dessen ID. Dann teilen sich
 * alle mit Zugriff auf das Projekt je Branch denselben Graphen, egal auf welchem Rechner und unter welchem Pfad.
 */
@FunctionalInterface
public interface GraphProjects {

    /**
     * @param name      Projektname; Backend-Projekte eindeutig als {@code name@eigentümer}
     * @param projectId Backend-Projekt oder {@code null}
     */
    record Identity(String name, Long projectId) {
    }

    /** @return {@code null}, wenn das Verzeichnis kein bekanntes Projekt ist (dann gilt der Name aus den Einstellungen) */
    Identity identify(Path root);
}
