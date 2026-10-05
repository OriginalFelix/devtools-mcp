package systems.grebe.devtools.mcp.modules.web;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import com.github.tjake.jlama.model.AbstractModel;
import com.github.tjake.jlama.safetensors.DType;
import com.github.tjake.jlama.safetensors.SafeTensorSupport;
import com.github.tjake.jlama.safetensors.prompt.PromptContext;
import com.github.tjake.jlama.safetensors.prompt.PromptSupport;
import com.github.tjake.jlama.util.PhysicalCoreExecutor;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Eigener JVM-Prozess für das lokale LLM (Jlama). Jlama rechnet mit der Vector API, einem Inkubator-Modul, das nur mit
 * {@code --add-modules jdk.incubator.vector} beim JVM-Start verfügbar ist – deshalb startet {@link LocalLlm} diesen
 * Prozess mit den passenden Optionen, statt Jlama in der App selbst zu laden. Nebenbei liegt das Modell (~1 GB) so
 * nicht im Heap der App und wird nach Leerlauf mit dem Prozess freigegeben.
 *
 * <p>Jede Anfrage hat ein Zeitbudget. Der Worker misst, wie schnell das Modell Eingabe verarbeitet (Prefill) und Tokens
 * erzeugt, lässt vor dem Start so viele der am schwächsten bewerteten Textstücke weg, bis die Anfrage voraussichtlich
 * ins Budget passt, und bricht die Generierung bei Ablauf an einer Satzgrenze ab. Ebenso bei Wiederholungsschleifen.
 *
 * <p>Protokoll über stdin/stdout, je Zeile ein JSON-Objekt. Anfrage: {@code {"system", "header", "segments":
 * [{"text","score"}], "instruction", "maxTokens", "budgetMs"}}; die Nachricht an das Modell ist Kopf, die Textstücke in
 * {@code <page>} und die Anweisung. Antworten: {@code {"type":"progress","message"}} (beliebig oft), dann
 * {@code {"type":"ready","model"}} nach dem Laden bzw. je Anfrage {@code {"type":"result","text","promptTokens",
 * "generatedTokens","segments","dropped","finish","millis"}} oder {@code {"type":"error","message"}}. Logausgaben gehen
 * nach stderr. Endet, wenn stdin geschlossen wird.
 *
 * <p>Argumente: Modell ({@code owner/name} auf Hugging Face), Modellverzeichnis, Threads (0 = Jlama-Standard),
 * optimiert ({@code true} = Llama-Modelle mit {@link FastLlamaModel}).
 */
public final class LlmWorker {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Gewicht neuer Messungen in den gleitenden Raten. */
    private static final double ALPHA = 0.5;
    /** So viele Tokens Eingabe bleiben mindestens, auch wenn das Budget knapp ist. */
    private static final int MIN_PROMPT_TOKENS = 150;

    private final PrintStream out;
    /**
     * Arbeitsverzeichnis für Modelltypen, deren KV-Cache Jlama als Dateien ablegt (nicht Llama); erst bei Bedarf
     * angelegt, nach jeder Anfrage geleert und am Ende gelöscht.
     */
    private Path scratch;
    /** Gemessene Millisekunden je Eingabe- bzw. erzeugtem Token; Startwerte vorsichtig, nach dem Aufwärmen gemessen. */
    private double prefillMs = 12;
    private double generateMs = 40;

    private LlmWorker(PrintStream out) {
        this.out = out;
    }

    /** Textstück der Seite mit Relevanz; bei Zeitnot fallen die schwächsten zuerst weg. */
    private record Segment(int index, String text, double score, int tokens) {
    }

