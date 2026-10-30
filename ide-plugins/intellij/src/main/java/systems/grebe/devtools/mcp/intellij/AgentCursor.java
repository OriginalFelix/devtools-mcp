package systems.grebe.devtools.mcp.intellij;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.editor.impl.DocumentMarkupModel;
import com.intellij.openapi.editor.markup.CustomHighlighterRenderer;
import com.intellij.openapi.editor.markup.HighlighterLayer;
import com.intellij.openapi.editor.markup.HighlighterTargetArea;
import com.intellij.openapi.editor.markup.RangeHighlighter;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectLocator;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.openapi.wm.IdeFrame;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;

/**
 * Der Cursor der KI im Editor, wie bei Code With Me: farbiger Strich an der Position, Namensschild hinter dem
 * Zeilenende, ein markierter Bereich hinterlegt. Es gibt genau einen; er liegt im Markup des Dokuments und erscheint so
 * in allen Editoren der Datei. Mit „Claude folgen“ öffnet die IDE die Datei (ohne Fokus) und scrollt hin – der Cursor
 * des Nutzers bleibt, wo er ist. Zugriffe auf den Zustand nur im UI-Thread.
 */
@Service
public final class AgentCursor {

    static final JBColor COLOR = new JBColor(new Color(0xC1, 0x5F, 0x3C), new Color(0xD9, 0x77, 0x57));
    private static final JBColor RANGE = new JBColor(new Color(0xC1, 0x5F, 0x3C, 40), new Color(0xD9, 0x77, 0x57, 50));
    /** So lange nach dem letzten Befehl folgt der Cursor den Änderungen an Dateien (danach z.B. kein git checkout mehr). */
    private static final long WATCH_MILLIS = TimeUnit.MINUTES.toMillis(10);

    private RangeHighlighter highlighter;
    private String label = "Claude";
    private volatile long lastCommand;

    public static AgentCursor getInstance() {
        return ApplicationManager.getApplication().getService(AgentCursor.class);
    }

