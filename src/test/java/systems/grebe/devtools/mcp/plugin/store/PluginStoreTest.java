package systems.grebe.devtools.mcp.plugin.store;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import systems.grebe.devtools.mcp.config.SettingsStore;
import systems.grebe.devtools.mcp.plugin.PluginManager;
import systems.grebe.devtools.mcp.plugin.PluginManager.State;
import systems.grebe.devtools.mcp.plugin.TestPlugins;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Plugin-Store gegen echte Maven-Repositories: ein {@code file:}-Repository und ein HTTP-Repository mit Basic-Auth
 * (JDK-HttpServer liefert das Maven-2-Layout aus einem Ordner).
 */
class PluginStoreTest {

    @TempDir
    Path home;

    @TempDir
    Path work;

    Path httpRepo;
    HttpServer server;
    AtomicInteger unauthorized = new AtomicInteger();
    SettingsStore settings;
    MavenPluginResolver resolver;
    PluginManager manager;
    PluginStore store;

    @BeforeEach
    void setUp() throws IOException {
        httpRepo = Files.createDirectories(work.resolve("http-repo"));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String expected = "Basic " + Base64.getEncoder().encodeToString("felix:geheim".getBytes(StandardCharsets.UTF_8));
        server.createContext("/repo/", ex -> {
            try (ex) {
                if (!expected.equals(ex.getRequestHeaders().getFirst("Authorization"))) {
                    unauthorized.incrementAndGet();
                    ex.getResponseHeaders().add("WWW-Authenticate", "Basic realm=\"test\"");
                    ex.sendResponseHeaders(401, -1);
                    return;
                }
                Path file = httpRepo.resolve(ex.getRequestURI().getPath().substring("/repo/".length())).normalize();
                if (!file.startsWith(httpRepo) || !Files.isRegularFile(file)) {
                    ex.sendResponseHeaders(404, -1);
                    return;
                }
                byte[] body = Files.readAllBytes(file);
                if (ex.getRequestMethod().equals("HEAD")) {
                    ex.sendResponseHeaders(200, -1);
                    return;
                }
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
            }
        });
        server.start();

        settings = new SettingsStore(home);
        resolver = new MavenPluginResolver(home.resolve("plugins/.repository"), () -> settings.plugins().repositories());
        manager = new PluginManager(PluginManager.defaultDirectory(settings), settings, Set::of, () -> null, resolver);
        store = new PluginStore(settings, manager, resolver);
        settings.savePlugins(settings.plugins().withRepositories(List.of())); // kein Maven Central im Test
    }

    @AfterEach
    void tearDown() {
        manager.close();
        resolver.close();
        server.stop(0);
    }

