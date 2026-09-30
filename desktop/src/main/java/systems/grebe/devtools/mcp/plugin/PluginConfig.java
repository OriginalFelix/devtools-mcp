package systems.grebe.devtools.mcp.plugin;

import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import systems.grebe.devtools.mcp.config.SettingsStore;
import systems.grebe.devtools.mcp.core.ToolModule;
import systems.grebe.devtools.mcp.core.ToolRegistry;
import systems.grebe.devtools.mcp.plugin.store.MavenPluginResolver;
import systems.grebe.devtools.mcp.plugin.store.PluginStore;

/**
 * Verdrahtung des Plugin-Systems. Plugins werden geladen, sobald die App bereit ist (dann stehen alle App-Beans für
 * {@code @Autowired} in Plugins fertig), und ihre Module gehen sofort an die {@link ToolRegistry}.
 */
@Configuration(proxyBeanMethods = false)
public class PluginConfig {

    @Bean(destroyMethod = "close")
    MavenPluginResolver pluginResolver(SettingsStore store) {
        return new MavenPluginResolver(PluginManager.defaultDirectory(store).resolve(".repository"),
                () -> store.plugins().repositories());
    }

    /**
     * Eingebaute Module lazy über den {@link ObjectProvider}: das Plugin-Modul selbst braucht den Manager, eine
     * direkte {@code List<ToolModule>} wäre ein Zirkelbezug.
     */
    @Bean(destroyMethod = "close")
    PluginManager pluginManager(SettingsStore store, ObjectProvider<ToolModule> builtinModules,
                                ObjectProvider<ToolRegistry> registry, MavenPluginResolver resolver,
                                ApplicationContext app) {
        return new PluginManager(PluginManager.defaultDirectory(store), store,
                () -> builtinModules.stream().map(ToolModule::id).collect(Collectors.toUnmodifiableSet()),
                registry::getObject, resolver,
                app instanceof ConfigurableApplicationContext c ? c : null);
    }

    @Bean
    PluginStore pluginStore(SettingsStore store, PluginManager manager, MavenPluginResolver resolver) {
        return new PluginStore(store, manager, resolver);
    }

    @Bean
    PluginActivator pluginActivator(PluginManager manager) {
        return new PluginActivator(manager);
    }

    /** Übergibt die Plugin-Module an die Registry, sobald die App bereit ist. */
    static final class PluginActivator {
        private final PluginManager manager;

        PluginActivator(PluginManager manager) {
            this.manager = manager;
        }

        @EventListener(ApplicationReadyEvent.class)
        public void attach() {
            manager.attachRegistry();
        }
    }
}
