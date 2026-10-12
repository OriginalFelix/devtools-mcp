package systems.grebe.devtools.mcp.modules.decide;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ToolHints;
import systems.grebe.devtools.mcp.modules.decide.DecideSettings.Mode;
import systems.grebe.devtools.mcp.modules.decide.DecisionSchema.Field;
import systems.grebe.devtools.mcp.modules.decide.DecisionSchema.FieldSpec;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Tools des Moduls Entscheidungen. */
class DecideTools {

    static final int MAX_BATCH = 100;
    private static final JsonMapper PRETTY = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
    private static final JsonMapper COMPACT = JsonMapper.builder().build();

    private static final String FIELDS = "Felder der Entscheidung, je Feld name, type (choice|score|boolean|multi), "
            + "question, options und optional descriptions je Option. Leer = Felder der Aufgabe 'task'.";
    private static final String TASK = "Gespeicherte Aufgabe (decide_tasks): liefert Felder und Hinweise, nutzt deren "
            + "Kalibrierung und Beispiele aus Rückmeldungen";
    private static final String MODE = "laya (schnelles Modell), hybrid (bei Unsicherheit zusätzlich das LLM) oder llm "
            + "(nur das LLM); leer = Einstellung der App";

    private final DecideSettings settings;
    private final DecideEngines engines;
    private final TaskStore store;
    private final DecisionLog log;

    DecideTools(DecideSettings settings, DecideEngines engines, TaskStore store, DecisionLog log) {
        this.settings = settings;
        this.engines = engines;
        this.store = store;
        this.log = log;
    }

    @Tool(name = "run", description = "Trifft eine typisierte Entscheidung lokal (ohne API, ohne Generieren): "
            + "unstrukturierte Eingabe rein, je Feld ein Wert aus dem Schema mit Wahrscheinlichkeiten raus – "
            + "klassifizieren, routen, bewerten, prüfen (\"smarte if-Abfragen\"). Liefert je Feld value (bei multi values), "
            + "probabilities, confidence (Wahrscheinlichkeit der Antwort), engine (laya, llm oder laya+llm) und calibrated; "
            + "uncertain=true, wenn eine Antwort unter der Schwelle liegt – dann selbst prüfen oder nachfragen. "
            + "Die decisionId für decide_feedback aufheben, wenn die richtige Antwort später bekannt wird.")
    @ToolHints(readOnly = true, openWorld = false)
    public String run(
            @ToolParam(description = "Eingabe: Text, Ticket, Log-Auszug, Mail oder Zustand als JSON") String input,
            @ToolParam(required = false, description = FIELDS) List<FieldSpec> fields,
            @ToolParam(required = false, description = TASK) String task,
            @ToolParam(required = false, description = "Zusätzliche Regeln/Kontext für diese Entscheidung") String instructions,
            @ToolParam(required = false, description = MODE) String mode) {
        Request r = request(fields, task, instructions, mode);
        Decider.Outcome o = new Decider(settings, engines).decide(input, r.fields(), r.instructions(), r.task(), r.mode(), false);
        String id = log.add(r.taskName(), input, r.fields(), o.raw());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("decisionId", id);
        if (r.taskName() != null) {
            out.put("task", r.taskName());
        }
        out.put("decisions", o.decisions());
        out.put("uncertain", o.uncertain());
        out.put("millis", o.millis());
        if (!o.notes().isEmpty()) {
            out.put("notes", o.notes());
        }
        return PRETTY.writeValueAsString(out);
    }

