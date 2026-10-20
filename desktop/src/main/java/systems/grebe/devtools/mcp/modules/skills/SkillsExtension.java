package systems.grebe.devtools.mcp.modules.skills;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.UnaryOperator;

import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpRequestHandler;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import systems.grebe.devtools.mcp.config.DataHome;
import systems.grebe.devtools.mcp.core.McpExtension;
import systems.grebe.devtools.mcp.core.ToolRegistry;

/**
 * MCP-Extension {@code io.modelcontextprotocol/skills} (SEP-2640): Clients mit Skills-Unterstützung erkennen die
 * gespeicherten Skills als echte Agent Skills – {@code skills/list} und {@code skills/get} liefern Frontmatter und
 * Manifest (Größe + SHA-256 je Datei), {@code resources/read} die {@code SKILL.md} und Zusatzdateien unter
 * {@code skill://<name>/…}, {@code resources/directory/read} die Verzeichnisse. Außerdem stehen die {@code SKILL.md}
 * in {@code resources/list} – so kommen auch Clients ohne die Extension (z.B. per {@code @}-Erwähnung) an die Skills.
 *
 * <p>Angeboten wird die Extension, solange {@code skills_view} aktiv ist. Die Ergebnisse sind benutzerbezogen
 * ({@code cacheScope: private}) und sofort veraltet ({@code ttlMs: 0}): Skills ändern sich laufend
 * ({@code skills_patch}); Änderungen meldet der Server zusätzlich per {@code notifications/resources/list_changed}.
 */
@Component
public class SkillsExtension implements McpExtension {

    static final String ID = "io.modelcontextprotocol/skills";
    static final String LIST = "skills/list";
    static final String GET = "skills/get";
    static final String DIRECTORY_READ = "resources/directory/read";

    private static final Logger LOG = LoggerFactory.getLogger(SkillsExtension.class);
    private static final long TTL_MS = 0;
    private static final String CACHE_SCOPE = "private";

    private final SkillBackend skills;
    private final SkillCatalog catalog;
    private final ToolRegistry registry;

    @Autowired
    public SkillsExtension(SkillBackend skills, ToolRegistry registry, DataHome home) {
        this(skills, new SkillCatalog(skills, home.dir().resolve("tmp")), registry);
    }

    SkillsExtension(SkillBackend skills, SkillCatalog catalog, ToolRegistry registry) {
        this.skills = skills;
        this.catalog = catalog;
        this.registry = registry;
    }

    /** Geänderte Skills oder Schalter → Clients laden {@code resources/list} neu. */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        Runnable changed = () -> {
            try {
                registry.localRuntime().server().notifyResourcesListChanged();
            } catch (RuntimeException e) {
                LOG.debug("resources/list_changed nicht gesendet: {}", e.toString());
            }
        };
        skills.addChangeListener(changed);
        registry.addChangeListener(changed);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public Map<String, Object> settings() {
        return enabled() ? Map.of("directoryRead", true) : null;
    }

    boolean enabled() {
        return registry.isToolActive(SkillsModule.ID, "skills_view");
    }

    @Override
    public Map<String, UnaryOperator<McpRequestHandler<?>>> requestHandlers() {
        Map<String, UnaryOperator<McpRequestHandler<?>>> handlers = new LinkedHashMap<>();
        handlers.put(LIST, original -> (exchange, params) -> call(this::list));
        handlers.put(GET, original -> (exchange, params) -> call(() -> get(uri(params))));
        handlers.put(McpSchema.METHOD_RESOURCES_READ, original -> (exchange, params) -> isSkill(params)
                ? call(() -> read(uri(params))) : delegate(original, exchange, params));
        handlers.put(DIRECTORY_READ, original -> (exchange, params) -> isSkill(params)
                ? call(() -> directory(uri(params))) : delegate(original, exchange, params));
        handlers.put(McpSchema.METHOD_RESOURCES_LIST, original -> (exchange, params) -> original == null
                ? call(() -> resources(List.of()))
                : original.handle(exchange, params).flatMap(r -> call(() -> resources(r instanceof
                        McpSchema.ListResourcesResult l ? l.resources() : List.of()))));
        return handlers;
    }

    // ------------------------------------------------------------------ Methoden

    Map<String, Object> list() {
        List<Map<String, Object>> entries = enabled()
                ? catalog.list().stream().map(SkillsExtension::entry).toList() : List.of();
        Map<String, Object> r = complete();
        r.put("skills", entries);
        return withCache(r);
    }

