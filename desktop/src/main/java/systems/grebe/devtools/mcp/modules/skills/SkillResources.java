package systems.grebe.devtools.mcp.modules.skills;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import systems.grebe.devtools.mcp.core.LiveInstructionsTransport;
import systems.grebe.devtools.mcp.core.McpRuntime;
import systems.grebe.devtools.mcp.core.ToolRegistry;
import systems.grebe.devtools.mcp.core.ToolScope;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stellt die Skills nach der MCP-Skills-Extension ({@code io.modelcontextprotocol/skills}, SEP-2640) bereit: jeder
 * Skill als Agent Skill mit {@code skill://<name>/SKILL.md} (YAML-Frontmatter {@code name}, {@code description}) und
 * seinen Zusatzdateien unter {@code skill://<name>/<pfad>} – lesbar per {@code resources/read}, gelistet in
 * {@code resources/list} (auch für Clients ohne die Extension, z.B. als @-Erwähnung) – plus {@code skills/list} und
 * {@code skills/get} mit vollständigem Manifest (SHA-256 und Größe je Datei). Die Deklaration in den Capabilities
 * übernimmt {@link SkillsCapability}.
 *
 * <p>Angeboten wird nur, solange {@code skills_view} aktiv ist; die Resources folgen Änderungen an Skills und Schaltern.
 */
@Component
public class SkillResources {

    static final String LIST = "skills/list";
    static final String GET = "skills/get";
    static final String SCHEME = "skill://";
    static final String SKILL_MD = "SKILL.md";

    private static final Logger LOG = LoggerFactory.getLogger(SkillResources.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ToolRegistry registry;
    private final SkillBackend skills;
    /** Beim Server registrierte Resources je URI. */
    private final Map<String, McpSchema.Resource> registered = new HashMap<>();
    private final ExecutorService syncer = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().daemon().name("skill-resources-sync").factory());
    private final AtomicBoolean syncPending = new AtomicBoolean();

    public SkillResources(ToolRegistry registry, SkillBackend skills, LiveInstructionsTransport transport) {
        this.registry = registry;
        this.skills = skills;
        transport.addRequestHandler(LIST, (exchange, params) -> Mono.fromCallable(() -> inScope(this::list)));
        transport.addRequestHandler(GET, (exchange, params) -> Mono.fromCallable(() -> inScope(() -> get(params))));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        registry.addChangeListener(this::syncLater);
        skills.addChangeListener(this::syncLater);
        sync();
    }

    /**
     * Nicht im Thread der Änderung – der Listener kann mitten in einem Schreibvorgang des Backends laufen. Eigener
     * Thread statt commonPool: dort schließt der JDK-HttpClient die Backend-Antworten ab, wartende Syncs würden ihn
     * aushungern. Anstöße, solange einer wartet, fallen zusammen.
     */
    private void syncLater() {
        if (syncPending.compareAndSet(false, true)) {
            syncer.execute(() -> {
                syncPending.set(false);
                sync();
            });
        }
    }

    /** Gleicht die Resources am Server mit den sichtbaren Skills ab. */
    synchronized void sync() {
        McpRuntime runtime = registry.localRuntime();
        Map<String, McpSchema.Resource> wanted;
        try {
            wanted = active() ? inScope(this::resources) : Map.of();
        } catch (RuntimeException e) {
            LOG.warn("Skill-Resources konnten nicht ermittelt werden: {}", e.toString());
            return;
        }
        McpSyncServer server = runtime.server();
        boolean changed = false;
        for (String uri : List.copyOf(registered.keySet())) {
            if (!registered.get(uri).equals(wanted.get(uri))) { // weg oder geändert (Beschreibung, Größe)
                server.removeResource(uri);
                registered.remove(uri);
                changed = true;
            }
        }
        for (McpSchema.Resource r : wanted.values()) {
            if (!registered.containsKey(r.uri())) {
                server.addResource(new McpServerFeatures.SyncResourceSpecification(r,
                        (exchange, request) -> inScope(() -> read(request.uri()))));
                registered.put(r.uri(), r);
                changed = true;
            }
        }
        if (changed) {
            server.notifyResourcesListChanged();
        }
    }

    private boolean active() {
        return registry.localRuntime().isActive(SkillsModule.ID, "skills_view");
    }

    private <T> T inScope(Supplier<T> body) {
        return ToolScope.callIn(registry.localRuntime().scope(), body);
    }

    /** SKILL.md und Zusatzdateien aller sichtbaren Skills. */
    private Map<String, McpSchema.Resource> resources() {
        Map<String, McpSchema.Resource> out = new LinkedHashMap<>();
        for (SkillViews.Summary s : visible()) {
            String md = uri(s.name(), SKILL_MD);
            out.put(md, McpSchema.Resource.builder().uri(md).name(s.name()).title(s.name())
                    .description(s.description()).mimeType("text/markdown").build());
            if (s.fileCount() == 0) {
                continue;
            }
            for (SkillViews.File f : skills.details(s.name()).map(SkillViews.Details::files).orElse(List.of())) {
                String uri = uri(s.name(), f.path());
                out.put(uri, McpSchema.Resource.builder().uri(uri).name(s.name() + "/" + f.path())
                        .mimeType(f.mediaType()).size(f.size()).build());
            }
        }
        return out;
    }

    /** Sichtbare Skills, je Name einmal (wie {@code skills_view} ihn auflöst). */
    private List<SkillViews.Summary> visible() {
        Map<String, SkillViews.Summary> byName = new LinkedHashMap<>();
        skills.overview().forEach(s -> byName.putIfAbsent(s.name(), s));
        return List.copyOf(byName.values());
    }

    Map<String, Object> list() {
        List<Map<String, Object>> entries = new ArrayList<>();
        if (active()) {
            for (SkillViews.Summary s : visible()) {
                skills.details(s.name()).ifPresent(d -> entries.add(entry(d)));
            }
        }
        return result("skills", entries);
    }

    Map<String, Object> get(Object params) {
        String uri = params instanceof Map<?, ?> m && m.get("uri") instanceof String s ? s : null;
        Ref ref = Ref.parse(uri).filter(r -> r.path().equals(SKILL_MD) && active()).orElseThrow(() -> unknown(uri));
        return result("skill", entry(skills.details(ref.name()).orElseThrow(() -> unknown(uri))));
    }

    McpSchema.ReadResourceResult read(String uri) {
        Ref ref = Ref.parse(uri).orElseThrow(() -> unknown(uri));
        SkillViews.Details d = skills.details(ref.name()).orElseThrow(() -> unknown(uri));
        if (ref.path().equals(SKILL_MD)) {
            return text(uri, "text/markdown", skillMd(d));
        }
        SkillViews.File f = d.files().stream().filter(x -> x.path().equals(ref.path())).findFirst()
                .orElseThrow(() -> unknown(uri));
        if (f.inline()) {
            return text(uri, f.mediaType(), content(f));
        }
        return new McpSchema.ReadResourceResult(List.of(new McpSchema.BlobResourceContents(uri, f.mediaType(),
                Base64.getEncoder().encodeToString(attachment(ref)))));
    }

    /** Eintrag für {@code skills/list} und {@code skills/get}: Frontmatter und Manifest aus den ausgelieferten Bytes. */
    private Map<String, Object> entry(SkillViews.Details d) {
        String name = d.summary().name();
        List<Map<String, Object>> files = new ArrayList<>();
        String md = skillMd(d);
        files.add(manifest(uri(name, SKILL_MD), digest(md), utf8(md)));
        for (SkillViews.File f : d.files()) {
            files.add(f.inline() ? manifest(uri(name, f.path()), digest(content(f)), utf8(content(f)))
                    : manifest(uri(name, f.path()), "sha256:" + f.blob(), f.size()));
        }
        return Map.of("uri", uri(name, SKILL_MD), "frontmatter", frontmatter(d.summary()), "resources", files);
    }

    private static Map<String, Object> manifest(String uri, String digest, long size) {
        return Map.of("uri", uri, "digest", digest, "size", size);
    }

    private static Map<String, Object> result(String key, Object value) {
        // Inhalte hängen am Benutzerkonto und ändern sich jederzeit – nicht teilen, nicht zwischenspeichern
        return Map.of(key, value, "resultType", "complete", "ttlMs", 0, "cacheScope", "private");
    }

    private static Map<String, String> frontmatter(SkillViews.Summary s) {
        return Map.of("name", s.name(), "description", s.description());
    }

    /** Agent-Skills-Format: Frontmatter (als JSON-String – gültiges YAML, auch mit Doppelpunkten) und Inhalt. */
    static String skillMd(SkillViews.Details d) {
        return "---\nname: " + d.summary().name() + "\ndescription: "
                + JSON.writeValueAsString(d.summary().description()) + "\n---\n\n" + d.content();
    }

    private byte[] attachment(Ref ref) {
        try {
            Path tmp = Files.createTempFile("skill-", ".bin");
            try {
                skills.exportFile(ref.name(), ref.path(), tmp);
                return Files.readAllBytes(tmp);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static McpSchema.ReadResourceResult text(String uri, String mimeType, String text) {
        return new McpSchema.ReadResourceResult(List.of(new McpSchema.TextResourceContents(uri, mimeType, text)));
    }

    private static String content(SkillViews.File f) {
        return f.content() == null ? "" : f.content();
    }

    private static long utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    static String digest(String s) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String uri(String name, String path) {
        return SCHEME + name + "/" + path;
    }

    private static McpError unknown(String uri) {
        return McpError.builder(McpSchema.ErrorCodes.INVALID_PARAMS).message("Unbekannter Skill: " + uri).build();
    }

    /** {@code skill://<name>/<pfad>}. */
    record Ref(String name, String path) {
        static Optional<Ref> parse(String uri) {
            if (uri == null || !uri.startsWith(SCHEME)) {
                return Optional.empty();
            }
            String rest = uri.substring(SCHEME.length());
            int slash = rest.indexOf('/');
            return slash <= 0 || slash == rest.length() - 1 ? Optional.empty()
                    : Optional.of(new Ref(rest.substring(0, slash), rest.substring(slash + 1)));
        }
    }
}
