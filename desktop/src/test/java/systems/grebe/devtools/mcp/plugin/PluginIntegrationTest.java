package systems.grebe.devtools.mcp.plugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import systems.grebe.devtools.mcp.config.SettingsStore;
import systems.grebe.devtools.mcp.core.ToolModule;
import systems.grebe.devtools.mcp.core.ToolRegistry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plugins end-to-end über einen echten MCP-Client: ein Plugin aus dem Ordner ist ab Start da (Tools und
 * Instructions), eine Installation zur Laufzeit erscheint sofort in {@code tools/list}, Deaktivieren und Entfernen
 * nehmen die Tools wieder weg – ohne Neustart.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "devtools.local-user.email=plugin@example.com")
class PluginIntegrationTest {

    @TempDir
    static Path home;

    @TempDir
    static Path work;

    /** Läuft vor dem Start des Spring-Kontexts: Plugin liegt schon im Ordner, wenn die App hochfährt. */
    @BeforeAll
    static void placePluginBeforeStartup() throws Exception {
        Path dir = Files.createDirectories(home.resolve("plugins"));
        TestPlugins.echoPlugin("startup", "1.0", "startup", "start ", null).build(dir.resolve("startup.jar"));
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        SettingsStore settingsStore() {
            return new SettingsStore(home);
        }

    }

    @LocalServerPort
    int port;

    @Autowired
    PluginManager plugins;

    @Autowired
    ToolRegistry registry;

    @Autowired
    org.springframework.context.ApplicationContext appContext;

    McpSyncClient client;

    @BeforeEach
    void connect() {
        client = newClient();
    }

