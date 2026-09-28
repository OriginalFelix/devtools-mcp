package systems.grebe.devtools.mcp.modules.skills;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.ToolCallListener;

/** Selbstverbesserung: Review-Anleitung mit Session-Kontext (nur registriert, wenn Schreiben erlaubt ist). */
public class SkillReviewTools {

    private final SkillService service;
    private final SkillReview review;
    private final SkillReviewTracker tracker;

    SkillReviewTools(SkillService service, SkillReview review, SkillReviewTracker tracker) {
        this.service = service;
        this.review = review;
        this.tracker = tracker;
    }

    @Tool(name = "review", description = "Skill-Review zur Selbstverbesserung: liefert eine Checkliste (Signale, "
            + "Reihenfolge patchen vor neu anlegen, was nicht festzuhalten ist), die in dieser Session geladenen und "
            + "geänderten Skills und die vorhandene Bibliothek. Aufrufen, wenn eine Aufgabe mit mehreren Schritten "
            + "abgeschlossen ist, der Nutzer korrigiert hat oder der Server daran erinnert – danach die Checkliste "
            + "abarbeiten." + ShellHints.SKILLS)
    public String review(
            @ToolParam(required = false, description = "Optional: worum es im Review gehen soll, z.B. 'Korrektur zur "
                    + "Formatierung' oder 'neuer Workaround für Gradle-Toolchains'") String focus,
            ToolContext toolContext) {
        SkillReviewTracker.SessionState session = tracker.state(ToolCallListener.sessionId(toolContext));
        return review.render(session, service.list(null, null), focus);
    }
}
