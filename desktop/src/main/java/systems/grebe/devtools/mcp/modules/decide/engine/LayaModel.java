package systems.grebe.devtools.mcp.modules.decide.engine;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Laya-Entscheidungsmodell (github.com/NandhaKishorM/laya, Apache-2.0) in reinem Java: ModernBERT-Encoder, zwei
 * Transformer-Schichten als Entscheidungskopf, ein Scorer je Option. Eine Frage ergibt eine Zeile
 * {@code [CLS] <typ> question: <anweisung> [SEP] [MASK] option0 [MASK] option1 … [SEP] <zustand> [SEP]}; das Logit
 * einer Option ist der Scorer am {@code [MASK]} vor ihr. Nachgebaut nach {@code laya/common.py}
 * ({@code build_head}, {@code build_sequence}, {@code DecisionModel.forward}) im sequentiellen Layout.
 */
public final class LayaModel implements AutoCloseable {

    /** Fragetypen in der Reihenfolge der Typ-Embeddings. */
    public enum QType {
        CHOICE("choice"), SCORE("score"), NOUL("noul");

        final String label;

        QType(String label) {
            this.label = label;
        }
    }

    /** Eine Frage mit bereits gerenderten Optionstexten (siehe {@link #renderChoice} u.a.). */
    public record Question(QType type, String instructions, List<String> options) {
    }

    /** Roh-Logits je Frage und Verbrauch wie {@code usage} bei Laya. */
    public record Result(List<float[]> logits, int inputTokens, int stateTokens, int stateTokensDropped) {
    }

    private static final int MAX_OPTION_TOKENS = 48;
    /** Tokens je Vorwärtsrechnung (Laya: {@code max_tokens_per_batch}). */
    private static final int MAX_BATCH_TOKENS = 4096;
    private static final double TEMP_MIN = 0.5;
    private static final double TEMP_MAX = 5.0;

    private record HeadLayer(float[] norm1W, float[] norm1B, Tensor inProj, float[] inProjB, Tensor outProj,
                             float[] outProjB, float[] norm2W, float[] norm2B, Tensor lin1, float[] lin1B, Tensor lin2,
                             float[] lin2B) {
    }

    private final SafeTensors weights;
    private final Kernels k;
    private final BpeTokenizer tokenizer;
    private final ModernBert encoder;
    private final HeadLayer[] head;
    private final float[] typeEmb;
    private final float[] scorerNormW;
    private final float[] scorerNormB;
    private final Tensor scorer1;
    private final float[] scorer1B;
    private final float[] scorer2;
    private final float scorer2B;
    private final int clsId;
    private final int sepId;
    private final int maskId;
    private final String maskToken;
    private final int maxLen;
    private final int headMaxLen;
    private final double[] temperature;
    private final Map<String, Double> temperatureByOptions;
    private final String name;

