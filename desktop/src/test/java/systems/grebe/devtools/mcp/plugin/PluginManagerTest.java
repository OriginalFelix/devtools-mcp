package systems.grebe.devtools.mcp.plugin;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;
import systems.grebe.devtools.mcp.config.SettingsStore;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolModule;
import systems.grebe.devtools.mcp.plugin.PluginManager.PluginInfo;
import systems.grebe.devtools.mcp.plugin.PluginManager.State;
import systems.grebe.devtools.mcp.plugin.store.MavenPluginResolver;
import systems.grebe.devtools.mcp.plugin.store.PluginRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Plugin-Ordner, Lebenszyklus, Abhängigkeiten und ClassLoader – mit echten, zur Testzeit kompilierten Jars. */
class PluginManagerTest {

    @TempDir
    Path home;

    @TempDir
    Path work;

    SettingsStore store;
    MavenPluginResolver resolver;
    PluginManager manager;

    @BeforeEach
    void setUp() {
        store = new SettingsStore(home);
        resolver = new MavenPluginResolver(home.resolve("plugins/.repository"), () -> store.plugins().repositories());
    }

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.close();
        }
        resolver.close();
    }

    private PluginManager manager() {
        manager = new PluginManager(PluginManager.defaultDirectory(store), store, () -> Set.of("git", "sonar"),
                () -> null, resolver);
        return manager;
    }

    private Path pluginsDir() {
        return PluginManager.defaultDirectory(store);
    }

    private Path echo(String name, String version, String moduleId, String prefix, String extraYml) {
        return TestPlugins.echoPlugin(name, version, moduleId, prefix, extraYml)
                .build(work.resolve(name + "-" + version + ".jar"));
    }

    private static String call(ToolModule module, String text) {
        ToolCallback cb = module.createTools(ModuleConfig.of(module.configSchema(), java.util.Map.of())).getFirst();
        return cb.call("{\"text\":\"" + text + "\"}");
    }

    private String lifecycle(String plugin) throws Exception {
        return Files.readString(pluginsDir().resolve(plugin).resolve("lifecycle.log")).strip();
    }

    @Test
    void loadsPluginsFromFolderAndTheirToolsWork() throws Exception {
        Files.createDirectories(pluginsDir());
        Files.copy(echo("hello", "1.0.0", "hello", "hi ", null), pluginsDir().resolve("hello.jar"));

        List<PluginInfo> plugins = manager().plugins();

        assertThat(plugins).singleElement().satisfies(p -> {
            assertThat(p.name()).isEqualTo("hello");
            assertThat(p.version()).isEqualTo("1.0.0");
            assertThat(p.state()).isEqualTo(State.ENABLED);
            assertThat(p.error()).isNull();
            assertThat(p.modules()).containsExactly("hello");
            assertThat(p.authors()).containsExactly("Test");
        });
        ToolModule module = manager.modules().getFirst();
        assertThat(module).isInstanceOf(PluginToolModule.class);
        assertThat(module.instructions()).isEqualTo("Echo-Hinweis hello");
        assertThat(call(module, "welt")).contains("hi welt");
        assertThat(lifecycle("hello")).isEqualTo("load 1.0.0\nenable 1.0.0");
        // Plugin-Klassen kommen aus dem eigenen ClassLoader, die API aus der App
        Class<?> tools = ((PluginToolModule) module).delegate().getClass();
        assertThat(tools.getClassLoader()).isInstanceOf(PluginClassLoader.class);
        assertThat(tools.getClassLoader().loadClass(DevToolsPlugin.class.getName())).isSameAs(DevToolsPlugin.class);
    }

    @Test
    void brokenJarsAreReportedWithoutAffectingOthers() throws Exception {
        Files.createDirectories(pluginsDir());
        Files.copy(echo("good", "1.0.0", "good", "", null), pluginsDir().resolve("good.jar"));
        TestPlugins.jar().file("readme.txt", "kein Plugin").build(pluginsDir().resolve("nope.jar"));
        TestPlugins.jar().pluginYml("name: badmain\nversion: 1\nmain: com.acme.Missing\n")
                .build(pluginsDir().resolve("badmain.jar"));
        TestPlugins.jar().pluginYml("name: future\nversion: 1\nmain: com.acme.Future\napi-version: 99\n")
                .build(pluginsDir().resolve("future.jar"));
        TestPlugins.jar().pluginYml("name: notaplugin\nversion: 1\nmain: com.acme.Plain\n")
                .source("com.acme.Plain", "package com.acme; public class Plain {}")
                .build(pluginsDir().resolve("plain.jar"));
        Files.copy(echo("taken", "1.0.0", "git", "", null), pluginsDir().resolve("taken.jar"));

        manager();
        assertThat(manager.plugin("good").orElseThrow().state()).isEqualTo(State.ENABLED);
        assertThat(manager.plugin("nope.jar").orElseThrow()).satisfies(p -> {
            assertThat(p.state()).isEqualTo(State.FAILED);
            assertThat(p.valid()).isFalse();
            assertThat(p.error()).contains("keine plugin.yml");
        });
        assertThat(manager.plugin("badmain").orElseThrow().error()).contains("com.acme.Missing", "nicht im Plugin-Jar");
        assertThat(manager.plugin("future").orElseThrow().error()).contains("Plugin-API 99");
        assertThat(manager.plugin("notaplugin").orElseThrow().error()).contains("erweitert nicht");
        assertThat(manager.plugin("taken").orElseThrow()).satisfies(p -> {
            assertThat(p.state()).isEqualTo(State.FAILED);
            assertThat(p.error()).contains("'git' ist bereits vergeben");
        });
        assertThat(manager.modules()).extracting(ToolModule::id).containsExactly("good");
    }

    @Test
    void dependenciesLoadFirstAndShareClasses() throws Exception {
        Files.createDirectories(pluginsDir());
        // "base" stellt eine Klasse bereit, "addon" (depend: base) verwendet sie
        Path baseJar = TestPlugins.jar()
                .pluginYml("name: base\nversion: 1.0\nmain: base.BasePlugin\n")
                .source("base.Greeter", "package base; public class Greeter { public static String greet(String s) { "
                        + "return \"Hallo \" + s; } }")
                .source("base.BasePlugin", "package base; public class BasePlugin extends "
                        + "systems.grebe.devtools.mcp.plugin.DevToolsPlugin {}")
                .build(pluginsDir().resolve("zz-base.jar")); // Dateiname sortiert nach addon – Reihenfolge kommt aus depend
        TestPlugins.jar()
                .pluginYml("name: addon\nversion: 1.0\nmain: addon.AddonPlugin\ndepend: [base]\n")
                .classpath(baseJar)
                .source("addon.AddonPlugin", """
                        package addon;
                        import java.util.List;
                        import org.springframework.ai.tool.ToolCallback;
                        import org.springframework.ai.tool.definition.ToolDefinition;
                        import systems.grebe.devtools.mcp.core.ModuleConfig;
                        import systems.grebe.devtools.mcp.core.ToolModule;
                        public class AddonPlugin extends systems.grebe.devtools.mcp.plugin.DevToolsPlugin {
                            @Override public void onEnable() {
                                if (context().plugin("base").isEmpty()) throw new IllegalStateException("base fehlt");
                                registerModule(new ToolModule() {
                                    public String id() { return "addon"; }
                                    public String displayName() { return "Addon"; }
                                    public String description() { return "d"; }
                                    public List<ToolCallback> createTools(ModuleConfig c) {
                                        return List.of(new ToolCallback() {
                                            public ToolDefinition getToolDefinition() {
                                                return ToolDefinition.builder().name("greet").description("g")
                                                        .inputSchema("{\\"type\\":\\"object\\"}").build();
                                            }
                                            public String call(String in) { return base.Greeter.greet("Plugin"); }
                                        });
                                    }
                                });
                            }
                        }
                        """)
                .build(pluginsDir().resolve("addon.jar"));

        manager();
        assertThat(manager.plugin("addon").orElseThrow().state()).as(manager.plugin("addon").toString())
                .isEqualTo(State.ENABLED);
        assertThat(manager.loadOrderKeys()).containsSubsequence("base", "addon");
        ToolModule addon = manager.modules().stream().filter(m -> m.id().equals("addon")).findFirst().orElseThrow();
        assertThat(addon.createTools(ModuleConfig.of(List.of(), java.util.Map.of())).getFirst().call("{}"))
                .isEqualTo("Hallo Plugin");

        // Abhängigkeit abschalten → addon wird mit deaktiviert und scheitert beim Neustart an der fehlenden Abhängigkeit
        manager.setEnabled("base", false);
        assertThat(manager.plugin("base").orElseThrow().state()).isEqualTo(State.DISABLED);
        assertThat(manager.plugin("addon").orElseThrow()).satisfies(p -> {
            assertThat(p.state()).isEqualTo(State.FAILED);
            assertThat(p.error()).contains("'base'", "nicht aktiv");
        });
        manager.setEnabled("base", true);
        assertThat(manager.plugin("addon").orElseThrow().state()).isEqualTo(State.ENABLED);
    }

    @Test
    void missingAndCyclicDependenciesFail() throws Exception {
        Files.createDirectories(pluginsDir());
        Files.copy(echo("lonely", "1", "lonely", "", "depend: [ghost]\n"), pluginsDir().resolve("lonely.jar"));
        Files.copy(echo("cyca", "1", "cyca", "", "softdepend: [cycb]\n"), pluginsDir().resolve("cyca.jar"));
        Files.copy(echo("cycb", "1", "cycb", "", "depend: [cyca]\n"), pluginsDir().resolve("cycb.jar"));
        Files.copy(echo("soft", "1", "soft", "", "softdepend: [ghost]\n"), pluginsDir().resolve("soft.jar"));

        manager();
        assertThat(manager.plugin("lonely").orElseThrow().error()).contains("'ghost'", "nicht installiert");
        assertThat(manager.plugin("cyca").orElseThrow().error()).contains("Zyklische Abhängigkeit");
        assertThat(manager.plugin("cycb").orElseThrow().error()).contains("Zyklische Abhängigkeit");
        assertThat(manager.plugin("soft").orElseThrow().state()).isEqualTo(State.ENABLED); // optional fehlt: egal
    }

    @Test
    void disableIsPersistedAndSurvivesRestart() throws Exception {
        manager().install(echo("hello", "1.0.0", "hello", "", null), null);
        manager.setEnabled("hello", false);

        assertThat(manager.plugin("hello").orElseThrow().state()).isEqualTo(State.DISABLED);
        assertThat(manager.modules()).isEmpty();
        assertThat(lifecycle("hello")).isEqualTo("load 1.0.0\nenable 1.0.0\ndisable 1.0.0");
        assertThat(store.plugins().disabled()).containsExactly("hello");

        manager.close();
        manager = new PluginManager(pluginsDir(), new SettingsStore(home), Set::of, () -> null, resolver);
        assertThat(manager.plugin("hello").orElseThrow().state()).isEqualTo(State.DISABLED);
        manager.setEnabled("hello", true);
        assertThat(manager.plugin("hello").orElseThrow().state()).isEqualTo(State.ENABLED);
    }

    @Test
    void installReplacesOldVersionAndUninstallDeletesJar() throws Exception {
        manager().install(echo("hello", "1.0.0", "hello", "v1 ", null), "com.acme:hello:1.0.0");
        assertThat(pluginsDir().resolve("hello-1.0.0.jar")).exists();
        assertThat(manager.plugin("hello").orElseThrow().source()).isEqualTo("com.acme:hello:1.0.0");

        PluginInfo v2 = manager.install(echo("hello", "2.0.0", "hello", "v2 ", null), "com.acme:hello:2.0.0");
        assertThat(v2.version()).isEqualTo("2.0.0");
        assertThat(v2.state()).isEqualTo(State.ENABLED);
        assertThat(pluginsDir().resolve("hello-1.0.0.jar")).doesNotExist();
        assertThat(call(manager.modules().getFirst(), "x")).contains("v2 x"); // neue Klassen, nicht die alten
        assertThat(lifecycle("hello")).endsWith("disable 1.0.0\nload 2.0.0\nenable 2.0.0");

        manager.uninstall("hello");
        assertThat(manager.plugins()).isEmpty();
        assertThat(pluginsDir().resolve("hello-2.0.0.jar")).doesNotExist();
        assertThat(pluginsDir().resolve("hello/lifecycle.log")).exists(); // Datenordner bleibt
        assertThat(store.plugins().sources()).doesNotContainKey("hello");
        assertThatThrownBy(() -> manager.uninstall("hello")).hasMessageContaining("nicht installiert");
    }

    @Test
    void reloadPicksUpJarsCopiedIntoTheFolder() throws Exception {
        assertThat(manager().plugins()).isEmpty();
        Files.createDirectories(pluginsDir());
        Files.copy(echo("late", "1", "late", "", null), pluginsDir().resolve("late.jar"));
        assertThat(manager.plugins()).isEmpty();

        manager.reload();

        assertThat(manager.plugin("late").orElseThrow().state()).isEqualTo(State.ENABLED);
    }

    @Test
    void librariesAreResolvedFromMavenRepositoryWithTransitiveDependencies() throws Exception {
        Path repo = work.resolve("repo");
        // util-core <- util-api (transitiv über den POM von util-api)
        Path core = TestPlugins.jar().source("lib.core.Core", "package lib.core; public class Core { "
                + "public static String name() { return \"core\"; } }").build(work.resolve("core.jar"));
        Path api = TestPlugins.jar().classpath(core).source("lib.api.Api", "package lib.api; public class Api { "
                + "public static String hello() { return \"api+\" + lib.core.Core.name(); } }").build(work.resolve("api.jar"));
        TestPlugins.deploy(repo, "com.acme.lib", "util-core", "1.0", core);
        TestPlugins.deploy(repo, "com.acme.lib", "util-api", "1.0", "jar", api,
                "<dependency><groupId>com.acme.lib</groupId><artifactId>util-core</artifactId><version>1.0</version>"
                        + "</dependency>");
        store.savePlugins(store.plugins().withRepositories(List.of(
                new PluginRepository("local", "Lokal", repo.toUri().toString(), "", "", false, "", true))));

        Path plugin = TestPlugins.jar()
                .pluginYml("name: withlib\nversion: 1\nmain: wl.Main\nlibraries:\n  - com.acme.lib:util-api:1.0\n")
                .classpath(api)
                .source("wl.Main", """
                        package wl;
                        public class Main extends systems.grebe.devtools.mcp.plugin.DevToolsPlugin {
                            @Override public void onEnable() {
                                logger().info(lib.api.Api.hello());
                                if (!"api+core".equals(lib.api.Api.hello())) throw new IllegalStateException();
                            }
                        }
                        """).build(work.resolve("withlib.jar"));

        PluginInfo info = manager().install(plugin, null);

        assertThat(info.state()).as(String.valueOf(info.error())).isEqualTo(State.ENABLED);
        assertThat(home.resolve("plugins/.repository/com/acme/lib/util-core/1.0/util-core-1.0.jar")).exists();
    }

    @Test
    void unresolvableLibraryFailsOnlyThatPlugin() throws Exception {
        store.savePlugins(store.plugins().withRepositories(List.of(new PluginRepository("empty", "", 
                work.resolve("empty-repo").toUri().toString(), "", "", false, "", true))));
        Files.createDirectories(pluginsDir());
        Files.copy(echo("needs", "1", "needs", "", "libraries: [com.acme:gibtsnicht:1.0]\n"),
                pluginsDir().resolve("needs.jar"));
        Files.copy(echo("fine", "1", "fine", "", null), pluginsDir().resolve("fine.jar"));

        manager();

        assertThat(manager.plugin("needs").orElseThrow().error()).contains("com.acme:gibtsnicht:1.0");
        assertThat(manager.plugin("fine").orElseThrow().state()).isEqualTo(State.ENABLED);
    }

    @Test
    void pluginCodeRunsWithThePluginAsContextClassLoader() throws Exception {
        // Bibliotheken wie Jackson oder ServiceLoader suchen über den Thread-Context-ClassLoader
        Files.createDirectories(pluginsDir());
        TestPlugins.jar()
                .pluginYml("name: tccl\nversion: 1\nmain: tccl.Main\n")
                .source("tccl.Main", """
                        package tccl;
                        import java.util.List;
                        import org.springframework.ai.tool.ToolCallback;
                        import org.springframework.ai.tool.definition.ToolDefinition;
                        import systems.grebe.devtools.mcp.core.ModuleConfig;
                        import systems.grebe.devtools.mcp.core.ToolModule;
                        public class Main extends systems.grebe.devtools.mcp.plugin.DevToolsPlugin {
                            static String find() {
                                try {
                                    Thread.currentThread().getContextClassLoader().loadClass("tccl.Main");
                                    return "gefunden";
                                } catch (ClassNotFoundException e) { return "fehlt"; }
                            }
                            @Override public void onEnable() {
                                String atEnable = find();
                                registerModule(new ToolModule() {
                                    public String id() { return "tccl"; }
                                    public String displayName() { return "T"; }
                                    public String description() { return find(); }
                                    public List<ToolCallback> createTools(ModuleConfig c) {
                                        return List.of(new ToolCallback() {
                                            public ToolDefinition getToolDefinition() {
                                                return ToolDefinition.builder().name("t").description("t")
                                                        .inputSchema("{\\"type\\":\\"object\\"}").build();
                                            }
                                            public String call(String in) { return atEnable + "/" + find(); }
                                        });
                                    }
                                });
                            }
                        }
                        """).build(pluginsDir().resolve("tccl.jar"));

        ToolModule module = manager().modules().getFirst();
        assertThat(module.description()).isEqualTo("gefunden");
        assertThat(module.createTools(ModuleConfig.of(List.of(), java.util.Map.of())).getFirst().call("{}"))
                .isEqualTo("gefunden/gefunden");
        assertThat(Thread.currentThread().getContextClassLoader()).isNotInstanceOf(PluginClassLoader.class);
    }

    @Test
    void springPluginGetsComponentScanInjectionBeanMethodsAndLifecycle() throws Exception {
        Files.createDirectories(pluginsDir());
        TestPlugins.springPlugin("springy", "1.0", "springy", null).build(pluginsDir().resolve("springy.jar"));

        PluginInfo info = manager().plugin("springy").orElseThrow();

        assertThat(info.state()).as(String.valueOf(info.error())).isEqualTo(State.ENABLED);
        assertThat(info.modules()).containsExactly("springy"); // @Component-Modul ohne registerModule aufgenommen
        ToolModule module = manager.modules().getFirst();
        ToolCallback tool = module.createTools(ModuleConfig.of(List.of(), java.util.Map.of())).getFirst();
        // @Service injiziert (Singleton: Zähler läuft weiter), @Bean aus der Hauptklasse, @Value mit Default ohne App
        assertThat(tool.call("{}")).contains("Hallo aus @Bean", "app=ohne-app", "bean=keine", "zähler=1");
        assertThat(tool.call("{}")).contains("zähler=2");
        assertThat(module.instructions()).isEqualTo("Spring-Hinweis springy 1.0"); // PluginContext injiziert
        assertThat(lifecycle("springy")).isEqualTo("PostConstruct\nonEnable");

        manager.setEnabled("springy", false);
        assertThat(lifecycle("springy")).isEqualTo("PostConstruct\nonEnable\nonDisable\nPreDestroy");
    }

    @Test
    void failingBeanFailsOnlyThatPluginWithTheSpringMessage() throws Exception {
        Files.createDirectories(pluginsDir());
        TestPlugins.jar()
                .pluginYml("name: badbean\nversion: 1\nmain: badbean.Main\n")
                .source("badbean.Main", "package badbean; public class Main extends "
                        + "systems.grebe.devtools.mcp.plugin.DevToolsPlugin {}")
                .source("badbean.NeedsMissing", """
                        package badbean;
                        @org.springframework.stereotype.Component
                        public class NeedsMissing {
                            public NeedsMissing(java.util.concurrent.ExecutorService gibtsNicht) { }
                        }
                        """).build(pluginsDir().resolve("badbean.jar"));
        Files.copy(echo("fine", "1", "fine", "", null), pluginsDir().resolve("fine.jar"));

        manager();

        assertThat(manager.plugin("badbean").orElseThrow()).satisfies(p -> {
            assertThat(p.state()).isEqualTo(State.FAILED);
            assertThat(p.error()).contains("ExecutorService");
        });
        assertThat(manager.plugin("fine").orElseThrow().state()).isEqualTo(State.ENABLED);
    }

    @Test
    void descriptorValidation() {
        assertThat(parse("name: ok\nversion: 1\nmain: a.B\nauthor: x\nauthors: [y]\nlibraries: [g:a:1]")).satisfies(d -> {
            assertThat(d.authors()).containsExactly("x", "y");
            assertThat(d.apiVersion()).isEqualTo(1);
        });
        assertThatThrownBy(() -> parse("version: 1\nmain: a.B")).hasMessageContaining("'name' fehlt");
        assertThatThrownBy(() -> parse("name: Bad_Name\nversion: 1\nmain: a.B")).hasMessageContaining("ungültig");
        assertThatThrownBy(() -> parse("name: ok\nversion: 1\nmain: NoPackage")).hasMessageContaining("voll qualifiziert");
        assertThatThrownBy(() -> parse("name: ok\nversion: 1\nmain: systems.grebe.devtools.mcp.X"))
                .hasMessageContaining("Paket der Anwendung");
        assertThatThrownBy(() -> parse("name: ok\nversion: 1\nmain: a.B\ndepend: [ok]"))
                .hasMessageContaining("selbst");
        assertThatThrownBy(() -> parse("name: ok\nversion: 1\nmain: a.B\nlibraries: [nur-ein-teil]"))
                .hasMessageContaining("Maven-Koordinate");
        assertThatThrownBy(() -> parse("- liste")).hasMessageContaining("keine Zuordnung");
        // SafeConstructor: keine beliebigen Java-Objekte aus YAML
        assertThatThrownBy(() -> parse("name: !!javax.script.ScriptEngineManager [x]\nversion: 1\nmain: a.B"))
                .hasMessageContaining("kein gültiges YAML");
    }

    private static PluginDescriptor parse(String yml) {
        return PluginDescriptorReader.parse(new ByteArrayInputStream(yml.getBytes(StandardCharsets.UTF_8)));
    }
}
