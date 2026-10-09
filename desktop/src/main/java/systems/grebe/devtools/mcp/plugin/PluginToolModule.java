package systems.grebe.devtools.mcp.plugin;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.ConnectionTestResult;
import systems.grebe.devtools.mcp.core.DelegatingToolCallback;
import systems.grebe.devtools.mcp.core.ModuleAction;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolModule;
import systems.grebe.devtools.mcp.core.ToolScope;

/**
 * Hülle um ein Modul aus einem Plugin: merkt sich das Plugin (für die Anzeige) und setzt bei jedem Aufruf in
 * Plugin-Code den Thread-Context-ClassLoader auf den des Plugins – Bibliotheken wie Jackson oder {@code ServiceLoader}
 * finden so die Klassen des Plugins.
 */
public final class PluginToolModule implements ToolModule {

    private final ToolModule delegate;
    private final String pluginName;
    private final ClassLoader loader;

    PluginToolModule(ToolModule delegate, String pluginName, ClassLoader loader) {
        this.delegate = delegate;
        this.pluginName = pluginName;
        this.loader = loader;
    }

    /** Name des Plugins, aus dem das Modul stammt. */
    public String pluginName() {
        return pluginName;
    }

    /** Das Modul, wie das Plugin es registriert hat. */
    public ToolModule delegate() {
        return delegate;
    }

    /** Plugin-Name, falls das Modul aus einem Plugin stammt. */
    public static java.util.Optional<String> pluginOf(ToolModule module) {
        return module instanceof PluginToolModule p ? java.util.Optional.of(p.pluginName) : java.util.Optional.empty();
    }

    @Override
    public String id() {
        return withLoader(delegate::id);
    }

    @Override
    public String displayName() {
        return withLoader(delegate::displayName);
    }

    @Override
    public String description() {
        return withLoader(delegate::description);
    }

    @Override
    public String instructions() {
        return withLoader(delegate::instructions);
    }

    @Override
    public String briefInstructions() {
        return withLoader(delegate::briefInstructions);
    }

    @Override
    public List<ConfigField> configSchema() {
        return withLoader(delegate::configSchema);
    }

    @Override
    public String parentModule() {
        return withLoader(delegate::parentModule);
    }

    @Override
    public List<ToolCallback> createTools(ModuleConfig config) {
        return wrap(withLoader(() -> delegate.createTools(config)));
    }

    @Override
    public List<ToolCallback> createTools(ModuleConfig config, ToolScope scope) {
        return wrap(withLoader(() -> delegate.createTools(config, scope)));
    }

    private List<ToolCallback> wrap(List<ToolCallback> tools) {
        return tools.stream().<ToolCallback>map(cb -> new LoaderToolCallback(cb, loader)).toList();
    }

    @Override
    public Set<String> sharedDirectoryFields() {
        Set<String> fields = withLoader(delegate::sharedDirectoryFields);
        return fields == null ? Set.of() : fields;
    }

    @Override
    public ConnectionTestResult testConnection(ModuleConfig config) {
        return withLoader(() -> delegate.testConnection(config));
    }

    @Override
    public List<ModuleAction> actions() {
        return withLoader(delegate::actions).stream().<ModuleAction>map(a -> new LoaderAction(a, loader)).toList();
    }

    @Override
    public boolean enabledByDefault() {
        return withLoader(delegate::enabledByDefault);
    }

    @Override
    public boolean hasTools() {
        return withLoader(delegate::hasTools);
    }

    @Override
    public int order() {
        return withLoader(delegate::order);
    }

    @Override
    public Map<String, String> initialValues(Function<String, Map<String, String>> savedValues) {
        return withLoader(() -> delegate.initialValues(savedValues));
    }

    private <T> T withLoader(Supplier<T> call) {
        return withLoader(loader, call);
    }

    static <T> T withLoader(ClassLoader loader, Supplier<T> call) {
        Thread t = Thread.currentThread();
        ClassLoader previous = t.getContextClassLoader();
        t.setContextClassLoader(loader);
        try {
            return call.get();
        } finally {
            t.setContextClassLoader(previous);
        }
    }

    private record LoaderToolCallback(ToolCallback delegate, ClassLoader loader) implements DelegatingToolCallback {
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
            return withLoader(loader, () -> delegate.call(toolInput));
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            return withLoader(loader, () -> delegate.call(toolInput, toolContext));
        }
    }

    private record LoaderAction(ModuleAction delegate, ClassLoader loader) implements ModuleAction {
        @Override
        public String id() {
            return delegate.id();
        }

        @Override
        public String label() {
            return delegate.label();
        }

        @Override
        public String description() {
            return delegate.description();
        }

        @Override
        public List<String> targets(ModuleConfig config) {
            return withLoader(loader, () -> delegate.targets(config));
        }

        @Override
        public String describe(ModuleConfig config, String target) {
            return withLoader(loader, () -> delegate.describe(config, target));
        }

        @Override
        public List<Flag> flags() {
            return delegate.flags();
        }

        @Override
        public ActionResult run(ModuleConfig config, String target, Set<String> flags, Progress progress) {
            return withLoader(loader, () -> delegate.run(config, target, flags, progress));
        }
    }
}