    public static void main(String[] args) throws Exception {
        // stdout gehört dem Protokoll; alles andere (auch Logback, das sich System.out beim Start greift) nach stderr
        PrintStream protocol = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        System.setOut(System.err);
        quietLogging();
        LlmWorker worker = new LlmWorker(protocol);
        if (args.length < 2) {
            worker.error("Aufruf: LlmWorker <owner/modell> <modellverzeichnis> [threads]");
            System.exit(2);
        }
        int threads = args.length > 2 ? Integer.parseInt(args[2]) : 0;
        boolean optimized = args.length <= 3 || Boolean.parseBoolean(args[3]);
        AbstractModel model;
        try {
            model = worker.load(args[0], Path.of(args[1]), threads, optimized);
        } catch (Throwable t) {
            worker.error("Modell " + args[0] + " nicht ladbar: " + message(t));
            worker.deleteScratch();
            System.exit(3);
            return;
        }
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            try {
                worker.generate(model, JSON.readTree(line));
            } catch (Throwable t) {
                worker.error(message(t));
            }
        }
        model.close();
        worker.deleteScratch();
        System.exit(0);
    }

    private AbstractModel load(String name, Path dir, int threads, boolean optimized) throws Exception {
        if (threads > 0) {
            PhysicalCoreExecutor.overrideThreadCount(Math.min(threads, Runtime.getRuntime().availableProcessors()));
        }
        Files.createDirectories(dir);
        long[] lastReport = {0};
        File modelDir = SafeTensorSupport.maybeDownloadModel(dir.toString(), name, (file, done, total) -> {
            long now = System.currentTimeMillis();
            if (now - lastReport[0] >= 1000 || done >= total) {
                lastReport[0] = now;
                progress("Lade Modell " + name + " herunter: " + file + " " + mb(done)
                        + (total > 0 ? " / " + mb(total) : ""));
            }
        });
        progress("Lade Modell " + name + " in den Speicher …");
        AbstractModel model = FastLlamaModel.load(modelDir, this::scratch, DType.F32, DType.I8, optimized);
        if (model.promptSupport().isEmpty()) {
            throw new IllegalStateException("Modell hat kein Chat-Template – nur Instruct-/Chat-Modelle sind geeignet");
        }
        // Aufwärmen: JIT und Puffer, damit die erste echte Anfrage nicht langsamer ist; liefert erste Messwerte
        progress("Wärme Modell auf …");
        run(model, "", List.of(), "Repeat this sentence: The quick brown fox jumps over the lazy dog near the river bank.",
                "", 6, 0, 0);
        send(JSON.createObjectNode().put("type", "ready").put("model", name));
        return model;
    }

    private void generate(AbstractModel model, JsonNode request) {
        List<Segment> segments = new ArrayList<>();
        for (JsonNode s : request.path("segments")) {
            String text = s.path("text").asString("");
            segments.add(new Segment(segments.size(), text, s.path("score").asDouble(0),
                    model.getTokenizer().encode(text + "\n").length));
        }
        String system = request.path("system").asString("");
        String header = request.path("header").asString("");
        String instruction = request.path("instruction").asString("");
        int maxTokens = Math.max(16, request.path("maxTokens").asInt(256));
        float temperature = (float) Math.max(0, request.path("temperature").asDouble(0.2));
        long budget = request.path("budgetMs").asLong(0);

        int total = tokens(model, system, header, segments, instruction);
        int dropped = 0;
        if (budget > 0) {
            // erwartete Antwortlänge: höchstens das halbe Budget, mehr schneidet die Frist ohnehin ab
            double answerMs = Math.min(maxTokens * generateMs, budget / 2.0);
            int allowed = (int) Math.max(MIN_PROMPT_TOKENS, (budget - answerMs) / prefillMs);
            List<Segment> byScore = new ArrayList<>(segments);
            byScore.sort(Comparator.comparingDouble(Segment::score));
            for (Segment weakest : byScore) {
                if (total <= allowed || segments.size() <= 1) {
                    break;
                }
                segments.remove(weakest);
                total -= weakest.tokens();
                dropped++;
            }
        }
        ObjectNode result = run(model, system, segments, instruction, header, maxTokens, budget, temperature);
        send(result.put("segments", segments.size()).put("dropped", dropped));
    }

    private ObjectNode run(AbstractModel model, String system, List<Segment> segments, String instruction,
                           String header, int maxTokens, long budget, float temperature) {
        PromptContext ctx = context(model, system, header, segments, instruction);
        int promptTokens = model.getTokenizer().encode(ctx.getPrompt()).length;
        int limit = model.getConfig().contextLength;
        if (promptTokens + 2 >= limit) {
            throw new IllegalArgumentException("Eingabe zu lang: " + promptTokens + " Tokens, Kontext des Modells " + limit);
        }
        try {
            return run(model, ctx, promptTokens, limit, maxTokens, budget, temperature);
        } finally {
            release(model);
        }
    }

    private ObjectNode run(AbstractModel model, PromptContext ctx, int promptTokens, int limit, int maxTokens,
                           long budget, float temperature) {
        long start = System.currentTimeMillis();
        long deadline = budget > 0 ? start + budget : Long.MAX_VALUE;
        long[] firstToken = {0};
        StringBuilder text = new StringBuilder();
        int[] generated = {0};
        String finish;
        try {
            var r = model.generate(UUID.randomUUID(), ctx, temperature, Math.min(limit, promptTokens + maxTokens + 2),
                    (token, ms) -> {
                        long now = System.currentTimeMillis();
                        if (firstToken[0] == 0) {
                            firstToken[0] = now;
                        }
                        text.append(token);
                        generated[0]++;
                        if (Repetition.loops(text)) {
                            throw new StopGeneration("REPETITION");
                        }
                        if (now >= deadline) {
                            throw new StopGeneration("TIME");
                        }
                        if (generated[0] % 16 == 0) {
                            progress(generated[0] + " Tokens erzeugt …");
                        }
                    });
            finish = String.valueOf(r.finishReason);
        } catch (StopGeneration e) {
            finish = e.getMessage();
        }
        long end = System.currentTimeMillis();
        learn(promptTokens, firstToken[0] == 0 ? end - start : firstToken[0] - start, generated[0],
                firstToken[0] == 0 ? 0 : end - firstToken[0]);
        String out = switch (finish) {
            case "REPETITION" -> Repetition.cut(text.toString());
            case "TIME" -> completeSentences(text.toString());
            default -> text.toString();
        };
        return JSON.createObjectNode().put("type", "result").put("text", out.strip())
                .put("promptTokens", promptTokens).put("generatedTokens", generated[0])
                .put("finish", finish).put("millis", end - start);
    }

    /**
     * Gibt den Kontext der Anfrage frei: {@code close()} leert in Jlama 0.8.4 nur den KV-Cache samt der Sitzungen (je
     * Anfrage eine neue, die Jlama sonst für immer aufhebt) – das Modell bleibt geladen. Liegen KV-Seiten als Dateien im
     * Arbeitsverzeichnis (nicht bei Llama), werden sie gelöscht.
     */
    private void release(AbstractModel model) {
        model.close();
        clearScratch();
    }

    private File scratch() {
        try {
            if (scratch == null) {
                scratch = Files.createTempDirectory("devtools-llm-");
            }
            return scratch.toFile();
        } catch (IOException e) {
            throw new UncheckedIOException("Arbeitsverzeichnis für den KV-Cache nicht anlegbar", e);
        }
    }

    private void deleteScratch() {
        if (scratch != null) {
            clearScratch();
            try {
                Files.deleteIfExists(scratch);
            } catch (IOException e) {
                System.err.println("Arbeitsverzeichnis " + scratch + " nicht gelöscht: " + e.getMessage());
            }
        }
    }

    private void clearScratch() {
        if (scratch == null) {
            return;
        }
        try (Stream<Path> files = Files.list(scratch)) {
            for (Path f : files.toList()) {
                Files.deleteIfExists(f);
            }
        } catch (IOException e) {
            System.err.println("Arbeitsverzeichnis " + scratch + " nicht geleert: " + e.getMessage());
        }
    }

    private void learn(int promptTokens, long prefillMillis, int generated, long generateMillis) {
        if (promptTokens >= 32 && prefillMillis > 0) {
            prefillMs = (1 - ALPHA) * prefillMs + ALPHA * prefillMillis / (double) promptTokens;
        }
        if (generated >= 4 && generateMillis > 0) {
            generateMs = (1 - ALPHA) * generateMs + ALPHA * generateMillis / (double) (generated - 1);
        }
    }

    private static int tokens(AbstractModel model, String system, String header, List<Segment> segments,
                              String instruction) {
        return model.getTokenizer().encode(context(model, system, header, segments, instruction).getPrompt()).length;
    }

    private static PromptContext context(AbstractModel model, String system, String header, List<Segment> segments,
                                         String instruction) {
        PromptSupport.Builder prompt = model.promptSupport().orElseThrow().builder();
        if (!system.isBlank()) {
            prompt.addSystemMessage(system);
        }
        StringBuilder user = new StringBuilder(header);
        if (!segments.isEmpty()) {
            user.append("<page>\n");
            segments.forEach(s -> user.append(s.text()).append('\n'));
            user.append("</page>\n\n");
        }
        user.append(instruction);
        return prompt.addUserMessage(user.toString()).build();
    }

    /** Bei Abbruch wegen der Frist: bis zum letzten vollständigen Satz bzw. zur letzten vollständigen Zeile. */
    static String completeSentences(String text) {
        String s = text.stripTrailing();
        int end = Math.max(s.lastIndexOf('\n'), Math.max(s.lastIndexOf(". "), Math.max(s.lastIndexOf("! "),
                s.lastIndexOf("? "))) + 1);
        if (s.endsWith(".") || s.endsWith("!") || s.endsWith("?")) {
            return s;
        }
        return end > s.length() / 3 ? s.substring(0, end).stripTrailing() : s + " …";
    }

    private void progress(String message) {
        send(JSON.createObjectNode().put("type", "progress").put("message", message));
    }

    private void error(String message) {
        send(JSON.createObjectNode().put("type", "error").put("message", message));
    }

    private synchronized void send(ObjectNode node) {
        out.println(JSON.writeValueAsString(node));
    }

    private static String mb(long bytes) {
        return (bytes / (1024 * 1024)) + " MB";
    }

    private static String message(Throwable t) {
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null || m.isBlank() ? "" : ": " + m);
    }

    /**
     * Bricht die Generierung ab. Ein {@link Error}, weil Jlama Exceptions aus dem Token-Callback abfängt und nur loggt;
     * Puffer gibt Jlama per try-with-resources trotzdem frei.
     */
    private static final class StopGeneration extends Error {
        StopGeneration(String reason) {
            super(reason, null, false, false);
        }
    }

    /** Ohne Spring-Konfiguration stünde Logback auf DEBUG; Jlama soll nur Warnungen und seine Startmeldungen loggen. */
    private static void quietLogging() {
        if (LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) instanceof ch.qos.logback.classic.Logger root) {
            root.setLevel(ch.qos.logback.classic.Level.INFO);
        }
    }
}