    private String httpUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/repo/";
    }

    private PluginRepository httpRepository(String password, String catalog) {
        return new PluginRepository("team", "Team-Nexus", httpUrl(), "felix", password, false, catalog, true);
    }

    private Path echo(String name, String version, String prefix) {
        return TestPlugins.echoPlugin(name, version, name, prefix, null).build(work.resolve(name + "-" + version + ".jar"));
    }

    @Test
    void catalogSearchInstallAndUpdateFromAuthenticatedHttpRepository() throws Exception {
        TestPlugins.deploy(httpRepo, "com.acme.devtools", "jira-plugin", "1.0.0", echo("jira", "1.0.0", "v1 "));
        Path catalogYml = TestPlugins.textFile(work, "catalog.yml", """
                plugins:
                  - coordinates: com.acme.devtools:jira-plugin
                    name: Jira
                    description: Tickets lesen und kommentieren
                    author: Team Tools
                    tags: [ticket, atlassian]
                  - coordinates: kaputt
                """);
        TestPlugins.deploy(httpRepo, "com.acme.devtools", "catalog", "1", "yml", catalogYml, null);
        store.saveRepository(httpRepository("geheim", "com.acme.devtools:catalog"));

        // Passwort liegt verschlüsselt in settings.json und kommt nach Neustart wieder
        String json = Files.readString(home.resolve("settings.json"));
        assertThat(json).contains("\"username\" : \"felix\"").doesNotContain("geheim");
        assertThat(new SettingsStore(home).plugins().repositories().getFirst().password()).isEqualTo("geheim");

        assertThat(store.testRepository(settings.plugins().repositories().getFirst()))
                .contains("Katalog com.acme.devtools:catalog in Version 1");

        PluginStore.CatalogResult catalog = store.search("atlassian");
        assertThat(catalog.errors()).isEmpty();
        assertThat(catalog.entries()).singleElement().satisfies(e -> {
            assertThat(e.name()).isEqualTo("Jira");
            assertThat(e.coordinates()).isEqualTo("com.acme.devtools:jira-plugin");
            assertThat(e.repositoryId()).isEqualTo("team");
        });
        assertThat(store.search("gibtsnicht").entries()).isEmpty();
        assertThat(store.versions("com.acme.devtools", "jira-plugin")).containsExactly("1.0.0");

        PluginManager.PluginInfo installed = store.install("com.acme.devtools:jira-plugin");
        assertThat(installed.state()).as(String.valueOf(installed.error())).isEqualTo(State.ENABLED);
        assertThat(installed.version()).isEqualTo("1.0.0");
        assertThat(installed.source()).isEqualTo("com.acme.devtools:jira-plugin:1.0.0");
        assertThat(store.installed("com.acme.devtools", "jira-plugin")).isPresent();
        assertThat(store.updates()).isEmpty();

        // neue Version veröffentlicht → Update erkannt (1.10.0 > 1.9.0, nicht lexikografisch) und installiert
        TestPlugins.deploy(httpRepo, "com.acme.devtools", "jira-plugin", "1.9.0", echo("jira", "1.9.0", "v19 "));
        TestPlugins.deploy(httpRepo, "com.acme.devtools", "jira-plugin", "1.10.0", echo("jira", "1.10.0", "v110 "));
        assertThat(store.versions("com.acme.devtools", "jira-plugin")).containsExactly("1.10.0", "1.9.0", "1.0.0");
        assertThat(store.updates()).singleElement().satisfies(u -> {
            assertThat(u.installedVersion()).isEqualTo("1.0.0");
            assertThat(u.latestVersion()).isEqualTo("1.10.0");
        });
        PluginManager.PluginInfo updated = store.install(store.updates().getFirst().coordinates());
        assertThat(updated.version()).isEqualTo("1.10.0");
        assertThat(manager.plugins()).hasSize(1);
        assertThat(manager.modules().getFirst().createTools(
                systems.grebe.devtools.mcp.core.ModuleConfig.of(List.of(), java.util.Map.of()))
                .getFirst().call("{\"text\":\"x\"}")).contains("v110 x");
        assertThat(store.updates()).isEmpty();

        // gezielt eine ältere Version (Downgrade)
        assertThat(store.install("com.acme.devtools:jira-plugin:1.9.0").version()).isEqualTo("1.9.0");
    }

    @Test
    void wrongPasswordIsReportedAndSavingWithEmptyPasswordKeepsTheOldOne() {
        TestPlugins.deploy(httpRepo, "com.acme.devtools", "jira-plugin", "1.0.0", echo("jira", "1.0.0", ""));
        store.saveRepository(httpRepository("falsch", ""));
        assertThatThrownBy(() -> store.install("com.acme.devtools:jira-plugin"))
                .hasMessageContaining("com.acme.devtools:jira-plugin");
        assertThat(unauthorized.get()).isPositive();

        store.saveRepository(httpRepository("geheim", ""));
        // Bearbeiten ohne neues Passwort (Dialog: „(unverändert)“) behält das gespeicherte
        store.saveRepository(new PluginRepository("team", "Umbenannt", httpUrl(), "felix", "", false, "", true));
        assertThat(settings.plugins().repositories().getFirst().password()).isEqualTo("geheim");
        assertThat(settings.plugins().repositories().getFirst().name()).isEqualTo("Umbenannt");
        assertThat(store.install("com.acme.devtools:jira-plugin").state()).isEqualTo(State.ENABLED);
    }

    @Test
    void fileRepositoryWithoutCatalogAndRepositoryOrder() {
        Path fileRepo = work.resolve("file-repo");
        TestPlugins.deploy(fileRepo, "com.acme", "local-plugin", "0.1.0", echo("localp", "0.1.0", ""));
        store.saveRepository(new PluginRepository("local", "", fileRepo.toUri().toString(), "", "", false, "", true));
        store.saveRepository(new PluginRepository("second", "", work.resolve("other").toUri().toString(), "", "", false,
                "", true));

        assertThat(store.catalog().entries()).isEmpty(); // kein Katalog – direkt über Koordinaten
        assertThat(store.install("com.acme:local-plugin").name()).isEqualTo("localp");

        store.moveRepository("second", -1);
        assertThat(store.repositories()).extracting(PluginRepository::id).containsExactly("second", "local");
        store.removeRepository("second");
        assertThat(store.repositories()).extracting(PluginRepository::id).containsExactly("local");

        assertThatThrownBy(() -> store.install("com.acme:gibtsnicht"))
                .hasMessageContaining("in keinem aktiven Repository");
        assertThatThrownBy(() -> store.install("nur-ein-teil")).hasMessageContaining("groupId:artifactId");
    }

    @Test
    void tamperedArtifactIsRejectedByChecksum() throws Exception {
        Path fileRepo = work.resolve("file-repo");
        Path jar = TestPlugins.deploy(fileRepo, "com.acme", "evil", "1.0", echo("evil", "1.0", ""));
        Files.write(jar, new byte[] {1, 2, 3}, java.nio.file.StandardOpenOption.APPEND); // nach dem Signieren verändert
        store.saveRepository(new PluginRepository("local", "", fileRepo.toUri().toString(), "", "", false, "", true));

        assertThatThrownBy(() -> store.install("com.acme:evil:1.0")).hasMessageContaining("Checksum");
        assertThat(manager.plugins()).isEmpty();
    }

    @Test
    void unreachableCatalogIsReportedPerRepository() {
        store.saveRepository(new PluginRepository("gone", "", "http://127.0.0.1:1/repo/", "", "", false, "a:b", true));
        store.saveRepository(new PluginRepository("off", "", "http://127.0.0.1:1/x/", "", "", false, "c:d", false));

        PluginStore.CatalogResult result = store.catalog();

        assertThat(result.entries()).isEmpty();
        assertThat(result.errors()).containsOnlyKeys("gone"); // deaktivierte Repositories werden nicht gefragt
    }

    @Test
    void repositoryValidation() {
        assertThat(new PluginRepository("ok", "", "https://nexus.example.com/repo", "", "", false, "", true).validate())
                .isEmpty();
        assertThat(new PluginRepository("", "", "ftp://x", "", "", false, "nope", true).validate())
                .anyMatch(s -> s.startsWith("ID ungültig"))
                .anyMatch(s -> s.contains("https://, http:// oder file:"))
                .anyMatch(s -> s.contains("groupId:artifactId"));
        assertThatThrownBy(() -> store.saveRepository(new PluginRepository("x y", "", "https://a", "", "", false, "",
                true))).hasMessageContaining("ID ungültig");
        assertThat(new PluginRepository("r", "", "https://a", "u", "pw", false, "", true).toString())
                .doesNotContain("pw");
        assertThat(PluginStore.isNewer("1.10.0", "1.9.0")).isTrue();
        assertThat(PluginStore.isNewer("1.0.0", "1.0.0-SNAPSHOT")).isTrue();
        assertThat(PluginStore.isNewer("1.0", "2.0")).isFalse();
    }
}
