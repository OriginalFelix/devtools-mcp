package systems.grebe.devtools.mcp.modules.skills;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.core.ToolRegistry;

/**
 * MCP-Prompt {@code skills_review}: derselbe Review wie das Tool, aber vom Nutzer auslösbar (in Claude Code z.B. als
 * Slash-Befehl, in Hermes über {@code get_prompt}). Wird nur angeboten, solange das Tool {@code skills_review} aktiv
 * ist, und folgt Schaltern in der UI zur Laufzeit.
 */
@Component
public class SkillReviewPrompt {

    static final String NAME = "skills_review";
    private static final Logger LOG = LoggerFactory.getLogger(SkillReviewPrompt.class);

    private final McpSyncServer server;
    private final ToolRegistry registry;
    private final SkillService service;
    private final SkillReview review;
    private final SkillReviewTracker tracker;
    private boolean registered;

    public SkillReviewPrompt(McpSyncServer server, ToolRegistry registry, SkillService service, SkillReview review,
                             SkillReviewTracker tracker) {
        this.server = server;
        this.registry = registry;
        this.service = service;
        this.review = review;
        this.tracker = tracker;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        registry.addChangeListener(this::sync);
        sync();
    }

    synchronized void sync() {
        boolean wanted = registry.isToolActive(SkillsModule.ID, "skills_review");
        if (wanted == registered) {
            return;
        }
        try {
            if (wanted) {
                server.addPrompt(specification());
            } else {
                server.removePrompt(NAME);
            }
            server.notifyPromptsListChanged();
            registered = wanted;
        } catch (RuntimeException e) {
            LOG.warn("Prompt {} konnte nicht {} werden", NAME, wanted ? "registriert" : "entfernt", e);
        }
    }

    McpServerFeatures.SyncPromptSpecification specification() {
        McpSchema.Prompt prompt = McpSchema.Prompt.builder(NAME)
                .title("Skill-Review")
                .description("Unterhaltung durchgehen und Gelerntes als Skill festhalten oder bestehende Skills "
                        + "verbessern (Selbstverbesserung).")
                .arguments(List.of(McpSchema.PromptArgument.builder("focus")
                        .description("Optional: Schwerpunkt, z.B. eine Korrektur oder ein neuer Workaround")
                        .required(false).build()))
                .build();
        return new McpServerFeatures.SyncPromptSpecification(prompt, (exchange, request) -> {
            Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();
            Object focus = args.get("focus");
            String text = review.render(tracker.state(exchange.sessionId()), service.list(null, null),
                    focus == null ? null : focus.toString())
                    + "\n\nArbeite die Checkliste jetzt mit den skills_*-Tools ab.";
            return new McpSchema.GetPromptResult("Skill-Review",
                    List.of(McpSchema.PromptMessage.builder(McpSchema.Role.USER, new McpSchema.TextContent(text)).build()));
        });
    }
}
