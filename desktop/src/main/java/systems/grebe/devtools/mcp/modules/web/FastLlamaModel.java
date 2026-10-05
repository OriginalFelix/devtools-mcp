package systems.grebe.devtools.mcp.modules.web;

import java.io.File;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import com.github.tjake.jlama.math.VectorMath;
import com.github.tjake.jlama.model.AbstractModel;
import com.github.tjake.jlama.model.CausalSelfAttention;
import com.github.tjake.jlama.model.DistributedContext;
import com.github.tjake.jlama.model.MLPBlock;
import com.github.tjake.jlama.model.ModelSupport;
import com.github.tjake.jlama.model.RMSNorm;
import com.github.tjake.jlama.model.TransformerBlock;
import com.github.tjake.jlama.model.llama.LlamaConfig;
import com.github.tjake.jlama.model.llama.LlamaModel;
import com.github.tjake.jlama.model.llama.LlamaTokenizer;
import com.github.tjake.jlama.safetensors.Config;
import com.github.tjake.jlama.safetensors.DType;
import com.github.tjake.jlama.safetensors.SafeTensorSupport;
import com.github.tjake.jlama.safetensors.WeightLoader;
import com.github.tjake.jlama.safetensors.tokenizer.Tokenizer;
import com.github.tjake.jlama.tensor.AbstractTensor;
import com.github.tjake.jlama.tensor.KvBufferCache;
import com.github.tjake.jlama.tensor.operations.TensorOperations;
import com.github.tjake.jlama.tensor.operations.TensorOperationsProvider;
import com.github.tjake.jlama.util.JsonSupport;

/**
 * Jlamas Llama-Modell mit schnellerer Prefill-Phase (Verarbeitung der Eingabe), ohne Jlama zu forken: Die Unterklasse
 * baut ihre Transformer-Blöcke mit {@link FastAttention} und {@link FastRmsNorm}. Beide rechnen dasselbe in derselben
 * Reihenfolge wie Jlama 0.8.4 (gleiche Ausgabe Token für Token), nur anders organisiert:
 * <ul>
 *   <li>Jlamas Attention arbeitet die Eingabe Position für Position ab – RoPE seriell mit Einzelzugriffen, danach je
 *       Position und Layer ein eigenes paralleles {@code for} über die Köpfe (bei 500 Tokens × 16 Layern 8 000
 *       Mini-Parallelisierungen). {@link FastAttention} schreibt erst K/V aller Positionen, rotiert parallel über die
 *       Positionen und rechnet die Attention in <em>einem</em> parallelen {@code for} über alle (Position, Kopf).</li>
 *   <li>Jlamas RMSNorm rechnet seriell mit Einzelzugriffen je Element; {@link FastRmsNorm} parallel über die Zeilen auf
 *       {@code float[]}.</li>
 * </ul>
 * Gemessen auf einem i9-13950HX: Prefill etwa 25 % schneller. Beim Erzeugen (ein Token je Schritt) rechnet weiter
 * Jlamas eigener Code.
 */
final class FastLlamaModel extends LlamaModel {

    private FastLlamaModel(Config config, WeightLoader weights, Tokenizer tokenizer, DType workingDType,
                           DType workingQType) {
        super(InferenceType.FULL_GENERATION, config, weights, tokenizer, workingDType, workingQType, Optional.empty());
    }

    /**
     * Lädt ein Modell zum Erzeugen wie {@code ModelSupport.loadModel}. Llama-Modelle baut der Worker selbst – mit den
     * schnelleren Bausteinen ({@code optimized}) oder als Jlamas {@link LlamaModel} – und ohne Arbeitsverzeichnis: Dann
     * hält Jlama den KV-Cache im Speicher, statt ihn wie sonst als Dateien in einem Temp-Verzeichnis abzulegen, die beim
     * Schließen nicht gelöscht werden (rund 8 MB je 128 Positionen und Anfrage). Andere Modelltypen (Mistral, Qwen …)
     * lädt Jlama selbst; ihr KV-Cache landet in {@code scratchDir} (erst dann angelegt), das der Aufrufer aufräumt.
     */
    static AbstractModel load(File modelDir, Supplier<File> scratchDir, DType workingDType, DType workingQType,
                              boolean optimized) throws IOException {
        File configFile = new File(modelDir, "config.json");
        if (SafeTensorSupport.detectModel(configFile) != ModelSupport.ModelType.LLAMA) {
            return ModelSupport.loadModel(modelDir, scratchDir.get(), workingDType, workingQType, Optional.empty(),
                    Optional.empty());
        }
        Config config = JsonSupport.om.readValue(configFile, LlamaConfig.class);
        WeightLoader weights = SafeTensorSupport.loadWeights(modelDir);
        LlamaTokenizer tokenizer = new LlamaTokenizer(modelDir.toPath());
        return optimized
                ? new FastLlamaModel(config, weights, tokenizer, workingDType, workingQType)
                : new LlamaModel(InferenceType.FULL_GENERATION, config, weights, tokenizer, workingDType, workingQType,
                        Optional.empty());
    }