    Map<String, Object> get(String uri) {
        requireEnabled();
        SkillCatalog.Entry e = catalog.byUri(uri)
                .orElseThrow(() -> new IllegalArgumentException("Unbekannter Skill: " + uri));
        Map<String, Object> r = complete();
        r.put("skill", entry(e));
        return withCache(r);
    }

    Map<String, Object> read(String uri) {
        requireEnabled();
        SkillCatalog.Content c = catalog.read(uri);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("uri", c.uri());
        content.put("mimeType", c.mimeType());
        if (c.text() != null) {
            content.put("text", c.text());
        } else {
            content.put("blob", Base64.getEncoder().encodeToString(c.bytes()));
        }
        if (uri.endsWith("/" + SkillCatalog.SKILL_MD)) {
            markUsed(SkillCatalog.parse(uri).name());
        }
        Map<String, Object> r = complete();
        r.put("contents", List.of(content));
        return withCache(r);
    }

    Map<String, Object> directory(String uri) {
        requireEnabled();
        List<Map<String, Object>> children = catalog.directory(uri).stream().map(c -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("uri", c.uri());
            m.put("name", c.name());
            m.put("mimeType", c.mimeType());
            return m;
        }).toList();
        Map<String, Object> r = complete();
        r.put("resources", children);
        return withCache(r);
    }

    /** Vorhandene Ressourcen plus je Skill seine {@code SKILL.md}. */
    McpSchema.ListResourcesResult resources(List<McpSchema.Resource> existing) {
        List<McpSchema.Resource> out = new ArrayList<>(existing);
        if (enabled()) {
            Set<String> names = new LinkedHashSet<>();
            for (SkillViews.Summary s : skills.overview()) {
                if (names.add(s.name())) {
                    out.add(McpSchema.Resource.builder(SkillCatalog.uri(s.name(), SkillCatalog.SKILL_MD), s.name())
                            .title("Skill " + s.name()).description(s.description())
                            .mimeType(SkillCatalog.MARKDOWN).build());
                }
            }
        }
        return new McpSchema.ListResourcesResult(out, null, null);
    }

    // ------------------------------------------------------------------ Hilfen

    static Map<String, Object> entry(SkillCatalog.Entry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("uri", e.uri());
        m.put("frontmatter", e.frontmatter());
        m.put("resources", e.resources().stream().map(r -> {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("uri", r.uri());
            f.put("digest", r.digest());
            f.put("size", r.size());
            return f;
        }).toList());
        return m;
    }

    /** Wie {@code skills_view}: das Laden zählt als Verwendung (Statistik, Hinweise). */
    private void markUsed(String name) {
        try {
            skills.view(name, null);
        } catch (RuntimeException e) {
            LOG.debug("Verwendung von Skill {} nicht gezählt: {}", name, e.toString());
        }
    }

    private void requireEnabled() {
        if (!enabled()) {
            throw new IllegalArgumentException("Skills sind in der DevTools-App abgeschaltet (Module → Skills).");
        }
    }

    private static Map<String, Object> complete() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("resultType", "complete");
        return r;
    }

    private static Map<String, Object> withCache(Map<String, Object> r) {
        r.put("ttlMs", TTL_MS);
        r.put("cacheScope", CACHE_SCOPE);
        return r;
    }

    private static boolean isSkill(Object params) {
        String uri = params instanceof Map<?, ?> m && m.get("uri") instanceof String s ? s : null;
        return uri != null && uri.startsWith(SkillCatalog.SCHEME);
    }

    static String uri(Object params) {
        if (params instanceof Map<?, ?> m && m.get("uri") instanceof String s && !s.isBlank()) {
            return s;
        }
        throw new IllegalArgumentException("Parameter 'uri' fehlt.");
    }

    private static Mono<Object> delegate(McpRequestHandler<?> original, McpAsyncServerExchange exchange,
                                         Object params) {
        if (original == null) {
            return Mono.error(invalid("Unbekannte Ressource: " + (params instanceof Map<?, ?> m ? m.get("uri") : null)));
        }
        return original.handle(exchange, params).map(r -> (Object) r);
    }

    /** Blockierender Aufruf; fachliche Fehler → {@code -32602}, sonst {@code -32603}. */
    static Mono<Object> call(Callable<?> body) {
        return Mono.fromCallable(body).map(r -> (Object) r).onErrorMap(e -> !(e instanceof McpError), e ->
                e instanceof IllegalArgumentException ? invalid(e.getMessage())
                        : McpError.builder(McpSchema.ErrorCodes.INTERNAL_ERROR).message(String.valueOf(e.getMessage()))
                        .build());
    }

    private static McpError invalid(String message) {
        return McpError.builder(McpSchema.ErrorCodes.INVALID_PARAMS).message(message).build();
    }
}
