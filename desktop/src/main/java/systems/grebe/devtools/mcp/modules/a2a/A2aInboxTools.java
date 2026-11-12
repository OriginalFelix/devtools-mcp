package systems.grebe.devtools.mcp.modules.a2a;

import java.util.List;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ToolHints;

/** Server-Tools: Anfragen fremder Agenten an diese Claude-Sitzung beantworten. */
class A2aInboxTools {

    private final A2aServer server;

    A2aInboxTools(A2aServer server) {
        this.server = server;
    }

    @Tool(name = "inbox", description = "Zeigt die offenen Anfragen fremder A2A-Agenten an diese Sitzung (Tasks).")
    @ToolHints(readOnly = true, openWorld = false)
    public String inbox() {
        List<String> open = server.inbox();
        return open.isEmpty() ? "Keine offenen A2A-Anfragen." : String.join("\n\n", open);
    }

    @Tool(name = "reply", description = "Beantwortet eine A2A-Anfrage (Task) eines fremden Agenten. state: completed "
            + "(Standard, schließt ab), input-required (Rückfrage), working (Zwischenstand), rejected (ablehnen), "
            + "failed. Nur weitergeben, was der Nutzer teilen würde.")
    public String reply(@ToolParam(description = "Task-ID aus der Anfrage") String task,
                        @ToolParam(description = "Antworttext") String text,
                        @ToolParam(required = false, description = "completed, input-required, working, rejected oder "
                                + "failed") String state) {
        return server.reply(task, text, state);
    }
}
