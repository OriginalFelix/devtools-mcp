package systems.grebe.devtools.mcp.modules.decide.engine;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import systems.grebe.devtools.mcp.config.Home;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Qwen2.5-0.5B-Instruct (GGUF Q8_0) in reinem Java; läuft nur, wenn das Modell heruntergeladen ist. */
class LlamaModelTest {

    static final Path FILE = Home.defaultHome().resolve("models").resolve("qwen2.5-0.5b-instruct-q8_0.gguf");
    static Kernels kernels;
    static LlamaModel model;

    @BeforeAll
    static void load() throws Exception {
        assumeTrue(Files.isRegularFile(FILE), "Qwen-GGUF nicht heruntergeladen");
        kernels = new Kernels(0);
        model = LlamaModel.load(FILE, kernels, 2048);
    }

    @AfterAll
    static void close() {
        if (model != null) {
            model.close();
            kernels.shutdown();
            model = null;
            kernels = null;
        }
    }

    @Test
    void tokenizerRoundTrip() {
        BpeTokenizer tok = model.tokenizer();
        String text = "<|im_start|>user\nGrüße aus Köln – 3.14 Äpfel!<|im_end|>";
        int[] ids = tok.encode(text);
        assertThat(ids[0]).isEqualTo(tok.id("<|im_start|>"));
        assertThat(tok.decode(ids)).isEqualTo(text);
        assertThat(tok.encode("A")).hasSize(1);
    }

    @Test
    void generatesCoherentText() {
        long start = System.nanoTime();
        String out = model.generate("<|im_start|>system\nYou are a helpful assistant.<|im_end|>\n"
                + "<|im_start|>user\nWhat is the capital of France? Answer in one word.<|im_end|>\n<|im_start|>assistant\n", 8);
        System.out.printf("Antwort: %s (%d ms)%n", out, (System.nanoTime() - start) / 1_000_000);
        assertThat(out).contains("Paris");
    }

    @Test
    void scoresOptionLetters() {
        BpeTokenizer tok = model.tokenizer();
        String prompt = "<|im_start|>system\nAntworte nur mit dem Buchstaben der passenden Option.<|im_end|>\n"
                + "<|im_start|>user\nEingabe: Die App stürzt beim Speichern ab, seit dem letzten Update.\n\n"
                + "Frage: Um welche Art von Anliegen geht es?\nA) Fehler\nB) Funktionswunsch\nC) Frage zur Bedienung"
                + "<|im_end|>\n<|im_start|>assistant\n";
        int[] candidates = {tok.id("A"), tok.id("B"), tok.id("C")};
        LlamaModel.Session s = model.session();
        long start = System.nanoTime();
        float[] logits = s.feed(tok.encode(prompt), candidates);
        long first = (System.nanoTime() - start) / 1_000_000;
        // Zweiter Aufruf mit gleichem Anfang: nur der geänderte Rest wird gerechnet
        start = System.nanoTime();
        s.feed(tok.encode(prompt.replace("Frage zur Bedienung", "Frage zur Abrechnung")), candidates);
        long second = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("Logits A/B/C: %.2f %.2f %.2f (%d ms, mit Cache %d ms)%n", logits[0], logits[1], logits[2], first, second);
        assertThat(logits[0]).isGreaterThan(logits[1]).isGreaterThan(Float.NEGATIVE_INFINITY);
        assertThat(logits[0]).isGreaterThan(logits[2]);
    }
}
