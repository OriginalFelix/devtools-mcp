package systems.grebe.devtools.mcp.plugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolModule;
import systems.grebe.devtools.mcp.core.ToolScope;

import static org.assertj.core.api.Assertions.assertThat;

/** Die Hülle um Plugin-Module reicht die gesamte {@link ToolModule}-SPI durch – auch Scope, Freigaben, Elternmodul. */
class PluginToolModuleTest {

    private static final ModuleConfig CONFIG = ModuleConfig.of(List.of(), Map.of());

    /** Schutz gegen Drift: Eine neue Methode in ToolModule darf nicht stillschweigend beim Default hängen bleiben. */
    @Test
    void overridesEveryMethodOfTheToolModuleSpi() {
        List<String> missing = new ArrayList<>();
        for (Method m : ToolModule.class.getMethods()) {
            try {
                PluginToolModule.class.getDeclaredMethod(m.getName(), m.getParameterTypes());
            } catch (NoSuchMethodException e) {
                missing.add(m.toGenericString());
            }
        }
        assertThat(missing).isEmpty();
    }

    @Test
    void forwardsScopeSharedDirectoriesAndParentWithThePluginClassLoader() {
        ClassLoader pluginLoader = new ClassLoader(getClass().getClassLoader()) {
        };
        Scoped delegate = new Scoped();
        PluginToolModule module = new PluginToolModule(delegate, "demo", pluginLoader);

        assertThat(module.parentModule()).isEqualTo("git");
        assertThat(delegate.loaderSeen).isSameAs(pluginLoader);
        assertThat(module.sharedDirectoryFields()).containsExactly("roots");

        assertThat(module.createTools(CONFIG, ToolScope.LOCAL)).isEmpty();
        assertThat(delegate.scopes).containsExactly(ToolScope.LOCAL);
        assertThat(delegate.loaderSeen).isSameAs(pluginLoader);
        assertThat(Thread.currentThread().getContextClassLoader()).isNotSameAs(pluginLoader);
    }

    @Test
    void pluginWithoutOwnScopeVariantStillGetsTheOneArgumentCall() {
        Plain delegate = new Plain();
        PluginToolModule module = new PluginToolModule(delegate, "demo", getClass().getClassLoader());

        assertThat(module.createTools(CONFIG, ToolScope.LOCAL)).isEmpty();
        assertThat(delegate.calls).isEqualTo(1);
        assertThat(module.parentModule()).isNull();
        assertThat(module.sharedDirectoryFields()).isEmpty();
    }

    private static class Plain implements ToolModule {
        int calls;

        @Override
        public String id() {
            return "demo";
        }

        @Override
        public String displayName() {
            return "Demo";
        }

        @Override
        public String description() {
            return "Demo";
        }

        @Override
        public List<ToolCallback> createTools(ModuleConfig config) {
            calls++;
            return List.of();
        }
    }

    private static final class Scoped extends Plain {
        final List<ToolScope> scopes = new ArrayList<>();
        ClassLoader loaderSeen;

        @Override
        public String parentModule() {
            loaderSeen = Thread.currentThread().getContextClassLoader();
            return "git";
        }

        @Override
        public Set<String> sharedDirectoryFields() {
            return Set.of("roots");
        }

        @Override
        public List<ToolCallback> createTools(ModuleConfig config, ToolScope scope) {
            scopes.add(scope);
            loaderSeen = Thread.currentThread().getContextClassLoader();
            return List.of();
        }
    }
}
