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
     * Graph eines Projekts auf einem Branch. In der Datenbank identifiziert ihn das Projekt – ein Backend-Projekt
     * ({@code projectId}) für alle Benutzer mit Zugriff gemeinsam, sonst der Projektname im Bereich des Benutzers – und
     * der Branch; Pfad und Rechner spielen keine Rolle. Die Datei-Ablage liegt im Projektverzeichnis ({@code root}).
     *
     * @param project   Projektname wie im {@code ProjectProvider}; Backend-Projekte eindeutig als {@code name@eigentümer}
     * @param root      Projektwurzel (absoluter Pfad auf dem Rechner der App)
     * @param branch    Branch, {@code null} ohne Git
     * @param projectId Backend-Projekt, {@code null} für andere Verzeichnisse
     */
    record Key(String project, String root, String branch, Long projectId) {

        public Key(String project, String root, String branch) {
            this(project, root, branch, null);
        }

        public Key(String project, Path root, String branch) {
            this(project, root.toString(), branch, null);
        }

        /** Derselbe Graph auf einem anderen Branch. */
        public Key withBranch(String other) {
            return new Key(project, root, other, projectId);
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

    /**
     * Übersicht über einen gespeicherten Graphen.
     *
     * @param builtBy wer ihn zuletzt gebaut hat ({@link #localBuilder()}); {@code null} bei der Datei-Ablage
     */
    record Stored(String branch, String commit, String builtAt, long files, long nodes, long edges, String location,
                  String builtBy) {
    }

    /**
     * Wer auf diesem Rechner baut: {@code <betriebssystem-benutzer>@<rechner>}. Graphen gelöschter Branches entfernt
     * die App nur, wenn sie hier gebaut wurden – den Branch eines anderen kennt das eigene Git womöglich nicht.
     */
    static String localBuilder() {
        return LocalBuilder.VALUE;
    }

    /** Einmal ermittelt – die Namensauflösung des Rechners kann dauern. */
    final class LocalBuilder {

        static final String VALUE = System.getProperty("user.name", "?") + "@" + host();

        private LocalBuilder() {
        }

        private static String host() {
            try {
                return java.net.InetAddress.getLocalHost().getHostName();
            } catch (java.io.IOException e) {
                return System.getenv().getOrDefault("COMPUTERNAME", System.getenv().getOrDefault("HOSTNAME", "?"));
            }
        }
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
     * Speichert inkrementell: wendet {@code delta} auf den gespeicherten Graphen an, wenn dort noch die Generation
     * {@code base} gilt ({@link GraphReader#generation()}) – in einem Schritt, Leser sehen den alten oder den neuen Stand.
     *
     * @return Leser auf den neuen Stand oder {@code null}, wenn das nicht geht (inzwischen anderer Stand gespeichert,
     * Generation mit anderen Branches geteilt, Ablage kann es nicht) – dann ganz neu schreiben ({@link #write})
     */
    default GraphReader update(Key key, String base, GraphDelta delta) {
        return null;
    }

    /**
     * Übernimmt für den Branch von {@code key} den gespeicherten Graphen von {@code source} (gleiches Projekt, gleicher
     * Stand – z.B. ein eben angelegter Branch): beide zeigen auf dieselbe Generation, nichts wird gebaut oder kopiert.
     * Ein späterer Aufbau eines der beiden schreibt eine eigene Generation.
     *
     * @return Leser oder {@code null}, wenn das nicht geht (kein Graph für {@code source}, Ablage kann es nicht)
     */
    default GraphReader link(Key key, Key source) {
        return null;
    }

    /**
     * Gespeicherte Graphen des Projekts, nach Branch sortiert.
     *
     * @param project Projekt; der Branch des Schlüssels spielt keine Rolle
     */
    List<Stored> branches(Key project);

    /** Löscht den Graphen des Branches; {@code true}, wenn es einen gab. */
    boolean delete(Key key);
}
