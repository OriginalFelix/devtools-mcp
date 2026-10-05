package systems.grebe.devtools.mcp.modules.window;

import java.awt.Rectangle;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import systems.grebe.devtools.mcp.modules.window.platform.NativeWindow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WindowLaunchToolsTest {

    /** „Startet“ nichts, sondern meldet den Testprozess als gestartet. */
    static final class FakeLauncher implements ProgramLauncher {
        final List<String> started = new ArrayList<>();
        boolean guard;

        @Override
        public Launched launch(String program, List<String> arguments) {
            started.add(program + " " + arguments);
            return new Launched(ProcessHandle.current().pid(), Instant.now());
        }

        @Override
        public boolean needsFocusGuard() {
            return guard;
        }
    }

    private final FakeDesktop desktop = new FakeDesktop();
    private final FakeLauncher launcher = new FakeLauncher();
    private final WindowSession session = new WindowSession();
    private final List<Runnable> background = new ArrayList<>();

    private WindowLaunchTools tools(String include) {
        // self = -1: der Testprozess gilt nicht als "diese App"
        ProcessFilter filter = new ProcessFilter(include == null ? null : Pattern.compile(include), null, -1);
        return new WindowLaunchTools(desktop.support(session, filter), launcher, background::add, millis -> { });
    }

    @Test
    void launchesInBackgroundAndBindsTheProcess() {
        long self = ProcessHandle.current().pid();
        desktop.windows.add(new NativeWindow(0x55, self, 1L, "Dokument1 - Word", new Rectangle(0, 0, 800, 600), false));

        String out = tools("winword").launch("winword", List.of("/w"));

        assertThat(launcher.started).containsExactly("winword [/w]");
        assertThat(session.current().process().pid()).isEqualTo(self);
        assertThat(out).contains("Gestartet im Hintergrund", "Dokument1 - Word", "keinen Fokus");
        assertThat(background).isEmpty(); // kein Wächter nötig (z.B. macOS: open -g)
    }

    @Test
    void startsTheFocusGuardWhereNeeded() {
        launcher.guard = true;
        desktop.windows.add(new NativeWindow(0x55, ProcessHandle.current().pid(), 1L, "Word",
                new Rectangle(0, 0, 800, 600), false));

        tools(null).launch("winword", null);

        assertThat(background).hasSize(1);
    }

    @Test
    void refusesProgramsOutsideTheFilter() {
        assertThatThrownBy(() -> tools("winword").launch("notepad", List.of()))
                .hasMessageContaining("nicht freigegeben");
        assertThatThrownBy(() -> tools(null).launch("C:\\Program Files\\KeePass\\KeePass.exe", List.of()))
                .hasMessageContaining("immer ausgeschlossen");
        assertThat(launcher.started).isEmpty();
    }
}
