package systems.grebe.devtools.mcp.modules.skills;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.modelcontextprotocol.server.McpRequestHandler;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Mono;
import systems.grebe.devtools.mcp.core.ToolRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Protokoll-Methoden der Extension {@code io.modelcontextprotocol/skills}. */
@SuppressWarnings("unchecked")
class SkillsExtensionTest {

    @TempDir
    Path temp;

    SkillBackend backend = mock(SkillBackend.class);
    ToolRegistry registry = mock(ToolRegistry.class);
    SkillsExtension extension;

    @BeforeEach
    void setUp() {
        SkillViews.Details d = SkillCatalogTest.details("deploy", "Verwenden, wenn deployt wird.");
        when(backend.overview()).thenReturn(List.of(d.summary()));
        when(backend.details("deploy")).thenReturn(Optional.of(d));
        when(backend.file("deploy", "assets/logo.png")).thenReturn(Optional.of(d.files().get(1)));
        when(backend.exportFile(eq("deploy"), eq("assets/logo.png"), any())).thenAnswer(inv -> {
            Files.write(inv.<Path>getArgument(2), SkillCatalogTest.PNG);
            return "ok";
        });
        when(registry.isToolActive(SkillsModule.ID, "skills_view")).thenReturn(true);
        extension = new SkillsExtension(backend, new SkillCatalog(backend, temp), registry);
    }

    private Map<String, Object> call(String method, McpRequestHandler<?> original, Map<String, Object> params) {
        return (Map<String, Object>) extension.requestHandlers().get(method).apply(original).handle(null, params)
                .block();
    }

    @Test
    void declaresDirectoryReadWhileSkillsAreReadable() {
        assertThat(extension.id()).isEqualTo("io.modelcontextprotocol/skills");
        assertThat(extension.settings()).isEqualTo(Map.of("directoryRead", true));
        when(registry.isToolActive(SkillsModule.ID, "skills_view")).thenReturn(false);
        assertThat(extension.settings()).isNull();
        assertThat(call("skills/list", null, Map.of())).containsEntry("skills", List.of());
    }

    @Test
    void listAndGetReturnCompleteEntriesWithCacheHints() {
        Map<String, Object> list = call("skills/list", null, Map.of());
        assertThat(list).containsEntry("resultType", "complete").containsEntry("ttlMs", 0L)
                .containsEntry("cacheScope", "private");
        Map<String, Object> entry = ((List<Map<String, Object>>) list.get("skills")).getFirst();
        assertThat(entry).containsEntry("uri", "skill://deploy/SKILL.md");
        assertThat((Map<String, Object>) entry.get("frontmatter")).containsEntry("name", "deploy");
        assertThat((List<?>) entry.get("resources")).hasSize(3).first()
                .satisfies(r -> assertThat((Map<String, Object>) r).containsKeys("uri", "digest", "size"));

        assertThat(call("skills/get", null, Map.of("uri", "skill://deploy/SKILL.md")))
                .containsEntry("skill", entry).containsEntry("resultType", "complete");
        assertThatThrownBy(() -> call("skills/get", null, Map.of("uri", "skill://fremd/SKILL.md")))
                .isInstanceOfSatisfying(McpError.class, e -> assertThat(e.getJsonRpcError().code())
                        .isEqualTo(McpSchema.ErrorCodes.INVALID_PARAMS));
    }

    @Test
    void readServesSkillFilesAndLeavesOtherResourcesToTheServer() {
        Map<String, Object> md = call("resources/read", null, Map.of("uri", "skill://deploy/SKILL.md"));
        assertThat((List<?>) md.get("contents")).singleElement().satisfies(c -> assertThat((Map<String, Object>) c)
                .containsEntry("mimeType", "text/markdown").containsKey("text"));
        verify(backend).view("deploy", null); // zählt als Verwendung wie skills_view

        Map<String, Object> png = call("resources/read", null, Map.of("uri", "skill://deploy/assets/logo.png"));
        assertThat((List<?>) png.get("contents")).singleElement().satisfies(c -> assertThat((Map<String, Object>) c)
                .containsEntry("blob", Base64.getEncoder().encodeToString(SkillCatalogTest.PNG)));

        McpSchema.ReadResourceResult other = new McpSchema.ReadResourceResult(List.of(), null);
        McpRequestHandler<?> original = (exchange, params) -> Mono.just(other);
        assertThat((Object) extension.requestHandlers().get("resources/read").apply(original)
                .handle(null, Map.of("uri", "file:///x")).block()).isSameAs(other);
        assertThatThrownBy(() -> call("resources/read", null, Map.of("uri", "file:///x")))
                .isInstanceOfSatisfying(McpError.class, e -> assertThat(e.getJsonRpcError().code())
                        .isEqualTo(McpSchema.ErrorCodes.INVALID_PARAMS));
    }

    @Test
    void directoryReadListsChildren() {
        Map<String, Object> dir = call("resources/directory/read", null, Map.of("uri", "skill://deploy/assets"));
        assertThat((List<?>) dir.get("resources")).singleElement().isEqualTo(Map.of(
                "uri", "skill://deploy/assets/logo.png", "name", "logo.png", "mimeType", "image/png"));
        assertThatThrownBy(() -> call("resources/directory/read", null, Map.of("uri", "skill://deploy/fehlt")))
                .isInstanceOf(McpError.class);
    }

    @Test
    void resourcesListAddsOneSkillMdPerSkill() {
        McpSchema.Resource existing = McpSchema.Resource.builder("file:///readme", "readme").build();
        McpRequestHandler<?> original = (exchange, params) ->
                Mono.just(new McpSchema.ListResourcesResult(List.of(existing), null, null));
        McpSchema.ListResourcesResult r = (McpSchema.ListResourcesResult) (Object) extension.requestHandlers()
                .get("resources/list").apply(original).handle(null, Map.of()).block();
        assertThat(r.resources()).extracting(McpSchema.Resource::uri)
                .containsExactly("file:///readme", "skill://deploy/SKILL.md");
        assertThat(r.resources().get(1).description()).isEqualTo("Verwenden, wenn deployt wird.");
    }
}