    private LayaModel(Path dir, Kernels kernels) throws IOException {
        JsonMapper json = JsonMapper.builder().build();
        JsonNode cfg = readJson(json, dir.resolve("rl_agent_config.json"));
        JsonNode encCfg = readJson(json, dir.resolve("encoder/config.json"));
        Path tokCfgFile = dir.resolve("tokenizer/tokenizer_config.json");
        JsonNode tokCfg = Files.isRegularFile(tokCfgFile) ? readJson(json, tokCfgFile) : json.createObjectNode();
        this.k = kernels;
        this.name = cfg.path("model_name").asString("laya");
        this.tokenizer = BpeTokenizer.fromTokenizerJson(dir.resolve("tokenizer/tokenizer.json"));
        this.clsId = specialId(tokCfg, "cls_token");
        this.sepId = specialId(tokCfg, "sep_token");
        this.maskId = specialId(tokCfg, "mask_token");
        this.maskToken = tokenizer.token(maskId);
        this.maxLen = cfg.path("max_len").asInt(512);
        this.headMaxLen = cfg.path("head_max_len").asInt(192);
        this.temperature = new double[] {1, 1, 1};
        JsonNode t = cfg.path("temperature");
        for (int i = 0; i < 3 && i < t.size(); i++) {
            temperature[i] = clamp(t.get(i).asDouble(1.0));
        }
        this.temperatureByOptions = new HashMap<>();
        for (Map.Entry<String, JsonNode> e : cfg.path("temperature_by_options").properties()) {
            temperatureByOptions.put(e.getKey(), clamp(e.getValue().asDouble(1.0)));
        }
        if ("parallel".equals(cfg.path("option_layout").asString("sequential"))) {
            throw new IOException("Laya-Checkpoints mit paralleler Optionsanordnung werden nicht unterstützt");
        }

        this.weights = SafeTensors.open(dir.resolve("model.safetensors"));
        try {
            this.encoder = new ModernBert(weights, "encoder.", ModernBert.Config.of(encCfg), kernels);
            int layers = cfg.path("head_layers").asInt(2);
            this.head = new HeadLayer[layers];
            for (int l = 0; l < layers; l++) {
                String p = "head.layers." + l + ".";
                head[l] = new HeadLayer(weights.floats(p + "norm1.weight"), weights.floats(p + "norm1.bias"),
                        weights.tensor(p + "self_attn.in_proj_weight"), weights.floats(p + "self_attn.in_proj_bias"),
                        weights.tensor(p + "self_attn.out_proj.weight"), weights.floats(p + "self_attn.out_proj.bias"),
                        weights.floats(p + "norm2.weight"), weights.floats(p + "norm2.bias"),
                        weights.tensor(p + "linear1.weight"), weights.floats(p + "linear1.bias"),
                        weights.tensor(p + "linear2.weight"), weights.floats(p + "linear2.bias"));
            }
            this.typeEmb = weights.floats("type_emb.weight");
            this.scorerNormW = weights.floats("scorer.0.weight");
            this.scorerNormB = weights.floats("scorer.0.bias");
            this.scorer1 = weights.tensor("scorer.1.weight");
            this.scorer1B = weights.floats("scorer.1.bias");
            this.scorer2 = weights.floats("scorer.3.weight");
            this.scorer2B = weights.floats("scorer.3.bias")[0];
        } catch (RuntimeException e) {
            weights.close();
            throw e;
        }
    }

    /** Lädt einen Checkpoint-Ordner (wie von Hugging Face: {@code model.safetensors}, {@code rl_agent_config.json} …). */
    public static LayaModel load(Path dir, Kernels kernels) throws IOException {
        return new LayaModel(dir, kernels);
    }

