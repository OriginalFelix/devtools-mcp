package systems.grebe.devtools.mcp.modules.window.cursor;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

import javax.imageio.ImageIO;

/**
 * Bild des KI-Zeigers: Pfeil in der Akzentfarbe mit weißem Rand, damit er sich vom Zeiger des Nutzers abhebt. Gezeichnet
 * mit Java2D (ohne Toolkit, also auch headless), vormultipliziertes ARGB.
 */
public final class CursorImage {

    public static final Color ACCENT = new Color(0xE0, 0x4E, 0xC8);

    /** Kantenlänge in logischen Pixeln (bei 100 % Skalierung). */
    public static final int SIZE = 26;

    /** Spitze des Pfeils in logischen Pixeln – dort liegt die Position des Zeigers. */
    private static final double TIP = 2;

    private final BufferedImage image;
    private final Point hotspot;

    private CursorImage(BufferedImage image, Point hotspot) {
        this.image = image;
        this.hotspot = hotspot;
    }

    /** Zeichnet den Zeiger in {@link #ACCENT}; {@code scale} = Bildpixel je logischem Pixel (z.B. 1.5 bei 150 %). */
    public static CursorImage render(double scale) {
        return render(scale, ACCENT);
    }

    /** Zeichnet den Zeiger in {@code color}; {@code scale} = Bildpixel je logischem Pixel (2 bei Retina). */
    public static CursorImage render(double scale, Color color) {
        double s = Math.max(0.5, scale);
        int px = (int) Math.ceil(SIZE * s);
        BufferedImage img = new BufferedImage(px, px, BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.transform(AffineTransform.getScaleInstance(s, s));
            Path2D arrow = arrow();
            g.setColor(new Color(0, 0, 0, 70));
            g.fill(AffineTransform.getTranslateInstance(1.2, 1.6).createTransformedShape(arrow));
            g.setColor(color);
            g.fill(arrow);
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(arrow);
        } finally {
            g.dispose();
        }
        int tip = (int) Math.round(TIP * s);
        return new CursorImage(img, new Point(tip, tip));
    }

    /** Klassischer Pfeil mit Spitze bei {@link #TIP}. */
    private static Path2D arrow() {
        Path2D p = new Path2D.Double();
        p.moveTo(TIP, TIP);
        p.lineTo(TIP, 20.5);
        p.lineTo(7.2, 16.2);
        p.lineTo(10.6, 23.6);
        p.lineTo(14, 22.1);
        p.lineTo(10.7, 14.9);
        p.lineTo(16.8, 14.9);
        p.closePath();
        return p;
    }

    public BufferedImage image() {
        return image;
    }

    public int width() {
        return image.getWidth();
    }

    public int height() {
        return image.getHeight();
    }

    /** Lage der Spitze im Bild (Bildpixel). */
    public Point hotspot() {
        return hotspot;
    }

    /** Pixel zeilenweise von oben, je Wert {@code 0xAARRGGBB} vormultipliziert – im Speicher (little endian) BGRA. */
    public int[] premultipliedArgb() {
        // getRGB liefert nicht vormultipliziert – die Rohdaten des Rasters sind es (TYPE_INT_ARGB_PRE)
        int[] out = new int[width() * height()];
        image.getRaster().getDataElements(0, 0, width(), height(), out);
        return out;
    }

    public byte[] png() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
