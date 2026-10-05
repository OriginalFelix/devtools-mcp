package systems.grebe.devtools.mcp.modules.window;

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
}
