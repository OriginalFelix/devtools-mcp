package systems.grebe.devtools.mcp.modules.window;

import java.awt.Color;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiColorsTest {

    private final AiColors colors = new AiColors();

    @Test
    void eachNewAiGetsTheMiddleOfTheLargestGap() {
        assertThat(colors.assign("a")).isEqualTo(0);
        assertThat(colors.assign("b")).isEqualTo(180);
        assertThat(colors.assign("c")).isEqualTo(90);
        assertThat(colors.assign("d")).isEqualTo(270);
        assertThat(colors.assign("e")).isEqualTo(45);
    }

    @Test
    void keepsTheHueOfAnAi() {
        colors.assign("a");
        double b = colors.assign("b");

        assertThat(colors.assign("b")).isEqualTo(b);
    }

    @Test
    void reusesReleasedGaps() {
        colors.assign("a");
        colors.assign("b");
        colors.assign("c");

        colors.release("c"); // 90° wird frei

        assertThat(colors.assign("neu")).isEqualTo(90);
    }

    @Test
    void usesPureColorsAndReadableText() {
        assertThat(AiColors.color(0)).isEqualTo(new Color(255, 0, 0));
        assertThat(AiColors.color(180)).isEqualTo(new Color(0, 255, 255));
        assertThat(AiColors.textColor(AiColors.color(60))).isEqualTo(Color.BLACK); // Gelb
        assertThat(AiColors.textColor(AiColors.color(240))).isEqualTo(Color.WHITE); // Blau
    }
}
