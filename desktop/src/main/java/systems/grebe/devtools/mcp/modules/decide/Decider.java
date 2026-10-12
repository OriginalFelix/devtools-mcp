package systems.grebe.devtools.mcp.modules.decide;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import systems.grebe.devtools.mcp.modules.decide.DecideSettings.Mode;
import systems.grebe.devtools.mcp.modules.decide.DecisionSchema.Field;
import systems.grebe.devtools.mcp.modules.decide.DecisionSchema.Kind;
import systems.grebe.devtools.mcp.modules.decide.engine.BpeTokenizer;
import systems.grebe.devtools.mcp.modules.decide.engine.LayaModel;

/**
 * Trifft typisierte Entscheidungen: jedes Feld wird zu einer oder mehreren Einzelfragen mit festen Optionen. Laya
 * rechnet alle Fragen in einem Durchlauf; ist eine Antwort unsicherer als die Schwelle (Modus hybrid), bewertet das
 * LLM die Optionen dieser Frage – es generiert nichts, sondern vergleicht die Logits der Antwort-Tokens. Ergebnis ist
 * immer ein Wert aus dem Schema mit Wahrscheinlichkeiten.
 *
 * <p>Formulierungen nach Messungen mit laya-multilingual und Qwen2.5-0.5B: Laya bekommt die Frage bzw. Aussage des
 * Nutzers unverändert, Optionen von multi als englische Aussage („The text is about X.“). Das LLM bekommt englische
 * Anweisungen; Ja/Nein-Fragen vergleichen die Tokens „Yes“/„No“, Auswahlfragen die Buchstaben – gemittelt über
 * zyklisch vertauschte Reihenfolgen, weil kleine Modelle sonst vor allem die Position (meist A) wählen.
 */
final class Decider {

    static final String LAYA = "laya";
    static final String LLM = "llm";
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final int MAX_LETTERS = 26;
    private static final int DEMO_CHARS = 300;
    /** Höchstens so viele vertauschte Reihenfolgen je Auswahlfrage (bei mehr Optionen gleichmäßig verteilt). */
    private static final int MAX_SHIFTS = 6;

    /**
     * Eine Einzelfrage: ein Feld oder bei multi eine Option davon.
     *
     * @param llmOptions Optionen für das LLM in kanonischer Reihenfolge; leer = Ja/Nein-Frage ({@code llmQuestion})
     */
    record Unit(String key, Field field, String option, LayaModel.QType qtype, String layaInstructions,
                List<String> layaOptions, String llmQuestion, List<String> llmOptions) {

        int size() {
            return layaOptions.size();
        }

        boolean yesNo() {
            return llmOptions.isEmpty();
        }
    }

    /** Ergebnis einer Einzelfrage, Wahrscheinlichkeiten in kanonischer Reihenfolge ({@link Field#values()}). */
    record UnitResult(double[] p, String engine, boolean calibrated, double[] laya, double[] llm, float[] layaLogits,
                      float[] llmLogits) {
    }

    /** Ergebnis eines Aufrufs. */
    record Outcome(Map<String, Object> decisions, Map<String, Map<String, float[]>> raw, List<String> notes, long millis,
                   double confidence, boolean uncertain) {
    }

    private final DecideSettings settings;
    private final DecideEngines engines;

    Decider(DecideSettings settings, DecideEngines engines) {
        this.settings = settings;
        this.engines = engines;
    }

