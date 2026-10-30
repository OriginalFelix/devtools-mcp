package systems.grebe.devtools.mcp.intellij;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.openapi.wm.IdeFrame;

/** Was der Nutzer vor sich hat: je offenem Projekt aktive Datei, Cursor, Markierung und offene Dateien. */
final class EditorState {

    /** Längere Markierungen werden gekürzt. */
    static final int MAX_SELECTION = 100_000;

    private EditorState() {
    }

    /** {@code {ide, projects: [{name, path, active, file, line, column, selection, openFiles}]}}, Zeilen/Spalten ab 1. */
    static JsonObject json() {
        return Edt.call(() -> ReadAction.compute(EditorState::build));
    }

    private static JsonObject build() {
        IdeFrame frame = IdeFocusManager.getGlobalInstance().getLastFocusedFrame();
        Project active = frame == null ? null : frame.getProject();
        JsonArray projects = new JsonArray();
        for (Project p : ProjectManager.getInstance().getOpenProjects()) {
            if (p.isDisposed() || p.getBasePath() == null) {
                continue;
            }
            JsonObject o = new JsonObject();
            o.addProperty("name", p.getName());
            o.addProperty("path", p.getBasePath());
            o.addProperty("active", p == active);
            FileEditorManager fem = FileEditorManager.getInstance(p);
            Editor ed = fem.getSelectedTextEditor();
            VirtualFile vf = ed == null ? null : FileDocumentManager.getInstance().getFile(ed.getDocument());
            if (vf != null) {
                Document d = ed.getDocument();
                int caret = ed.getCaretModel().getOffset();
                o.addProperty("file", vf.getPath());
                o.addProperty("line", d.getLineNumber(caret) + 1);
                o.addProperty("column", column(d, caret));
                SelectionModel sm = ed.getSelectionModel();
                String text = sm.getSelectedText();
                if (sm.hasSelection() && text != null) {
                    JsonObject s = new JsonObject();
                    s.addProperty("startLine", d.getLineNumber(sm.getSelectionStart()) + 1);
                    s.addProperty("startColumn", column(d, sm.getSelectionStart()));
                    s.addProperty("endLine", d.getLineNumber(sm.getSelectionEnd()) + 1);
                    s.addProperty("endColumn", column(d, sm.getSelectionEnd()));
                    s.addProperty("text", text.length() > MAX_SELECTION
                            ? text.substring(0, MAX_SELECTION) + "\n… (gekürzt)" : text);
                    o.add("selection", s);
                }
            }
            JsonArray open = new JsonArray();
            for (VirtualFile f : fem.getOpenFiles()) {
                open.add(f.getPath());
            }
            o.add("openFiles", open);
            projects.add(o);
        }
        JsonObject out = new JsonObject();
        out.addProperty("ide", ApplicationInfo.getInstance().getFullApplicationName());
        out.add("projects", projects);
        return out;
    }

    private static int column(Document d, int offset) {
        return offset - d.getLineStartOffset(d.getLineNumber(offset)) + 1;
    }

    /** Lesbare Fassung für das MCP-Tool {@code editor}. */
    static String text(JsonObject state) {
        StringBuilder sb = new StringBuilder();
        for (JsonElement e : state.getAsJsonArray("projects")) {
            JsonObject p = e.getAsJsonObject();
            String file = p.has("file") ? p.get("file").getAsString() : null;
            sb.append("Projekt ").append(p.get("name").getAsString()).append(" (").append(p.get("path").getAsString())
                    .append(')').append(p.get("active").getAsBoolean() ? " – zuletzt aktiv" : "").append('\n');
            if (file == null) {
                sb.append("Keine Datei im Editor.\n");
            } else {
                sb.append("Datei: ").append(file).append(" – Cursor Zeile ").append(p.get("line").getAsInt())
                        .append(", Spalte ").append(p.get("column").getAsInt()).append('\n');
                if (p.has("selection")) {
                    JsonObject s = p.getAsJsonObject("selection");
                    sb.append("Markiert: Zeile ").append(s.get("startLine").getAsInt()).append(", Spalte ")
                            .append(s.get("startColumn").getAsInt()).append(" bis Zeile ")
                            .append(s.get("endLine").getAsInt()).append(", Spalte ")
                            .append(s.get("endColumn").getAsInt()).append(":\n```\n")
                            .append(s.get("text").getAsString().stripTrailing()).append("\n```\n");
                }
            }
            StringBuilder open = new StringBuilder();
            for (JsonElement f : p.getAsJsonArray("openFiles")) {
                if (!f.getAsString().equals(file)) {
                    open.append(open.isEmpty() ? "" : ", ").append(f.getAsString());
                }
            }
            if (!open.isEmpty()) {
                sb.append("Weitere offene Dateien: ").append(open).append('\n');
            }
            sb.append('\n');
        }
        String out = sb.toString().strip();
        return out.isEmpty() ? "In der IDE ist kein Projekt geöffnet." : out;
    }
}
