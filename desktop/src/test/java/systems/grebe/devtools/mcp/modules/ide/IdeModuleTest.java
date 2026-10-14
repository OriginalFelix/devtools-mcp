package systems.grebe.devtools.mcp.modules.ide;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.Workspaces;
import systems.grebe.devtools.mcp.modules.ide.spi.Ide;
import systems.grebe.devtools.mcp.modules.ide.spi.IdeProvider;
import systems.grebe.devtools.mcp.modules.ticket.spi.ProviderSettings;
import systems.grebe.devtools.mcp.project.spi.ProjectDirectory;
import systems.grebe.devtools.mcp.project.spi.ProjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ServiceLoader-Erkennung, Schema, Auswahl der IDE, Freigabeprüfung und die Tools mit einer Fake-IDE. */
class IdeModuleTest {

    @TempDir
    Path tmp;

    /** Sammelordner mit den Projekten {@code app} (Git, .idea) und {@code lib} (Fake-Marker). */
    Path root;
    Path app;
    Path lib;
    List<String> calls = new ArrayList<>();

    /** Fake-IDE: protokolliert Aufrufe, erkennt Projekte mit {@code <id>.marker}; schließen kann nur {@code fake}. */
    class FakeProvider implements IdeProvider {
        final String id;

        FakeProvider(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String displayName() {
            return "Fake " + id;
        }

        @Override
        public boolean recognizes(Path dir) {
            return Files.exists(dir.resolve(id + ".marker"));
        }

        @Override
        public Ide create(ProviderSettings settings) {
            return new Ide() {
                @Override
                public Set<Capability> capabilities() {
                    return id.equals("fake") ? Set.of(Capability.values())
                            : Set.of(Capability.OPEN_PROJECT, Capability.SHOW);
                }

                @Override
                public Availability probe() {
                    return Availability.found("/opt/" + id);
                }

                @Override
                public String openProject(Path dir) {
                    calls.add(id + " open " + dir);
                    return "geöffnet";
                }

                @Override
                public String closeProject(Path dir) {
                    calls.add(id + " close " + dir);
                    return "geschlossen";
                }

                @Override
                public String show(Location l) {
                    calls.add(id + " show " + l.spec() + " in " + l.project());
                    return "gezeigt";
                }

                @Override
                public String diff(Path left, Path right) {
                    calls.add(id + " diff " + left.getFileName() + " " + right.getFileName());
                    return "verglichen";
                }
            };
        }
    }

    IdeProviders fakes = new IdeProviders(List.of(new FakeProvider("fake"), new FakeProvider("other")));

    /** Freigegeben ist der Sammelordner {@code root} – wie im Modul „Freigaben“. */
    ProjectProvider projects = new ProjectProvider() {
        @Override
        public List<ProjectDirectory> projects(Predicate<Path> marker) {
            return ws(marker).all().entrySet().stream()
                    .map(e -> new ProjectDirectory(e.getKey(), e.getValue(), true)).toList();
        }

        @Override
        public ProjectDirectory resolve(String nameOrPath, Predicate<Path> marker) {
            Path p = ws(marker).resolve(nameOrPath, null);
            return new ProjectDirectory(p.getFileName().toString(), p, true);
        }

        private Workspaces ws(Predicate<Path> marker) {
            return new Workspaces(List.of(root.toString()), marker, "Projekte");
        }
    };

    @BeforeEach
    void projects() throws Exception {
        root = Files.createDirectories(tmp.resolve("src"));
        app = Files.createDirectories(root.resolve("app"));
        Files.createDirectories(app.resolve(".git"));
        Files.createDirectories(app.resolve("src/main"));
        Files.writeString(app.resolve("src/main/App.java"), "class App {}\n");
        Files.writeString(app.resolve("src/main/Old.java"), "class Old {}\n");
        lib = Files.createDirectories(root.resolve("lib"));
        Files.writeString(lib.resolve("other.marker"), "");
        Files.writeString(lib.resolve("Lib.java"), "class Lib {}\n");
        Files.createDirectories(tmp.resolve("secret"));
        Files.writeString(tmp.resolve("secret/Secret.java"), "class Secret {}\n");
    }

    private IdeModule module(IdeProviders providers) {
        return new IdeModule(providers, () -> projects);
    }

    private IdeEnvironment env(Map<String, String> values) {
        IdeModule m = module(fakes);
        return new IdeEnvironment(fakes, projects, ModuleConfig.of(m.configSchema(), values));
    }

    private IdeTools tools(Map<String, String> values) {
        return new IdeTools(env(values));
    }

    @Test
    void serviceLoaderFindsBuiltInProvidersAndBuildsSchema() {
        IdeProviders builtin = new IdeProviders();
        assertThat(builtin.providers()).extracting(IdeProvider::id).containsExactly("intellij", "vscode",
                "android-studio", "eclipse", "netbeans", "visualstudio", "xcode", "clion", "goland", "pycharm", "rider",
                "webstorm", "cursor", "vscodium");
        IdeModule m = module(builtin);
        assertThat(m.configSchema()).extracting(ConfigField::key).contains("defaultIde", "intellij.enabled",
                "intellij.executable", "vscode.enabled", "eclipse.workspace", "visualstudio.executable",
                "timeoutSeconds");
        assertThat(m.configSchema()).filteredOn(f -> f.key().equals("defaultIde"))
                .flatExtracting(ConfigField::options).startsWith("auto", "intellij", "vscode");
        assertThat(m.instructions()).contains("`ide_show`", "`ide_open_project`", "idea");
    }

