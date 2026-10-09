package systems.grebe.devtools.mcp.modules.window;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Kurzfassung für kompakte Instructions („Kontext sparen“): was die KI ohne context_guide wissen muss. */
class WindowModuleInstructionsTest {

    @Test
    void briefInstructionsNameTheWorkflowAndForbidTheShell() {
        String brief = new WindowModule().briefInstructions();

        assertThat(brief).isNotBlank().doesNotContain("\n")
                .contains("window_list", "window_bind", "window_screenshot", "Shell");
        assertThat(brief.length()).isLessThanOrEqualTo(300); // eine Zeile je Modul
    }
}
