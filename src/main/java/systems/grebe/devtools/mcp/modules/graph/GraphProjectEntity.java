package systems.grebe.devtools.mcp.modules.graph;

import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Property;

/**
 * Projekt in der Neo4j-Ablage (Spring Data Neo4j). Schlüssel ist die Projektwurzel; die Branches hängen über
 * {@code (:GraphProject)-[:HAS_BRANCH]->(:GraphBranch)} daran.
 */
@Node("GraphProject")
public class GraphProjectEntity {

    @Id
    private String root;

    @Property("name")
    private String name;

    protected GraphProjectEntity() {
    }

    GraphProjectEntity(String root, String name) {
        this.root = root;
        this.name = name;
    }

    public String getRoot() {
        return root;
    }

    public String getName() {
        return name;
    }

    void setName(String name) {
        this.name = name;
    }
}
