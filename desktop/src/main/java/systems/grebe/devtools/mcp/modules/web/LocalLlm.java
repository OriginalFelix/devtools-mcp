package systems.grebe.devtools.mcp.modules.web;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.modules.scripts.JavaClasspath;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Lokales LLM über Jlama in einem eigenen JVM-Prozess ({@link LlmWorker}). Ein Prozess für die ganze App – das Modell
 * liegt nur einmal im Speicher und bleibt zwischen Anfragen geladen; jede Anfrage rechnet in frischem Kontext. Anfragen
 * laufen nacheinander in einer fairen Warteschlange (Reihenfolge des Eintreffens, Wartende lassen sich abbrechen). Der
 * Prozess startet beim ersten Aufruf (lädt das Modell dabei bei Bedarf von Hugging Face herunter), wird bei geänderten
 * Einstellungen neu gestartet und nach Leerlauf beendet (Leerlauf 0 = nie).
 */
@Component
public class LocalLlm implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(LocalLlm.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Ohne Lebenszeichen (Fortschritt, Ergebnis) gilt der Worker als hängend; Downloads melden sich jede Sekunde. */
    private static final Duration SILENCE_TIMEOUT = Duration.ofMinutes(10);
    private static final int STDERR_LINES = 40;

    /**
     * @param model     Modell auf Hugging Face ({@code owner/name}), Jlama-kompatibel (safetensors)
     * @param modelDir  Ablage der Modelle
     * @param threads   Rechen-Threads, 0 = Jlama-Standard (Hälfte der logischen Kerne)
     * @param heapMb    Heap des Worker-Prozesses; Gewichte und KV-Cache liegen außerhalb
     * @param idle      danach wird der Prozess beendet und der Speicher freigegeben; 0 = nie
     * @param optimized Llama-Modelle mit {@link FastLlamaModel} (schnellere Verarbeitung der Eingabe, gleiche Ausgabe)
     */
    public record Options(String model, Path modelDir, int threads, int heapMb, Duration idle, boolean optimized) {
    }

    /**
     * Anfrage an das Modell. Die Nachricht ist {@code header}, die Textstücke in {@code <page>} und {@code instruction};
     * passt sie nicht ins Zeitbudget, fallen die Textstücke mit der geringsten Bewertung weg.
     */
    public record Request(String system, String header, List<Segment> segments, String instruction, int maxTokens,
                          float temperature) {
        /** Leichte Zufälligkeit: bei 0 gerät ein 1B-Modell schneller in Wiederholungsschleifen. */
        public static final float DEFAULT_TEMPERATURE = 0.2f;

        public Request {
            segments = segments == null ? List.of() : List.copyOf(segments);
        }

        public Request(String system, String header, List<Segment> segments, String instruction, int maxTokens) {
            this(system, header, segments, instruction, maxTokens, DEFAULT_TEMPERATURE);
        }
    }

    /** Textstück mit Relevanz (höher = wichtiger). */
    public record Segment(String text, double score) {
    }

    /**
     * Antwort des Modells mit Verbrauch.
     *
     * @param segments verwendete Textstücke (nach Kürzung für das Zeitbudget)
     * @param finish   Grund des Endes: STOP_TOKEN, MAX_TOKENS, TIME (Frist), REPETITION (Schleife abgebrochen)
     */
    public record Completion(String text, int promptTokens, int generatedTokens, int segments, String finish,
                             long millis) {
    }

    /** Mindestzeit für das Modell, auch wenn die Frist schon fast abgelaufen ist. */
    private static final long MIN_BUDGET_MILLIS = 4000;

    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "local-llm-idle");
        t.setDaemon(true);
        return t;
    });
    /** Faire Warteschlange vor dem Worker: wer zuerst kommt, rechnet zuerst. */
    private final ReentrantLock queue = new ReentrantLock(true);
    /** volatile: {@link #running()} liest ohne Sperre, während eine Anfrage läuft. */
    private volatile Worker worker;
    /** Ob der Halter der Warteschlange gerade den Worker startet (für die Wartemeldung). */
    private volatile boolean starting;
    private ScheduledFuture<?> idleStop;

    /** Ob das Modell bereits vollständig heruntergeladen ist (Marker von Jlama). */
    public static boolean downloaded(Options o) {
        return Files.exists(localModelDir(o).resolve(".finished"));
    }

    /** Verzeichnis, in das Jlama das Modell lädt ({@code <owner>_<name>}). */
    public static Path localModelDir(Options o) {
        String[] parts = o.model().strip().split("/", 2);
        return parts.length == 2 ? o.modelDir().resolve(parts[0] + "_" + parts[1]) : o.modelDir().resolve("na_" + parts[0]);
    }

    /** Ob gerade ein Worker läuft. */
    public boolean running() {
        Worker w = worker;
        return w != null && w.process.isAlive();
    }

    /**
     * Lädt das Modell vorab (Download, Start des Workers), damit der erste Tool-Aufruf nicht darauf warten muss.
     *
     * @return Name des geladenen Modells
     */
    public String warmUp(Options o, Consumer<String> progress) {
        enter(progress);
        try {
            ensureWorker(o, progress);
            scheduleIdleStop(o);
            return o.model();
        } finally {
            queue.unlock();
        }
    }

    /**
     * Startet den Worker im Hintergrund, falls er nicht läuft und das Modell schon heruntergeladen ist – z.B. während
     * die Seite noch geladen wird. Ein folgendes {@link #complete} wartet dann nur auf den Rest des Starts.
     */
    public void prepare(Options o) {
        Worker w = worker;
        if (w != null && w.process.isAlive() && w.options.equals(o) || !downloaded(o)) {
            return;
        }
        Thread.ofVirtual().name("local-llm-prepare").start(() -> {
            try {
                warmUp(o, null);
            } catch (RuntimeException e) {
                LOG.debug("Lokales LLM nicht vorab gestartet", e); // der eigentliche Aufruf meldet den Fehler
            }
        });
    }

    /**
     * Fragt das Modell. Blockiert, bis die Antwort da ist; parallele Aufrufe warten aufeinander. Die Antwort kommt
     * spätestens zur Frist (abzüglich Start des Prozesses), notfalls gekürzt.
     *
     * @param deadline bis dahin soll die Antwort da sein; {@code null} = ohne Frist
     * @param progress Zwischenstände (Download, Tokens) für die Anzeige
     */
    public Completion complete(Options o, Request request, Instant deadline, Consumer<String> progress) {
        enter(progress);
        try {
            return completeLocked(o, request, deadline, progress);
        } finally {
            queue.unlock();
        }
    }

    private Completion completeLocked(Options o, Request request, Instant deadline, Consumer<String> progress) {
        Worker w = ensureWorker(o, progress);
        try {
            long budget = deadline == null ? 0
                    : Math.max(MIN_BUDGET_MILLIS, Duration.between(Instant.now(), deadline).toMillis());
            ObjectNode json = JSON.createObjectNode().put("system", request.system()).put("header", request.header())
                    .put("instruction", request.instruction()).put("maxTokens", request.maxTokens())
                    .put("temperature", request.temperature())
                    .put("budgetMs", budget);
            ArrayNode segments = json.putArray("segments");
            request.segments().forEach(seg -> segments.addObject().put("text", seg.text()).put("score", seg.score()));
            w.send(JSON.writeValueAsString(json));
            JsonNode m = w.await(progress, "result");
            return new Completion(m.path("text").asString(""), m.path("promptTokens").asInt(),
                    m.path("generatedTokens").asInt(), m.path("segments").asInt(), m.path("finish").asString(""),
                    m.path("millis").asLong());
        } catch (RuntimeException e) {
            if (!w.process.isAlive()) {
                worker = null;
            }
            throw e;
        } finally {
            scheduleIdleStop(o);
        }
    }

    /** Stellt sich in die Warteschlange; ist der Worker belegt, steht im Fortschritt, wie viele davor warten. */
    private void enter(Consumer<String> progress) {
        try {
            // tryLock() ohne Zeit würde sich vordrängeln; mit Zeit 0 gilt die Fairness
            if (queue.tryLock(0, TimeUnit.MILLISECONDS)) {
                return;
            }
            if (progress != null) {
                int ahead = queue.getQueueLength() + (starting ? 0 : 1);
                progress.accept(starting && ahead == 0 ? "Lokales LLM startet …"
                        : "Warte auf das lokale LLM – " + ahead + " Anfrage(n) davor …");
            }
            queue.lockInterruptibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Abgebrochen, während auf das lokale LLM gewartet wurde");
        }
    }

    private Worker ensureWorker(Options o, Consumer<String> progress) {
        if (worker != null && (!worker.process.isAlive() || !worker.options.equals(o))) {
            stopWorker();
        }
        if (worker == null) {
            starting = true;
            Worker w = Worker.start(o);
            try {
                w.await(progress, "ready");
            } catch (RuntimeException e) {
                w.destroy();
                throw e;
            } finally {
                starting = false;
            }
            worker = w;
        }
        return worker;
    }

    private void scheduleIdleStop(Options o) {
        if (idleStop != null) {
            idleStop.cancel(false);
            idleStop = null;
        }
        if (o.idle().isZero()) {
            return;
        }
        long seconds = Math.max(30, o.idle().toSeconds());
        idleStop = timer.schedule(this::stopIdle, seconds, TimeUnit.SECONDS);
    }

    private void stopIdle() {
        // läuft gerade eine Anfrage, plant sie danach selbst neu
        if (!queue.tryLock()) {
            return;
        }
        try {
            if (worker != null) {
                LOG.info("Lokales LLM {} nach Leerlauf beendet", worker.options.model());
                stopWorker();
            }
        } finally {
            queue.unlock();
        }
    }

    private void stopWorker() {
        if (worker != null) {
            worker.destroy();
            worker = null;
        }
    }

    @PreDestroy
    @Override
    public void close() {
        // nicht auf laufende Anfragen warten: der Prozess wird beendet, die Anfrage scheitert mit einer Meldung
        timer.shutdownNow();
        Worker w = worker;
        worker = null;
        if (w != null) {
            w.destroy();
        }
    }

    /** Laufender Worker-Prozess mit seinen Strömen. */
    private static final class Worker {
        private static final JsonNode EXIT = JSON.createObjectNode().put("type", "exit");

        final Options options;
        final Process process;
        final Writer stdin;
        final BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
        final Deque<String> stderr = new ArrayDeque<>();
        final Path argFile;

        private Worker(Options options, Process process, Path argFile) {
            this.options = options;
            this.process = process;
            this.argFile = argFile;
            this.stdin = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
        }

        static Worker start(Options o) {
            Path argFile = null;
            try {
                // Der Klassenpfad der App sprengt unter Windows die Länge einer Kommandozeile – daher als @-Datei
                argFile = Files.createTempFile("devtools-llm-", ".args");
                argFile.toFile().deleteOnExit(); // falls die App endet, ohne den Worker sauber zu beenden
                Files.writeString(argFile, "-cp " + quote(JavaClasspath.get()) + "\n");
                List<String> cmd = new ArrayList<>(List.of(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "--add-modules=jdk.incubator.vector",
                        "--enable-native-access=ALL-UNNAMED",
                        "-Xmx" + Math.max(256, o.heapMb()) + "m",
                        "-Djava.awt.headless=true",
                        "-Dstdout.encoding=UTF-8",
                        "-Dstderr.encoding=UTF-8",
                        "@" + argFile,
                        LlmWorker.class.getName(),
                        o.model().strip(),
                        o.modelDir().toAbsolutePath().toString(),
                        Integer.toString(Math.max(0, o.threads())),
                        Boolean.toString(o.optimized())));
                LOG.info("Starte lokales LLM {} (Modelle in {})", o.model(), o.modelDir());
                Process p = new ProcessBuilder(cmd).start();
                Worker w = new Worker(o, p, argFile);
                w.pump(p.getInputStream(), "local-llm-out", line -> {
                    try {
                        w.messages.add(JSON.readTree(line));
                    } catch (RuntimeException e) {
                        w.stderrLine(line);
                    }
                });
                w.pump(p.getErrorStream(), "local-llm-err", w::stderrLine);
                return w;
            } catch (IOException e) {
                deleteQuietly(argFile);
                throw new IllegalStateException("Lokales LLM nicht startbar: " + e.getMessage(), e);
            }
        }

        private void pump(InputStream in, String name, Consumer<String> sink) {
            Thread t = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        sink.accept(line);
                    }
                } catch (IOException ignored) {
                    // Prozess beendet
                } finally {
                    if (name.endsWith("out")) {
                        messages.add(EXIT);
                    }
                }
            }, name);
            t.setDaemon(true);
            t.start();
        }

        private void stderrLine(String line) {
            LOG.debug("[local-llm] {}", line);
            synchronized (stderr) {
                stderr.addLast(line);
                while (stderr.size() > STDERR_LINES) {
                    stderr.removeFirst();
                }
            }
        }

        void send(String line) {
            try {
                stdin.write(line);
                stdin.write('\n');
                stdin.flush();
            } catch (IOException e) {
                throw new IllegalStateException("Lokales LLM nicht erreichbar: " + e.getMessage() + stderrTail(), e);
            }
        }

        /** Wartet auf die Nachricht {@code type}; Fortschritt geht an {@code progress}, Fehler werden geworfen. */
        JsonNode await(Consumer<String> progress, String type) {
            while (true) {
                JsonNode m;
                try {
                    m = messages.poll(SILENCE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    destroy();
                    throw new IllegalStateException("Abgebrochen – lokales LLM beendet");
                }
                if (m == null) {
                    destroy();
                    throw new IllegalStateException("Lokales LLM antwortet seit " + SILENCE_TIMEOUT.toMinutes()
                            + " Minuten nicht – beendet." + stderrTail());
                }
                switch (m.path("type").asString("")) {
                    case "progress" -> {
                        if (progress != null) {
                            progress.accept(m.path("message").asString(""));
                        }
                    }
                    case "error" -> throw new IllegalStateException(m.path("message").asString("Fehler im lokalen LLM"));
                    case "exit" -> {
                        waitForExit();
                        throw new IllegalStateException("Lokales LLM unerwartet beendet"
                                + (process.isAlive() ? "" : " (Exit-Code " + process.exitValue() + ")") + "." + stderrTail());
                    }
                    default -> {
                        if (type.equals(m.path("type").asString(""))) {
                            return m;
                        }
                    }
                }
            }
        }

        private void waitForExit() {
            try {
                process.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private String stderrTail() {
            synchronized (stderr) {
                List<String> relevant = stderr.stream()
                        .filter(l -> !l.startsWith("WARNING:") && !l.isBlank())
                        .toList();
                if (relevant.isEmpty()) {
                    return "";
                }
                return "\nLetzte Ausgaben:\n" + String.join("\n",
                        relevant.subList(Math.max(0, relevant.size() - 10), relevant.size()));
            }
        }

        void destroy() {
            try {
                stdin.close(); // Worker endet bei EOF von selbst
            } catch (IOException ignored) {
                // schon zu
            }
            try {
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
            deleteQuietly(argFile);
        }

        /** Argument für eine @-Datei: in Anführungszeichen, Backslash ist dort Escape-Zeichen. */
        private static String quote(String s) {
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }

        private static void deleteQuietly(Path p) {
            if (p != null) {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // temporäre Datei, egal
                }
            }
        }
    }
}