    /**
     * Befehl der App bzw. des MCP-Tools: {@code file} (absolut), {@code line}, {@code column}, optional
     * {@code endLine}/{@code endColumn} und {@code label} – oder {@code clear=true}. Aufruf aus einem Hintergrund-Thread.
     */
    String apply(Map<String, String> p) {
        if ("true".equals(p.get("clear"))) {
            return Edt.call(() -> {
                clear();
                return "Cursor entfernt.";
            });
        }
        String path = p.get("file");
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("'file' fehlt.");
        }
        // Hintergrund-Thread: liest geänderte Datei neu ein, bevor der Cursor sie zeigt
        VirtualFile vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(Path.of(path).toString());
        if (vf == null || vf.isDirectory()) {
            throw new IllegalArgumentException("Datei nicht gefunden: " + path);
        }
        int line = number(p, "line", 1);
        int column = number(p, "column", 1);
        int endLine = number(p, "endLine", line);
        int endColumn = number(p, "endColumn", p.containsKey("endLine") ? Integer.MAX_VALUE : column);
        String l = p.getOrDefault("label", "").isBlank() ? "Claude" : p.get("label").strip();
        lastCommand = System.currentTimeMillis();
        return Edt.call(() -> {
            Document doc = ReadAction.compute(() -> FileDocumentManager.getInstance().getDocument(vf));
            if (doc == null) {
                throw new IllegalArgumentException("Keine Textdatei: " + path);
            }
            label = l;
            int start = offset(doc, line, column);
            int end = Math.max(start, offset(doc, endLine, endColumn));
            place(vf, doc, start, end);
            return "Cursor in " + vf.getName() + " Zeile " + line + (end > start ? " bis " + endLine : "")
                    + (FollowAction.enabled() ? "" : " (der Nutzer folgt gerade nicht)");
        });
    }

    /** Ob der Cursor gerade Änderungen an Dateien folgt. UI-Thread. */
    boolean watching() {
        return highlighter != null && System.currentTimeMillis() - lastCommand < WATCH_MILLIS;
    }

    /** Nach einer Änderung von außen (die KI hat die Datei geschrieben): Cursor auf den geänderten Bereich. UI-Thread. */
    void follow(VirtualFile vf, Document doc, int start, int end) {
        lastCommand = System.currentTimeMillis();
        int len = doc.getTextLength();
        place(vf, doc, Math.min(start, len), Math.min(end, len));
    }

    void clear() {
        if (highlighter != null) {
            Document d = highlighter.getDocument();
            highlighter.dispose();
            highlighter = null;
            repaint(d);
        }
    }

    private void place(VirtualFile vf, Document doc, int start, int end) {
        clear();
        Project project = project(vf);
        TextAttributes attrs = end > start ? new TextAttributes(null, RANGE, null, null, Font.PLAIN) : null;
        highlighter = DocumentMarkupModel.forDocument(doc, project, true).addRangeHighlighter(start, end,
                HighlighterLayer.SELECTION - 1, attrs, HighlighterTargetArea.EXACT_RANGE);
        highlighter.setCustomRenderer(new Renderer(label));
        highlighter.setErrorStripeMarkColor(COLOR);
        highlighter.setErrorStripeTooltip(label);
        if (FollowAction.enabled()) {
            for (FileEditor fe : FileEditorManager.getInstance(project).openFile(vf, false, true)) {
                if (fe instanceof TextEditor te) {
                    Editor e = te.getEditor();
                    e.getScrollingModel().scrollTo(e.offsetToLogicalPosition(end), ScrollType.MAKE_VISIBLE);
                }
            }
        }
        repaint(doc);
    }

    private static Project project(VirtualFile vf) {
        Project p = ReadAction.compute(() -> ProjectLocator.getInstance().guessProjectForFile(vf));
        if (p != null) {
            return p;
        }
        IdeFrame frame = IdeFocusManager.getGlobalInstance().getLastFocusedFrame();
        if (frame != null && frame.getProject() != null) {
            return frame.getProject();
        }
        Project[] open = ProjectManager.getInstance().getOpenProjects();
        if (open.length == 0) {
            throw new IllegalStateException("In der IDE ist kein Projekt geöffnet.");
        }
        return open[0];
    }

    private static void repaint(Document doc) {
        for (Editor e : EditorFactory.getInstance().getEditors(doc)) {
            e.getContentComponent().repaint();
        }
    }

    /** Offset zu Zeile/Spalte (ab 1), auf das Dokument bzw. Zeilenende begrenzt. */
    static int offset(Document doc, int line, int column) {
        if (doc.getLineCount() == 0) {
            return 0;
        }
        int l = Math.clamp(line - 1, 0, doc.getLineCount() - 1);
        int start = doc.getLineStartOffset(l);
        return (int) Math.min(doc.getLineEndOffset(l), start + Math.max(0L, column - 1L));
    }

    private static int number(Map<String, String> p, String key, int fallback) {
        String v = p.get(key);
        try {
            return v == null || v.isBlank() ? fallback : Integer.parseInt(v.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + key + "' ist keine Zahl: " + v);
        }
    }

    /** Strich an der Position (Ende des Bereichs) und Namensschild hinter dem Zeilenende – beides in derselben Zeile. */
    private record Renderer(String label) implements CustomHighlighterRenderer {

        @Override
        public void paint(Editor editor, RangeHighlighter h, Graphics g) {
            if (!h.isValid()) {
                return;
            }
            Document d = editor.getDocument();
            int offset = Math.min(h.getEndOffset(), d.getTextLength());
            Point p = editor.offsetToXY(offset);
            int lh = editor.getLineHeight();
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setColor(COLOR);
                g2.fillRect(p.x, p.y, JBUI.scale(2), lh);
                int lineEnd = d.getLineEndOffset(d.getLineNumber(offset));
                int x = Math.max(editor.offsetToXY(lineEnd).x, p.x) + JBUI.scale(16);
                Font f = editor.getColorsScheme().getFont(EditorFontType.BOLD);
                g2.setFont(f.deriveFont(f.getSize2D() * 0.85f));
                FontMetrics fm = g2.getFontMetrics();
                int w = fm.stringWidth(label) + JBUI.scale(10);
                int th = Math.min(lh, fm.getHeight() + JBUI.scale(2));
                int y = p.y + (lh - th) / 2;
                g2.fillRoundRect(x, y, w, th, JBUI.scale(6), JBUI.scale(6));
                g2.setColor(Color.WHITE);
                g2.drawString(label, x + JBUI.scale(5), y + (th - fm.getHeight()) / 2 + fm.getAscent());
            } finally {
                g2.dispose();
            }
        }
    }
}
