package systems.grebe.devtools.mcp.modules.skills;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.core.McpRuntime;
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

    private final ToolRegistry registry;
    private final SkillBackend service;
    private final SkillReview review;
    private final SkillReviewTracker tracker;
    /** Runtimes, an deren Server der Prompt gerade registriert ist. */
    private final Set<McpRuntime> registered = Collections.newSetFromMap(new WeakHashMap<>());

    public SkillReviewPrompt(ToolRegistry registry, SkillBackend service, SkillReview review,
                             SkillReviewTracker tracker) {
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

    /** Gleicht die Runtime ab (Tool {@code skills_review} an/aus). */
    void sync() {
        sync(registry.localRuntime());
    }

    synchronized void sync(McpRuntime runtime) {
        boolean wanted = runtime.isActive(SkillsModule.ID, "skills_review");
        if (wanted == registered.contains(runtime)) {
            return;
        }
        McpSyncServer server = runtime.server();
        try {
            if (wanted) {
                server.addPrompt(specification());
                registered.add(runtime);
            } else {
                server.removePrompt(NAME);
                registered.remove(runtime);
            }
            server.notifyPromptsListChanged();
        } catch (RuntimeException e) {
            LOG.warn("Prompt {} konnte in {} nicht {} werden", NAME, runtime.scope(), wanted ? "registriert" : "entfernt",
                    e);
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
