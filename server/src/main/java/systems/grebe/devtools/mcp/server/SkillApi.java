package systems.grebe.devtools.mcp.server;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import systems.grebe.devtools.mcp.modules.skills.SkillService;
import systems.grebe.devtools.mcp.modules.skills.SkillViews;
import tools.jackson.databind.JsonNode;

/**
 * Skills zentral auf dem Server ({@code /api/skills/**}) – dieselben Operationen wie die lokale Ablage der Desktop-App
 * ({@link SkillService}), ausgeführt als der per Desktop-Token angemeldete Benutzer ({@link ApiSkillOwner}). Texte
 * kommen als {@code {"text": …}}, fachliche Fehler als 400 mit {@code {"error": …}}.
 */
@RestController
@RequestMapping(SecurityConfig.API + "/skills")
public class SkillApi {

    private final SkillService skills;

    public SkillApi(SkillService skills) {
        this.skills = skills;
    }

    @GetMapping
    public List<SkillViews.Summary> overview() {
        return skills.overview();
    }

    @PostMapping("/details")
    public SkillViews.Details details(@RequestBody JsonNode args) {
        return skills.details(str(args, "name")).orElse(null);
    }

    @PostMapping("/count")
    public Map<String, Integer> count() {
        return Map.of("count", skills.visibleCount());
    }

    @PostMapping("/{op}")
    public Map<String, String> call(@PathVariable String op, @RequestBody JsonNode a) {
        String text = switch (op) {
            case "list" -> skills.list(str(a, "query"), str(a, "category"));
            case "view" -> skills.view(str(a, "name"), str(a, "filePath"));
            case "history" -> skills.history(str(a, "name"), integer(a, "revision"));
            case "create" -> skills.create(str(a, "name"), str(a, "description"), str(a, "content"),
                    str(a, "category"), tags(a), max(a));
            case "update" -> skills.update(str(a, "name"), str(a, "description"), str(a, "content"),
                    str(a, "category"), tags(a), str(a, "note"), integer(a, "expectedRevision"), max(a));
            case "patch" -> skills.patch(str(a, "name"), str(a, "oldString"), str(a, "newString"),
                    a.has("replaceAll") ? a.get("replaceAll").asBoolean() : null, str(a, "filePath"), str(a, "note"),
                    integer(a, "expectedRevision"), max(a));
            case "writeFile" -> skills.writeFile(str(a, "name"), str(a, "filePath"), str(a, "content"),
                    str(a, "note"), max(a));
            case "removeFile" -> skills.removeFile(str(a, "name"), str(a, "filePath"), str(a, "note"));
            case "delete" -> skills.delete(str(a, "name"));
            case "publish" -> skills.publish(str(a, "name"));
            case "unpublish" -> skills.unpublish(str(a, "name"));
            default -> throw new IllegalArgumentException("Unbekannte Skill-Operation " + op);
        };
        return Map.of("text", text);
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> badRequest(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", String.valueOf(e.getMessage())));
    }

    private static String str(JsonNode a, String key) {
        JsonNode n = a == null ? null : a.get(key);
        return n == null || n.isNull() ? null : n.asString();
    }

    private static Integer integer(JsonNode a, String key) {
        JsonNode n = a == null ? null : a.get(key);
        return n == null || n.isNull() ? null : n.asInt();
    }

    private static List<String> tags(JsonNode a) {
        JsonNode n = a == null ? null : a.get("tags");
        return n == null || n.isNull() ? null : n.valueStream().map(JsonNode::asString).toList();
    }

    /** Inhaltsgrenze wie in der Desktop-App eingestellt, höchstens 1 Mio. Zeichen. */
    private static int max(JsonNode a) {
        Integer v = integer(a, "maxContentChars");
        return v == null ? 100_000 : Math.min(v, 1_000_000);
    }
}
