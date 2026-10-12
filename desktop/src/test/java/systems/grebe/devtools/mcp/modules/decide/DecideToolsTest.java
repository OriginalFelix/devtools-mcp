package systems.grebe.devtools.mcp.modules.decide;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolBeans;
import systems.grebe.devtools.mcp.modules.decide.DecisionSchema.Field;
import systems.grebe.devtools.mcp.modules.decide.DecisionSchema.FieldSpec;
import systems.grebe.devtools.mcp.modules.decide.DecisionSchema.Kind;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Schema, Kalibrierung, Aufgaben und Rückmeldungen – ohne Modelle. */
class DecideToolsTest {

    @TempDir
    Path dir;

    DecideModule module = new DecideModule(new DecideEngines());
    TaskStore store;
    DecisionLog log;
    DecideTools tools;

    @BeforeEach
    void setUp() {
        ModuleConfig config = ModuleConfig.of(module.configSchema(), Map.of(DecideModule.MODELS_DIR, dir.resolve("models").toString()));
        store = new TaskStore(dir.resolve("tasks"));
        log = new DecisionLog();
        tools = new DecideTools(DecideSettings.of(config), new DecideEngines(), store, log);
    }

    @Test
    void schemaInfersTypesAndRejectsInvalidFields() {
        List<Field> fields = DecisionSchema.validate(List.of(
                new FieldSpec("category", null, null, List.of("bug", "feature", "bug"), null),
                new FieldSpec("urgent", null, "Der Kunde braucht heute eine Lösung.", null, Map.of("true", "dringend")),
                new FieldSpec("severity", "scale", null, List.of("niedrig", "mittel", "hoch"), null),
                new FieldSpec("topics", "tags", "Welche Themen?", List.of("login", "billing"), null)));
        assertThat(fields).extracting(Field::kind).containsExactly(Kind.CHOICE, Kind.BOOLEAN, Kind.SCORE, Kind.MULTI);
        assertThat(fields.get(0).options()).containsExactly("bug", "feature");
        assertThat(fields.get(1).values()).containsExactly("false", "true");

        assertThatThrownBy(() -> DecisionSchema.validate(List.of(new FieldSpec("x", "choice", null, List.of("nur"), null))))
                .hasMessageContaining("mindestens 2");
        assertThatThrownBy(() -> DecisionSchema.validate(List.of(new FieldSpec("a b", null, null, null, null))))
                .hasMessageContaining("Ungültiger Feldname");
        assertThatThrownBy(() -> DecisionSchema.validate(List.of(new FieldSpec("x", "freitext", null, null, null))))
                .hasMessageContaining("Unbekannter Feldtyp");
    }

    @Test
    void unitsMapBooleanAndMultiToYesNoQuestions() {
        List<Field> fields = DecisionSchema.validate(List.of(
                new FieldSpec("urgent", "boolean", "Dringend?", null, null),
                new FieldSpec("topics", "multi", "Themen", List.of("login", "billing"), null)));
        List<Decider.Unit> units = Decider.units(fields);
        assertThat(units).extracting(Decider.Unit::key).containsExactly("urgent", "topics/login", "topics/billing");
        Decider.Unit urgent = units.get(0);
        assertThat(urgent.yesNo()).isTrue();
        assertThat(urgent.layaInstructions()).isEqualTo("Dringend?");
        assertThat(units.get(1).layaInstructions()).isEqualTo("The text is about login.");
        assertThat(Decider.canonicalIndex(urgent, "ja")).isEqualTo(1);
        assertThat(Decider.canonicalIndex(units.get(2), List.of("billing"))).isEqualTo(1);
        assertThat(Decider.canonicalIndex(units.get(1), "billing")).isZero();
    }

