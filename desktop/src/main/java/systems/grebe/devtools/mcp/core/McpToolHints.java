package systems.grebe.devtools.mcp.core;

import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.tool.ToolCallback;

/** Übersetzt die {@link ToolBeans.Hints} der Plugin-API in MCP-Tool-Annotations und zurück. */
public final class McpToolHints {

    private McpToolHints() {
    }

    /** Annotations eines Callbacks (auch durch Hüllen hindurch) oder {@code null}. */
    public static McpSchema.ToolAnnotations annotations(ToolCallback cb) {
        ToolBeans.Hints h = ToolBeans.hints(cb);
        return h == null ? null
                : new McpSchema.ToolAnnotations(h.title(), h.readOnly(), h.destructive(), h.idempotent(),
                        h.openWorld(), null);
    }

    /**
     * Versieht einen Callback mit MCP-Tool-Annotations – für Tools ohne {@code @Tool}-Methode (z.B. aus Skripten).
     * {@code null} lässt den Callback unverändert.
     */
    public static ToolCallback withAnnotations(ToolCallback cb, McpSchema.ToolAnnotations a) {
        return a == null ? cb : ToolBeans.withHints(cb, new ToolBeans.Hints(a.title(), a.readOnlyHint(),
                a.destructiveHint(), a.idempotentHint(), a.openWorldHint()));
    }
}