    /** Wie {@code LlamaModel.loadTransformerBlockWeights}, nur mit den schnelleren Bausteinen. */
    @Override
    protected TransformerBlock[] loadTransformerBlockWeights() {
        DType qType = modelQType.orElse(modelDType);
        DistributedContext dctx = c.dctx();
        TransformerBlock[] blocks = new TransformerBlock[dctx.numberOfLayers];
        IntStream.range(dctx.layerStart, dctx.layerEnd).parallel().forEach(i -> {
            int relativeLayer = i - dctx.layerStart;
            String base = "model.layers." + i + ".";
            String attention = base + "self_attn.";
            String mlp = base + "mlp.";
            blocks[relativeLayer] = new TransformerBlock(this, relativeLayer,
                    new FastRmsNorm(this, weights.load(base + "input_layernorm.weight").quantize(qType)),
                    new FastAttention(this, relativeLayer,
                            weights.load(attention + "q_proj.weight", dctx, true, false).quantize(qType),
                            weights.load(attention + "k_proj.weight", dctx, true, false).quantize(qType),
                            weights.load(attention + "v_proj.weight", dctx, true, false).quantize(qType),
                            weights.load(attention + "o_proj.weight", dctx, false, true).quantize(qType)),
                    new FastRmsNorm(this, weights.load(base + "post_attention_layernorm.weight").quantize(qType)),
                    new MLPBlock(this, c.activationFunction,
                            weights.load(mlp + "gate_proj.weight", dctx, true, false).quantize(qType),
                            weights.load(mlp + "down_proj.weight", dctx, false, true).quantize(qType),
                            weights.load(mlp + "up_proj.weight", dctx, true, false).quantize(qType)));
        });
        return blocks;
    }

    /** Quantisiert Aktivierungen wie Jlamas Attention ({@code maybeQuantize} ist protected). */
    AbstractTensor quantizeActivations(AbstractTensor t) {
        return maybeQuantize(t);
    }

    /** RMSNorm wie in Jlama, aber parallel über die Zeilen und auf {@code float[]} statt get/set je Element. */
    static final class FastRmsNorm extends RMSNorm {
        private final float[] w;

        FastRmsNorm(AbstractModel model, AbstractTensor weights) {
            super(model, weights);
            int length = model.getConfig().embeddingLength;
            this.w = new float[length];
            for (int j = 0; j < length; j++) {
                w[j] = weights.get(0, j);
            }
        }

        @Override
        public AbstractTensor forward(AbstractTensor input, int offset, int length) {
            if (input.dType() != DType.F32 || input.dims() != 2) {
                return super.forward(input, offset, length);
            }
            Config c = m.getConfig();
            AbstractTensor out = m.makeDenseTensor(input.shape());
            MemorySegment in = input.getMemorySegment();
            MemorySegment os = out.getMemorySegment();
            VectorMath.pfor(0, input.shape().first(), b -> {
                float[] row = new float[length];
                MemorySegment.copy(in, ValueLayout.JAVA_FLOAT_UNALIGNED, (long) input.getOffset(b, offset) * Float.BYTES,
                        row, 0, length);
                double ss = 0.0f;
                for (int j = 0; j < length; j++) {
                    float v = row[j];
                    ss += v * v;
                }
                ss /= c.embeddingLength;
                ss += c.layerNormEps;
                float scale = (float) (1.0 / Math.sqrt(ss));
                for (int j = 0; j < length; j++) {
                    row[j] = w[offset + j] * (scale * row[j]);
                }
                MemorySegment.copy(row, 0, os, ValueLayout.JAVA_FLOAT_UNALIGNED,
                        (long) out.getOffset(b, offset) * Float.BYTES, length);
            });
            return out;
        }
    }

