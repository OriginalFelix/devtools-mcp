package systems.grebe.devtools.mcp.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

import javafx.scene.image.Image;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;

/** Programmatisch gezeichnetes App-Icon (keine Binärressourcen nötig). */
public final class AppIcons {

    private AppIcons() {
    }

    public static BufferedImage awtIcon(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int arc = Math.max(4, size / 4);
        g.setColor(new Color(0x2B, 0x6C, 0xB0));
        g.fillRoundRect(0, 0, size, size, arc, arc);
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(Math.max(1.5f, size / 12f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        // stilisierte spitze Klammern "< >"
        int m = size / 4;
        int c = size / 2;
        int d = size / 12;
        g.drawPolyline(new int[]{c - d, m, c - d}, new int[]{m, c, size - m}, 3);
        g.drawPolyline(new int[]{c + d, size - m, c + d}, new int[]{m, c, size - m}, 3);
        g.dispose();
        return img;
    }

    public static Image fxIcon(int size) {
        BufferedImage awt = awtIcon(size);
        WritableImage out = new WritableImage(size, size);
        PixelWriter pw = out.getPixelWriter();
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                pw.setArgb(x, y, awt.getRGB(x, y));
            }
        }
        return out;
    }
}
