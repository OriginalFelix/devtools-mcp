package systems.grebe.devtools.mcp.remote;

import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.backend.graph.GraphStorage;
import systems.grebe.devtools.mcp.modules.graph.CodeGraph.GraphFile;
import systems.grebe.devtools.mcp.modules.graph.GraphDelta;
import systems.grebe.devtools.mcp.modules.graph.GraphProvider;
import systems.grebe.devtools.mcp.modules.graph.GraphReader;

/**
 * Graph-Storage für das Graph-Modul: im Local-Mode (eingebettetes Backend) direkt die {@link GraphStorage} im selben
 * Prozess – ohne Umweg über HTTP –, mit Team-Server dessen GraphQL-API ({@link GraphQlGraphProvider}). Entschieden
 * wird bei jedem Zugriff, ein Wechsel des Backends gilt also sofort.
 */
@Primary // eingebettet ist auch die GraphStorage des Backends ein GraphProvider
@Component
public class BackendGraphs implements GraphProvider {

    private final BackendConnection backend;
    private final ObjectProvider<GraphStorage> local;
    private final GraphQlGraphProvider remote;

    public BackendGraphs(BackendConnection backend, ObjectProvider<GraphStorage> local) {
        this.backend = backend;
        this.local = local;
        this.remote = new GraphQlGraphProvider(backend);
    }

    /** Die gerade zuständige Ablage. */
    GraphProvider current() {
        GraphStorage storage = backend.embedded() ? local.getIfAvailable() : null;
        return storage != null ? storage : remote;
    }

    @Override
    public String describe() {
        return current().describe();
    }

    @Override
    public String check() {
        return current().check();
    }

    @Override
    public String location(Key key) {
        return current().location(key);
    }

    @Override
    public State state(Key key) {
        return current().state(key);
    }

    @Override
    public GraphReader reader(Key key) {
        return current().reader(key);
    }

    @Override
    public GraphReader write(Key key, GraphFile data) {
        return current().write(key, data);
    }

    @Override
    public List<Stored> branches(Key project) {
        return current().branches(project);
    }

    @Override
    public boolean delete(Key key) {
        return current().delete(key);
    }

    @Override
    public GraphReader update(Key key, String base, GraphDelta delta) {
        return current().update(key, base, delta);
    }

    @Override
    public GraphReader link(Key key, Key source) {
        return current().link(key, source);
    }
}