    /**
     * Attention wie in Jlama (GQA ohne Bias und Soft-Capping – der Fall von Llama 3.x), für mehrere Positionen aber in
     * drei Phasen statt Position für Position. Eine einzelne Position (Erzeugen) und alle anderen Fälle rechnet Jlama.
     */
    static final class FastAttention extends CausalSelfAttention {
        private final FastLlamaModel m;
        private final Config c;
        private final DistributedContext d;
        private final int layer;
        private final AbstractTensor queryWeights;
        private final AbstractTensor keyWeights;
        private final AbstractTensor valueWeights;
        private final AbstractTensor outputWeights;
        private final float scale;
        private final int attentionLength;
        private final boolean supported;

        FastAttention(FastLlamaModel m, int layer, AbstractTensor queryWeights, AbstractTensor keyWeights,
                      AbstractTensor valueWeights, AbstractTensor outputWeights) {
            super(m, layer, queryWeights, keyWeights, valueWeights, outputWeights);
            this.m = m;
            this.c = m.getConfig();
            this.d = c.dctx();
            this.layer = layer;
            this.queryWeights = queryWeights;
            this.keyWeights = keyWeights;
            this.valueWeights = valueWeights;
            this.outputWeights = outputWeights;
            // wie im Konstruktor von CausalSelfAttention
            this.attentionLength = c.numberOfHeads * c.headSize;
            this.scale = c.attentionMultiplier != null ? c.attentionMultiplier : (float) (1.0 / StrictMath.sqrt(c.headSize));
            this.supported = c.isGQA && c.attnLogitSoftCapping == null;
        }

        @Override
        public AbstractTensor forward(AbstractTensor input, int startPosition, KvBufferCache.KvBuffer kvMem,
                                      Optional<Consumer<List<AbstractTensor>>> tensorReducer) {
            int batch = input.shape().first();
            if (!supported || batch == 1) {
                return super.forward(input, startPosition, kvMem, tensorReducer);
            }
            TensorOperations ops = TensorOperationsProvider.get();
            try (AbstractTensor queryBatch = m.makeDenseTensor(batch, attentionLength);
                 AbstractTensor tmpKeyBatch = m.makeDenseTensor(batch, c.kvLength);
                 AbstractTensor tmpValBatch = m.makeDenseTensor(batch, c.kvLength);
                 AbstractTensor valueBatch = m.makeDenseTensor(batch, attentionLength)) {
                VectorMath.pchunk(d.attentionSegmentStart, d.attentionSegmentLength, (start, length) ->
                        ops.dotProductChunk(queryBatch, input, queryWeights, 0, c.embeddingLength, start, length));
                VectorMath.pchunk(d.kvSegmentStart, d.kvSegmentLength, (start, length) -> {
                    ops.dotProductChunk(tmpKeyBatch, input, keyWeights, 0, c.embeddingLength, start, length);
                    ops.dotProductChunk(tmpValBatch, input, valueWeights, 0, c.embeddingLength, start, length);
                });

                // 1. K/V aller Positionen in den Cache (seriell: dabei entstehen Seiten des KV-Caches)
                AbstractTensor[] queries = new AbstractTensor[batch];
                AbstractTensor[] values = new AbstractTensor[batch];
                AbstractTensor[] keys = new AbstractTensor[batch];
                for (int bi = 0; bi < batch; bi++) {
                    int position = startPosition + bi;
                    AbstractTensor key = kvMem.getKeyTensorForPosition(layer, position);
                    AbstractTensor val = kvMem.getValTensorForPosition(layer, position);
                    AbstractTensor tmpKey = tmpKeyBatch.slice(bi);
                    AbstractTensor tmpVal = tmpValBatch.slice(bi);
                    if (key.dType() != tmpKey.dType()) {
                        // quantisierter KV-Cache: das kann nur Jlama selbst (bis hier ist nichts geschrieben)
                        return super.forward(input, startPosition, kvMem, tensorReducer);
                    }
                    key.copyFrom(tmpKey, tmpKey.getOffset(0, d.kvSegmentStart), key.getOffset(0, d.kvSegmentStart),
                            d.kvSegmentLength);
                    val.copyFrom(tmpVal, tmpVal.getOffset(0, d.kvSegmentStart), val.getOffset(0, d.kvSegmentStart),
                            d.kvSegmentLength);
                    keys[bi] = key;
                    queries[bi] = queryBatch.slice(bi);
                    values[bi] = valueBatch.slice(bi);
                }
                AbstractTensor[][] keyPages = new AbstractTensor[batch][];
                AbstractTensor[][] valuePages = new AbstractTensor[batch][];
                for (int bi = 0; bi < batch; bi++) {
                    keyPages[bi] = kvMem.getKeyTensorsUptoPosition(layer, startPosition + bi);
                    valuePages[bi] = kvMem.getValTensorsUptoPosition(layer, startPosition + bi);
                }

                // 2. RoPE, parallel über die Positionen (Rechnung wie in Jlama)
                c.ropeFreqs.ifPresent(rf -> VectorMath.pfor(0, batch, bi -> rope(rf, queries[bi], keys[bi],
                        startPosition + bi)));

                // 3. Attention in einem parallelen for über alle (Position, Kopf)
                int heads = d.headEnd - d.headStart;
                VectorMath.pfor(0, batch * heads, t -> attend(ops, queries[t / heads], values[t / heads],
                        keyPages[t / heads], valuePages[t / heads], startPosition + t / heads, d.headStart + t % heads));

                AbstractTensor result = m.makeDenseTensor(batch, c.embeddingLength);
                try (AbstractTensor vq = m.quantizeActivations(valueBatch)) {
                    VectorMath.pchunk(0, c.embeddingLength, (start, length) -> ops.dotProductChunk(result, vq,
                            outputWeights, d.attentionSegmentStart, d.attentionSegmentLength, start, length));
                    tensorReducer.ifPresent(func -> func.accept(Collections.singletonList(result)));
                }
                return result;
            }
        }

