package systems.grebe.devtools.mcp.modules.window;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import javax.imageio.ImageIO;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.ToolHints;
import systems.grebe.devtools.mcp.core.ToolImages;
import systems.grebe.devtools.mcp.modules.window.platform.NativeWindow;

/** Fenster finden, Prozess binden und Screenshots – ohne Maus- oder Tastatureingaben. */
@ToolHints(readOnly = true, openWorld = false)
public class WindowReadTools {

    static final String WINDOW = "Fenster-ID aus window_windows (z.B. 0x1A2B). Leer = Vordergrundfenster des gebundenen "
            + "Prozesses, sonst sein größtes Fenster.";

    private final WindowSupport support;

    WindowReadTools(WindowSupport support) {
        this.support = support;
    }

    @Tool(name = "list", description = "Listet die Prozesse mit sichtbaren Fenstern, die gesteuert werden dürfen: PID, "
            + "Prozessname und je Fenster ID, Titel, UI-Thread (nur Windows) und Größe. Danach mit window_bind einen "
            + "Prozess binden." + ShellHints.WINDOW)
    public String list(
            @ToolParam(required = false, description = "Filter: Teil von Prozessname oder Fenstertitel (ohne "
                    + "Groß-/Kleinschreibung)") String filter) {
        String f = filter == null || filter.isBlank() ? null : filter.toLowerCase(Locale.ROOT);
        Map<Long, List<NativeWindow>> byPid = new LinkedHashMap<>();
        for (NativeWindow w : support.windows().windows()) {
            byPid.computeIfAbsent(w.pid(), k -> new ArrayList<>()).add(w);
        }
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        int rejected = 0;
        for (var e : byPid.entrySet()) {
            Optional<ProcessFilter.Info> info = ProcessFilter.info(e.getKey());
            if (info.isEmpty() || support.filter().rejection(e.getKey()).isPresent()) {
                rejected++;
                continue;
            }
            String name = info.get().name();
            if (f != null && !name.toLowerCase(Locale.ROOT).contains(f)
                    && e.getValue().stream().noneMatch(w -> w.title().toLowerCase(Locale.ROOT).contains(f))) {
                continue;
            }
            shown++;
            sb.append(name).append(" (PID ").append(e.getKey()).append(")\n");
            e.getValue().forEach(w -> sb.append("  ").append(describe(w)).append('\n'));
        }
        WindowSession.Binding b = support.session().current();
        StringBuilder head = new StringBuilder(shown + " Prozess(e) mit Fenstern");
        if (rejected > 0) {
            head.append(", ").append(rejected).append(" nicht freigegeben/ausgeschlossen");
        }
        head.append(" – ").append(support.windows().name()).append('\n');
        head.append(b == null ? "Gebunden: nichts\n" : "Gebunden: " + b.describe() + "\n");
        support.windows().warnings().forEach(w -> head.append("Hinweis: ").append(w).append('\n'));
        return (head + "\n" + sb).strip();
    }

    @Tool(name = "bind", description = "Bindet einen Prozess: danach wirken window_screenshot und die Eingabe-Tools "
            + "nur auf dessen Fenster (inkl. Dialoge) und die seiner Kindprozesse – nie auf den Elternprozess, auf "
            + "Geschwisterprozesse nur, wenn in der App erlaubt. Angabe per PID oder Prozessname aus window_list. Eine "
            + "Bindung je Benutzer; sie verfällt, wenn der Prozess endet." + ShellHints.WINDOW)
    public String bind(
            @ToolParam(required = false, description = "PID aus window_list") Long pid,
            @ToolParam(required = false, description = "Statt PID: Prozessname oder regulärer Ausdruck auf Name/"
                    + "Kommandozeile; muss genau einen Prozess mit Fenstern treffen") String process,
            @ToolParam(required = false, description = "true (Standard) = auch Fenster von Kindprozessen (z.B. "
                    + "Electron/Chromium, Starter-Skripte); nicht bei Shell-Prozessen wie dem Explorer")
            Boolean includeChildren) {
        long target = pid != null ? pid : findByName(process);
        ProcessHandle handle = ProcessHandle.of(target).filter(ProcessHandle::isAlive)
                .orElseThrow(() -> new IllegalArgumentException("Prozess " + target + " läuft nicht."));
        support.filter().rejection(target).ifPresent(r -> {
            throw new IllegalArgumentException(r);
        });
        String name = ProcessFilter.info(handle).map(ProcessFilter.Info::name).orElse("?");
        support.session().bind(new WindowSession.Binding(handle, name, includeChildren == null || includeChildren),
                support.settings().allowSiblings());
        return "Gebunden: " + support.session().current().describe() + scope() + "\n\n" + windowList();
    }

