package systems.grebe.devtools.mcp.modules.window;

import java.awt.Rectangle;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import systems.grebe.devtools.mcp.modules.window.platform.NativeWindow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reichweite einer Bindung im echten Prozessbaum: Testprozess (Eltern) → A und B (Geschwister), A → Enkel. Gebunden
 * wird A: Nachfahren ja, Elternprozess nie, Geschwister nur mit Schalter.
 */
class ProcessTreeScopeTest {

    private static final long PARENT = ProcessHandle.current().pid();

    private Process a;
    private Process b;
    private long grandchild;

    /** Prozess, der selbst ein Kind startet (Shell mit nachfolgendem Befehl forkt). */
    private static Process withChild() throws Exception {
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        return new ProcessBuilder(windows ? List.of("cmd", "/c", "ping -n 30 127.0.0.1 >NUL & exit")
                : List.of("sh", "-c", "sleep 30; true")).start();
    }

    @BeforeEach
    void start() throws Exception {
        a = withChild();
        b = withChild();
        for (int i = 0; i < 50 && grandchild == 0; i++) {
            grandchild = a.toHandle().children().findFirst().map(ProcessHandle::pid).orElse(0L);
            Thread.sleep(100);
        }
        assertThat(grandchild).as("Kindprozess von A").isNotZero();
    }

    @AfterEach
    void stop() {
        for (Process p : List.of(a, b)) {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
        }
    }

    private WindowSupport bindA(FakeDesktop desktop, boolean siblings) {
        for (long pid : List.of(PARENT, a.pid(), b.pid(), grandchild)) {
            desktop.windows.add(new NativeWindow(pid, pid, 1L, "Fenster " + pid, new Rectangle(0, 0, 100, 100), false));
        }
        WindowSession session = new WindowSession();
        session.bind(new WindowSession.Binding(a.toHandle(), "A", true));
        return desktop.support(session, new ProcessFilter(null, null, -1), siblings);
    }

    @Test
    void reachesDescendantsButNeverTheParentOrSiblingsByDefault() {
        WindowSupport support = bindA(new FakeDesktop(), false);
        assertThat(support.boundPids()).contains(a.pid(), grandchild).doesNotContain(PARENT, b.pid());
        assertThat(support.boundWindows()).extracting(NativeWindow::pid).containsExactlyInAnyOrder(a.pid(), grandchild);
        assertThatThrownBy(() -> support.resolve(Long.toString(PARENT))).hasMessageContaining("gehört nicht");
        assertThatThrownBy(() -> support.resolve(Long.toString(b.pid()))).hasMessageContaining("gehört nicht");
    }

    @Test
    void siblingsOnlyWhenAllowedAndParentStillNot() {
        WindowSupport support = bindA(new FakeDesktop(), true);
        assertThat(support.boundPids()).contains(a.pid(), grandchild, b.pid()).doesNotContain(PARENT);
        assertThat(support.resolve(Long.toString(b.pid())).pid()).isEqualTo(b.pid());
        assertThatThrownBy(() -> support.resolve(Long.toString(PARENT))).hasMessageContaining("gehört nicht");
    }

    @Test
    void withoutChildrenOnlyTheProcessItself() {
        WindowSession session = new WindowSession();
        session.bind(new WindowSession.Binding(a.toHandle(), "A", false));
        assertThat(session.require().pids(false)).containsExactly(a.pid());
        assertThat(session.require().pids(true)).contains(a.pid(), b.pid()).doesNotContain(grandchild, PARENT);
    }
}
