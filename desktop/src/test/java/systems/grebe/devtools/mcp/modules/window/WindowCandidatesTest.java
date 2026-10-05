package systems.grebe.devtools.mcp.modules.window;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.List;

import org.junit.jupiter.api.Test;

import systems.grebe.devtools.mcp.modules.window.platform.NativeWindow;

import static org.assertj.core.api.Assertions.assertThat;

class WindowCandidatesTest {

    private final FakeDesktop desktop = new FakeDesktop();
    private final long me = ProcessHandle.current().pid();

    private NativeWindow window(boolean minimized) {
        NativeWindow w = new NativeWindow(0x10 + desktop.windows.size(), me, 1L, "Fenster",
                new Rectangle(0, 0, 400, 300), minimized);
        desktop.windows.add(w);
        return w;
    }

    private static WindowCandidates.Candidate of(List<WindowCandidates.Candidate> list, NativeWindow w) {
        return list.stream().filter(c -> c.window().equals(w)).findFirst().orElseThrow();
    }

    @Test
    void listsWindowsWithProcessNameAndPath() {
        window(false);

        List<WindowCandidates.Candidate> list = new WindowCandidates(desktop, -1).list();

        assertThat(list).hasSize(1);
        assertThat(list.getFirst().pid()).isEqualTo(me);
        assertThat(list.getFirst().processName()).isNotBlank();
        assertThat(list.getFirst().executable()).isNotBlank();
    }

    @Test
    void hidesThisApp() {
        window(false);

        assertThat(new WindowCandidates(desktop, me).list()).isEmpty();
    }

    @Test
    void previewIsEmptyWithoutImageOrWhenMinimized() {
        NativeWindow open = window(false);
        NativeWindow minimized = window(true);
        WindowCandidates candidates = new WindowCandidates(desktop, -1);
        List<WindowCandidates.Candidate> list = candidates.list();

        assertThat(candidates.preview(of(list, open))).isEmpty(); // FakeDesktop ohne Bild

        desktop.backgroundImage = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
        assertThat(candidates.preview(of(list, open))).isPresent();
        assertThat(candidates.preview(of(list, minimized))).isEmpty();
    }
}
