package systems.grebe.devtools.mcp.modules.decide.engine;

import tools.jackson.databind.JsonNode;

/**
 * ModernBERT-Encoder (auch mmBERT) wie {@code transformers.ModernBertModel}: Token-Embedding + LayerNorm, je Schicht
 * Pre-Norm-Attention mit RoPE (global oder lokal mit Fenster) und GeGLU-MLP, LayerNorms ohne Bias, abschließend
 * {@code final_norm}. Gerechnet wird für mehrere Sequenzen auf einmal, aneinandergehängt ohne Padding.
 */
public final class ModernBert {

    /** Konfiguration aus {@code config.json}. */
    public record Config(int hidden, int layers, int heads, int intermediate, float eps, int localAttention,
                         boolean[] global, double thetaGlobal, double thetaLocal, int maxPositions, int vocab) {

        public static Config of(JsonNode c) {
            int layers = c.path("num_hidden_layers").asInt();
            boolean[] global = new boolean[layers];
            JsonNode types = c.path("layer_types");
            int every = c.path("global_attn_every_n_layers").asInt(3);
            for (int l = 0; l < layers; l++) {
                global[l] = types.isArray() && types.size() == layers
                        ? "full_attention".equals(types.get(l).asString())
                        : l % every == 0;
            }
            double thetaGlobal = c.path("global_rope_theta").asDouble(160000);
            double thetaLocal = c.path("local_rope_theta").asDouble(10000);
            JsonNode rope = c.path("rope_parameters");
            if (rope.isObject()) {
                thetaGlobal = rope.path("full_attention").path("rope_theta").asDouble(rope.path("rope_theta").asDouble(thetaGlobal));
                thetaLocal = rope.path("sliding_attention").path("rope_theta").asDouble(rope.path("rope_theta").asDouble(thetaLocal));
            }
            return new Config(c.path("hidden_size").asInt(), layers, c.path("num_attention_heads").asInt(),
                    c.path("intermediate_size").asInt(), (float) c.path("norm_eps").asDouble(1e-5),
                    c.path("local_attention").asInt(128), global, thetaGlobal, thetaLocal,
                    c.path("max_position_embeddings").asInt(8192), c.path("vocab_size").asInt());
        }

        int headDim() {
            return hidden / heads;
        }
    }

    private record Layer(float[] attnNorm, Tensor wqkv, Tensor wo, float[] mlpNorm, Tensor wi, Tensor wo2) {
    }

    private final Config cfg;
    private final Kernels k;
    private final Tensor tokEmb;
    private final float[] embNorm;
    private final float[] finalNorm;
    private final Layer[] layers;
    private Kernels.Rope ropeGlobal;
    private Kernels.Rope ropeLocal;

    public ModernBert(SafeTensors st, String prefix, Config cfg, Kernels kernels) {
        this.cfg = cfg;
        this.k = kernels;
        this.tokEmb = st.tensor(prefix + "embeddings.tok_embeddings.weight");
        this.embNorm = st.floats(prefix + "embeddings.norm.weight");
        this.finalNorm = st.floats(prefix + "final_norm.weight");
        this.layers = new Layer[cfg.layers()];
        for (int l = 0; l < cfg.layers(); l++) {
            String p = prefix + "layers." + l + ".";
            float[] attnNorm = st.has(p + "attn_norm.weight") ? st.floats(p + "attn_norm.weight") : null;
            layers[l] = new Layer(attnNorm, st.tensor(p + "attn.Wqkv.weight"), st.tensor(p + "attn.Wo.weight"),
                    st.floats(p + "mlp_norm.weight"), st.tensor(p + "mlp.Wi.weight"), st.tensor(p + "mlp.Wo.weight"));
        }
        if (st.has(prefix + "embeddings.norm.bias")) {
            throw new IllegalArgumentException("ModernBERT mit Norm-Bias wird nicht unterstützt");
        }
    }

    public Config config() {
        return cfg;
    }

    private synchronized Kernels.Rope rope(boolean global, int positions) {
        Kernels.Rope r = global ? ropeGlobal : ropeLocal;
        if (r == null || r.positions() < positions) {
            int n = Math.max(positions, 1024);
            r = Kernels.Rope.of(cfg.headDim(), global ? cfg.thetaGlobal() : cfg.thetaLocal(), n);
            if (global) {
                ropeGlobal = r;
            } else {
                ropeLocal = r;
            }
        }
        return r;
    }

    /**
     * Letzte Hidden States {@code [Σ lengths × hidden]} für die aneinandergehängten Sequenzen {@code ids}.
     */
    public float[] forward(int[] ids, int[] lengths) {
        int d = cfg.hidden();
        int n = ids.length;
        int maxLen = 0;
        for (int l : lengths) {
            maxLen = Math.max(maxLen, l);
        }
        float[] x = new float[n * d];
        for (int t = 0; t < n; t++) {
            tokEmb.row(ids[t], x, t * d);
        }
        Kernels.layerNorm(x, n, d, embNorm, null, cfg.eps(), x);

        int heads = cfg.heads();
        int hd = cfg.headDim();
        int inter = cfg.intermediate();
        float[] xn = new float[n * d];
        float[] qkv = new float[n * 3 * d];
        float[] att = new float[n * d];
        float[] proj = new float[n * d];
        float[] up = new float[n * 2 * inter];
        float[] act = new float[n * inter];
        for (int l = 0; l < layers.length; l++) {
            Layer layer = layers[l];
            if (layer.attnNorm() == null) {
                System.arraycopy(x, 0, xn, 0, x.length);
            } else {
                Kernels.layerNorm(x, n, d, layer.attnNorm(), null, cfg.eps(), xn);
            }
            k.matmul(layer.wqkv(), xn, n, qkv, null);
            boolean global = cfg.global()[l];
            Kernels.Rope rope = rope(global, maxLen);
            for (int s = 0, base = 0; s < lengths.length; base += lengths[s], s++) {
                for (int i = 0; i < lengths[s]; i++) {
                    int row = (base + i) * 3 * d;
                    for (int h = 0; h < heads; h++) {
                        rope.applyHalves(qkv, row + h * hd, i);
                        rope.applyHalves(qkv, row + d + h * hd, i);
                    }
                }
            }
            k.attention(qkv, 3 * d, 0, d, 2 * d, lengths, heads, hd, global ? 0 : cfg.localAttention() / 2, null, att);
            k.matmul(layer.wo(), att, n, proj, null);
            add(x, proj);

            Kernels.layerNorm(x, n, d, layer.mlpNorm(), null, cfg.eps(), xn);
            k.matmul(layer.wi(), xn, n, up, null);
            k.parallelFor(n, t -> {
                int u = t * 2 * inter;
                int a = t * inter;
                for (int i = 0; i < inter; i++) {
                    act[a + i] = Kernels.gelu(up[u + i]) * up[u + inter + i];
                }
            });
            k.matmul(layer.wo2(), act, n, proj, null);
            add(x, proj);
        }
        Kernels.layerNorm(x, n, d, finalNorm, null, cfg.eps(), x);
        return x;
    }

    static void add(float[] x, float[] y) {
        for (int i = 0; i < x.length; i++) {
            x[i] += y[i];
        }
    }
}
