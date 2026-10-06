package systems.grebe.devtools.mcp.core;

import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Dekorator um jedes Modul-Tool: erzwingt das Modul-Präfix im Namen, protokolliert Aufrufe,
 * liefert String-Ergebnisse als Klartext statt als JSON-String-Literal und reicht erfolgreiche
 * Aufrufe an die {@link ToolCallListener} weiter.
 */
public final class ManagedToolCallback implements DelegatingToolCallback {

    private static final Logger LOG = LoggerFactory.getLogger(ManagedToolCallback.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String moduleId;
    private final ToolCallback delegate;
    private final ToolDefinition definition;
    private final ToolInvocationLog log;
    private final List<ToolCallListener> listeners;

    public ManagedToolCallback(String moduleId, ToolCallback delegate, ToolInvocationLog log) {
        this(moduleId, delegate, log, List.of());
    }

    public ManagedToolCallback(String moduleId, ToolCallback delegate, ToolInvocationLog log,
                               List<ToolCallListener> listeners) {
        this.moduleId = moduleId;
        this.delegate = delegate;
        this.log = log;
        this.listeners = List.copyOf(listeners);
        ToolDefinition d = delegate.getToolDefinition();
        this.definition = ToolDefinition.builder()
                .name(prefixed(moduleId, d.name()))
                .description(d.description())
                .inputSchema(d.inputSchema())
                .build();
    }

    public static String prefixed(String moduleId, String name) {
        String prefix = moduleId + "_";
        return name.startsWith(prefix) ? name : prefix + name;
    }

    /** Das eingehüllte Modul-Tool. */
    @Override
    public ToolCallback delegate() {
        return delegate;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        long start = System.nanoTime();
        try {
            String raw = toolContext == null ? delegate.call(toolInput) : delegate.call(toolInput, toolContext);
            String result = notifyListeners(toolInput, toolContext, unwrapJsonString(raw));
            log.record(moduleId, definition.name(), toolInput, result, since(start), true);
            return result;
        } catch (RuntimeException e) {
            log.record(moduleId, definition.name(), toolInput, describe(e), since(start), false);
            throw e;
        }
    }

    private String notifyListeners(String toolInput, ToolContext toolContext, String result) {
        if (listeners.isEmpty()) {
            return result;
        }
        ToolCallListener.ToolCall call = new ToolCallListener.ToolCall(moduleId, definition.name(), toolInput,
                ToolCallListener.sessionId(toolContext));
        String current = result;
        for (ToolCallListener l : listeners) {
            try {
                String next = l.afterSuccess(call, current);
                if (next != null) {
                    current = next;
                }
            } catch (RuntimeException e) {
                LOG.warn("ToolCallListener {} fehlgeschlagen für {}", l.getClass().getSimpleName(), call.toolName(), e);
            }
        }
        return current;
    }

    private static Duration since(long start) {
        return Duration.ofNanos(System.nanoTime() - start);
    }

    /** Liefert die eigentliche Ursache (MethodToolCallback verpackt Exceptions). */
    public static String describe(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String msg = root.getMessage();
        return msg == null || msg.isBlank() ? root.getClass().getSimpleName() : msg;
    }

    static String unwrapJsonString(String raw) {
        if (raw == null || raw.length() < 2 || raw.charAt(0) != '"') {
            return raw;
        }
        try {
            JsonNode node = JSON.readTree(raw);
            return node.isString() ? node.asString() : raw;
        } catch (RuntimeException e) {
            return raw;
        }
    }
}