    @Tool(name = "batch", description = "Wie decide_run für viele Eingaben mit denselben Feldern (Map-Reduce über "
            + "Datensätze, Tickets, Logzeilen): eine JSON-Zeile je Eingabe mit index, decisionId, den Werten je Feld, "
            + "der kleinsten confidence und uncertain. Höchstens " + MAX_BATCH + " Eingaben je Aufruf.")
    @ToolHints(readOnly = true, openWorld = false)
    public String batch(
            @ToolParam(description = "Eingaben, je eine Entscheidung") List<String> inputs,
            @ToolParam(required = false, description = FIELDS) List<FieldSpec> fields,
            @ToolParam(required = false, description = TASK) String task,
            @ToolParam(required = false, description = "Zusätzliche Regeln/Kontext für alle Entscheidungen") String instructions,
            @ToolParam(required = false, description = MODE) String mode) {
        if (inputs == null || inputs.isEmpty()) {
            throw new IllegalArgumentException("Keine Eingaben – 'inputs' mit mindestens einem Text übergeben.");
        }
        if (inputs.size() > MAX_BATCH) {
            throw new IllegalArgumentException("Höchstens " + MAX_BATCH + " Eingaben je Aufruf – in mehrere Aufrufe aufteilen.");
        }
        Request r = request(fields, task, instructions, mode);
        Decider decider = new Decider(settings, engines);
        StringBuilder sb = new StringBuilder();
        long start = System.nanoTime();
        int uncertain = 0;
        for (int i = 0; i < inputs.size(); i++) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("index", i);
            try {
                Decider.Outcome o = decider.decide(inputs.get(i), r.fields(), r.instructions(), r.task(), r.mode(), false);
                line.put("decisionId", log.add(r.taskName(), inputs.get(i), r.fields(), o.raw()));
                Map<String, Object> values = new LinkedHashMap<>();
                o.decisions().forEach((name, d) -> {
                    Map<?, ?> m = (Map<?, ?>) d;
                    values.put(name, m.containsKey("values") ? m.get("values") : m.get("value"));
                });
                line.put("values", values);
                line.put("confidence", Calibration.round4(o.confidence()));
                line.put("uncertain", o.uncertain());
                if (o.uncertain()) {
                    uncertain++;
                }
            } catch (IllegalArgumentException e) {
                line.put("error", e.getMessage());
            }
            sb.append(COMPACT.writeValueAsString(line)).append('\n');
        }
        sb.append(inputs.size()).append(" Entscheidungen in ").append((System.nanoTime() - start) / 1_000_000)
                .append(" ms, davon ").append(uncertain).append(" unsicher.");
        return sb.toString();
    }

    @Tool(name = "feedback", description = "Meldet die richtige Antwort zu einer Entscheidung: speichert sie als "
            + "Beispiel der Aufgabe und kalibriert die Wahrscheinlichkeiten je Feld (Temperatur ab "
            + Calibration.MIN_RECORDS + " Rückmeldungen); ähnliche Beispiele gehen außerdem in den Prompt des LLM. "
            + "Mit decisionId aus decide_run/decide_batch; ohne decisionId mit input – dann rechnen beide Modelle "
            + "(langsamer), z.B. um eine Aufgabe mit bekannten Fällen zu trainieren. Braucht eine Aufgabe ('task' oder "
            + "die der Entscheidung); fehlt sie, wird sie mit den Feldern der Entscheidung angelegt.")
    @ToolHints(readOnly = false, destructive = false, openWorld = false)
    public String feedback(
            @ToolParam(required = false, description = "decisionId aus decide_run oder decide_batch") String decisionId,
            @ToolParam(description = "Feldname") String field,
            @ToolParam(description = "Richtige Antwort: Option (choice), Stufe oder Stufennummer ab 0 (score), true/false "
                    + "(boolean), kommagetrennte Optionen (multi, leer = keine)") String value,
            @ToolParam(required = false, description = "Aufgabe, in der gespeichert wird") String task,
            @ToolParam(required = false, description = "Eingabe, wenn es keine decisionId gibt") String input) {
        // Lesen, ergänzen, speichern am Stück – sonst überschreiben sich gleichzeitige Rückmeldungen
        synchronized (store) {
            return feedbackLocked(decisionId, field, value, task, input);
        }
    }

    private String feedbackLocked(String decisionId, String field, String value, String task, String input) {
        DecisionLog.Entry entry = null;
        if (decisionId != null && !decisionId.isBlank()) {
            entry = log.get(decisionId).orElseThrow(() -> new IllegalArgumentException("Entscheidung " + decisionId
                    + " ist unbekannt oder zu alt (es werden die letzten " + DecisionLog.CAPACITY + " gehalten) – mit "
                    + "'input' statt decisionId melden."));
        } else if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("decisionId oder input angeben.");
        }
        String taskName = task != null && !task.isBlank() ? task : entry == null ? null : entry.task();
        if (taskName == null) {
            throw new IllegalArgumentException("Keine Aufgabe – 'task' angeben; sie wird mit den Feldern der Entscheidung "
                    + "angelegt, falls es sie noch nicht gibt.");
        }
        TaskStore.Task t = store.get(taskName).orElse(null);
        if (t == null) {
            if (entry == null) {
                throw new IllegalArgumentException("Aufgabe '" + taskName + "' gibt es nicht – erst mit decide_task_save "
                        + "anlegen oder eine decisionId melden.");
            }
            t = new TaskStore.Task(TaskStore.normalize(taskName), null, null,
                    entry.fields().stream().map(DecisionSchema::spec).toList(), null, null, null);
        }
        List<Field> taskFields = t.validFields();
        Field taskField = taskFields.stream().filter(f -> f.name().equals(field)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Feld '" + field + "' gibt es in der Aufgabe nicht – Felder: "
                        + String.join(", ", taskFields.stream().map(Field::name).toList())));
        String text;
        Field decided;
        Map<String, Map<String, float[]>> raw;
        if (entry != null) {
            text = entry.input();
            decided = entry.fields().stream().filter(f -> f.name().equals(field)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Die Entscheidung " + decisionId + " hat kein Feld '"
                            + field + "'."));
            raw = entry.raw();
        } else {
            text = input;
            decided = taskField;
            // im Modus hybrid beide Modelle rechnen, damit auch das Gewicht der Kombination lernt
            Decider.Outcome o = new Decider(settings, engines).decide(input, List.of(taskField), t.instructions(), t,
                    settings.mode(), settings.mode() == Mode.HYBRID);
            raw = o.raw();
        }
        boolean sameShape = decided.kind() == taskField.kind() && decided.values().equals(taskField.values());

        Object label = normalize(decided, value);
        String now = Instant.now().toString();
        int records = 0;
        for (Decider.Unit u : Decider.units(List.of(decided))) {
            int correct = Decider.canonicalIndex(u, label);
            if (correct < 0) {
                throw new IllegalArgumentException("'" + value + "' ist keine Option von '" + field + "' – erlaubt: "
                        + String.join(", ", decided.kind() == DecisionSchema.Kind.BOOLEAN ? List.of("true", "false")
                        : decided.options()));
            }
            if (!sameShape) {
                continue;
            }
            for (Map.Entry<String, float[]> e : raw.getOrDefault(u.key(), Map.of()).entrySet()) {
                t.feedback().add(new TaskStore.Feedback(u.key(), e.getKey(), e.getValue(), correct, now));
                records++;
            }
        }
        addExample(t, text, field, label);
        TaskStore.Task saved = store.save(t);

        StringBuilder sb = new StringBuilder("Gespeichert in Aufgabe ").append(saved.name()).append(": ").append(field)
                .append(" = ").append(label).append(" (").append(records).append(" Logit-Datensätze).");
        if (!sameShape) {
            sb.append(" Die Optionen des Feldes haben sich seit der Entscheidung geändert – nur als Beispiel gespeichert, "
                    + "nicht für die Kalibrierung.");
        }
        sb.append('\n').append(calibrationSummary(saved, taskField));
        return sb.toString();
    }

    private static Object normalize(Field f, String value) {
        String v = value == null ? "" : value.strip();
        return switch (f.kind()) {
            case BOOLEAN -> {
                Boolean b = Decider.bool(v);
                if (b == null) {
                    throw new IllegalArgumentException("'" + value + "' ist kein Ja/Nein-Wert – true oder false angeben.");
                }
                yield b;
            }
            case MULTI -> {
                List<String> chosen = new ArrayList<>();
                for (String part : v.isEmpty() ? new String[0] : v.split("\\s*,\\s*")) {
                    String opt = f.options().stream().filter(o -> o.equalsIgnoreCase(part)).findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("'" + part + "' ist keine Option von '" + f.name()
                                    + "' – erlaubt: " + String.join(", ", f.options())));
                    chosen.add(opt);
                }
                yield chosen;
            }
            default -> {
                int i = Decider.indexOf(f, v);
                yield i < 0 ? v : f.options().get(i);
            }
        };
    }

    private static void addExample(TaskStore.Task t, String input, String field, Object label) {
        for (int i = 0; i < t.examples().size(); i++) {
            TaskStore.Example e = t.examples().get(i);
            if (input.equals(e.input())) {
                Map<String, Object> labels = new LinkedHashMap<>(e.labels() == null ? Map.of() : e.labels());
                labels.put(field, label);
                t.examples().set(i, new TaskStore.Example(input, labels, "user", e.created()));
                return;
            }
        }
        t.examples().add(new TaskStore.Example(input, new LinkedHashMap<>(Map.of(field, label)), "user",
                Instant.now().toString()));
    }

    /** Je Einzelfrage des Feldes und Modell: Anzahl Rückmeldungen, Trefferquote, Temperatur. */
    static String calibrationSummary(TaskStore.Task t, Field f) {
        StringBuilder sb = new StringBuilder("Kalibrierung ").append(f.name()).append(':');
        for (Decider.Unit u : Decider.units(List.of(f))) {
            for (String engine : List.of(Decider.LAYA, Decider.LLM)) {
                List<Calibration.Sample> s = t.samples(u.key(), engine);
                if (s.isEmpty()) {
                    continue;
                }
                double temp = Calibration.fit(s);
                sb.append("\n- ").append(u.key()).append(" / ").append(engine).append(": ").append(s.size())
                        .append(" Rückmeldungen, Treffer ").append(Math.round(Calibration.accuracy(s) * 100)).append(" %, ")
                        .append(Double.isNaN(temp) ? "Temperatur ab " + Calibration.MIN_RECORDS + " Rückmeldungen"
                                : "Temperatur " + String.format(java.util.Locale.ROOT, "%.2f", temp));
            }
            if (t.pairs(u.key()).size() >= Calibration.MIN_RECORDS) {
                sb.append("\n- ").append(u.key()).append(": Gewicht des LLM in der Kombination ")
                        .append(String.format(java.util.Locale.ROOT, "%.2f", Decider.weight(t, u.key())));
            }
        }
        return sb.toString();
    }

    @Tool(name = "tasks", description = "Gespeicherte Entscheidungsaufgaben: ohne name die Liste (Felder, Anzahl "
            + "Beispiele/Rückmeldungen), mit name die Aufgabe mit Feldern, Hinweisen und Kalibrierung je Feld.")
    @ToolHints(readOnly = true, openWorld = false)
    public String tasks(@ToolParam(required = false, description = "Name der Aufgabe") String name) {
        if (name == null || name.isBlank()) {
            List<TaskStore.Task> all = store.list();
            if (all.isEmpty()) {
                return "Keine Aufgaben gespeichert. Anlegen mit decide_task_save oder über decide_feedback mit 'task'.";
            }
            StringBuilder sb = new StringBuilder();
            for (TaskStore.Task t : all) {
                sb.append(t.name()).append(" – ").append(t.description() == null ? "" : t.description() + " – ")
                        .append(t.fields().stream().map(f -> f.name() + ":" + (f.type() == null ? "choice" : f.type())).toList())
                        .append(", ").append(t.examples().size()).append(" Beispiele, ").append(t.feedback().size())
                        .append(" Rückmeldungen\n");
            }
            return sb.toString().strip();
        }
        TaskStore.Task t = store.require(name);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", t.name());
        out.put("description", t.description());
        out.put("instructions", t.instructions());
        out.put("fields", t.fields());
        out.put("examples", t.examples().size());
        out.put("updated", t.updated());
        StringBuilder cal = new StringBuilder();
        for (Field f : t.validFields()) {
            cal.append(calibrationSummary(t, f)).append('\n');
        }
        return PRETTY.writeValueAsString(out) + "\n" + cal.toString().strip();
    }

    @Tool(name = "task_save", description = "Legt eine Entscheidungsaufgabe an oder ändert sie: Name, Felder, "
            + "Beschreibung und Hinweise. decide_run/decide_batch nehmen sie über 'task'. Beispiele und Rückmeldungen "
            + "bleiben erhalten; Rückmeldungen zu Feldern mit geänderten Optionen werden verworfen.")
    @ToolHints(readOnly = false, destructive = false, idempotent = true, openWorld = false)
    public String taskSave(
            @ToolParam(description = "Name (Kleinbuchstaben, Ziffern, - und _), z.B. ticket-triage") String name,
            @ToolParam(description = "Felder der Entscheidung (wie bei decide_run)") List<FieldSpec> fields,
            @ToolParam(required = false, description = "Wofür die Aufgabe da ist") String description,
            @ToolParam(required = false, description = "Regeln/Kontext, die bei jeder Entscheidung gelten") String instructions) {
        List<Field> valid = DecisionSchema.validate(fields);
        List<FieldSpec> specs = valid.stream().map(DecisionSchema::spec).toList();
        TaskStore.Task old = store.get(name).orElse(null);
        List<TaskStore.Example> examples = old == null ? List.of() : old.examples();
        List<TaskStore.Feedback> feedback = new ArrayList<>();
        int dropped = 0;
        if (old != null) {
            Map<String, Integer> sizes = new LinkedHashMap<>();
            for (Decider.Unit u : Decider.units(valid)) {
                sizes.put(u.key(), u.size());
            }
            for (TaskStore.Feedback f : old.feedback()) {
                Integer size = sizes.get(f.key());
                if (size != null && f.logits() != null && f.logits().length == size && sameOptions(old, valid, f.key())) {
                    feedback.add(f);
                } else {
                    dropped++;
                }
            }
        }
        TaskStore.Task saved = store.save(new TaskStore.Task(name, blankToNull(description), blankToNull(instructions), specs,
                examples, feedback, null));
        return (old == null ? "Aufgabe angelegt: " : "Aufgabe geändert: ") + saved.name() + " mit " + valid.size() + " Feld(ern)"
                + (dropped > 0 ? "; " + dropped + " Rückmeldungen zu geänderten Feldern verworfen" : "") + ".";
    }

    private static boolean sameOptions(TaskStore.Task old, List<Field> now, String key) {
        String name = key.contains("/") ? key.substring(0, key.indexOf('/')) : key;
        Field before = old.validFields().stream().filter(f -> f.name().equals(name)).findFirst().orElse(null);
        Field after = now.stream().filter(f -> f.name().equals(name)).findFirst().orElse(null);
        return before != null && after != null && before.kind() == after.kind() && before.values().equals(after.values());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    @Tool(name = "status", description = "Zustand des Moduls: Modus, Schwelle, Modelle (vorhanden, Pfad, geladen) und "
            + "Rechen-Threads.")
    @ToolHints(readOnly = true, openWorld = false)
    public String status() {
        StringBuilder sb = new StringBuilder();
        sb.append("Modus: ").append(settings.mode().id()).append(", Schwelle ").append(Math.round(settings.threshold() * 100))
                .append(" %\n");
        sb.append("Laya (").append(settings.laya().id()).append("): ")
                .append(ModelFiles.layaPresent(settings) ? "vorhanden" : "fehlt").append(" – ").append(ModelFiles.layaDir(settings))
                .append('\n');
        sb.append("LLM: ").append(ModelFiles.llmPresent(settings) ? "vorhanden" : "fehlt").append(" – ")
                .append(ModelFiles.llmPath(settings)).append('\n');
        sb.append(engines.describe()).append('\n');
        sb.append("Rechenkerne: ").append(systems.grebe.devtools.mcp.modules.decide.engine.Kernels.simd()).append('\n');
        sb.append("Automatischer Download: ").append(settings.autoDownload() ? "an" : "aus");
        return sb.toString();
    }

    private record Request(List<Field> fields, String instructions, TaskStore.Task task, String taskName, Mode mode) {
    }

    private Request request(List<FieldSpec> fields, String task, String instructions, String mode) {
        TaskStore.Task t = task == null || task.isBlank() ? null : store.require(task);
        List<Field> f = fields != null && !fields.isEmpty() ? DecisionSchema.validate(fields)
                : t != null ? t.validFields() : DecisionSchema.validate(fields);
        String ins = joinInstructions(t == null ? null : t.instructions(), instructions);
        Mode m = mode == null || mode.isBlank() ? settings.mode() : Mode.parse(mode);
        return new Request(f, ins, t, t == null ? null : t.name(), m);
    }

    private static String joinInstructions(String a, String b) {
        boolean ha = a != null && !a.isBlank();
        boolean hb = b != null && !b.isBlank();
        return ha && hb ? a.strip() + "\n" + b.strip() : ha ? a.strip() : hb ? b.strip() : null;
    }
}
