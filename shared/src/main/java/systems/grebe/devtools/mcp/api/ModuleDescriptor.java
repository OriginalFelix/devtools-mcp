package systems.grebe.devtools.mcp.api;

import java.util.List;

import systems.grebe.devtools.mcp.core.ConfigField;

/**
 * Beschreibung eines Moduls der Desktop-App für den Server: Der Server führt keine Module aus, braucht aber Felder und
 * Tools, um Einstellungen in der Web-UI zu bearbeiten.
 *
 * @param tools Tools des Moduls (Name und Beschreibung), unabhängig davon, ob sie gerade aktiv sind
 */
public record ModuleDescriptor(String id, String displayName, String description, boolean enabledByDefault,
                               boolean hasTools, int order, List<ConfigField> schema, List<ToolDescriptor> tools) {

    public ModuleDescriptor {
        schema = schema == null ? List.of() : List.copyOf(schema);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }

    /** Ein Tool des Moduls. */
    public record ToolDescriptor(String name, String description) {
    }
}
