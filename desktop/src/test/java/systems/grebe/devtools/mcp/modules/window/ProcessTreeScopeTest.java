package systems.grebe.devtools.mcp.modules.window;

import java.awt.Rectangle;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import systems.grebe.devtools.mcp.core.ToolSession;
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
        session.bind(new WindowSession.Binding(a.toHandle(), "A", true), false);
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
    void withoutWindowIdOnlyWindowsOfTheBoundTreeEvenIfASiblingIsInFront() {
        FakeDesktop desktop = new FakeDesktop();
        WindowSupport support = bindA(desktop, true);
        desktop.foreground = b.pid();

        assertThat(support.resolve(null).pid()).isIn(a.pid(), grandchild);
        assertThat(support.resolve(Long.toString(b.pid())).pid()).isEqualTo(b.pid()); // mit ID weiter erreichbar
    }

    @Test
    void siblingOfAnotherAisProcessIsNeitherBindableNorReachable() {
        WindowSessions sessions = new WindowSessions(new AiColors(), java.time.Duration.ofMinutes(30), () -> 0);
        ToolSession claude = new ToolSession("s1", "Claude");
        ToolSession codex = new ToolSession("s2", "Codex");
        WindowSession first = ToolSession.callIn(claude, sessions::current);
        WindowSession second = ToolSession.callIn(codex, sessions::current);
        first.bind(new WindowSession.Binding(a.toHandle(), "A", true), false);

        assertThatThrownBy(() -> second.bind(new WindowSession.Binding(b.toHandle(), "B", true), true))
                .hasMessage("B wird gerade von Claude gesteuert (Geschwisterprozess).");

        second.bind(new WindowSession.Binding(b.toHandle(), "B", true), false); // ohne Geschwister kein Konflikt
        FakeDesktop desktop = new FakeDesktop();
        WindowSupport support = desktop.support(second, new ProcessFilter(null, null, -1), true);
        assertThat(support.boundPids()).contains(b.pid()).doesNotContain(a.pid(), grandchild); // Schalter später an
    }

    @Test
    void noSiblingsUnderASystemParent() {
        assertThat(WindowSession.Binding.systemParent(new FakeProcess(1, null))).isTrue();
        assertThat(WindowSession.Binding.systemParent(new FakeProcess(300, "/sbin/launchd"))).isTrue();
        assertThat(WindowSession.Binding.systemParent(new FakeProcess(300, "Explorer.EXE"))).isTrue();
        assertThat(WindowSession.Binding.systemParent(new FakeProcess(300, "/usr/lib/systemd/systemd"))).isTrue();
        assertThat(WindowSession.Binding.systemParent(new FakeProcess(300, null))).as("unbekannt").isTrue();
        assertThat(WindowSession.Binding.systemParent(ProcessHandle.current())).isFalse();

        FakeProcess launchd = new FakeProcess(1, null);
        FakeProcess calculator = new FakeProcess(500, "/System/Applications/Calculator.app", launchd);
        new FakeProcess(501, "/Applications/IntelliJ IDEA.app", launchd);
        assertThat(new WindowSession.Binding(calculator, "Calculator", true).pids(true)).containsExactly(500L);
    }

    /** Prozess mit festem Programm und Elternprozess, ohne echten Prozess dahinter. */
    private static final class FakeProcess implements ProcessHandle {
        private final long pid;
        private final String command;
        private final FakeProcess parent;
        private final List<ProcessHandle> children = new java.util.ArrayList<>();

        FakeProcess(long pid, String command) {
            this(pid, command, null);
        }

        FakeProcess(long pid, String command, FakeProcess parent) {
            this.pid = pid;
            this.command = command;
            this.parent = parent;
            if (parent != null) {
                parent.children.add(this);
            }
        }

        @Override
        public long pid() {
            return pid;
        }

        @Override
        public java.util.Optional<ProcessHandle> parent() {
            return java.util.Optional.ofNullable(parent);
        }

        @Override
        public java.util.stream.Stream<ProcessHandle> children() {
            return children.stream();
        }

        @Override
        public java.util.stream.Stream<ProcessHandle> descendants() {
            return children.stream().flatMap(c -> java.util.stream.Stream.concat(java.util.stream.Stream.of(c),
                    c.descendants()));
        }

        @Override
        public Info info() {
            return new Info() {
                @Override
                public java.util.Optional<String> command() {
                    return java.util.Optional.ofNullable(command);
                }

                @Override
                public java.util.Optional<String> commandLine() {
                    return command();
                }

                @Override
                public java.util.Optional<String[]> arguments() {
                    return java.util.Optional.empty();
                }

                @Override
                public java.util.Optional<java.time.Instant> startInstant() {
                    return java.util.Optional.empty();
                }

                @Override
                public java.util.Optional<java.time.Duration> totalCpuDuration() {
                    return java.util.Optional.empty();
                }

                @Override
                public java.util.Optional<String> user() {
                    return java.util.Optional.empty();
                }
            };
        }

        @Override
        public java.util.concurrent.CompletableFuture<ProcessHandle> onExit() {
            return new java.util.concurrent.CompletableFuture<>();
        }

        @Override
        public boolean supportsNormalTermination() {
            return false;
        }

        @Override
        public boolean destroy() {
            return false;
        }

        @Override
        public boolean destroyForcibly() {
            return false;
        }

        @Override
        public boolean isAlive() {
            return true;
        }

        @Override
        public int compareTo(ProcessHandle other) {
            return Long.compare(pid, other.pid());
        }
    }

    @Test
    void withoutChildrenOnlyTheProcessItself() {
        WindowSession session = new WindowSession();
        session.bind(new WindowSession.Binding(a.toHandle(), "A", false), false);
        assertThat(session.require().pids(false)).containsExactly(a.pid());
        assertThat(session.require().pids(true)).contains(a.pid(), b.pid()).doesNotContain(grandchild, PARENT);
    }
}
