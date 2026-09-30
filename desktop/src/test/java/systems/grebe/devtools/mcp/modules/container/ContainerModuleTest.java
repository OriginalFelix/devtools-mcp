package systems.grebe.devtools.mcp.modules.container;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.modules.container.spi.ContainerRuntimeProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ServiceLoader-Erkennung, Konfiguration, Tool-Auswahl und Sicherheitsprüfungen – ohne echte Laufzeit. */
class ContainerModuleTest {

    @TempDir
    Path tmp;

    ContainerRuntimes runtimes = new ContainerRuntimes();
    ContainerModule module = new ContainerModule(runtimes);

    @BeforeEach
    void reset() {
        FakeRuntimeProvider.Fake.CALLS.clear();
    }

    private ModuleConfig config(Map<String, String> v) {
        Map<String, String> m = new HashMap<>(v);
        m.putIfAbsent("defaultRuntime", "fake");
        return ModuleConfig.of(module.configSchema(), m);
    }

    private ContainerEnvironment env(Map<String, String> v) {
        return new ContainerEnvironment(runtimes, config(v));
    }

    private List<String> toolNames(Map<String, String> v) {
        return module.createTools(config(v)).stream().map(ToolCallback::getToolDefinition).map(d -> d.name()).toList();
    }

    @Test
    void serviceLoaderFindsBuiltInAndTestProviders() {
        assertThat(runtimes.providers()).extracting(ContainerRuntimeProvider::id).containsExactly("docker", "podman", "fake");
        // Konfigurationsfelder werden automatisch mit Präfix erzeugt
        assertThat(module.configSchema()).extracting(ConfigField::key)
                .contains("docker.enabled", "docker.binary", "docker.context", "podman.connection", "fake.greeting", "fake.enabled");
        ConfigField def = module.configSchema().getFirst();
        assertThat(def.options()).containsExactly("auto", "docker", "podman", "fake");
        assertThat(module.configSchema()).filteredOn(f -> f.key().equals("fake.greeting"))
                .extracting(ConfigField::label).containsExactly("Fake-Laufzeit: Begrüßung");
    }

    @Test
    void providerSettingsReachTheRuntime() {
        ContainerReadTools t = new ContainerReadTools(env(Map.of("fake.greeting", "servus")));
        assertThat(t.runtimes()).contains("fake (Fake-Laufzeit): verfügbar, Version 1.0-servus").contains("Standard: fake");
    }

    @Test
    void disabledRuntimeIsNotOffered() {
        ContainerEnvironment e = env(Map.of("fake.enabled", "false"));
        assertThat(e.entries()).extracting(x -> x.provider().id()).doesNotContain("fake");
        assertThatThrownBy(() -> e.runtime("fake")).hasMessageContaining("nicht aktiviert");
    }

    @Test
    void writeToolsAreOffOnlyByDefault() {
        List<String> names = toolNames(Map.of());
        assertThat(names).contains("runtimes", "list", "inspect", "logs", "stats", "top", "diff", "images", "networks", "volumes")
                .doesNotContain("exec", "start", "stop", "run", "pull", "rm", "rmi", "copy_to", "compose_up", "compose_ps");
        List<String> all = toolNames(Map.of("allowExec", "true", "allowLifecycle", "true", "allowCopy", "true",
                "allowCreate", "true", "allowRemove", "true", "allowCompose", "true", "composeProjects", composeDir().toString()));
        assertThat(all).contains("exec", "start", "stop", "restart", "copy_from", "copy_to", "run", "pull", "rm", "rmi",
                "compose_projects", "compose_ps", "compose_logs", "compose_config", "compose_up", "compose_down", "compose_restart");
    }

