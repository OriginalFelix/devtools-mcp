package systems.grebe.devtools.mcp.core;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * {@link DelegatingToolCallback}, der Definition, Metadaten und Aufrufe unverändert an {@link #delegate()} weitergibt.
 * Hüllen überschreiben nur, was sie ändern (z.B. den Aufruf für Zeitlimit oder ClassLoader).
 */
public interface ForwardingToolCallback extends DelegatingToolCallback {

    @Override
    default ToolDefinition getToolDefinition() {
        return delegate().getToolDefinition();
    }

    @Override
    default ToolMetadata getToolMetadata() {
        return delegate().getToolMetadata();
    }

    @Override
    default String call(String toolInput) {
        return delegate().call(toolInput);
    }

    @Override
    default String call(String toolInput, ToolContext toolContext) {
        return delegate().call(toolInput, toolContext);
    }
}