    private long findByName(String process) {
        if (process == null || process.isBlank()) {
            throw new IllegalArgumentException("pid oder process angeben – window_list zeigt die Kandidaten.");
        }
        Pattern p;
        try {
            p = Pattern.compile(process, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            p = Pattern.compile(Pattern.quote(process), Pattern.CASE_INSENSITIVE);
        }
        Pattern pattern = p;
        List<ProcessFilter.Info> hits = support.windows().windows().stream().map(NativeWindow::pid).distinct()
                .map(ProcessFilter::info).flatMap(Optional::stream)
                .filter(i -> support.filter().rejection(i.pid()).isEmpty())
                .filter(i -> pattern.matcher(i.name() + " " + i.commandLine()).find()).toList();
        if (hits.size() == 1) {
            return hits.getFirst().pid();
        }
        if (hits.isEmpty()) {
            throw new IllegalArgumentException("Kein freigegebener Prozess mit Fenstern passt zu \"" + process
                    + "\" – window_list zeigt die Kandidaten.");
        }
        StringBuilder sb = new StringBuilder("\"" + process + "\" passt zu mehreren Prozessen – mit pid wählen:\n");
        hits.forEach(i -> sb.append("- ").append(i.name()).append(" (PID ").append(i.pid()).append(")\n"));
        throw new IllegalArgumentException(sb.toString().strip());
    }

    @Tool(name = "windows", description = "Listet die aktuellen Fenster des gebundenen Prozesses (vorderstes zuerst) mit "
            + "ID, Titel, UI-Thread und Größe – z.B. um einen neu geöffneten Dialog zu finden." + ShellHints.WINDOW)
    public String windows() {
        return "Gebunden: " + support.session().require().describe() + scope() + "\n\n" + windowList();
    }

    private String scope() {
        return support.settings().allowSiblings() ? " + Geschwisterprozesse" : "";
    }

    private String windowList() {
        List<NativeWindow> own = support.boundWindows();
        if (own.isEmpty()) {
            return "Keine sichtbaren Fenster.";
        }
        StringBuilder sb = new StringBuilder();
        own.forEach(w -> sb.append(support.isForeground(w) ? "* " : "- ").append(describe(w)).append('\n'));
        return sb.append("(* = im Vordergrund)").toString();
    }

    @Tool(name = "screenshot", description = "Bild eines Fensters des gebundenen Prozesses (PNG). Mit eigenem Zeiger "
            + "bleibt das Fenster im Hintergrund, sonst wird es dafür nach vorn geholt. Koordinaten für window_click, "
            + "window_scroll und window_drag beziehen sich auf die Pixel dieses Bildes. Nach jeder Eingabe erneut "
            + "aufrufen, um das Ergebnis zu prüfen." + ShellHints.WINDOW)
    public String screenshot(@ToolParam(required = false, description = WINDOW) String window) {
        support.windows().requireCapturePermission();
        return support.exclusive(() -> {
            NativeWindow w = support.resolve(window);
            // eigener Zeiger: das Fenster bleibt hinten, damit Eingaben des Nutzers nie dort landen
            boolean background = support.device().independentPointer();
            boolean front = true;
            BufferedImage shot = null;
            String note = null;
            if (background) {
                if (w.minimized()) {
                    throw new IllegalStateException("Fenster " + w.hexId() + " („" + w.title() + "“) ist minimiert – "
                            + "den Nutzer bitten, es wiederherzustellen.");
                }
                shot = support.windows().captureInBackground(w).orElse(null);
                if (shot == null) {
                    note = "Hinweis: Bild vom Bildschirm – liegen andere Fenster darüber, sind sie mit im Bild.";
                }
            } else {
                front = support.bringToFront(w);
            }
            w = support.refresh(w);
            Rectangle b = w.bounds();
            if (shot == null) {
                shot = support.device().capture(b);
            }
            double factor = Math.min(1.0, support.settings().maxImageSize()
                    / (double) Math.max(shot.getWidth(), shot.getHeight()));
            BufferedImage image = factor < 1.0 ? scale(shot, factor) : shot;
            // Bildpixel je Bildschirmpunkt – das Bild kann physische Pixel haben (PrintWindow bei HiDPI)
            support.session().scale(w.id(), image.getWidth() / (double) b.width);
            byte[] png = png(image);
            ToolImages.attach("image/png", png);
            StringBuilder sb = new StringBuilder(describe(w)).append('\n')
                    .append("Bild ").append(image.getWidth()).append('×').append(image.getHeight()).append(" px");
            if (factor < 1.0) {
                sb.append(String.format(Locale.ROOT, " (verkleinert, Faktor %.3f)", factor));
            }
            // „Kontext sparen“ meldet gleiche Ergebnisse lesender Tools als „unverändert“ – mit der Prüfsumme ist der
            // Text nur dann gleich, wenn es auch das Bild ist
            sb.append(", Prüfsumme ").append(checksum(png));
            sb.append(". Koordinaten für Eingaben in Pixeln dieses Bildes, Ursprung oben links.");
            if (!front) {
                sb.append("\nAchtung: Das Fenster ließ sich nicht in den Vordergrund holen – das Bild kann verdeckte "
                        + "Teile anderer Fenster zeigen.");
            }
            if (note != null) {
                sb.append('\n').append(note);
            }
            return sb.toString();
        });
    }

    @Tool(name = "unbind", description = "Hebt die Bindung an den Prozess auf." + ShellHints.WINDOW)
    public String unbind() {
        WindowSession.Binding b = support.session().current();
        support.session().unbind();
        support.release();
        return b == null ? "Es war kein Prozess gebunden." : "Bindung an " + b.describe() + " aufgehoben.";
    }

    static String describe(NativeWindow w) {
        Rectangle b = w.bounds();
        StringBuilder sb = new StringBuilder(w.hexId()).append("  „").append(w.title().isBlank() ? "(ohne Titel)" : w.title())
                .append("“  ").append(b.width).append('×').append(b.height).append(" @ ").append(b.x).append(',').append(b.y);
        if (w.threadId() != null) {
            sb.append("  Thread ").append(w.threadId());
        }
        if (w.minimized()) {
            sb.append("  [minimiert]");
        }
        return sb.toString();
    }

    private static BufferedImage scale(BufferedImage source, double factor) {
        int width = Math.max(1, (int) Math.round(source.getWidth() * factor));
        int height = Math.max(1, (int) Math.round(source.getHeight() * factor));
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(source, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static String checksum(byte[] data) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(data);
        return String.format(Locale.ROOT, "%08x", crc.getValue());
    }

    private static byte[] png(BufferedImage image) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
