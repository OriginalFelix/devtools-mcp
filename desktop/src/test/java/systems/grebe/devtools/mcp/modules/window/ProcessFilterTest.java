package systems.grebe.devtools.mcp.modules.window;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessFilterTest {

    private static ProcessFilter.Info p(String name, String cmd) {
        return new ProcessFilter.Info(1, name, cmd);
    }

    @Test
    void passwordManagersAndLoginDialogsAreAlwaysExcluded() {
        ProcessFilter all = new ProcessFilter(null, null, -1);
        assertThat(all.rejection(p("KeePassXC", "/usr/bin/keepassxc"))).isPresent();
        assertThat(all.rejection(p("consent", "C:\\Windows\\System32\\consent.exe"))).isPresent();
        assertThat(all.rejection(p("notepad", "C:\\Windows\\notepad.exe"))).isEmpty();
    }

    @Test
    void includeAndExcludeMatchNameAndCommandLine() {
        ProcessFilter f = new ProcessFilter(Pattern.compile("(?i)notepad|calc"), Pattern.compile("(?i)--secret"), -1);
        assertThat(f.rejection(p("notepad", "notepad.exe a.txt"))).isEmpty();
        assertThat(f.rejection(p("notepad", "notepad.exe --secret"))).get().asString().contains("ausgeschlossen");
        assertThat(f.rejection(p("explorer", "explorer.exe"))).get().asString().contains("nicht freigegeben");
    }

    @Test
    void thisAppIsNeverControlled() {
        assertThat(new ProcessFilter(null, null).rejection(ProcessHandle.current().pid())).get().asString()
                .contains("DevTools-App selbst");
    }

    @Test
    void childrenOfThisAppAreExcludedUnlessTheAiLaunchedThem() throws Exception {
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        Process child = new ProcessBuilder(windows ? java.util.List.of("ping", "-n", "30", "127.0.0.1")
                : java.util.List.of("sleep", "30")).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try {
            ProcessFilter f = new ProcessFilter(null, null);
            assertThat(f.rejection(child.pid())).get().asString().contains("DevTools-App selbst");

            f.launched(child.pid());

            assertThat(f.rejection(child.pid())).isEmpty();
            assertThat(f.rejection(ProcessHandle.current().pid())).isPresent(); // die App selbst bleibt gesperrt
        } finally {
            child.destroyForcibly();
        }
    }

    @Test
    void nameDropsExtension() {
        assertThat(ProcessFilter.name("C:\\Program Files\\App\\App.exe")).isEqualTo("App");
        assertThat(ProcessFilter.name("/usr/bin/gedit")).isEqualTo("gedit");
    }

    private static ProcessFilter sharing(Pattern include, Pattern exclude, Path dir) {
        return new ProcessFilter(include, exclude, -1, Set.of(), List.of(dir));
    }

    private static ProcessFilter.Info program(Path exe) {
        return ProcessFilter.Info.ofExecutable(exe);
    }

    @Test
    void programsInSharedDirectoriesOverrideIncludeAndExclude() {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath();
        ProcessFilter f = sharing(Pattern.compile("(?i)notepad"), Pattern.compile("(?i)foo"), tmp.resolve("tools"));

        assertThat(f.rejection(program(tmp.resolve("tools").resolve("sub").resolve("deep").resolve("foo.exe"))))
                .isEmpty();
        assertThat(f.rejection(program(tmp.resolve("other").resolve("foo.exe")))).isPresent();
        assertThat(f.rejection(program(tmp.resolve("toolsX").resolve("foo.exe")))).isPresent(); // kein Namenspräfix
    }

    @Test
    void sharedDirectoriesNeverUnlockPasswordManagers() {
        Path dir = Path.of(System.getProperty("java.io.tmpdir"), "tools").toAbsolutePath();

        assertThat(sharing(null, null, dir).rejection(program(dir.resolve("KeePass.exe")))).isPresent();
    }

    @Test
    void infoOfAnExecutableUsesNameWithoutExtension() {
        ProcessFilter.Info i = program(Path.of("Tools", "Foo.exe"));

        assertThat(i.name()).isEqualTo("Foo");
        assertThat(i.command()).endsWith("Foo.exe");
    }
}
