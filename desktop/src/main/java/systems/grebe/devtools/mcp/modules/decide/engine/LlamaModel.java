package systems.grebe.devtools.mcp.modules.decide.engine;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Decoder-LLM aus einer GGUF-Datei in reinem Java: Architekturen {@code qwen2}, {@code qwen3} und {@code llama}
 * (RMSNorm, RoPE, Grouped-Query-Attention, SwiGLU). Für Entscheidungen wird nichts generiert: {@link Session#feed}
 * rechnet den Prompt und liefert nur die Logits der erlaubten Antwort-Tokens.
 *
 * <p>Der KV-Cache einer {@link Session} merkt sich, welche Tokens er enthält; ein neuer Prompt mit gleichem Anfang
 * (System-Prompt, Eingabe bei mehreren Feldern) rechnet nur den neuen Teil.
 */
public final class LlamaModel implements AutoCloseable {

    private record Layer(float[] attnNorm, Tensor wq, float[] bq, Tensor wk, float[] bk, Tensor wv, float[] bv,
                         float[] qNorm, float[] kNorm, Tensor wo, float[] ffnNorm, Tensor gate, Tensor up, Tensor down) {
    }

    private final Gguf gguf;
    private final Kernels k;
    private final BpeTokenizer tokenizer;
    private final String arch;
    private final int dim;
    private final int layersCount;
    private final int heads;
    private final int kvHeads;
    private final int headDim;
    private final int ffn;
    private final float eps;
    private final int contextLength;
    private final boolean neoxRope;
    private final Kernels.Rope rope;
    private final Tensor tokEmb;
    private final Tensor output;
    private final float[] outputNorm;
    private final Layer[] layers;

    private LlamaModel(Gguf gguf, Kernels kernels, int maxContext) throws IOException {
        this.gguf = gguf;
        this.k = kernels;
        this.arch = gguf.string("general.architecture", "");
        if (!arch.equals("qwen2") && !arch.equals("qwen3") && !arch.equals("llama")) {
            throw new IOException("Architektur " + arch + " wird nicht unterstützt (qwen2, qwen3, llama)");
        }
        this.tokenizer = BpeTokenizer.fromGguf(gguf);
        this.dim = (int) gguf.number(arch + ".embedding_length", 0);
        this.layersCount = (int) gguf.number(arch + ".block_count", 0);
        this.heads = (int) gguf.number(arch + ".attention.head_count", 0);
        this.kvHeads = (int) gguf.number(arch + ".attention.head_count_kv", heads);
        this.headDim = (int) gguf.number(arch + ".attention.key_length", dim / heads);
        this.ffn = (int) gguf.number(arch + ".feed_forward_length", 0);
        this.eps = (float) gguf.real(arch + ".attention.layer_norm_rms_epsilon", 1e-6);
        this.contextLength = (int) Math.min(maxContext, gguf.number(arch + ".context_length", 4096));
        this.neoxRope = !arch.equals("llama");
        this.rope = Kernels.Rope.of(headDim, gguf.real(arch + ".rope.freq_base", 10000), contextLength);
        this.tokEmb = gguf.tensor("token_embd.weight");
        this.output = gguf.has("output.weight") ? gguf.tensor("output.weight") : tokEmb;
        this.outputNorm = gguf.tensor("output_norm.weight").toFloats();
        this.layers = new Layer[layersCount];
        for (int l = 0; l < layersCount; l++) {
            String p = "blk." + l + ".";
            layers[l] = new Layer(floats(p + "attn_norm.weight"),
                    gguf.tensor(p + "attn_q.weight"), optional(p + "attn_q.bias"),
                    gguf.tensor(p + "attn_k.weight"), optional(p + "attn_k.bias"),
                    gguf.tensor(p + "attn_v.weight"), optional(p + "attn_v.bias"),
                    optional(p + "attn_q_norm.weight"), optional(p + "attn_k_norm.weight"),
                    gguf.tensor(p + "attn_output.weight"), floats(p + "ffn_norm.weight"),
                    gguf.tensor(p + "ffn_gate.weight"), gguf.tensor(p + "ffn_up.weight"), gguf.tensor(p + "ffn_down.weight"));
        }
    }

    /**
     * @param maxContext größte Prompt-Länge in Tokens (KV-Cache), höchstens die des Modells
     */
    public static LlamaModel load(Path file, Kernels kernels, int maxContext) throws IOException {
        Gguf g = Gguf.open(file);
        try {
            return new LlamaModel(g, kernels, maxContext);
        } catch (IOException | RuntimeException e) {
            g.close();
            throw e;
        }
    }

    private float[] floats(String name) {
        return gguf.tensor(name).toFloats();
    }

    private float[] optional(String name) {
        return gguf.has(name) ? floats(name) : null;
    }

    public BpeTokenizer tokenizer() {
        return tokenizer;
    }

    public String architecture() {
        return arch;
    }

    public String name() {
        return gguf.string("general.name", arch);
    }

    public int contextLength() {
        return contextLength;
    }

    /** Neuer KV-Cache. Nicht thread-sicher; je Thread eine Session. */
    public Session session() {
        return new Session();
    }

    /** KV-Cache mit den Tokens, die er enthält. */
    public final class Session {
        private final int kvDim = kvHeads * headDim;
        private final float[][] keys = new float[layersCount][];
        private final float[][] values = new float[layersCount][];
        private int[] cached = new int[0];

        private Session() {
        }

        private void ensure(int positions) {
            int need = positions * kvDim;
            for (int l = 0; l < layersCount; l++) {
                if (keys[l] == null || keys[l].length < need) {
                    int cap = Math.min(contextLength, Math.max(positions, keys[l] == null ? 512 : keys[l].length / kvDim * 2)) * kvDim;
                    keys[l] = keys[l] == null ? new float[cap] : Arrays.copyOf(keys[l], cap);
                    values[l] = values[l] == null ? new float[cap] : Arrays.copyOf(values[l], cap);
                }
            }
        }

        /**
         * Rechnet den Prompt (nur ab dem ersten Token, das nicht schon im Cache steht) und liefert die Logits des
         * nächsten Tokens – für {@code candidates} oder, wenn {@code null}, für das ganze Vokabular.
         */
        public float[] feed(int[] prompt, int[] candidates) {
            if (prompt.length == 0) {
                throw new IllegalArgumentException("Leerer Prompt");
            }
            if (prompt.length > contextLength) {
                throw new IllegalArgumentException("Prompt mit " + prompt.length + " Tokens ist länger als der Kontext ("
                        + contextLength + ")");
            }
            int common = 0;
            while (common < cached.length && common < prompt.length && cached[common] == prompt[common]) {
                common++;
            }
            if (common == prompt.length) {
                common--; // Logits des letzten Tokens werden gebraucht
            }
            int[] fresh = Arrays.copyOfRange(prompt, common, prompt.length);
            cached = Arrays.copyOf(prompt, prompt.length);
            ensure(prompt.length);
            float[] last;
            try {
                last = forward(fresh, common);
            } catch (RuntimeException e) {
                cached = new int[0];
                throw e;
            }
            int n = candidates == null ? output.rows() : candidates.length;
            float[] logits = new float[n];
            k.parallelFor((n + 255) / 256, blk -> {
                float[] row = new float[dim];
                for (int i = blk * 256; i < Math.min(n, blk * 256 + 256); i++) {
                    output.row(candidates == null ? i : candidates[i], row, 0);
                    logits[i] = Kernels.dot(row, 0, last, 0, dim);
                }
            });
            return logits;
        }

        /** Verwirft den Cache. */
        public void reset() {
            cached = new int[0];
        }

        /** Rechnet {@code tokens} ab Position {@code start}; liefert den normierten Hidden State des letzten Tokens. */
        private float[] forward(int[] tokens, int start) {
            int t = tokens.length;
            int qDim = heads * headDim;
            float[] x = new float[t * dim];
            for (int i = 0; i < t; i++) {
                tokEmb.row(tokens[i], x, i * dim);
            }
            float[] xn = new float[t * dim];
            float[] q = new float[t * qDim];
            float[] kk = new float[t * kvDim];
            float[] vv = new float[t * kvDim];
            float[] att = new float[t * qDim];
            float[] proj = new float[t * dim];
            float[] g = new float[t * ffn];
            float[] u = new float[t * ffn];
            int group = heads / kvHeads;
            float scale = (float) (1.0 / Math.sqrt(headDim));
            for (int l = 0; l < layersCount; l++) {
                Layer L = layers[l];
                Kernels.rmsNorm(x, t, dim, L.attnNorm(), eps, xn);
                k.matmul(L.wq(), xn, t, q, L.bq());
                k.matmul(L.wk(), xn, t, kk, L.bk());
                k.matmul(L.wv(), xn, t, vv, L.bv());
                for (int i = 0; i < t; i++) {
                    int pos = start + i;
                    for (int h = 0; h < heads; h++) {
                        int off = i * qDim + h * headDim;
                        if (L.qNorm() != null) {
                            Kernels.rmsNorm(q, off, headDim, L.qNorm(), eps);
                        }
                        rotate(q, off, pos);
                    }
                    for (int h = 0; h < kvHeads; h++) {
                        int off = i * kvDim + h * headDim;
                        if (L.kNorm() != null) {
                            Kernels.rmsNorm(kk, off, headDim, L.kNorm(), eps);
                        }
                        rotate(kk, off, pos);
                    }
                }
                System.arraycopy(kk, 0, keys[l], start * kvDim, t * kvDim);
                System.arraycopy(vv, 0, values[l], start * kvDim, t * kvDim);
                float[] kc = keys[l];
                float[] vc = values[l];
                k.parallelFor(t * heads, task -> {
                    int i = task / heads;
                    int h = task % heads;
                    int pos = start + i;
                    int kvh = h / group;
                    int qo = i * qDim + h * headDim;
                    float[] scores = new float[pos + 1];
                    for (int j = 0; j <= pos; j++) {
                        scores[j] = Kernels.dot(q, qo, kc, j * kvDim + kvh * headDim, headDim) * scale;
                    }
                    Kernels.softmax(scores, 0, pos + 1);
                    Arrays.fill(att, qo, qo + headDim, 0f);
                    for (int j = 0; j <= pos; j++) {
                        Kernels.axpy(scores[j], vc, j * kvDim + kvh * headDim, att, qo, headDim);
                    }
                });
                k.matmul(L.wo(), att, t, proj, null);
                ModernBert.add(x, proj);

                Kernels.rmsNorm(x, t, dim, L.ffnNorm(), eps, xn);
                k.matmul(L.gate(), xn, t, g, null);
                k.matmul(L.up(), xn, t, u, null);
                for (int i = 0; i < g.length; i++) {
                    g[i] = Kernels.silu(g[i]) * u[i];
                }
                k.matmul(L.down(), g, t, proj, null);
                ModernBert.add(x, proj);
            }
            float[] last = new float[dim];
            Kernels.rmsNorm(Arrays.copyOfRange(x, (t - 1) * dim, t * dim), 1, dim, outputNorm, eps, last);
            return last;
        }

        private void rotate(float[] v, int off, int pos) {
            if (neoxRope) {
                rope.applyHalves(v, off, pos);
            } else {
                rope.applyPairs(v, off, pos);
            }
        }
    }

    /** Erzeugt Text (gierig) – nur für Tests und Diagnose. */
    public String generate(String prompt, int maxTokens) {
        Session s = session();
        BpeTokenizer.IntList ids = new BpeTokenizer.IntList();
        ids.addAll(tokenizer.encode(prompt));
        int start = ids.size();
        int eos = (int) gguf.number("tokenizer.ggml.eos_token_id", -1);
        for (int i = 0; i < maxTokens; i++) {
            float[] logits = s.feed(ids.toArray(), null);
            int best = 0;
            for (int j = 1; j < logits.length; j++) {
                if (logits[j] > logits[best]) {
                    best = j;
                }
            }
            if (best == eos || "<|im_end|>".equals(tokenizer.token(best)) || "<|endoftext|>".equals(tokenizer.token(best))) {
                break;
            }
            ids.add(best);
        }
        return tokenizer.decode(Arrays.copyOfRange(ids.toArray(), start, ids.size()));
    }

    @Override
    public void close() {
        gguf.close();
    }
}
