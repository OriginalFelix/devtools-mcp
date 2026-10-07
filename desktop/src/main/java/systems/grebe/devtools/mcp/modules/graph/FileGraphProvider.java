package systems.grebe.devtools.mcp.modules.graph;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import systems.grebe.devtools.mcp.modules.graph.CodeGraph.FileEntry;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.GraphFile;

/**
 * Datei-Ablage: je Branch eine Datei im Projektwurzelverzeichnis ({@code devtools-fileinfo@<branch>.graph}, ohne Git
 * {@code devtools-fileinfo.graph}). Abfragen laufen auf dem geladenen Graphen ({@link MemoryGraphReader}).
 */
final class FileGraphProvider implements GraphProvider {

    @Override
    public String describe() {
        return "Datei im Projekt";
    }

    @Override
    public String location(Key key) {
        return GraphStore.fileFor(key.path(), key.branch()).toString();
    }

    private static CodeGraph load(Key key) {
        return GraphStore.loadFile(GraphStore.fileFor(key.path(), key.branch()));
    }

    @Override
    public State state(Key key) {
        CodeGraph g;
        try {
            g = load(key);
        } catch (RuntimeException e) {
            return null; // beschädigt/fremd -> neu bauen
        }
        if (g == null) {
            return null;
        }
        Map<String, String> hashes = new HashMap<>();
        for (FileEntry f : g.data().files()) {
            hashes.put(f.path(), f.sha256());
        }
        return new State(g.data().generator(), hashes);
    }

    @Override
    public GraphReader reader(Key key) {
        CodeGraph g = load(key);
        return g == null ? null : new MemoryGraphReader(g, key.branch(), g.data().commit(), location(key));
    }

    @Override
    public GraphReader write(Key key, GraphFile data) {
        CodeGraph g = GraphStore.writeFile(GraphStore.fileFor(key.path(), key.branch()), data);
        return new MemoryGraphReader(g, key.branch(), data.commit(), location(key));
    }

    @Override
    public List<Stored> branches(Key project) {
        Path root = project.path();
        List<Stored> out = new ArrayList<>();
        for (Path file : GraphStore.files(root)) {
            try {
                Map<String, Object> h = GraphStore.headerFile(file);
                String branch = (String) h.get("branch");
                if (branch == null && !file.equals(GraphStore.fileFor(root))) {
                    continue; // Datei ohne Branch-Kopf (ältere Version) gehört zu keinem Branch
                }
                out.add(new Stored(branch, (String) h.get("commit"), (String) h.get("builtAt"), number(h.get("files")),
                        number(h.get("nodes")), number(h.get("edges")), file.toString(), null));
            } catch (IOException | RuntimeException e) {
                // unlesbare Datei überspringen
            }
        }
        out.sort(Comparator.comparing(s -> s.branch() == null ? "" : s.branch()));
        return out;
    }

    private static long number(Object o) {
        return o instanceof Number n ? n.longValue() : 0;
    }

    @Override
    public boolean delete(Key key) {
        Path root = key.path();
        String branch = key.branch();
        try {
            return Files.deleteIfExists(GraphStore.fileFor(root, branch));
        } catch (IOException e) {
            throw new UncheckedIOException("Graph-Datei nicht löschbar: " + GraphStore.fileFor(root, branch), e);
        }
    }
}