    @Test
    void temperatureFitSoftensOverconfidentModel() {
        // Modell mit Logit-Abstand 8 (≈ 100 % sicher), aber nur 70 % richtig → Temperatur deutlich > 1
        Random rnd = new Random(1);
        List<Calibration.Sample> samples = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            boolean right = rnd.nextDouble() < 0.7;
            samples.add(new Calibration.Sample(new float[] {8f, 0f}, right ? 0 : 1));
        }
        double t = Calibration.fit(samples);
        double[] p = Calibration.softmax(new float[] {8f, 0f}, t);
        assertThat(t).isGreaterThan(3);
        assertThat(p[0]).isBetween(0.65, 0.75);
        assertThat(Calibration.fit(samples.subList(0, 3))).isNaN();
        assertThat(Calibration.certainty(new double[] {0.5, 0.5})).isZero();
    }

    @Test
    void erfMatchesReferenceValues() {
        assertThat(systems.grebe.devtools.mcp.modules.decide.engine.Kernels.erf(0.5)).isCloseTo(0.5204998778130465, within());
        assertThat(systems.grebe.devtools.mcp.modules.decide.engine.Kernels.erf(1.0)).isCloseTo(0.8427007929497149, within());
        assertThat(systems.grebe.devtools.mcp.modules.decide.engine.Kernels.erf(-2.0)).isCloseTo(-0.9953222650189527, within());
        assertThat(systems.grebe.devtools.mcp.modules.decide.engine.Kernels.erf(4.5)).isCloseTo(0.9999999998033839, within());
    }

    private static org.assertj.core.data.Offset<Double> within() {
        return org.assertj.core.data.Offset.offset(1e-12);
    }

    @Test
    void toolCallbacksAcceptStructuredFields() {
        List<ToolCallback> callbacks = ToolBeans.callbacks(tools);
        ToolCallback save = callbacks.stream().filter(c -> c.getToolDefinition().name().equals("task_save")).findFirst()
                .orElseThrow();
        assertThat(save.getToolDefinition().inputSchema()).contains("\"descriptions\"").contains("\"options\"");
        String result = save.call("""
                {"name": "Ticket-Triage", "description": "Tickets einordnen",
                 "fields": [{"name": "category", "type": "choice", "question": "Art des Anliegens?",
                             "options": ["bug", "feature"], "descriptions": {"bug": "etwas ist kaputt"}},
                            {"name": "urgent", "type": "boolean", "question": "Muss heute gelöst werden."}]}
                """);
        assertThat(result).contains("Aufgabe angelegt: ticket-triage");
        TaskStore.Task t = store.require("ticket-triage");
        assertThat(t.validFields()).extracting(Field::name).containsExactly("category", "urgent");
        assertThat(tools.tasks(null)).contains("ticket-triage").contains("category:choice");
    }

    @Test
    void feedbackStoresExamplesAndCalibratesPerEngine() {
        tools.taskSave("triage", List.of(new FieldSpec("category", "choice", "Art?", List.of("bug", "feature"), null)),
                null, null);
        List<Field> fields = store.require("triage").validFields();
        String last = null;
        for (int i = 0; i < 8; i++) {
            Map<String, Map<String, float[]>> raw = Map.of("category",
                    Map.of(Decider.LAYA, new float[] {6f, 0f}, Decider.LLM, new float[] {1f, 0.5f}));
            String id = log.add("triage", "Eingabe " + i, fields, raw);
            last = tools.feedback(id, "category", i % 2 == 0 ? "bug" : "Feature", null, null);
        }
        assertThat(last).contains("category / laya: 8 Rückmeldungen, Treffer 50 %").contains("Temperatur");
        TaskStore.Task t = store.require("triage");
        assertThat(t.examples()).hasSize(8);
        assertThat(t.examples().get(1).labels()).containsEntry("category", "feature");
        assertThat(t.samples("category", Decider.LLM)).hasSize(8);

        assertThatThrownBy(() -> tools.feedback(log.add("triage", "x", fields, Map.of()), "category", "frage", null, null))
                .hasMessageContaining("keine Option");
        assertThatThrownBy(() -> tools.feedback("unbekannt", "category", "bug", null, null))
                .hasMessageContaining("unbekannt oder zu alt");
    }

    @Test
    void feedbackWithoutTaskCreatesItFromTheDecision() {
        List<Field> fields = DecisionSchema.validate(List.of(new FieldSpec("ok", "boolean", "Passt?", null, null)));
        String id = log.add(null, "Text", fields, Map.of("ok", Map.of(Decider.LAYA, new float[] {0f, 2f})));
        assertThatThrownBy(() -> tools.feedback(id, "ok", "true", null, null)).hasMessageContaining("Keine Aufgabe");
        assertThat(tools.feedback(id, "ok", "ja", "neu", null)).contains("Gespeichert in Aufgabe neu: ok = true");
        assertThat(store.require("neu").validFields().get(0).kind()).isEqualTo(Kind.BOOLEAN);
    }

    @Test
    void demosUseSimilarUserExamples() {
        List<Field> fields = DecisionSchema.validate(List.of(new FieldSpec("category", "choice", "Art?",
                List.of("bug", "feature"), null)));
        TaskStore.Task t = new TaskStore.Task("t", null, null, fields.stream().map(DecisionSchema::spec).toList(),
                List.of(new TaskStore.Example("Absturz beim Speichern der Datei", Map.of("category", "bug"), "user", null),
                        new TaskStore.Example("Bitte Dunkelmodus einbauen", Map.of("category", "feature"), "user", null)),
                null, null);
        Decider.Unit u = Decider.units(fields).get(0);
        List<String> demos = Decider.demos(u, t, "Die App hat einen Absturz beim Speichern");
        assertThat(demos).containsExactly("\"Absturz beim Speichern der Datei\" → bug");
        String suffix = Decider.suffix(u, t, "Die App hat einen Absturz beim Speichern", new int[] {1, 0});
        assertThat(suffix).contains("A) feature").contains("B) bug").contains("(A–B)");
        assertThat(Decider.shifts(3)).containsExactly(0, 1, 2);
        assertThat(Decider.shifts(12)).containsExactly(0, 2, 4, 6, 8, 10);
    }
}
