package systems.grebe.devtools.mcp.modules.graph;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import systems.grebe.devtools.mcp.modules.graph.CodeGraph.GraphFile;

/**
 * Zugriff auf die gespeicherten Code-Graphen: Stand prüfen, lesen, speichern und je Projekt und Branch verwalten. Ein
 * Graph gehört immer zu genau einem Projekt und einem Branch ({@link Key}); Projekte ohne Git haben genau einen
 * Graphen ({@code branch == null}).
 *
 * <p>Umsetzungen:
 * <ul>
 *   <li>{@code GraphStorage} im Backend – die zentrale Graph-Ablage (ArcadeDB, eingebettet oder extern). Im Local-Mode
 *       (Backend in der Desktop-App eingebettet) verwendet die App sie direkt.</li>
 *   <li>Mit Team-Server dieselbe Ablage über die GraphQL-API des Backends ({@code BackendGraphs} in der App) – Lesen
 *       und Schreiben laufen dann als GraphQL-Operationen.</li>
 *   <li>Datei-Ablage der App: {@code devtools-fileinfo*.graph} im Projekt.</li>
 * </ul>
 *
 * <p>Fehler der Ablage (nicht erreichbar, Anmeldung abgelehnt) kommen als {@link IllegalStateException} mit einem
 * Hinweis, was zu tun ist.
 */
public interface GraphProvider {

    /**
     * Graph eines Projekts auf einem Branch.
     *
     * @param project Anzeigename des Projekts (Ordnername)
     * @param root    Projektwurzel (absoluter Pfad auf dem Rechner der App) – identifiziert das Projekt in der Ablage
     * @param branch  Branch, {@code null} ohne Git
     */
    record Key(String project, String root, String branch) {

        public Key(String project, Path root, String branch) {
            this(project, root.toString(), branch);
        }

        /** Branch für Ausgaben. */
        public String branchLabel() {
            return branch == null ? "(ohne Git)" : branch;
        }

        /** Projektwurzel als Pfad – nur auf dem Rechner der App sinnvoll. */
        public Path path() {
            return Path.of(root);
        }
    }

    /** Stand eines gespeicherten Graphen – genug, um zu entscheiden, ob neu gebaut werden muss. */
    record State(String generator, Map<String, String> fileHashes) {
    }

    /** Übersicht über einen gespeicherten Graphen. */
    record Stored(String branch, String commit, String builtAt, long files, long nodes, long edges, String location) {
    }

    /** Kurzbeschreibung der Ablage für Meldungen, z.B. {@code ArcadeDB eingebettet (…/graphdb)}. */
    String describe();

    /**
     * Prüft, ob die Ablage erreichbar ist (vor einem minutenlangen Aufbau und für „Verbindung testen“), und liefert
     * eine Beschreibung mit Version.
     */
    default String check() {
        return describe();
    }

    /** Wo der Graph liegt (Dateipfad bzw. Datenbank + Schlüssel). */
    String location(Key key);

    /** Stand oder {@code null}, wenn es keinen (lesbaren) Graphen gibt. */
    State state(Key key);

    /** Leser oder {@code null}, wenn es keinen Graphen gibt. */
    GraphReader reader(Key key);

    /** Speichert den Graphen (ersetzt einen vorhandenen) und liefert einen Leser darauf. */
    GraphReader write(Key key, GraphFile data);

    /**
     * Gespeicherte Graphen des Projekts, nach Branch sortiert.
     *
     * @param root Projektwurzel wie in {@link Key#root()}
     */
    List<Stored> branches(String root);

    /** Löscht den Graphen eines Branches; {@code true}, wenn es einen gab. */
    boolean delete(String root, String branch);
}
