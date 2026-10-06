package systems.grebe.devtools.mcp.plugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import systems.grebe.devtools.mcp.config.SettingsStore;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.ContextLoaderProxy;
import systems.grebe.devtools.mcp.core.McpToolHints;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolModule;
import systems.grebe.devtools.mcp.core.ToolProgress;
import systems.grebe.devtools.mcp.modules.ticket.TicketModule;
import systems.grebe.devtools.mcp.modules.ticket.TicketProviders;
import systems.grebe.devtools.mcp.modules.ticket.spi.ProviderSettings;
import systems.grebe.devtools.mcp.modules.ticket.spi.TicketProvider;
import systems.grebe.devtools.mcp.modules.ticket.spi.TicketSystem;
import systems.grebe.devtools.mcp.plugin.PluginManager.State;
import systems.grebe.devtools.mcp.plugin.store.MavenPluginResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Plugins, die nur gegen {@code devtools-mcp-plugin-api} kompiliert sind, laufen in der App. */
class PluginApiOnlyTest {

    @TempDir
    Path home;

    @TempDir
    Path work;

    PluginManager manager;
    MavenPluginResolver resolver;

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.close();
        }
        if (resolver != null) {
            resolver.close();
        }
    }

    @Test
    void pluginCompiledAgainstApiOnlyLoadsAndItsToolsWork() throws Exception {
        SettingsStore store = new SettingsStore(home);
        resolver = new MavenPluginResolver(home.resolve("plugins/.repository"), () -> store.plugins().repositories());
        Path dir = PluginManager.defaultDirectory(store);
        Files.createDirectories(dir);
        TestPlugins.echoPlugin("apionly", "1.0.0", "apionly", "api ", null).apiOnly()
                .build(dir.resolve("apionly.jar"));
        TestPlugins.springPlugin("apispring", "1.0.0", "apispring", null).apiOnly()
                .build(dir.resolve("apispring.jar"));

        manager = new PluginManager(dir, store, Set::of, () -> null, resolver);

        assertThat(manager.plugins()).allSatisfy(p -> assertThat(p.state()).as(p.name() + ": " + p.error())
                .isEqualTo(State.ENABLED));
        ToolModule echo = manager.modules().stream().filter(m -> m.id().equals("apionly")).findFirst().orElseThrow();
        String result = echo.createTools(ModuleConfig.of(echo.configSchema(), Map.of())).getFirst()
                .call("{\"text\":\"welt\"}");
        assertThat(result).contains("api welt");
    }

    /**
     * Ein Plugin nur gegen die API: Ticket-Provider über META-INF/services, Tools über {@code ToolBeans} mit
     * {@code @ToolHints} und Fortschritt über {@code ToolProgress}.
     */
    @Test
    void providersHintsAndProgressFromApiOnlyPlugin() throws Exception {
        SettingsStore store = new SettingsStore(home);
        resolver = new MavenPluginResolver(home.resolve("plugins/.repository"), () -> store.plugins().repositories());
        Path dir = Files.createDirectories(PluginManager.defaultDirectory(store));
        TestPlugins.jar().apiOnly()
                .pluginYml("name: redmine\nversion: 1.0.0\nmain: com.acme.redmine.Main\n")
                .file("META-INF/services/" + TicketProvider.class.getName(), "com.acme.redmine.RedmineProvider\n")
                .source("com.acme.redmine.RedmineProvider", """
                        package com.acme.redmine;
                        import java.util.List;
                        import systems.grebe.devtools.mcp.core.ConfigField;
                        import systems.grebe.devtools.mcp.core.FieldType;
                        import systems.grebe.devtools.mcp.modules.ticket.spi.*;
                        public class RedmineProvider implements TicketProvider {
                            public String id() { return "redmine"; }
                            public String displayName() { return "Redmine"; }
                            public List<ConfigField> configFields() {
                                return List.of(ConfigField.of("baseUrl", "Server-URL", FieldType.URL));
                            }
                            public String projectHelp() { return Loaders.current(); }
                            public TicketSystem create(ProviderSettings settings) { return new RedmineSystem(); }
                        }
                        """)
                .source("com.acme.redmine.Loaders", """
                        package com.acme.redmine;
                        final class Loaders {
                            static String current() { return Thread.currentThread().getContextClassLoader().getName(); }
                        }
                        """)
                .source("com.acme.redmine.RedmineSystem", """
                        package com.acme.redmine;
                        import java.util.List;
                        import systems.grebe.devtools.mcp.modules.ticket.spi.TicketSystem;
                        public class RedmineSystem implements TicketSystem, AutoCloseable {
                            public static volatile String closedWith;
                            public String id() { return "redmine"; }
                            public Availability probe() { return new Availability(true, Loaders.current(), "u", null); }
                            public List<Board> boards(String project) { return List.of(); }
                            public BoardView board(String board, String project, BoardOptions options) { return null; }
                            public TicketPage search(TicketQuery query) {
                                throw new IllegalStateException("Redmine-Suche: " + Loaders.current());
                            }
                            public TicketDetails ticket(String key, String project, int maxComments) { return null; }
                            public void close() { closedWith = Loaders.current(); }
                            @Override public String toString() { return "RedmineSystem"; }
                        }
                        """)
                .source("com.acme.redmine.Main", """
                        package com.acme.redmine;
                        import java.util.List;
                        import org.springframework.ai.tool.ToolCallback;
                        import org.springframework.ai.tool.annotation.Tool;
                        import systems.grebe.devtools.mcp.core.*;
                        import systems.grebe.devtools.mcp.plugin.DevToolsPlugin;
                        public class Main extends DevToolsPlugin {
                            @ToolHints(readOnly = true, openWorld = false)
                            public static class Tools {
                                @Tool(name = "rm_ping", description = "Ping")
                                public String ping() {
                                    ToolProgress.report("pinge …");
                                    return "pong " + ToolProgress.active();
                                }
                            }
                            @Override public void onEnable() {
                                registerModule(new ToolModule() {
                                    public String id() { return "rm"; }
                                    public String displayName() { return "Redmine-Extras"; }
                                    public String description() { return "Test"; }
                                    public List<ToolCallback> createTools(ModuleConfig c) {
                                        return ToolBeans.callbacks(new Tools());
                                    }
                                });
                            }
                        }
                        """)
                .build(dir.resolve("redmine.jar"));
        manager = new PluginManager(dir, store, Set::of, () -> null, resolver);
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("pluginManager", manager);
        TicketProviders providers = new TicketProviders(beans.getBeanProvider(PluginManager.class));

        assertThat(manager.plugin("redmine").orElseThrow()).satisfies(p -> {
            assertThat(p.state()).as(p.error()).isEqualTo(State.ENABLED);
            assertThat(p.providers()).containsExactly("TicketProvider redmine");
        });
        assertThat(providers.provider("redmine")).isNotNull();
        assertThat(providers.provider("jira")).isNotNull(); // eingebaute bleiben
        assertThat(new TicketModule(providers).configSchema()).extracting(ConfigField::key)
                .contains("redmine.enabled", "redmine.baseUrl");

        // Aufrufe in Provider und erzeugtes System laufen mit dem ClassLoader des Plugins
        TicketProvider redmine = providers.provider("redmine");
        assertThat(redmine.projectHelp()).isEqualTo("plugin-redmine");
        TicketSystem system = redmine.create(new ProviderSettings(k -> Optional.empty(), Duration.ofSeconds(5)));
        assertThat(system.probe().version()).isEqualTo("plugin-redmine");
        assertThat(system.boards("X")).isEmpty();
        assertThatThrownBy(() -> system.search(null)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Redmine-Suche: plugin-redmine");
        assertThat(system).isInstanceOf(AutoCloseable.class).hasToString("RedmineSystem");
        ((AutoCloseable) system).close();
        Class<?> impl = ContextLoaderProxy.unwrap(system).getClass();
        assertThat(impl.getName()).isEqualTo("com.acme.redmine.RedmineSystem");
        assertThat(impl.getDeclaredField("closedWith").get(null)).isEqualTo("plugin-redmine");
        assertThat(Thread.currentThread().getContextClassLoader().getName()).isNotEqualTo("plugin-redmine");

        // Hinweise kommen durch die ClassLoader-Hülle des Plugins durch, Fortschritt erreicht den Client
        ToolCallback ping = manager.modules().getFirst()
                .createTools(ModuleConfig.of(List.of(), Map.of())).getFirst();
        var hints = McpToolHints.annotations(ping);
        assertThat(hints.readOnlyHint()).isTrue();
        assertThat(hints.openWorldHint()).isFalse();
        List<String> progress = new ArrayList<>();
        String result = ToolProgress.callWith((message, count) -> progress.add(count + ":" + message),
                () -> ping.call("{}"));
        assertThat(result).contains("pong true");
        assertThat(progress).containsExactly("1:pinge …");

        // abgeschaltet → Provider weg, ohne dass die Registry neu gebaut werden muss
        manager.setEnabled("redmine", false);
        assertThat(providers.provider("redmine")).isNull();
        assertThat(manager.activeProviders(TicketProvider.class)).isEmpty();
    }

    @Test
    void appInternalsAreNotPartOfTheApi() {
        var spec = TestPlugins.jar().apiOnly()
                .source("com.acme.Internal", """
                        package com.acme;
                        public class Internal {
                            systems.grebe.devtools.mcp.config.SettingsStore store;
                        }
                        """);

        assertThatThrownBy(() -> spec.build(work.resolve("internal.jar")))
                .hasMessageContaining("javac fehlgeschlagen")
                .hasMessageContaining("SettingsStore");
    }
}
