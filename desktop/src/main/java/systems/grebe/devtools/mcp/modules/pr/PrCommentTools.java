package systems.grebe.devtools.mcp.modules.pr;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.modules.pr.spi.GitServer;

import static systems.grebe.devtools.mcp.modules.pr.PrTools.PR;
import static systems.grebe.devtools.mcp.modules.pr.PrTools.PROJECT;
import static systems.grebe.devtools.mcp.modules.pr.PrTools.PROVIDER;
import static systems.grebe.devtools.mcp.modules.pr.PrTools.REPOSITORY;
import static systems.grebe.devtools.mcp.modules.pr.PrTools.blankToNull;

/** Kommentieren und Antworten (Schalter {@code allowComment}). */
public class PrCommentTools {

    private final PrEnvironment env;

    PrCommentTools(PrEnvironment env) {
        this.env = env;
    }

    @Tool(name = "comment", description = "Neuer Kommentar an einem Pull/Merge Request (Markdown): allgemein, mit path "
            + "und line an einer Zeile der neuen Fassung (nur Zeilen im Diff) oder mit path ohne line an der geänderten "
            + "Datei als Ganzes. Für Antworten auf bestehende Threads pr_reply verwenden. Nur auf Anweisung des Nutzers."
            + ShellHints.PR)
    public String comment(
            @ToolParam(description = "Kommentartext") String body,
            @ToolParam(required = false, description = PR + ". Leer = zum aktuellen Branch.") String pr,
            @ToolParam(required = false, description = "Datei für einen Code- oder Datei-Kommentar (Pfad wie in pr_diff)") String path,
            @ToolParam(required = false, description = "Zeile in der neuen Fassung der Datei; leer = Kommentar zur ganzen Datei") Integer line,
            @ToolParam(required = false, description = REPOSITORY) String repository,
            @ToolParam(required = false, description = PROJECT) String project,
            @ToolParam(required = false, description = PROVIDER) String provider) {
        PrEnvironment.Target t = env.target(provider, repository, project, pr);
        String ref = PrTools.refOrCurrent(t, pr);
        env.checkWrite(t, ref, "Kommentieren");
        String p = blankToNull(path);
        if (p == null && line != null) {
            throw new IllegalArgumentException("'line' braucht 'path' (Datei wie in pr_diff).");
        }
        return PrTools.written(t.server().comment(ref, t.project(), new GitServer.NewComment(env.commentBody(body),
                p == null ? null : p.replace('\\', '/'), line)));
    }

    @Tool(name = "reply", description = "Antwortet in einem Kommentar-Thread (ID aus pr_comments), z.B. um zu erklären, "
            + "wie eine Review-Anmerkung umgesetzt wurde (mit Commit) oder um nachzufragen. Nur auf Anweisung des Nutzers."
            + ShellHints.PR)
    public String reply(
            @ToolParam(description = "Thread-ID aus pr_comments (in eckigen Klammern)") String thread,
            @ToolParam(description = "Antworttext (Markdown)") String body,
            @ToolParam(required = false, description = PR + ". Leer = zum aktuellen Branch.") String pr,
            @ToolParam(required = false, description = REPOSITORY) String repository,
            @ToolParam(required = false, description = PROJECT) String project,
            @ToolParam(required = false, description = PROVIDER) String provider) {
        if (thread == null || thread.isBlank()) {
            throw new IllegalArgumentException("Thread-ID fehlt ('thread', aus pr_comments).");
        }
        PrEnvironment.Target t = env.target(provider, repository, project, pr);
        String ref = PrTools.refOrCurrent(t, pr);
        env.checkWrite(t, ref, "Antworten");
        return PrTools.written(t.server().reply(ref, t.project(), stripBrackets(thread), env.commentBody(body)));
    }

    static String stripBrackets(String id) {
        String s = id.trim();
        return s.startsWith("[") && s.endsWith("]") ? s.substring(1, s.length() - 1).trim() : s;
    }
}
