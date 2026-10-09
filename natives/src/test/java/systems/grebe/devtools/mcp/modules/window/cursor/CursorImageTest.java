package systems.grebe.devtools.mcp.modules.window.cursor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CursorImageTest {

    @Test
    void scalesWithDpiAndKeepsTipAtHotspot() {
        CursorImage normal = CursorImage.render(1);
        CursorImage retina = CursorImage.render(2);

        assertThat(normal.width()).isEqualTo(CursorImage.SIZE);
        assertThat(retina.width()).isEqualTo(2 * CursorImage.SIZE);
        assertThat(retina.hotspot()).isEqualTo(new java.awt.Point(2 * normal.hotspot().x, 2 * normal.hotspot().y));
        // an der Spitze ist der Pfeil gezeichnet, in der Ecke gegenüber ist alles durchsichtig
        assertThat(alpha(retina, retina.hotspot().x + 1, retina.hotspot().y + 3)).isPositive();
        assertThat(alpha(retina, retina.width() - 1, 0)).isZero();
    }

    @Test
    void pixelsArePremultiplied() {
        CursorImage img = CursorImage.render(1.5);
        int[] px = img.premultipliedArgb();

        assertThat(px).hasSize(img.width() * img.height());
        for (int p : px) {
            int a = p >>> 24;
            assertThat((p >> 16) & 0xFF).isLessThanOrEqualTo(a);
            assertThat((p >> 8) & 0xFF).isLessThanOrEqualTo(a);
            assertThat(p & 0xFF).isLessThanOrEqualTo(a);
        }
    }

    @Test
    void drawsInTheGivenColor() {
        java.awt.Color cyan = new java.awt.Color(0, 200, 255);
        CursorImage img = CursorImage.render(2, cyan);
        // innerhalb des Pfeils, weg vom weißen Rand
        int x = img.hotspot().x + 6;
        int y = img.hotspot().y + 18;
        int p = img.premultipliedArgb()[y * img.width() + x];
        int a = p >>> 24;

        assertThat(a).isEqualTo(255);
        assertThat((p >> 16) & 0xFF).isCloseTo(cyan.getRed(), org.assertj.core.data.Offset.offset(10));
        assertThat((p >> 8) & 0xFF).isCloseTo(cyan.getGreen(), org.assertj.core.data.Offset.offset(10));
        assertThat(p & 0xFF).isCloseTo(cyan.getBlue(), org.assertj.core.data.Offset.offset(10));
    }

    @Test
    void encodesPng() {
        byte[] png = CursorImage.render(2).png();

        assertThat(png).startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
    }

    private static int alpha(CursorImage img, int x, int y) {
        return img.premultipliedArgb()[y * img.width() + x] >>> 24;
    }
}
