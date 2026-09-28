package systems.grebe.devtools.mcp.modules.graph;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import systems.grebe.devtools.mcp.modules.graph.CodeGraph.GraphFile;

/**
 * Ablage der Code-Graphen. Ein Graph gehört immer zu genau einem Projekt und einem Branch ({@link Key}); Projekte
 * ohne Git haben genau einen Graphen ({@code branch == null}).
 *
 * <p>Umsetzungen: {@link Neo4jGraphStorage} (Standard) und {@link FileGraphStorage} ({@code devtools-fileinfo*.graph}
 * im Projekt).
 */
interface GraphStorage {

    /**
     * Graph eines Projekts auf einem Branch.
     *
     * @param project Anzeigename des Projekts (Ordnername)
     * @param root    Projektwurzel – identifiziert das Projekt in der Ablage
     * @param branch  Branch, {@code null} ohne Git
     */
    record Key(String project, Path root, String branch) {

        /** Branch für Ausgaben. */
        String branchLabel() {
            return branch == null ? "(ohne Git)" : branch;
        }
    }

    /** Stand eines gespeicherten Graphen – genug, um zu entscheiden, ob neu gebaut werden muss. */
    record State(String generator, Map<String, String> fileHashes) {
    }

    /** Übersicht über einen gespeicherten Graphen. */
    record Stored(String branch, String commit, String builtAt, long files, long nodes, long edges, String location) {
    }

    /** Kurzbeschreibung der Ablage für Meldungen, z.B. {@code neo4j bolt://localhost:7687}. */
    String describe();

    /** Wo der Graph liegt (Dateipfad bzw. Datenbank + Schlüssel). */
    String location(Key key);

    /** Stand oder {@code null}, wenn es keinen (lesbaren) Graphen gibt. */
    State state(Key key);

    /** Leser oder {@code null}, wenn es keinen Graphen gibt. */
    GraphReader reader(Key key);

    /** Speichert den Graphen (ersetzt einen vorhandenen) und liefert einen Leser darauf. */
    GraphReader write(Key key, GraphFile data);

    /** Gespeicherte Graphen des Projekts, nach Branch sortiert. */
    List<Stored> branches(Path root);

    /** Löscht den Graphen eines Branches; {@code true}, wenn es einen gab. */
    boolean delete(Path root, String branch);
}