    private Path composeDir() {
        try {
            Path p = Files.createDirectories(tmp.resolve("shop"));
            Files.writeString(p.resolve("compose.yaml"), "services:\n  web:\n    image: nginx:alpine\n");
            return p;
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Test
    void readToolsFilterAndMask() {
        ContainerReadTools t = new ContainerReadTools(env(Map.of("allowedContainers", "app-.*")));
        String list = t.list(null, null, null);
        assertThat(list).contains("app-web", "app-old").doesNotContain("secret-db");
        assertThatThrownBy(() -> t.inspect("secret-db", null, null)).hasMessageContaining("nicht freigegeben");

        String inspect = t.inspect("app-web", null, null);
        assertThat(inspect).contains("DB_PASSWORD=***", "API_KEY=***", "APP_MODE=test", "80/tcp -> 127.0.0.1:8080", "bridge (10.0.0.2)")
                .doesNotContain("geheim");
        assertThat(t.inspect("app-web", true, null)).doesNotContain("GraphDriver").doesNotContain("geheim");
        assertThat(t.logs("app-web", null, null, "error", null, null)).isEqualTo("ERROR kaputt");
        assertThatThrownBy(() -> t.logs("app-web", null, "1h; rm", null, null, null)).hasMessageContaining("Ungültige Zeitangabe");
        assertThat(t.stats(null, null)).contains("app-web").doesNotContain("secret-db");
    }

    @Test
    void execRespectsAllowlist() {
        ContainerExecTools t = new ContainerExecTools(env(Map.of("execAllowlist", "ls\ncat")));
        assertThat(t.exec("app-web", List.of("/bin/ls", "-la"), null, null, null, null)).startsWith("Exit-Code 0");
        assertThatThrownBy(() -> t.exec("app-web", List.of("rm", "-rf", "/"), null, null, null, null))
                .hasMessageContaining("nicht freigegeben");
        assertThatThrownBy(() -> t.exec("bad name", List.of("ls"), null, null, null, null)).hasMessageContaining("Ungültiger Containername");
    }

    @Test
    void runBindsPortsLocallyAndChecksImagesAndMounts() throws Exception {
        Path shared = Files.createDirectories(tmp.resolve("shared"));
        ContainerCreateTools t = new ContainerCreateTools(env(Map.of("allowedImages", "(nginx|postgres).*",
                "hostDirectories", shared.toString())));
        String out = t.run("nginx:alpine", "own-web", List.of("A=1"), List.of("8080:80", "443"),
                List.of("data:/var/lib", shared + ":/srv:ro"), null, null, null);
        assertThat(out).contains("127.0.0.1:8080:80", "127.0.0.1::443");
        String call = FakeRuntimeProvider.Fake.CALLS.getLast();
        assertThat(call).contains("devtools-mcp=true").contains("data:/var/lib").contains(shared + ":/srv:ro");

        assertThatThrownBy(() -> t.run("redis:7", "x", null, null, null, null, null, null)).hasMessageContaining("nicht freigegeben");
        assertThatThrownBy(() -> t.run("nginx", "x", null, List.of("0.0.0.0:80:80"), null, null, null, null))
                .hasMessageContaining("nur auf 127.0.0.1");
        assertThatThrownBy(() -> t.run("nginx", "x", null, null, List.of(tmp.getParent() + ":/host"), null, null, null))
                .hasMessageContaining("nicht in einem freigegebenen");
        assertThatThrownBy(() -> t.run("nginx", "x", List.of("kaputt"), null, null, null, null, null))
                .hasMessageContaining("KEY=VALUE");
    }

    @Test
    void removeOnlyOwnContainers() {
        ContainerRemoveTools t = new ContainerRemoveTools(env(Map.of()));
        assertThatThrownBy(() -> t.rm("app-web", null, null, null)).hasMessageContaining("nicht über container_run angelegt");
        assertThat(t.rm("own-web", true, null, null)).contains("gelöscht");
        assertThat(FakeRuntimeProvider.Fake.CALLS).containsExactly("rm own-web");
        ContainerRemoveTools any = new ContainerRemoveTools(env(Map.of("removeOnlyOwn", "false")));
        assertThat(any.rm("app-web", null, null, null)).contains("gelöscht");
    }

    @Test
    void copyOnlyWithinSharedDirectories() throws Exception {
        Path shared = Files.createDirectories(tmp.resolve("shared"));
        ContainerCopyTools t = new ContainerCopyTools(env(Map.of("hostDirectories", shared.toString())));
        assertThat(t.copyFrom("app-web", "/etc/nginx/nginx.conf", shared.resolve("nginx.conf").toString(), null)).contains("Kopiert");
        assertThatThrownBy(() -> t.copyFrom("app-web", "/etc/passwd", tmp.resolve("x").toString(), null))
                .hasMessageContaining("nicht in einem freigegebenen");
        assertThatThrownBy(() -> t.copyFrom("app-web", "relativ", shared.toString(), null)).hasMessageContaining("absolut");
    }

    @Test
    void migratesSettingsFromJavaModule() {
        Map<String, String> v = module.initialValues(id -> id.equals("java")
                ? Map.of("containerCli", "podman", "allowedContainers", "dev-.*") : Map.of());
        assertThat(v).containsEntry("defaultRuntime", "podman").containsEntry("allowedContainers", "dev-.*");
        Map<String, String> off = module.initialValues(id -> Map.of("containerCli", "aus"));
        assertThat(off).containsEntry("docker.enabled", "false").containsEntry("podman.enabled", "false");
        assertThat(module.initialValues(id -> Map.of())).isEmpty();
    }
}
