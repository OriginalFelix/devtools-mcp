package systems.grebe.devtools.mcp.core;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * Erzeugt Tool-Callbacks aus Objekten mit {@code @Tool}-Methoden wie {@link ToolCallbacks#from}, übernimmt aber
 * zusätzlich die {@link ToolHints} – die App meldet sie dem Client als MCP-Tool-Annotations.
 *
 * <pre>{@code
 * public List<ToolCallback> createTools(ModuleConfig config) {
 *     return ToolBeans.callbacks(new JiraTools(config));
 * }
 * }</pre>
 */
public final class ToolBeans {

    private ToolBeans() {
    }

    /**
     * Hinweise eines Tools (Werte wie bei MCP-Tool-Annotations, {@code null} = keine Angabe).
     *
     * @param title optionaler Anzeigename
     */
    public record Hints(String title, Boolean readOnly, Boolean destructive, Boolean idempotent, Boolean openWorld) {

        /** Aus der Annotation; destructive/idempotent sind laut Spezifikation nur bei nicht lesenden Tools bedeutsam. */
        public static Hints of(ToolHints h) {
            return new Hints(null, h.readOnly(), h.readOnly() ? null : h.destructive(),
                    h.readOnly() ? null : h.idempotent(), h.openWorld());
        }
    }

    /** Callbacks aller {@code @Tool}-Methoden der Objekte, mit Hinweisen aus {@link ToolHints}. */
    public static List<ToolCallback> callbacks(Object... beans) {
        List<ToolCallback> out = new ArrayList<>();
        for (Object bean : beans) {
            Map<String, ToolHints> hints = hints(bean.getClass());
            for (ToolCallback cb : ToolCallbacks.from(bean)) {
                ToolHints h = hints.get(cb.getToolDefinition().name());
                out.add(h == null ? cb : new Hinted(cb, Hints.of(h)));
            }
        }
        return out;
    }

    /**
     * Versieht einen Callback mit Hinweisen – für Tools ohne {@code @Tool}-Methode. {@code null} lässt den Callback
     * unverändert.
     */
    public static ToolCallback withHints(ToolCallback cb, Hints hints) {
        return hints == null ? cb : new Hinted(cb, hints);
    }

    /** Hinweise eines Callbacks (auch durch {@link DelegatingToolCallback Hüllen} hindurch) oder {@code null}. */
    public static Hints hints(ToolCallback cb) {
        if (cb instanceof Hinted h) {
            return h.hints;
        }
        if (cb instanceof DelegatingToolCallback d) {
            return hints(d.delegate());
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

    /** Callback mit Hinweisen; ansonsten unverändert. */
    private record Hinted(ToolCallback delegate, Hints hints) implements ToolCallback {

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
