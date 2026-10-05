package systems.grebe.devtools.mcp.modules.scripts;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Text-Argumente aus Gherkin-Tabellen nach dem Eingabeschema des Ziel-Tools umwandeln. */
class RegistryToolCallerTest {

    private static final String SCHEMA = """
            {"type": "object", "properties": {
              "name": {"type": "string"},
              "limit": {"type": "integer"},
              "factor": {"type": "number"},
              "force": {"type": "boolean"},
              "tags": {"type": "array", "items": {}},
              "options": {"type": "object"},
              "maybe": {"type": ["integer", "null"]}
            }}""";

    @Test
    void textValuesFollowTheSchema() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("name", " 42 ");
        args.put("limit", "5");
        args.put("factor", "1,5");
        args.put("force", "ja");
        args.put("tags", "a, b,, c");
        args.put("options", "{\"x\": 1}");
        args.put("maybe", "7");
        Map<String, Object> out = RegistryToolCaller.convert("t_x", SCHEMA, args);
        assertThat(out).containsEntry("name", " 42 ").containsEntry("limit", 5L).containsEntry("factor", 1.5)
                .containsEntry("force", true).containsEntry("tags", List.of("a", "b", "c"))
                .containsEntry("options", Map.of("x", 1)).containsEntry("maybe", 7L);
        assertThat(RegistryToolCaller.convert("t_x", SCHEMA, Map.of("tags", "[\"a b\"]")))
                .containsEntry("tags", List.of("a b"));
        // keine Texte (aus JSON-DocStrings) bleiben, wie sie sind
        assertThat(RegistryToolCaller.convert("t_x", SCHEMA, Map.of("limit", 3))).containsEntry("limit", 3);
    }

    @Test
    void wrongValuesAndUnknownParametersAreRejected() {
        assertThatThrownBy(() -> RegistryToolCaller.convert("t_x", SCHEMA, Map.of("limit", "viele")))
                .hasMessageContaining("'limit'").hasMessageContaining("integer").hasMessageContaining("viele");
        assertThatThrownBy(() -> RegistryToolCaller.convert("t_x", SCHEMA, Map.of("force", "vielleicht")))
                .hasMessageContaining("boolean");
        assertThatThrownBy(() -> RegistryToolCaller.convert("t_x", SCHEMA, Map.of("lmit", "5")))
                .hasMessageContaining("kennt keinen Parameter 'lmit'").hasMessageContaining("limit");
    }
}
