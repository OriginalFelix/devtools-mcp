package systems.grebe.devtools.mcp.core;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * Erzeugt Tool-Callbacks aus Objekten mit {@code @Tool}-Methoden wie {@link ToolCallbacks#from}, übernimmt aber
 * zusätzlich die {@link ToolHints} als MCP-Tool-Annotations.
 */
public final class ToolBeans {

    private ToolBeans() {
    }

    /** Callbacks aller {@code @Tool}-Methoden der Objekte, mit Annotations aus {@link ToolHints}. */
    public static List<ToolCallback> callbacks(Object... beans) {
        List<ToolCallback> out = new ArrayList<>();
        for (Object bean : beans) {
            Map<String, ToolHints> hints = hints(bean.getClass());
            for (ToolCallback cb : ToolCallbacks.from(bean)) {
                ToolHints h = hints.get(cb.getToolDefinition().name());
                out.add(h == null ? cb : new Hinted(cb, annotations(h)));
            }
        }
        return out;
    }

    /** Annotations eines Callbacks (auch durch Dekoratoren wie {@link ManagedToolCallback} hindurch) oder {@code null}. */
    public static McpSchema.ToolAnnotations annotations(ToolCallback cb) {
        if (cb instanceof Hinted h) {
            return h.annotations;
        }
        if (cb instanceof ManagedToolCallback m) {
            return annotations(m.delegate());
        }
        return null;
    }

    private static Map<String, ToolHints> hints(Class<?> type) {
        ToolHints classHints = type.getAnnotation(ToolHints.class);
        Map<String, ToolHints> out = new HashMap<>();
        for (Method m : type.getMethods()) {
            Tool tool = m.getAnnotation(Tool.class);
            if (tool == null) {
                continue;
            }
            ToolHints h = m.getAnnotation(ToolHints.class);
            if (h == null) {
                h = classHints;
            }
            if (h != null) {
                out.put(tool.name().isBlank() ? m.getName() : tool.name(), h);
            }
        }
        return out;
    }

    private static McpSchema.ToolAnnotations annotations(ToolHints h) {
        // destructive/idempotent sind laut Spezifikation nur bei nicht lesenden Tools bedeutsam
        return new McpSchema.ToolAnnotations(null, h.readOnly(), h.readOnly() ? null : h.destructive(),
                h.readOnly() ? null : h.idempotent(), h.openWorld(), null);
    }

    /** Callback mit Annotations; ansonsten unverändert. */
    private static final class Hinted implements ToolCallback {
        private final ToolCallback delegate;
        private final McpSchema.ToolAnnotations annotations;

        Hinted(ToolCallback delegate, McpSchema.ToolAnnotations annotations) {
            this.delegate = delegate;
            this.annotations = annotations;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return delegate.getToolMetadata();
        }

        @Override
        public String call(String toolInput) {
            return delegate.call(toolInput);
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            return delegate.call(toolInput, toolContext);
        }
    }
}