        private void attend(TensorOperations ops, AbstractTensor query, AbstractTensor value, AbstractTensor[] kvp,
                            AbstractTensor[] vvp, int position, int h) {
            int xoffset = c.maybeMapToGroupHead(h) * c.headSize;
            int yoffset = h * c.headSize;
            if (yoffset >= query.shape().last()) {
                return;
            }
            try (AbstractTensor attn = m.makeDenseTensor(1, kvp[0].shape().first() * kvp.length)) {
                for (int i = 0; i < kvp.length; i++) {
                    int len = kvp[i].shape().first();
                    int offset = i * len;
                    int size = i == kvp.length - 1 ? (position + 1) - offset : len;
                    ops.batchDotProduct(attn, query, kvp[i], yoffset, xoffset, c.headSize, offset, 0, size);
                }
                ops.scale(scale, attn, 0, position + 1);
                VectorMath.softMax(attn, 0, position + 1);
                for (int i = 0; i < vvp.length; i++) {
                    int len = vvp[i].shape().first();
                    int offset = i * len;
                    int size = i == vvp.length - 1 ? (position + 1) - offset : len;
                    ops.saxpy(attn, vvp[i], value, xoffset, yoffset, c.headSize, offset, 0, size);
                }
            }
        }

        private void rope(float[][] rf, AbstractTensor query, AbstractTensor key, int position) {
            int headPiece = c.headSize / 2;
            int poffset = position * headPiece;
            for (int h = d.headStart; h < d.headEnd; h++) {
                int offset = h * c.headSize;
                if (offset >= query.shape().last()) {
                    break;
                }
                int goffset = c.maybeMapToGroupHead(h) * c.headSize;
                for (int i = offset, g = goffset; i < offset + headPiece; i++, g++) {
                    float q0 = query.get(0, i);
                    float q1 = query.get(0, i + headPiece);
                    float[] f = rf[poffset + g];
                    query.set(q0 * f[0] - q1 * f[1], 0, i);
                    query.set(q0 * f[1] + q1 * f[0], 0, i + headPiece);
                }
            }
            for (int h = d.groupHeadStart; h < d.groupHeadEnd; h++) {
                int offset = h * c.headSize;
                if (offset >= key.shape().last()) {
                    break;
                }
                for (int i = offset; i < offset + headPiece; i++) {
                    float k0 = key.get(0, i);
                    float k1 = key.get(0, i + headPiece);
                    float[] f = rf[poffset + i];
                    key.set(k0 * f[0] - k1 * f[1], 0, i);
                    key.set(k0 * f[1] + k1 * f[0], 0, i + headPiece);
                }
            }
        }
    }
}
