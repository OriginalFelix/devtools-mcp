package systems.grebe.devtools.mcp.modules.decide.engine;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import systems.grebe.devtools.mcp.config.Home;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Parität mit den Referenzwerten des Laya-Projekts (Hugging-Face-Tokenizer, ONNX-Graph von {@code laya-multilingual}).
 * Läuft nur, wenn das Modell unter {@code ~/.devtools-mcp/models/laya-multilingual} liegt.
 */
class LayaParityTest {

    static final Path DIR = Home.defaultHome().resolve("models").resolve("laya-multilingual");
    static JsonNode tokenizerFixture;
    static JsonNode predictFixture;

    @BeforeAll
    static void fixtures() throws IOException {
        tokenizerFixture = read("tokenizer_ids.json");
        predictFixture = read("predict.json");
    }

    static JsonNode read(String name) throws IOException {
        try (InputStream in = LayaParityTest.class.getResourceAsStream("/decide/laya/" + name)) {
            return JsonMapper.builder().build().readTree(in);
        }
    }

    @Test
    void tokenizerMatchesHuggingFace() throws IOException {
        Path file = DIR.resolve("tokenizer/tokenizer.json");
        assumeTrue(Files.isRegularFile(file), "laya-multilingual nicht heruntergeladen");
        BpeTokenizer tok = BpeTokenizer.fromTokenizerJson(file);
        JsonNode texts = tokenizerFixture.path("corpus_text");
        JsonNode expected = tokenizerFixture.path("multilingual").path("corpus");
        List<String> failures = new ArrayList<>();
        int checked = 0;
        for (Map.Entry<String, JsonNode> e : expected.properties()) {
            JsonNode text = texts.get(e.getKey());
            if (text == null) {
                continue;
            }
            checked++;
            int[] want = ints(e.getValue());
            int[] got = tok.encode(text.asString());
            if (!java.util.Arrays.equals(want, got)) {
                failures.add(e.getKey() + ": erwartet " + java.util.Arrays.toString(want) + ", erhalten "
                        + java.util.Arrays.toString(got));
            }
        }
        assertThat(checked).isGreaterThan(5);
        assertThat(failures).isEmpty();
    }

    @Test
    void predictionsMatchOnnxGraph() throws IOException {
        assumeTrue(Files.isRegularFile(DIR.resolve("model.safetensors")), "laya-multilingual nicht heruntergeladen");
        Kernels kernels = new Kernels(0);
        try (LayaModel model = LayaModel.load(DIR, kernels)) {
            List<String> failures = new ArrayList<>();
            for (Map.Entry<String, JsonNode> c : predictFixture.path("single").properties()) {
                JsonNode fixture = c.getValue();
                List<String> ids = new ArrayList<>();
                List<LayaModel.Question> questions = new ArrayList<>();
                for (Map.Entry<String, JsonNode> q : fixture.path("questions").properties()) {
                    ids.add(q.getKey());
                    questions.add(question(q.getValue()));
                }
                long start = System.nanoTime();
                LayaModel.Result r = model.predict(questions, state(fixture.path("state")));
                long millis = (System.nanoTime() - start) / 1_000_000;
                System.out.printf("%s: %d Tokens, %d ms%n", c.getKey(), r.inputTokens(), millis);
                int wantTokens = fixture.path("usage").path("input_tokens").asInt();
                if (r.inputTokens() != wantTokens) {
                    failures.add(c.getKey() + ": input_tokens " + r.inputTokens() + " statt " + wantTokens);
                }
                for (int i = 0; i < ids.size(); i++) {
                    JsonNode answer = fixture.path("answers").path(ids.get(i));
                    double[] p = softmax(r.logits().get(i), model.temperature(questions.get(i).type(), r.logits().get(i).length));
                    double[] want = expected(answer);
                    for (int j = 0; j < want.length; j++) {
                        if (Math.abs(p[j] - want[j]) > 2e-3) {
                            failures.add(c.getKey() + "/" + ids.get(i) + ": erwartet " + java.util.Arrays.toString(want)
                                    + ", erhalten " + java.util.Arrays.toString(p));
                            break;
                        }
                    }
                }
            }
            assertThat(failures).isEmpty();
        } finally {
            kernels.shutdown();
        }
    }

    static LayaModel.Question question(JsonNode q) {
        String type = q.path("type").asString();
        String ins = q.path("instructions").asString();
        JsonNode crit = q.path("criteria");
        return switch (type) {
            case "choice" -> {
                List<String> labels = new ArrayList<>();
                Map<String, String> desc = new java.util.HashMap<>();
                for (Map.Entry<String, JsonNode> e : crit.properties()) {
                    labels.add(e.getKey());
                    desc.put(e.getKey(), e.getValue().isNull() ? null : criterion(e.getValue()));
                }
                yield new LayaModel.Question(LayaModel.QType.CHOICE, ins, LayaModel.renderChoice(labels, desc));
            }
            case "score" -> {
                List<String> levels = new ArrayList<>();
                crit.forEach(n -> levels.add(criterion(n)));
                yield new LayaModel.Question(LayaModel.QType.SCORE, ins, LayaModel.renderScore(levels));
            }
            default -> new LayaModel.Question(LayaModel.QType.NOUL, ins,
                    LayaModel.renderNoul(crit.has("false") ? criterion(crit.get("false")) : null,
                            crit.has("true") ? criterion(crit.get("true")) : null));
        };
    }

    /** Wie {@code render_criterion}: Text unverändert, Strukturen als JSON mit Pythons Trennzeichen. */
    static String criterion(JsonNode n) {
        return n.isString() ? n.asString() : python(n);
    }

    static double[] expected(JsonNode answer) {
        if (answer.path("type").asString().equals("noul")) {
            double t = answer.path("noul").asDouble();
            return new double[] {1 - t, t};
        }
        List<Double> p = new ArrayList<>();
        answer.path("probabilities").forEach(n -> p.add(n.asDouble()));
        return p.stream().mapToDouble(Double::doubleValue).toArray();
    }

    static double[] softmax(float[] logits, double temperature) {
        double max = Double.NEGATIVE_INFINITY;
        for (float l : logits) {
            max = Math.max(max, l / temperature);
        }
        double[] p = new double[logits.length];
        double sum = 0;
        for (int i = 0; i < p.length; i++) {
            p[i] = Math.exp(logits[i] / temperature - max);
            sum += p[i];
        }
        for (int i = 0; i < p.length; i++) {
            p[i] /= sum;
        }
        return p;
    }

    /** Wie Pythons {@code json.dumps(state, ensure_ascii=False)}. */
    static String state(JsonNode n) {
        if (n.isString()) {
            return n.asString();
        }
        return python(n);
    }

    static String python(JsonNode n) {
        if (n.isObject()) {
            List<String> parts = new ArrayList<>();
            for (Map.Entry<String, JsonNode> e : n.properties()) {
                parts.add(quote(e.getKey()) + ": " + python(e.getValue()));
            }
            return "{" + String.join(", ", parts) + "}";
        }
        if (n.isArray()) {
            List<String> parts = new ArrayList<>();
            n.forEach(c -> parts.add(python(c)));
            return "[" + String.join(", ", parts) + "]";
        }
        if (n.isString()) {
            return quote(n.asString());
        }
        return n.isNull() ? "null" : n.toString();
    }

    static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                case '\r' -> sb.append("\\r");
                default -> {
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    static int[] ints(JsonNode a) {
        int[] r = new int[a.size()];
        for (int i = 0; i < r.length; i++) {
            r[i] = a.get(i).asInt();
        }
        return r;
    }
}