    /**
     * @param task       gespeicherte Aufgabe (Kalibrierung, Beispiele) oder {@code null}
     * @param mode       Modus dieses Aufrufs
     * @param both       beide Modelle rechnen (für Rückmeldungen ohne vorherige Entscheidung)
     */
    Outcome decide(String input, List<Field> fields, String instructions, TaskStore.Task task, Mode mode, boolean both) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("Keine Eingabe – 'input' mit dem Text bzw. Zustand übergeben, über den "
                    + "entschieden werden soll.");
        }
        long start = System.nanoTime();
        List<String> notes = new ArrayList<>();
        List<Unit> units = units(fields);
        Map<String, UnitResult> results = new LinkedHashMap<>();
        Map<String, float[]> layaLogits = new LinkedHashMap<>();
        Map<String, double[]> layaP = new LinkedHashMap<>();
        Map<String, Boolean> layaCalibrated = new LinkedHashMap<>();

        boolean useLaya = settings.usesLaya(mode) || both;
        boolean useLlm = settings.usesLlm(mode) || both;
        if (useLaya) {
            String state = input.strip();
            String full = instructions == null || instructions.isBlank() ? state : state + "\n\n" + instructions.strip();
            LayaModel.Result r = engines.withLaya(settings, m -> {
                List<LayaModel.Question> qs = units.stream()
                        .map(u -> new LayaModel.Question(u.qtype(), u.layaInstructions(), u.layaOptions())).toList();
                LayaModel.Result res = m.predict(qs, full);
                for (int i = 0; i < units.size(); i++) {
                    Unit u = units.get(i);
                    float[] logits = res.logits().get(i);
                    double t = temperature(task, u.key(), LAYA);
                    layaCalibrated.put(u.key(), !Double.isNaN(t));
                    if (Double.isNaN(t)) {
                        t = m.temperature(u.qtype(), logits.length);
                    }
                    layaLogits.put(u.key(), logits);
                    layaP.put(u.key(), Calibration.softmax(logits, t));
                }
                return res;
            });
            if (r.stateTokensDropped() > 0) {
                notes.add("Eingabe für Laya gekürzt: " + r.stateTokensDropped() + " von " + r.stateTokens()
                        + " Tokens passten nicht in den Kontext (" + settings.laya().id() + ").");
            }
        }

        List<Unit> forLlm = new ArrayList<>();
        for (Unit u : units) {
            double[] p = layaP.get(u.key());
            boolean escalate = useLlm && (p == null || both || mode == Mode.LLM || max(p) < settings.threshold());
            if (escalate) {
                forLlm.add(u);
                if (p != null && mode == Mode.HYBRID && !both) {
                    notes.add(u.key() + ": Laya unsicher (" + pct(max(p)) + " < " + pct(settings.threshold())
                            + ") – mit dem LLM kombiniert.");
                }
            }
        }
        Map<String, float[]> llmLogits = Map.of();
        if (!forLlm.isEmpty()) {
            try {
                llmLogits = llm(input, instructions, forLlm, task, notes);
            } catch (IllegalStateException e) {
                if (mode == Mode.LLM || layaP.isEmpty()) {
                    throw e;
                }
                // Hybrid ohne LLM (fehlt, nicht ladbar): Laya-Ergebnis bleibt, mit Hinweis
                notes.add("LLM nicht verfügbar, nur Laya: " + e.getMessage());
            }
        }

        Map<String, Map<String, float[]>> raw = new LinkedHashMap<>();
        for (Unit u : units) {
            float[] ll = llmLogits.get(u.key());
            double[] pl = layaP.get(u.key());
            double[] pm = null;
            boolean llmCalibrated = false;
            if (ll != null) {
                double t = temperature(task, u.key(), LLM);
                llmCalibrated = !Double.isNaN(t);
                pm = Calibration.softmax(ll, Double.isNaN(t) ? 1.0 : t);
            }
            boolean layaCal = Boolean.TRUE.equals(layaCalibrated.get(u.key()));
            double[] p;
            String engine;
            boolean calibrated;
            if (pm != null && (pl == null || mode == Mode.LLM)) {
                p = pm;
                engine = LLM;
                calibrated = llmCalibrated;
            } else if (pm != null && max(pl) < settings.threshold()) {
                // Laya unsicher: nicht ersetzen, sondern gewichtet kombinieren – das Gewicht lernt die Aufgabe
                p = Calibration.combine(pl, pm, weight(task, u.key()));
                engine = LAYA + "+" + LLM;
                calibrated = layaCal && llmCalibrated;
            } else {
                p = pl;
                engine = LAYA;
                calibrated = layaCal;
            }
            results.put(u.key(), new UnitResult(p, engine, calibrated, pl, pm, layaLogits.get(u.key()), ll));
            Map<String, float[]> r = new LinkedHashMap<>();
            if (layaLogits.containsKey(u.key())) {
                r.put(LAYA, layaLogits.get(u.key()));
            }
            if (ll != null) {
                r.put(LLM, ll);
            }
            raw.put(u.key(), r);
        }

        Map<String, Object> decisions = new LinkedHashMap<>();
        double minConfidence = 1.0;
        for (Field f : fields) {
            Map<String, Object> out = assemble(f, units, results);
            decisions.put(f.name(), out);
            minConfidence = Math.min(minConfidence, (double) out.get("confidence"));
        }
        long millis = (System.nanoTime() - start) / 1_000_000;
        return new Outcome(decisions, raw, notes, millis, minConfidence, minConfidence < settings.threshold());
    }

    private static double temperature(TaskStore.Task task, String key, String engine) {
        return task == null ? Double.NaN : Calibration.fit(task.samples(key, engine));
    }

    /** Gewicht des LLM in der Kombination: aus Rückmeldungen mit beiden Modellen gelernt, sonst 0,5. */
    static double weight(TaskStore.Task task, String key) {
        if (task == null) {
            return 0.5;
        }
        double tl = Calibration.fit(task.samples(key, LAYA));
        double tm = Calibration.fit(task.samples(key, LLM));
        double w = Calibration.fitWeight(task.pairs(key), Double.isNaN(tl) ? 1 : tl, Double.isNaN(tm) ? 1 : tm);
        return Double.isNaN(w) ? 0.5 : w;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Einzelfragen

    static List<Unit> units(List<Field> fields) {
        List<Unit> out = new ArrayList<>();
        for (Field f : fields) {
            switch (f.kind()) {
                case CHOICE -> {
                    List<String> llm = new ArrayList<>();
                    for (String o : f.options()) {
                        llm.add(f.description(o) == null ? o : o + " – " + f.description(o));
                    }
                    out.add(new Unit(f.name(), f, null, LayaModel.QType.CHOICE, f.question(),
                            LayaModel.renderChoice(f.options(), f.descriptions()), f.question(), llm));
                }
                case SCORE -> {
                    List<String> levels = new ArrayList<>();
                    List<String> llm = new ArrayList<>();
                    for (String o : f.options()) {
                        levels.add(f.description(o) == null ? o : o + ": " + f.description(o));
                        llm.add(f.description(o) == null ? o : o + " – " + f.description(o));
                    }
                    out.add(new Unit(f.name(), f, null, LayaModel.QType.SCORE, f.question(), LayaModel.renderScore(levels),
                            f.question() + " (levels from low to high)", llm));
                }
                case BOOLEAN -> out.add(new Unit(f.name(), f, null, LayaModel.QType.NOUL, f.question(),
                        LayaModel.renderNoul(f.description("false"), f.description("true")),
                        "Is the following true according to the input: " + f.question()
                                + (f.description("true") == null ? "" : " (" + f.description("true") + ")"), List.of()));
                case MULTI -> {
                    for (String o : f.options()) {
                        String desc = f.description(o);
                        out.add(new Unit(f.name() + "/" + o, f, o, LayaModel.QType.NOUL,
                                "The text is about " + o + (desc == null ? "." : ": " + desc + "."),
                                LayaModel.renderNoul(null, null),
                                "Is the input about \"" + o + "\"" + (desc == null ? "" : " (" + desc + ")") + "?", List.of()));
                    }
                }
            }
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // LLM

    private Map<String, float[]> llm(String input, String instructions, List<Unit> units, TaskStore.Task task,
                                     List<String> notes) {
        for (Unit u : units) {
            if (u.llmOptions().size() > MAX_LETTERS) {
                throw new IllegalArgumentException("Feld '" + u.field().name() + "' hat mehr als " + MAX_LETTERS
                        + " Optionen – das LLM kann nur bis Z antworten. Modus 'laya' verwenden oder Optionen aufteilen.");
            }
        }
        return engines.withLlm(settings, ctx -> {
            BpeTokenizer tok = ctx.model().tokenizer();
            ChatFormat chat = ChatFormat.of(tok);
            int[] letters = new int[MAX_LETTERS];
            for (int i = 0; i < MAX_LETTERS; i++) {
                letters[i] = singleToken(tok, String.valueOf((char) ('A' + i)));
            }
            int[] noYes = {singleToken(tok, "No"), singleToken(tok, "Yes")};
            String text = input.strip();
            int limit = ctx.model().contextLength() - 16;
            Map<String, float[]> out = new LinkedHashMap<>();
            for (Unit u : units) {
                int k = u.size();
                double[] p = new double[k];
                int[] shifts = u.yesNo() ? new int[] {0} : shifts(k);
                for (int shift : shifts) {
                    int[] order = new int[k];
                    for (int i = 0; i < k; i++) {
                        order[i] = (i + shift) % k;
                    }
                    int[] prompt = null;
                    for (int attempt = 0; attempt < 8; attempt++) {
                        prompt = tok.encode(chat.prompt(SYSTEM, prefix(text, instructions), suffix(u, task, text, order)));
                        if (prompt.length <= limit) {
                            break;
                        }
                        String shorter = text.substring(0, (int) (text.length() * Math.min(0.9, (double) limit / prompt.length)));
                        notes.add(u.key() + ": Eingabe für das LLM auf " + shorter.length() + " von " + input.strip().length()
                                + " Zeichen gekürzt.");
                        text = shorter;
                    }
                    if (prompt.length > limit) {
                        throw new IllegalArgumentException("Frage '" + u.key() + "' passt nicht in den Kontext des LLM.");
                    }
                    if (u.yesNo()) {
                        // kanonisch [false, true] = [No, Yes]
                        double[] q = Calibration.softmax(ctx.session().feed(prompt, noYes), 1);
                        p[0] = q[0];
                        p[1] = q[1];
                    } else {
                        double[] q = Calibration.softmax(ctx.session().feed(prompt, java.util.Arrays.copyOf(letters, k)), 1);
                        for (int i = 0; i < k; i++) {
                            p[order[i]] += q[i] / shifts.length;
                        }
                    }
                }
                // Log-Wahrscheinlichkeiten als „Logits“: softmax(log p) = p, Temperatur-Kalibrierung wie bei Laya
                float[] logits = new float[k];
                for (int i = 0; i < k; i++) {
                    logits[i] = (float) Math.log(Math.max(1e-9, p[i]));
                }
                out.put(u.key(), logits);
            }
            return out;
        });
    }

    private static int singleToken(BpeTokenizer tok, String s) {
        int[] ids = tok.encode(s);
        if (ids.length != 1) {
            throw new IllegalStateException("Antwort-Token '" + s + "' ist im LLM kein einzelnes Token");
        }
        return ids[0];
    }

    /** Verschiebungen der Optionsreihenfolge: alle bei wenigen Optionen, sonst {@link #MAX_SHIFTS} gleichmäßig verteilt. */
    static int[] shifts(int k) {
        int n = Math.min(k, MAX_SHIFTS);
        int[] r = new int[n];
        for (int i = 0; i < n; i++) {
            r[i] = (int) Math.round((double) i * k / n);
        }
        return r;
    }

    static final String SYSTEM = "You are a precise decision module. You read an input and answer one question about it "
            + "by choosing exactly one of the given options. Answer with the option only.";

    /** Gemeinsamer Anfang aller Fragen eines Aufrufs – bleibt im KV-Cache. */
    static String prefix(String input, String instructions) {
        StringBuilder sb = new StringBuilder("Input:\n\"\"\"\n").append(input).append("\n\"\"\"");
        if (instructions != null && !instructions.isBlank()) {
            sb.append("\n\nNotes for the decision:\n").append(instructions.strip());
        }
        return sb.toString();
    }

    /**
     * Frage mit Optionen in der Reihenfolge {@code order} (Buchstabe i = Option {@code order[i]}); bei Ja/Nein-Fragen
     * ohne Optionen.
     */
    static String suffix(Unit u, TaskStore.Task task, String input, int[] order) {
        StringBuilder sb = new StringBuilder();
        List<String> demos = demos(u, task, input);
        if (!demos.isEmpty()) {
            sb.append("\n\nSimilar cases that were already decided:");
            demos.forEach(d -> sb.append("\n- ").append(d));
        }
        if (u.yesNo()) {
            sb.append("\n\n").append(u.llmQuestion()).append(" Answer Yes or No.");
            return sb.toString();
        }
        sb.append("\n\nQuestion: ").append(u.llmQuestion()).append("\nOptions:");
        for (int i = 0; i < order.length; i++) {
            sb.append('\n').append((char) ('A' + i)).append(") ").append(u.llmOptions().get(order[i]));
        }
        char lastLetter = (char) ('A' + order.length - 1);
        sb.append("\n\nAnswer with a single letter (A–").append(lastLetter).append(").");
        return sb.toString();
    }

    /** Bis zu drei ähnliche Beispiele mit Antwort aus Rückmeldungen (Wortüberlappung). */
    static List<String> demos(Unit u, TaskStore.Task task, String input) {
        if (task == null || task.examples().isEmpty()) {
            return List.of();
        }
        Set<String> words = words(input);
        record Scored(double score, String text) {
        }
        List<Scored> scored = new ArrayList<>();
        for (TaskStore.Example e : task.examples()) {
            if (!"user".equals(e.source()) || e.input() == null || e.input().strip().equals(input.strip())) {
                continue;
            }
            Object label = e.labels().get(u.field().name());
            int correct = label == null ? -1 : canonicalIndex(u, label);
            if (correct < 0) {
                continue;
            }
            Set<String> other = words(e.input());
            Set<String> inter = new HashSet<>(words);
            inter.retainAll(other);
            Set<String> union = new HashSet<>(words);
            union.addAll(other);
            double sim = union.isEmpty() ? 0 : (double) inter.size() / union.size();
            if (sim > 0.05) {
                String text = e.input().strip().replaceAll("\\s+", " ");
                if (text.length() > DEMO_CHARS) {
                    text = text.substring(0, DEMO_CHARS) + "…";
                }
                String answer = u.yesNo() ? (correct == 1 ? "Yes" : "No") : u.field().options().get(correct);
                scored.add(new Scored(sim, "\"" + text + "\" → " + answer));
            }
        }
        return scored.stream().sorted(Comparator.comparingDouble(Scored::score).reversed()).limit(3)
                .map(Scored::text).toList();
    }

    /** Index der richtigen Option (kanonische Reihenfolge) einer Einzelfrage für ein Label, −1 wenn unpassend. */
    static int canonicalIndex(Unit u, Object label) {
        return switch (u.field().kind()) {
            case CHOICE, SCORE -> indexOf(u.field(), label);
            case BOOLEAN -> {
                Boolean b = bool(label);
                yield b == null ? -1 : (b ? 1 : 0);
            }
            case MULTI -> {
                List<String> values = label instanceof List<?> l ? l.stream().map(String::valueOf).toList()
                        : List.of(String.valueOf(label).split("\\s*,\\s*"));
                yield values.stream().anyMatch(v -> v.equalsIgnoreCase(u.option())) ? 1 : 0;
            }
        };
    }

    static int indexOf(Field f, Object label) {
        String s = String.valueOf(label).strip();
        for (int i = 0; i < f.options().size(); i++) {
            if (f.options().get(i).equalsIgnoreCase(s)) {
                return i;
            }
        }
        if (f.kind() == Kind.SCORE && s.matches("\\d+")) {
            int level = Integer.parseInt(s);
            return level < f.options().size() ? level : -1;
        }
        return -1;
    }

    static Boolean bool(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        return switch (String.valueOf(v).strip().toLowerCase(Locale.ROOT)) {
            case "true", "ja", "yes", "1", "wahr", "j", "y" -> true;
            case "false", "nein", "no", "0", "falsch", "n" -> false;
            default -> null;
        };
    }

    private static Set<String> words(String s) {
        Set<String> out = new HashSet<>();
        Matcher m = WORD.matcher(s.toLowerCase(Locale.ROOT));
        while (m.find()) {
            if (m.group().length() > 2) {
                out.add(m.group());
            }
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Ergebnis

    private Map<String, Object> assemble(Field f, List<Unit> units, Map<String, UnitResult> results) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", f.kind().id());
        if (f.kind() == Kind.MULTI) {
            List<String> chosen = new ArrayList<>();
            Map<String, Object> probs = new LinkedHashMap<>();
            Map<String, Object> laya = new LinkedHashMap<>();
            Map<String, Object> llm = new LinkedHashMap<>();
            Set<String> engineSet = new java.util.TreeSet<>();
            double confidence = 1;
            boolean calibrated = true;
            for (Unit u : units) {
                if (u.field() != f) {
                    continue;
                }
                UnitResult r = results.get(u.key());
                double pTrue = r.p()[1];
                if (pTrue >= 0.5) {
                    chosen.add(u.option());
                }
                probs.put(u.option(), Calibration.round4(pTrue));
                if (r.laya() != null && !LAYA.equals(r.engine())) {
                    laya.put(u.option(), Calibration.round4(r.laya()[1]));
                }
                if (r.llm() != null && !LLM.equals(r.engine())) {
                    llm.put(u.option(), Calibration.round4(r.llm()[1]));
                }
                confidence = Math.min(confidence, Math.max(pTrue, 1 - pTrue));
                engineSet.addAll(List.of(r.engine().split("\\+")));
                calibrated &= r.calibrated();
            }
            out.put("values", chosen);
            out.put("probabilities", probs);
            out.put("confidence", Calibration.round4(confidence));
            out.put("engine", String.join("+", engineSet));
            out.put("calibrated", calibrated);
            if (!laya.isEmpty()) {
                out.put("laya", laya);
            }
            if (!llm.isEmpty()) {
                out.put("llm", llm);
            }
            return out;
        }
        Unit u = units.stream().filter(x -> x.field() == f).findFirst().orElseThrow();
        UnitResult r = results.get(u.key());
        double[] p = r.p();
        int best = Calibration.argmax(p);
        switch (f.kind()) {
            case BOOLEAN -> {
                out.put("value", p[1] >= 0.5);
                out.put("probability", Calibration.round4(p[1]));
            }
            case SCORE -> {
                double expected = 0;
                for (int i = 0; i < p.length; i++) {
                    expected += i * p[i];
                }
                out.put("value", f.options().get(best));
                out.put("level", best);
                out.put("expected", Calibration.round4(expected));
                out.put("probabilities", probabilities(f.options(), p));
            }
            default -> {
                out.put("value", f.options().get(best));
                out.put("probabilities", probabilities(f.options(), p));
            }
        }
        out.put("confidence", Calibration.round4(p[best]));
        out.put("certainty", Calibration.round4(Calibration.certainty(p)));
        out.put("engine", r.engine());
        out.put("calibrated", r.calibrated());
        if (r.laya() != null && !LAYA.equals(r.engine())) {
            out.put("laya", f.kind() == Kind.BOOLEAN ? (Object) Calibration.round4(r.laya()[1])
                    : probabilities(f.options(), r.laya()));
        }
        if (r.llm() != null && !LLM.equals(r.engine())) {
            out.put("llm", f.kind() == Kind.BOOLEAN ? (Object) Calibration.round4(r.llm()[1])
                    : probabilities(f.options(), r.llm()));
        }
        return out;
    }

    private static Map<String, Object> probabilities(List<String> options, double[] p) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < options.size(); i++) {
            m.put(options.get(i), Calibration.round4(p[i]));
        }
        return m;
    }

    private static double max(double[] p) {
        double m = 0;
        for (double v : p) {
            m = Math.max(m, v);
        }
        return m;
    }

    private static String pct(double v) {
        return Math.round(v * 100) + " %";
    }

    /** Chat-Vorlage des LLM, erkannt an seinen Sondertokens. */
    record ChatFormat(String system, String user, String assistant) {

        static ChatFormat of(BpeTokenizer tok) {
            if (tok.id("<|im_start|>") >= 0) {
                return new ChatFormat("<|im_start|>system\n%s<|im_end|>\n", "<|im_start|>user\n%s<|im_end|>\n",
                        "<|im_start|>assistant\n");
            }
            if (tok.id("<|start_header_id|>") >= 0) {
                return new ChatFormat("<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\n%s<|eot_id|>",
                        "<|start_header_id|>user<|end_header_id|>\n\n%s<|eot_id|>",
                        "<|start_header_id|>assistant<|end_header_id|>\n\n");
            }
            return new ChatFormat("%s\n\n", "%s\n\n", "Antwort: ");
        }

        String prompt(String sys, String prefix, String suffix) {
            // replace statt format: die Eingabe darf %-Zeichen enthalten
            return system.replace("%s", sys) + user.replace("%s", prefix + suffix) + assistant;
        }
    }
}
