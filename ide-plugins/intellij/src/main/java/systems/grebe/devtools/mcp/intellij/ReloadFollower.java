package systems.grebe.devtools.mcp.intellij;

import java.util.HashMap;
import java.util.Map;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManagerListener;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

/**
 * Live mitschauen: Lädt die IDE eine Datei neu, weil sie draußen geändert wurde (die KI schreibt Dateien direkt),
 * springt der Cursor der KI auf den geänderten Bereich – solange er gerade aktiv ist ({@link AgentCursor#watching()}).
 *
 * <p>shortcut: folgt nur Dateien, deren Dokument geladen ist (offen bzw. zuvor per Cursor gezeigt); für ungeöffnete
 * Dateien fehlt die alte Fassung zum Vergleich – bei Bedarf über die Local History nachrüsten.
 */
public final class ReloadFollower implements FileDocumentManagerListener {

    private final Map<Document, CharSequence> before = new HashMap<>();

    @Override
    public void beforeFileContentReload(@NotNull VirtualFile file, @NotNull Document document) {
        if (AgentCursor.getInstance().watching()) {
            before.put(document, document.getImmutableCharSequence());
        }
    }

    @Override
    public void fileContentReloaded(@NotNull VirtualFile file, @NotNull Document document) {
        CharSequence old = before.remove(document);
        int[] r = old == null ? null : Changes.range(old, document.getImmutableCharSequence());
        if (r != null) {
            // nicht in der laufenden Schreibaktion Editoren öffnen
            ApplicationManager.getApplication().invokeLater(() -> AgentCursor.getInstance().follow(file, document, r[0], r[1]));
        }
    }
}
