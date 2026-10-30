package systems.grebe.devtools.mcp.modules.ide;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.Workspaces;
import systems.grebe.devtools.mcp.project.spi.ProjectDirectory;
import systems.grebe.devtools.mcp.project.spi.ProjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ide_editor und ide_cursor gegen ein Fake-Plugin (HTTP-Server mit Lock-Datei, wie das IntelliJ-Plugin). */
class IdeLinkTest {

    @TempDir
    Path tmp;
    Path root;
    Path app;
    Path locks;
    HttpServer server;
    List<String> received = new ArrayList<>();
    String editorJson;

    ProjectProvider projects = new ProjectProvider() {
        @Override
        public List<ProjectDirectory> projects(Predicate<Path> marker) {
            return List.of();
        }

        @Override
        public ProjectDirectory resolve(String nameOrPath, Predicate<Path> marker) {
            Path p = new Workspaces(List.of(root.toString()), marker, "Projekte").resolve(nameOrPath, null);
            return new ProjectDirectory(p.getFileName().toString(), p, true);
        }
    };

    @BeforeEach
    void start() throws Exception {
        root = Files.createDirectories(tmp.resolve("src"));
        app = Files.createDirectories(root.resolve("app"));
        Files.createDirectories(app.resolve(".git"));
        Files.writeString(app.resolve("App.java"), "class App {\n  void run() {}\n}\n");
        Path secret = Files.createDirectories(tmp.resolve("secret"));
        Files.createDirectories(secret.resolve(".git"));
        editorJson = """
                {"ide":"IntelliJ IDEA","projects":[
                 {"name":"app","path":"%s","active":true,"file":"%s","line":2,"column":3,
                  "selection":{"startLine":2,"startColumn":3,"endLine":2,"endColumn":13,"text":"void run()"},
                  "openFiles":["%s","%s"]},
                 {"name":"secret","path":"%s","active":false,"file":"","openFiles":[]}]}
                """.formatted(app, app.resolve("App.java"), app.resolve("App.java"), app.resolve("Other.java"), secret);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
        locks = Files.createDirectories(tmp.resolve("locks"));
        Files.writeString(locks.resolve("1.json"), """
                {"pid":%d,"port":%d,"token":"geheim","ide":"IntelliJ IDEA"}""".formatted(ProcessHandle.current().pid(),
                server.getAddress().getPort()));
        // Lock-Datei einer beendeten IDE zählt nicht
        Files.writeString(locks.resolve("2.json"), """
                {"pid":%d,"port":1,"token":"x","ide":"Tot"}""".formatted(Long.MAX_VALUE));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws java.io.IOException {
        boolean ok = "Bearer geheim".equals(ex.getRequestHeaders().getFirst("Authorization"));
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        received.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath() + " "
                + URLDecoder.decode(body, StandardCharsets.UTF_8));
        byte[] out = (!ok ? "kein Zugriff" : ex.getRequestURI().getPath().equals("/editor") ? editorJson : "gesetzt")
                .getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(ok ? 200 : 401, out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }

    private IdeTools tools(Path lockDir) {
        IdeModule m = new IdeModule(new IdeProviders(List.of()), () -> projects, new IdeLink(lockDir));
        return new IdeTools(new IdeEnvironment(new IdeProviders(List.of()), projects,
                ModuleConfig.of(m.configSchema(), Map.of())), new IdeLink(lockDir));
    }

    @Test
    void editorShowsFileSelectionAndOpenFilesOnlyOfReleasedProjects() {
        assertThat(new IdeLink(locks).instances()).extracting(IdeLink.Instance::ide).containsExactly("IntelliJ IDEA");
        String out = tools(locks).editor();
        assertThat(out).contains("IntelliJ IDEA · Projekt app (" + app + ") – zuletzt aktiv",
                "Datei: " + app.resolve("App.java") + " – Cursor Zeile 2, Spalte 3",
                "Markiert: Zeile 2, Spalte 3 bis Zeile 2, Spalte 13:\n```\nvoid run()\n```",
                "Weitere offene Dateien: Other.java",
                "1 Projekt außerhalb der Freigaben ausgeblendet.");
        assertThat(out).doesNotContain("secret");
        assertThat(received).containsExactly("GET /editor ");
    }

    @Test
    void cursorPostsReleasedLocationAndClears() {
        IdeTools t = tools(locks);
        assertThat(t.cursor("App.java", 2, null, 3, null, null, "app")).isEqualTo("IntelliJ IDEA: gesetzt");
        assertThat(received.getLast()).isEqualTo("POST /cursor file=" + app.resolve("App.java")
                + "&line=2&column=1&endLine=3&endColumn=" + Integer.MAX_VALUE + "&label=Claude");
        assertThatThrownBy(() -> t.cursor(tmp.resolve("secret/x.java").toString(), 1, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> t.cursor("App.java", 3, null, 2, null, null, "app")).hasMessageContaining("vor 'line'");
        assertThat(t.cursor(null, null, null, null, null, null, null)).isEqualTo("Cursor entfernt.");
        assertThat(received.getLast()).isEqualTo("POST /cursor clear=true");
    }

    @Test
    void withoutPluginToolsSayWhatIsMissing() throws Exception {
        assertThatThrownBy(() -> tools(tmp.resolve("leer")).editor()).hasMessageContaining("DevTools-Plugin");
        Files.writeString(locks.resolve("1.json"), """
                {"pid":%d,"port":%d,"token":"falsch","ide":"IntelliJ IDEA"}""".formatted(ProcessHandle.current().pid(),
                server.getAddress().getPort()));
        assertThatThrownBy(() -> tools(locks).editor()).hasMessageContaining("kein Zugriff (HTTP 401)");
    }
}
