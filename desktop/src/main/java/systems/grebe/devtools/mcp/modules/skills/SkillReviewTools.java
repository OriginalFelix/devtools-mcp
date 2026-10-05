package systems.grebe.devtools.mcp.modules.skills;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.ToolCallListener;

/** Selbstverbesserung: Review-Anleitung mit Session-Kontext (nur registriert, wenn Schreiben erlaubt ist). */
public class SkillReviewTools {

    private final SkillBackend service;
    private final SkillReview review;
    private final SkillReviewTracker tracker;

    SkillReviewTools(SkillBackend service, SkillReview review, SkillReviewTracker tracker) {
        this.service = service;
        this.review = review;
        this.tracker = tracker;
    }

    @Tool(name = "review", description = "Nach mehrstufiger Aufgabe: prüfen, was als Skill bleibt. Liefert "
            + "Checkliste, die in dieser Session geladenen/geänderten Skills und die Bibliothek – danach abarbeiten."
            + ShellHints.SKILLS)
    public String review(
            @ToolParam(required = false, description = "Schwerpunkt, z.B. 'Korrektur zur Formatierung'") String focus,
            ToolContext toolContext) {
        SkillReviewTracker.SessionState session = tracker.state(ToolCallListener.sessionId(toolContext));
        return review.render(session, service.list(null, null), focus);
    }
}
