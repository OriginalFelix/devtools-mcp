package systems.grebe.devtools.mcp.modules.decide;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Gespeicherte Entscheidungsaufgaben je Benutzer/Profil: Felder, Hinweise, Beispiele mit richtiger Antwort und die
 * Logits früherer Entscheidungen mit Rückmeldung (für die Kalibrierung). Eine JSON-Datei je Aufgabe unter
 * {@code ~/.devtools-mcp/decide/<scope>/}.
 */
final class TaskStore {

    static final int MAX_EXAMPLES = 1000;
    static final int MAX_FEEDBACK_PER_KEY = 500;
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9_\\-]{0,63}");
    private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    /** Eine Aufgabe; {@code fields} in der Form der Tool-Eingabe. */
    record Task(String name, String description, String instructions, List<DecisionSchema.FieldSpec> fields,
                List<Example> examples, List<Feedback> feedback, String updated) {

        Task {
            examples = examples == null ? new ArrayList<>() : new ArrayList<>(examples);
            feedback = feedback == null ? new ArrayList<>() : new ArrayList<>(feedback);
        }

        List<DecisionSchema.Field> validFields() {
            return DecisionSchema.validate(fields);
        }

        /** Rückmeldungen zu einer Frage ({@code feld} oder {@code feld/option} bei multi) und einem Modell. */
        List<Calibration.Sample> samples(String key, String engine) {
            List<Calibration.Sample> out = new ArrayList<>();
            for (Feedback f : feedback) {
                if (f.key().equals(key) && f.engine().equals(engine) && f.logits() != null && f.correct() >= 0
                        && f.correct() < f.logits().length) {
                    out.add(new Calibration.Sample(f.logits(), f.correct()));
                }
            }
            return out;
        }

        /** Rückmeldungen, bei denen beide Modelle gerechnet haben (gleiche Frage, gleicher Zeitpunkt). */
        List<Calibration.Pair> pairs(String key) {
            Map<String, Feedback> laya = new java.util.HashMap<>();
            List<Calibration.Pair> out = new ArrayList<>();
            for (Feedback f : feedback) {
                if (f.key().equals(key) && Decider.LAYA.equals(f.engine()) && f.logits() != null) {
                    laya.put(f.created() + "\u0000" + f.correct(), f);
                }
            }
            for (Feedback f : feedback) {
                if (f.key().equals(key) && Decider.LLM.equals(f.engine()) && f.logits() != null) {
                    Feedback l = laya.get(f.created() + "\u0000" + f.correct());
                    if (l != null && l.logits().length == f.logits().length && f.correct() < f.logits().length) {
                        out.add(new Calibration.Pair(l.logits(), f.logits(), f.correct()));
                    }
                }
            }
            return out;
        }
    }

    /** Beispiel mit richtiger Antwort je Feld (Wert, bei multi Liste, bei boolean true/false). */
    record Example(String input, Map<String, Object> labels, String source, String created) {
    }

    /** Roh-Logits einer Entscheidung und die richtige Option. */
    record Feedback(String key, String engine, float[] logits, int correct, String created) {
    }

    private final Path dir;

    TaskStore(Path dir) {
        this.dir = dir;
    }

    static String normalize(String name) {
        String n = name == null ? "" : name.strip().toLowerCase(Locale.ROOT).replace(' ', '-');
        if (!NAME.matcher(n).matches()) {
            throw new IllegalArgumentException("Ungültiger Aufgabenname '" + name + "' – Kleinbuchstaben, Ziffern, - und _ "
                    + "(max. 64 Zeichen).");
        }
        return n;
    }

    private Path file(String name) {
        return dir.resolve(normalize(name) + ".json");
    }

    synchronized Optional<Task> get(String name) {
        Path f = file(name);
        if (!Files.isRegularFile(f)) {
            return Optional.empty();
        }
        try {
            return Optional.of(JSON.readValue(f.toFile(), Task.class));
        } catch (RuntimeException e) {
            throw new IllegalStateException("Aufgabe " + name + " ist nicht lesbar (" + f + "): " + e.getMessage(), e);
        }
    }

    Task require(String name) {
        return get(name).orElseThrow(() -> new IllegalArgumentException("Aufgabe '" + name + "' gibt es nicht – vorhanden: "
                + String.join(", ", list().stream().map(Task::name).toList())));
    }

    synchronized List<Task> list() {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Task> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList()) {
                try {
                    out.add(JSON.readValue(f.toFile(), Task.class));
                } catch (RuntimeException e) {
                    // defekte Datei überspringen, get() meldet sie
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    synchronized Task save(Task t) {
        String name = normalize(t.name());
        List<Example> examples = t.examples();
        if (examples.size() > MAX_EXAMPLES) {
            examples = new ArrayList<>(examples.subList(examples.size() - MAX_EXAMPLES, examples.size()));
        }
        List<Feedback> feedback = trimFeedback(t.feedback());
        Task saved = new Task(name, t.description(), t.instructions(), t.fields(), examples, feedback, Instant.now().toString());
        try {
            Files.createDirectories(dir);
            Path tmp = dir.resolve(name + ".json.tmp");
            JSON.writeValue(tmp.toFile(), saved);
            Files.move(tmp, file(name), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Aufgabe " + name + " nicht gespeichert: " + e.getMessage(), e);
        }
        return saved;
    }

    /** Nur die neuesten Rückmeldungen je Frage und Modell behalten. */
    private static List<Feedback> trimFeedback(List<Feedback> all) {
        Map<String, Integer> counts = new java.util.HashMap<>();
        List<Feedback> kept = new ArrayList<>();
        for (int i = all.size() - 1; i >= 0; i--) {
            Feedback f = all.get(i);
            if (counts.merge(f.key() + "\u0000" + f.engine(), 1, Integer::sum) <= MAX_FEEDBACK_PER_KEY) {
                kept.add(0, f);
            }
        }
        return kept;
    }

    synchronized boolean delete(String name) {
        try {
            return Files.deleteIfExists(file(name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
