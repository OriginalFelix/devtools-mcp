package systems.grebe.devtools.mcp.modules.graph;

import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Relationship;

/**
 * Ein Branch eines Projekts in der Neo4j-Ablage (Spring Data Neo4j) – der Anker eines Code-Graphen.
 *
 * <p>Die Code-Knoten ({@code :CodeNode}) und Quelldateien ({@code :SourceFile}) tragen die Property {@code g} = 
 * {@link #getGraphId() graphId}. Jeder Aufbau schreibt unter einer neuen {@code graphId} und hängt den Branch erst
 * danach in einer Transaktion um; Leser sehen deshalb nie einen halb geschriebenen Graphen. Bricht ein Aufbau ab,
 * steht dessen ID in {@link #getPendingGraphId() pendingGraphId} und wird beim nächsten Aufbau entfernt.
 *
 * <p>Abfrage im Neo4j Browser: {@code MATCH (b:GraphBranch {branch:'master'}) MATCH (n:CodeNode {g:b.graphId}) …}
 */
@Node("GraphBranch")
public class GraphBranchEntity {

    /** {@code <root>@<branch>}, ohne Git {@code <root>@}. */
    @Id
    private String key;

    private String root;
    private String branch;
    private String commitId;
    private String graphId;
    private String pendingGraphId;
    private String builtAt;
    private String generator;
    private int version;
    private long files;
    private long nodes;
    private long edges;
    /** {@code stats} des Graphen als JSON. */
    private String stats;
    /** Communities als JSON-Array. */
    private String communities;

    @Relationship(type = "HAS_BRANCH", direction = Relationship.Direction.INCOMING)
    private GraphProjectEntity project;

    protected GraphBranchEntity() {
    }

    GraphBranchEntity(String key, String root, String branch, GraphProjectEntity project) {
        this.key = key;
        this.root = root;
        this.branch = branch;
        this.project = project;
    }

    static String key(String root, String branch) {
        return root + "@" + (branch == null ? "" : branch);
    }

    public String getKey() {
        return key;
    }

    public String getRoot() {
        return root;
    }

    public String getBranch() {
        return branch;
    }

    public String getCommitId() {
        return commitId;
    }

    public String getGraphId() {
        return graphId;
    }

    public String getPendingGraphId() {
        return pendingGraphId;
    }

    public String getBuiltAt() {
        return builtAt;
    }

    public String getGenerator() {
        return generator;
    }

    public int getVersion() {
        return version;
    }

    public long getFiles() {
        return files;
    }

    public long getNodes() {
        return nodes;
    }

    public long getEdges() {
        return edges;
    }

    public String getStats() {
        return stats;
    }

    public String getCommunities() {
        return communities;
    }

    public GraphProjectEntity getProject() {
        return project;
    }

    void setPendingGraphId(String pendingGraphId) {
        this.pendingGraphId = pendingGraphId;
    }

    void setProject(GraphProjectEntity project) {
        this.project = project;
    }

    /** Neue Generation übernehmen. */
    void publish(String graphId, String commitId, String builtAt, String generator, int version, long files, long nodes,
                 long edges, String stats, String communities) {
        this.graphId = graphId;
        this.pendingGraphId = null;
        this.commitId = commitId;
        this.builtAt = builtAt;
        this.generator = generator;
        this.version = version;
        this.files = files;
        this.nodes = nodes;
        this.edges = edges;
        this.stats = stats;
        this.communities = communities;
    }
}