    private static JsonNode readJson(JsonMapper json, Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return json.readTree(in);
        }
    }

    private int specialId(JsonNode tokCfg, String key) throws IOException {
        JsonNode v = tokCfg.path(key);
        String token = v.isObject() ? v.path("content").asString() : v.asString(null);
        int id = token == null ? -1 : tokenizer.id(token);
        // Ohne tokenizer_config.json: die üblichen Namen (BERT, Gemma/mmBERT, RoBERTa)
        String[] fallback = switch (key) {
            case "cls_token" -> new String[] {"[CLS]", "<bos>", "<s>"};
            case "sep_token" -> new String[] {"[SEP]", "<eos>", "</s>"};
            default -> new String[] {"[MASK]", "<mask>"};
        };
        for (int i = 0; id < 0 && i < fallback.length; i++) {
            id = tokenizer.id(fallback[i]);
        }
        if (id < 0) {
            throw new IOException("Sondertoken " + key + " fehlt im Tokenizer");
        }
        return id;
    }

    private static double clamp(double t) {
        return Double.isFinite(t) ? Math.min(TEMP_MAX, Math.max(TEMP_MIN, t)) : 1.0;
    }

    public String name() {
        return name;
    }

    public int maxLen() {
        return maxLen;
    }

    public BpeTokenizer tokenizer() {
        return tokenizer;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Optionen wie laya.common.render_options

    /** {@code choice}: „label“ oder „label: Beschreibung“. */
    public static List<String> renderChoice(List<String> labels, Map<String, String> descriptions) {
        List<String> out = new ArrayList<>();
        for (String label : labels) {
            String d = descriptions == null ? null : descriptions.get(label);
            out.add(d == null || d.isEmpty() ? label : label + ": " + d);
        }
        return out;
    }

    /** {@code score}: „level i: Kriterium“. */
    public static List<String> renderScore(List<String> levels) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < levels.size(); i++) {
            out.add("level " + i + ": " + levels.get(i));
        }
        return out;
    }

    /** {@code noul}: immer [false, true]. */
    public static List<String> renderNoul(String falseCriterion, String trueCriterion) {
        return List.of(
                "false: " + (falseCriterion == null || falseCriterion.isEmpty() ? "no, the statement does not hold" : falseCriterion),
                "true: " + (trueCriterion == null || trueCriterion.isEmpty() ? "yes, the statement holds" : trueCriterion));
    }

    /** Temperatur für Fragetyp und Optionsanzahl aus dem Checkpoint, begrenzt auf [0,5; 5]. */
    public double temperature(QType type, int options) {
        String size = options <= 2 ? "2" : options <= 5 ? "3-5" : options <= 10 ? "6-10" : "11+";
        Double bucket = temperatureByOptions.get(type.label + ":" + size);
        return bucket != null ? bucket : temperature[type.ordinal()];
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Sequenz und Vorwärtsrechnung

    record Sequence(int[] ids, int[] markers, int stateUsed) {
    }

    Sequence build(Question q, int[] stateIds) {
        String mask = maskToken;
        String ins = q.instructions().replace(mask, " ");
        int[] headIds = tokenizer.encode(q.type().label + " question: " + ins);
        List<int[]> optIds = new ArrayList<>();
        for (String opt : q.options()) {
            int[] o = tokenizer.encode(" " + opt.replace(mask, " "));
            int[] withMask = new int[Math.min(o.length, MAX_OPTION_TOKENS) + 1];
            withMask[0] = maskId;
            System.arraycopy(o, 0, withMask, 1, withMask.length - 1);
            optIds.add(withMask);
        }
        int budget = headMaxLen - optIds.stream().mapToInt(o -> o.length).sum();
        if (budget < 16) {
            int per = Math.max(4, (headMaxLen - 16) / Math.max(1, optIds.size()));
            optIds.replaceAll(o -> Arrays.copyOf(o, Math.min(o.length, per)));
            budget = headMaxLen - optIds.stream().mapToInt(o -> o.length).sum();
        }
        headIds = Arrays.copyOf(headIds, Math.min(headIds.length, Math.max(8, budget)));
        BpeTokenizer.IntList ids = new BpeTokenizer.IntList();
        ids.add(clsId);
        ids.addAll(headIds);
        ids.add(sepId);
        int[] markers = new int[optIds.size()];
        for (int i = 0; i < optIds.size(); i++) {
            markers[i] = ids.size();
            ids.addAll(optIds.get(i));
        }
        ids.add(sepId);
        int room = Math.max(0, maxLen - ids.size() - 1);
        int used = Math.min(room, stateIds.length);
        ids.addAll(Arrays.copyOf(stateIds, used));
        ids.add(sepId);
        int[] all = ids.toArray();
        if (all.length > maxLen) {
            all = Arrays.copyOf(all, maxLen);
        }
        int[] kept = Arrays.stream(markers).filter(m -> m < maxLen).toArray();
        return new Sequence(all, kept, used);
    }

    /**
     * Rechnet alle Fragen zu einem Zustand in einem Durchlauf.
     *
     * @param state Zustand als Text (JSON-Dokumente bereits serialisiert)
     */
    public Result predict(List<Question> questions, String state) {
        int[] stateIds = tokenizer.encode(state.replace(maskToken, " "));
        List<Sequence> seqs = new ArrayList<>();
        for (Question q : questions) {
            Sequence s = build(q, stateIds);
            if (s.markers().length < q.options().size()) {
                throw new IllegalArgumentException("Zu viele oder zu lange Optionen: nur " + s.markers().length + " von "
                        + q.options().size() + " passen in die Sequenz – Optionen kürzen oder aufteilen.");
            }
            seqs.add(s);
        }
        // In Gruppen bis MAX_BATCH_TOKENS rechnen (wie max_tokens_per_batch bei Laya), damit viele Fragen zu einer
        // langen Eingabe nicht Hunderte MB Aktivierungen auf einmal brauchen
        List<float[]> logits = new ArrayList<>();
        int total = 0;
        int from = 0;
        while (from < seqs.size()) {
            int to = from;
            int tokens = 0;
            while (to < seqs.size() && (to == from || tokens + seqs.get(to).ids().length <= MAX_BATCH_TOKENS)) {
                tokens += seqs.get(to).ids().length;
                to++;
            }
            logits.addAll(forward(seqs.subList(from, to), questions.subList(from, to)));
            total += tokens;
            from = to;
        }
        int used = seqs.isEmpty() ? 0 : seqs.stream().mapToInt(Sequence::stateUsed).min().orElse(0);
        return new Result(logits, total, stateIds.length, stateIds.length - used);
    }

    /** Encoder, Kopf und Scorer für eine Gruppe von Sequenzen; Logits je Frage. */
    private List<float[]> forward(List<Sequence> seqs, List<Question> questions) {
        int[] lengths = seqs.stream().mapToInt(s -> s.ids().length).toArray();
        int[] ids = seqs.stream().flatMapToInt(s -> Arrays.stream(s.ids())).toArray();
        int n = ids.length;
        int d = encoder.config().hidden();

        float[] h = encoder.forward(ids, lengths);
        for (int s = 0, base = 0; s < seqs.size(); base += lengths[s], s++) {
            int qt = questions.get(s).type().ordinal();
            for (int t = base; t < base + lengths[s]; t++) {
                for (int i = 0; i < d; i++) {
                    h[t * d + i] += typeEmb[qt * d + i];
                }
            }
        }
        // Marker-Zeilen in den aneinandergehängten Zeilen; die letzte Kopf-Schicht braucht nur diese als Queries
        BpeTokenizer.IntList markerRows = new BpeTokenizer.IntList();
        for (int s = 0, base = 0; s < seqs.size(); base += lengths[s], s++) {
            for (int m : seqs.get(s).markers()) {
                markerRows.add(base + m);
            }
        }
        int[] rows = markerRows.toArray();
        float[] m = head(h, n, lengths, rows);

        // Scorer: LayerNorm → Linear → GELU → Linear(1)
        int r = rows.length;
        float[] mn = new float[r * d];
        Kernels.layerNorm(m, r, d, scorerNormW, scorerNormB, 1e-5f, mn);
        float[] z = new float[r * d];
        k.matmul(scorer1, mn, r, z, scorer1B);
        float[] logitsFlat = new float[r];
        for (int i = 0; i < r; i++) {
            float s = scorer2B;
            for (int j = 0; j < d; j++) {
                s += Kernels.gelu(z[i * d + j]) * scorer2[j];
            }
            logitsFlat[i] = s;
        }
        List<float[]> logits = new ArrayList<>();
        int pos = 0;
        for (Sequence s : seqs) {
            logits.add(Arrays.copyOfRange(logitsFlat, pos, pos + s.markers().length));
            pos += s.markers().length;
        }
        return logits;
    }

    /** Entscheidungskopf ({@code nn.TransformerEncoderLayer}, Pre-Norm, ReLU); liefert die Zeilen {@code rows}. */
    private float[] head(float[] h, int n, int[] lengths, int[] rows) {
        int d = encoder.config().hidden();
        int heads = Math.max(1, d / 64);
        int hd = d / heads;
        float[] xn = new float[n * d];
        float[] qkv = new float[n * 3 * d];
        for (int l = 0; l < head.length; l++) {
            HeadLayer L = head[l];
            boolean last = l == head.length - 1;
            Kernels.layerNorm(h, n, d, L.norm1W(), L.norm1B(), 1e-5f, xn);
            k.matmul(L.inProj(), xn, n, qkv, L.inProjB());
            int outRows = last ? rows.length : n;
            float[] att = new float[outRows * d];
            k.attention(qkv, 3 * d, 0, d, 2 * d, lengths, heads, hd, 0, last ? rows : null, att);
            float[] proj = new float[outRows * d];
            k.matmul(L.outProj(), att, outRows, proj, L.outProjB());
            float[] x;
            if (last) {
                x = new float[outRows * d];
                for (int i = 0; i < outRows; i++) {
                    System.arraycopy(h, rows[i] * d, x, i * d, d);
                }
            } else {
                x = h;
            }
            ModernBert.add(x, proj);
            float[] xn2 = new float[outRows * d];
            Kernels.layerNorm(x, outRows, d, L.norm2W(), L.norm2B(), 1e-5f, xn2);
            float[] f = new float[outRows * L.lin1().rows()];
            k.matmul(L.lin1(), xn2, outRows, f, L.lin1B());
            for (int i = 0; i < f.length; i++) {
                f[i] = Math.max(0f, f[i]);
            }
            k.matmul(L.lin2(), f, outRows, proj, L.lin2B());
            ModernBert.add(x, proj);
            if (last) {
                return x;
            }
        }
        // Ohne Kopf-Schichten: Marker-Zeilen direkt
        float[] x = new float[rows.length * d];
        for (int i = 0; i < rows.length; i++) {
            System.arraycopy(h, rows[i] * d, x, i * d, d);
        }
        return x;
    }

    @Override
    public void close() {
        weights.close();
    }
}
