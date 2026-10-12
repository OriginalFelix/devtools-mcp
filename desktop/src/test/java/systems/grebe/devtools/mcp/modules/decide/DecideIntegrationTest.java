package systems.grebe.devtools.mcp.modules.decide;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.modules.decide.DecisionSchema.FieldSpec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Ende-zu-Ende mit den echten Modellen; läuft nur, wenn Laya und das LLM heruntergeladen sind. */
class DecideIntegrationTest {

    @TempDir
    static Path dir;

    static final DecideEngines ENGINES = new DecideEngines();
    static DecideTools tools;

    static final List<FieldSpec> FIELDS = List.of(
            new FieldSpec("category", "choice", "Um welche Art von Anliegen geht es?", List.of("fehler", "wunsch", "frage"),
                    Map.of("fehler", "etwas funktioniert nicht wie erwartet", "wunsch", "eine neue Funktion wird gewünscht",
                            "frage", "Frage zur Bedienung oder zum Verhalten")),
            new FieldSpec("urgent", "boolean", "Das Anliegen ist dringend und muss heute gelöst werden.", null, null),
            new FieldSpec("severity", "score", "Wie schwer wiegt das Problem für den Kunden?",
                    List.of("gering", "mittel", "hoch", "kritisch"), null),
            new FieldSpec("topics", "multi", "Welche Themen spricht das Ticket an?", List.of("speichern", "anmeldung", "rechnung"),
                    null));

    @BeforeAll
    static void setUp() {
        DecideModule module = new DecideModule(ENGINES);
        DecideSettings s = DecideSettings.of(ModuleConfig.of(module.configSchema(), Map.of()));
        assumeTrue(ModelFiles.layaPresent(s) && ModelFiles.llmPresent(s), "Modelle nicht heruntergeladen");
        tools = new DecideTools(s, ENGINES, new TaskStore(dir.resolve("tasks")), new DecisionLog());
    }

    @AfterAll
    static void tearDown() {
        ENGINES.shutdown();
        tools = null;
    }

    @Test
    void decidesTicketTriageInAllModes() {
        String ticket = "Seit dem Update gestern stürzt die App jedes Mal beim Speichern ab und wir verlieren Daten. "
                + "Bitte sofort beheben, die ganze Buchhaltung steht still!";
        for (String mode : List.of("laya", "llm", "hybrid")) {
            String out = tools.run(ticket, FIELDS, null, null, mode);
            System.out.println("=== " + mode + "\n" + out);
            JsonNode d = json(out).path("decisions");
            assertThat(d.path("category").path("value").asString()).isIn("fehler", "wunsch", "frage");
            assertThat(d.path("severity").path("level").asInt()).isBetween(0, 3);
            assertThat(d.path("topics").path("values").isArray()).isTrue();
            if (mode.equals("laya")) {
                // Laya liegt hier sicher richtig; das kleine Standard-LLM nicht immer (siehe Modus-Hilfe)
                assertThat(d.path("category").path("value").asString()).isEqualTo("fehler");
                assertThat(d.path("urgent").path("value").asBoolean()).isTrue();
                assertThat(d.path("category").path("engine").asString()).isEqualTo("laya");
            }
        }
    }

    @Test
    void batchAndFeedback() {
        tools.taskSave("triage", FIELDS, "Support-Tickets", null);
        String out = tools.batch(List.of(
                "Könnten Sie bitte einen Dunkelmodus einbauen? Wäre schön.",
                "Wie kann ich mein Passwort ändern?",
                "Die Rechnung für März wurde doppelt abgebucht!"), null, "triage", null, null);
        System.out.println("=== batch\n" + out);
        assertThat(out.lines().filter(l -> l.startsWith("{"))).hasSize(3);
        String id = json(out.lines().findFirst().orElseThrow()).path("decisionId").asString();
        String fb = tools.feedback(id, "category", "wunsch", null, null);
        System.out.println("=== feedback\n" + fb);
        assertThat(fb).contains("Gespeichert in Aufgabe triage: category = wunsch");
    }

    static JsonNode json(String s) {
        return JsonMapper.builder().build().readTree(s);
    }
}