    private McpSyncClient newClient() {
        var transport = HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + port).endpoint("/mcp").build();
        McpSyncClient c = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(30)).build();
        c.initialize();
        return c;
    }

    /** Instructions, die eine neue Client-Session beim initialize bekommt. */
    private String freshInstructions() {
        McpSyncClient c = newClient();
        try {
            return c.getServerInstructions();
        } finally {
            c.closeGracefully();
        }
    }

    @AfterEach
    void close() {
        client.closeGracefully();
    }

    private List<String> toolNames() {
        return client.listTools().tools().stream().map(McpSchema.Tool::name).toList();
    }

    private String callEcho(String tool, String text) {
        McpSchema.CallToolResult r = client.callTool(McpSchema.CallToolRequest.builder(tool)
                .arguments(Map.of("text", text)).build());
        assertThat(r.isError()).isNotEqualTo(Boolean.TRUE);
        // erster Aufruf einer Session trägt den Bibliothekshinweis des Skills-Moduls – nur die erste Zeile zählt
        return ((McpSchema.TextContent) r.content().getFirst()).text().lines().findFirst().orElse("");
    }

    @Test
    void pluginFromFolderIsAvailableAtStartupInToolsAndInstructions() {
        assertThat(toolNames()).contains("startup_echo");
        assertThat(callEcho("startup_echo", "welt")).isEqualTo("start welt");
        assertThat(client.getServerInstructions()).contains("## Echo startup – Tools `startup_*`", "Echo-Hinweis startup");
        assertThat(registry.modules()).anySatisfy(m ->
                assertThat(PluginToolModule.pluginOf(m)).contains("startup"));
    }

    @Test
    void installDisableAndUninstallAtRuntimeChangeToolsImmediately() throws Exception {
        Path jar = TestPlugins.echoPlugin("hot", "1.0", "hot", "heiß ", null).build(work.resolve("hot.jar"));
        assertThat(toolNames()).doesNotContain("hot_echo");

        plugins.install(jar, null);
        assertThat(toolNames()).contains("hot_echo");
        assertThat(callEcho("hot_echo", "x")).isEqualTo("heiß x");

        // Modul-Schalter in der App gilt auch für Plugin-Module und bleibt über ein Update hinweg erhalten
        registry.setModuleEnabled("hot", false);
        assertThat(toolNames()).doesNotContain("hot_echo");
        Path v2 = TestPlugins.echoPlugin("hot", "2.0", "hot", "v2 ", null).build(work.resolve("hot2.jar"));
        plugins.install(v2, null);
        assertThat(toolNames()).doesNotContain("hot_echo");
        registry.setModuleEnabled("hot", true);
        assertThat(callEcho("hot_echo", "x")).isEqualTo("v2 x");

        plugins.setEnabled("hot", false);
        assertThat(toolNames()).doesNotContain("hot_echo");
        assertThat(registry.hasModule("hot")).isFalse();
        plugins.setEnabled("hot", true);
        assertThat(toolNames()).contains("hot_echo");

        plugins.uninstall("hot");
        assertThat(toolNames()).doesNotContain("hot_echo");
        assertThat(toolNames()).contains("startup_echo", "git_status"); // übrige Module unberührt
    }

    @Test
    void instructionsFollowPluginChangesForNewSessionsWithoutRestart() throws Exception {
        String before = client.getServerInstructions();
        assertThat(before).doesNotContain("Echo-Hinweis live");

        plugins.install(TestPlugins.echoPlugin("live", "1.0", "live", "", null).build(work.resolve("live.jar")), null);
        assertThat(freshInstructions()).contains("## Echo live – Tools `live_*`", "Echo-Hinweis live",
                "Echo-Hinweis startup"); // Plugin vom Start bleibt drin
        assertThat(client.getServerInstructions()).isEqualTo(before); // bestehende Session: Stand ihres initialize

        plugins.setEnabled("live", false);
        assertThat(freshInstructions()).doesNotContain("Echo-Hinweis live");
        plugins.setEnabled("live", true);
        assertThat(freshInstructions()).contains("Echo-Hinweis live");
        plugins.uninstall("live");
        assertThat(freshInstructions()).doesNotContain("Echo-Hinweis live").contains("## Git – Tools `git_*`");
    }

    @Test
    void springPluginAutowiresAppBeansAndAppProperties() throws Exception {
        Path jar = TestPlugins.springPlugin("wired", "1.0", "wired", "systems.grebe.devtools.mcp.config.SettingsStore")
                .build(work.resolve("wired.jar"));

        PluginManager.PluginInfo info = plugins.install(jar, null);

        assertThat(info.state()).as(String.valueOf(info.error())).isEqualTo(PluginManager.State.ENABLED);
        McpSchema.CallToolResult r = client.callTool(McpSchema.CallToolRequest.builder("wired_info").arguments(Map.of()).build());
        String text = ((McpSchema.TextContent) r.content().getFirst()).text();
        assertThat(text).contains("Hallo aus @Bean", "app=devtools-mcp", "bean=SettingsStore", "zähler=1");
        assertThat(freshInstructions()).contains("Spring-Hinweis wired 1.0");
        // der Plugin-Kontext ist ein Kind: die App sieht die Plugin-Beans nicht
        assertThat(appContext.getBeanNamesForType(ToolModule.class)).noneMatch(n -> n.toLowerCase().contains("info"));
        plugins.uninstall("wired");
        assertThat(toolNames()).doesNotContain("wired_info");
    }

    @Test
    void removedSpringPluginReleasesItsClassLoader() throws Exception {
        // Parent-BeanFactory, Spring- und JSON-Caches dürfen keine Plugin-Klassen festhalten – sonst bleibt bei jedem
        // Update/Entfernen ein kompletter ClassLoader samt Klassen im Speicher
        Path jar = TestPlugins.springPlugin("leaky", "1.0", "leaky", "systems.grebe.devtools.mcp.config.SettingsStore")
                .build(work.resolve("leaky.jar"));
        plugins.install(jar, null);
        McpSchema.CallToolResult r = client.callTool(McpSchema.CallToolRequest.builder("leaky_info")
                .arguments(Map.of()).build());
        assertThat(r.isError()).isNotEqualTo(Boolean.TRUE);
        java.lang.ref.WeakReference<ClassLoader> loader = new java.lang.ref.WeakReference<>(registry.modules().stream()
                .filter(m -> m.id().equals("leaky")).findFirst().map(m -> ((PluginToolModule) m).delegate())
                .orElseThrow().getClass().getClassLoader());
        assertThat(loader.get()).isInstanceOf(PluginClassLoader.class);

        plugins.uninstall("leaky");
        for (int i = 0; i < 20 && loader.get() != null; i++) {
            System.gc();
            Thread.sleep(50);
        }
        assertThat(loader.get()).as("ClassLoader des entfernten Plugins noch erreichbar").isNull();
    }
}
