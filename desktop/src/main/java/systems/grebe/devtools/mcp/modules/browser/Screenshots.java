package systems.grebe.devtools.mcp.modules.browser;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chromium.HasCdp;
import org.openqa.selenium.firefox.HasFullPageScreenshot;

/** Screenshots aufnehmen und für das LLM verkleinern. */
final class Screenshots {

    /** PNG bis zu dieser Größe, darüber JPEG. */
    private static final int MAX_PNG_BYTES = 1_500_000;

    /**
     * Ein aufbereitetes Bild.
     *
     * @param sourceWidth Breite der Aufnahme vor dem Verkleinern (Gerätepixel)
     * @param note        Hinweis für das Ergebnis (z.B. „ganze Seite nicht unterstützt“) oder leer
     */
    record Shot(byte[] data, String mimeType, int width, int height, int sourceWidth, String note) {
    }

    private Screenshots() {
    }

    /** Sichtbarer Ausschnitt, ein Element oder – wenn der Browser es kann – die ganze Seite. */
    static Shot capture(WebDriver driver, WebElement element, boolean fullPage, int maxEdge) {
        String note = "";
        byte[] raw;
        if (element != null) {
            raw = element.getScreenshotAs(OutputType.BYTES);
        } else if (fullPage && driver instanceof HasFullPageScreenshot ff) {
            raw = ff.getFullPageScreenshotAs(OutputType.BYTES);
        } else if (fullPage && driver instanceof HasCdp cdp) {
            raw = chromiumFullPage(driver, cdp);
        } else {
            raw = ((TakesScreenshot) driver).getScreenshotAs(OutputType.BYTES);
            if (fullPage) {
                note = "ganze Seite kann dieser Browser nicht aufnehmen – sichtbarer Ausschnitt";
            }
        }
        return scale(raw, maxEdge, note);
    }

    private static byte[] chromiumFullPage(WebDriver driver, HasCdp cdp) {
        Object size = ((JavascriptExecutor) driver).executeScript("const d = document.documentElement, b = document.body;"
                + "return [Math.max(d.scrollWidth, b ? b.scrollWidth : 0), Math.max(d.scrollHeight, b ? b.scrollHeight : 0)];");
        java.util.List<?> wh = (java.util.List<?>) size;
        long width = ((Number) wh.get(0)).longValue();
        // Sehr lange Seiten begrenzen: ab ~16k Pixeln schneidet Chrome ohnehin ab
        long height = Math.min(((Number) wh.get(1)).longValue(), 16_000);
        Map<String, Object> result = cdp.executeCdpCommand("Page.captureScreenshot", Map.of(
                "format", "png", "captureBeyondViewport", true,
                "clip", Map.of("x", 0, "y", 0, "width", width, "height", height, "scale", 1)));
        return Base64.getDecoder().decode((String) result.get("data"));
    }

    /** Verkleinert auf höchstens {@code maxEdge} Pixel an der längeren Kante. */
    static Shot scale(byte[] raw, int maxEdge, String note) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(raw));
            if (img == null) {
                throw new IllegalStateException("Screenshot ist kein lesbares Bild.");
            }
            int sourceWidth = img.getWidth();
            double factor = Math.min(1.0, (double) maxEdge / Math.max(img.getWidth(), img.getHeight()));
            if (factor >= 1.0) {
                return encode(img, sourceWidth, note, raw);
            }
            // In Halbierungsschritten verkleinern: einmal bilinear auf ein Zehntel wirkt verwaschen und flimmert
            BufferedImage current = img;
            int targetW = Math.max(1, (int) Math.round(img.getWidth() * factor));
            int targetH = Math.max(1, (int) Math.round(img.getHeight() * factor));
            while (current.getWidth() / 2 >= targetW && current.getHeight() / 2 >= targetH) {
                current = resize(current, current.getWidth() / 2, current.getHeight() / 2);
            }
            if (current.getWidth() != targetW || current.getHeight() != targetH) {
                current = resize(current, targetW, targetH);
            }
            return encode(current, sourceWidth, note, null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static BufferedImage resize(BufferedImage src, int w, int h) {
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return dst;
    }

    private static Shot encode(BufferedImage img, int sourceWidth, String note, byte[] originalPng) throws IOException {
        byte[] png = originalPng;
        if (png == null) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            png = out.toByteArray();
        }
        if (png.length <= MAX_PNG_BYTES) {
            return new Shot(png, "image/png", img.getWidth(), img.getHeight(), sourceWidth, note);
        }
        return new Shot(jpeg(img), "image/jpeg", img.getWidth(), img.getHeight(), sourceWidth, note);
    }

    private static byte[] jpeg(BufferedImage img) throws IOException {
        BufferedImage rgb = img;
        if (img.getType() != BufferedImage.TYPE_INT_RGB) {
            rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            try {
                g.drawImage(img, 0, 0, null);
            } finally {
                g.dispose();
            }
        }
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        ImageWriter writer = writers.next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.85f);
            writer.write(null, new IIOImage(rgb, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}