    @Test
    void createsAllTools() {
        IdeModule m = module(fakes);
        List<ToolCallback> tools = m.createTools(ModuleConfig.of(m.configSchema(), Map.of()));
        assertThat(tools).extracting(t -> t.getToolDefinition().name())
                .containsExactlyInAnyOrder("providers", "open_project", "close_project", "show", "diff");
    }

    @Test
    void selectsExplicitDefaultRecognizedOrFirstIde() {
        assertThatThrownBy(() -> env(Map.of()).select(null, app)).hasMessageContaining("Keine IDE aktiviert");

        Map<String, String> v = new HashMap<>(Map.of("fake.enabled", "true", "other.enabled", "true"));
        IdeEnvironment env = env(v);
        assertThat(env.select(null, app).id()).isEqualTo("fake");           // nichts erkannt: erste aktive
        assertThat(env.select(null, lib).id()).isEqualTo("other");          // erkennt other.marker
        assertThat(env.select("OTHER", app).id()).isEqualTo("other");
        assertThatThrownBy(() -> env.select("intellij", app)).hasMessageContaining("nicht aktiviert");

        v.put("defaultIde", "other");
        assertThat(env(v).select(null, app).id()).isEqualTo("other");
    }

    @Test
    void resolvesProjectsAndFilesOnlyInsideReleasedProjects() throws Exception {
        IdeEnvironment env = env(Map.of("fake.enabled", "true"));
        assertThat(env.project("app")).isEqualTo(app);
        assertThat(env.project(app.toString())).isEqualTo(app);
        // Verzeichnis innerhalb eines Projekts (Modul, Worktree) wird genau so geöffnet
        assertThat(env.project(app.resolve("src").toString())).isEqualTo(app.resolve("src"));
        assertThatThrownBy(() -> env.project(tmp.resolve("secret").toString())).hasMessageContaining("nicht freigegeben");
        assertThatThrownBy(() -> env.project(null)).hasMessageContaining("Mehrere");

        Ide.Location l = env.location("src/main/App.java", 12, 5, "app");
        assertThat(l.file()).isEqualTo(app.resolve("src/main/App.java"));
        assertThat(l.project()).isEqualTo(app);
        assertThat(l.spec()).endsWith("App.java:12:5");
        // ohne Projekt: nächstes Projektverzeichnis über der Datei
        assertThat(env.location(lib.resolve("Lib.java").toString(), null, null, null).project()).isEqualTo(lib);
        assertThatThrownBy(() -> env.location(tmp.resolve("secret/Secret.java").toString(), 1, null, null))
                .hasMessageContaining("nicht freigegeben");
        assertThatThrownBy(() -> env.location("src/main/Fehlt.java", 1, null, "app"))
                .hasMessageContaining("nicht gefunden");
        assertThatThrownBy(() -> env.location("src/main/App.java", -1, null, "app")).hasMessageContaining("ab 1");
        // eigenes Projekt (Git) unterhalb eines Projekts, z.B. ein Worktree
        Path worktree = Files.createDirectories(app.resolve(".claude/worktrees/x"));
        Files.writeString(worktree.resolve(".git"), "gitdir: ../../../.git/worktrees/x\n");
        Files.writeString(worktree.resolve("W.java"), "class W {}\n");
        assertThat(env.location(worktree.resolve("W.java").toString(), 1, null, null).project()).isEqualTo(worktree);
    }

    @Test
    void toolsDelegateToSelectedIdeAndCheckCapabilities() {
        IdeTools t = tools(Map.of("fake.enabled", "true", "other.enabled", "true"));
        assertThat(t.openProject("app", null)).isEqualTo("Fake fake: geöffnet");
        assertThat(t.show("src/main/App.java", 3, null, "app", null)).isEqualTo("Fake fake: gezeigt");
        assertThat(t.show(lib.resolve("Lib.java").toString(), 7, 2, null, null)).isEqualTo("Fake other: gezeigt");
        assertThat(t.diff("src/main/Old.java", "src/main/App.java", "app", null)).isEqualTo("Fake fake: verglichen");
        assertThat(t.closeProject("app", "fake")).isEqualTo("Fake fake: geschlossen");
        assertThat(calls).containsExactly(
                "fake open " + app,
                "fake show " + app.resolve("src/main/App.java") + ":3 in " + app,
                "other show " + lib.resolve("Lib.java") + ":7:2 in " + lib,
                "fake diff Old.java App.java",
                "fake close " + app);

        assertThatThrownBy(() -> t.closeProject("lib", null)).hasMessageContaining("Fake other kann „Projekt schließen“")
                .hasMessageContaining("selbst zu schließen");
        assertThatThrownBy(() -> t.diff(lib.resolve("Lib.java").toString(), lib.resolve("Lib.java").toString(), null,
                null)).hasMessageContaining("„Dateien vergleichen“");

        assertThat(t.providers()).contains("fake (Fake fake): gefunden – /opt/fake",
                "kann: Projekt öffnen, Projekt schließen, Code-Stelle zeigen, Dateien vergleichen",
                "kann: Projekt öffnen, Code-Stelle zeigen", "Standard: auto");
        assertThat(tools(Map.of("fake.enabled", "true")).providers()).contains("In der App aktivierbar: other");
    }

    @Test
    void connectionTestReportsInstallations() {
        IdeModule m = module(fakes);
        assertThat(m.testConnection(ModuleConfig.of(m.configSchema(), Map.of())).success()).isFalse();
        var r = m.testConnection(ModuleConfig.of(m.configSchema(), Map.of("fake.enabled", "true")));
        assertThat(r.success()).isTrue();
        assertThat(r.message()).contains("Fake fake: /opt/fake");
        assertThat(m.initialValues(id -> Map.of())).containsEntry("fake.enabled", "true")
                .containsEntry("other.enabled", "true");
    }
}
