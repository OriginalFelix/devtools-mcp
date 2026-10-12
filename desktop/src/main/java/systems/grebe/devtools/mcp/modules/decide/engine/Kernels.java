package systems.grebe.devtools.mcp.modules.decide.engine;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.function.IntConsumer;
import java.util.stream.IntStream;

/**
 * Rechenkerne der Inferenz in reinem Java: Matrixmultiplikation (parallel über Zeilenblöcke), Normen, Aktivierungen,
 * RoPE. Keine nativen Bibliotheken. Läuft die JVM mit {@code --add-modules jdk.incubator.vector}, rechnen die heißen
 * Schleifen mit der Vector API (SIMD, {@link VectorKernels}), sonst skalar mit mehreren Akkumulatoren.
 *
 * <p>Konvention: Aktivierungen sind zeilenweise {@code [n × dim]} in einem {@code float[]} abgelegt.
 */
public final class Kernels {

    /** Zeilen einer Gewichtsmatrix, die ein Task am Stück bearbeitet – der Eingabevektor bleibt dabei im L1-Cache. */
    private static final int ROW_BLOCK = 16;

    private static final ThreadLocal<float[]> ROWS = ThreadLocal.withInitial(() -> new float[0]);

    /**
     * Ob die Vector API verfügbar ist (Modul {@code jdk.incubator.vector} geladen); {@code -Ddevtools.decide.scalar=true}
     * erzwingt die skalaren Schleifen. Konstant, damit der JIT die Verzweigung wegfaltet.
     */
    static final boolean VECTOR = detectVector();

    private static boolean detectVector() {
        if (Boolean.getBoolean("devtools.decide.scalar")
                || ModuleLayer.boot().findModule("jdk.incubator.vector").isEmpty()) {
            return false;
        }
        try {
            float[] a = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17};
            return VectorKernels.dot(a, 0, a, 0, a.length) == 1785f;
        } catch (LinkageError e) {
            return false;
        }
    }

    /** Für Status und Log: womit gerechnet wird. */
    public static String simd() {
        return VECTOR ? "Vector API (SIMD, " + VectorKernels.lanes() + " float-Lanes)"
                : "skalar (schneller mit --add-modules jdk.incubator.vector beim Start der App)";
    }

    private final ForkJoinPool pool;

    /** @param threads Anzahl Rechen-Threads, ≤ 0 = alle Prozessoren */
    public Kernels(int threads) {
        int n = threads > 0 ? threads : Runtime.getRuntime().availableProcessors();
        this.pool = new ForkJoinPool(Math.max(1, n));
    }

    public int threads() {
        return pool.getParallelism();
    }

    public void shutdown() {
        pool.shutdownNow();
    }

    /** Führt {@code body(i)} für {@code i ∈ [0, n)} parallel im eigenen Pool aus; prüft vorher auf Abbruch. */
    public void parallelFor(int n, IntConsumer body) {
        checkInterrupted();
        if (n <= 1 || pool.getParallelism() == 1) {
            for (int i = 0; i < n; i++) {
                body.accept(i);
            }
            return;
        }
        try {
            pool.submit(() -> IntStream.range(0, n).parallel().forEach(body)).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Inferenz abgebrochen");
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof RuntimeException re) {
                throw re;
            }
            if (c instanceof Error err) {
                throw err;
            }
            throw new IllegalStateException(c);
        }
    }

    static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Inferenz abgebrochen");
        }
    }

    /**
     * {@code out[t, r] = Σ_c x[t, c] · w[r, c] (+ bias[r])} für {@code n} Zeilen {@code x}. Jede Gewichtszeile wird
     * genau einmal entpackt und gegen alle {@code n} Eingabezeilen gerechnet.
     */
    public void matmul(Tensor w, float[] x, int n, float[] out, float[] bias) {
        int rows = w.rows();
        int cols = w.cols();
        int blocks = (rows + ROW_BLOCK - 1) / ROW_BLOCK;
        parallelFor(blocks, blk -> {
            int r0 = blk * ROW_BLOCK;
            int r1 = Math.min(rows, r0 + ROW_BLOCK);
            float[] buf = ROWS.get();
            if (buf.length < (r1 - r0) * cols) {
                buf = new float[ROW_BLOCK * cols];
                ROWS.set(buf);
            }
            for (int r = r0; r < r1; r++) {
                w.row(r, buf, (r - r0) * cols);
            }
            float[] four = new float[4];
            for (int t = 0; t < n; t++) {
                int xo = t * cols;
                int oo = t * rows;
                int r = r0;
                if (VECTOR) {
                    // vier Gewichtszeilen je Durchgang: die Eingabezeile wird nur einmal geladen
                    for (; r + 3 < r1; r += 4) {
                        VectorKernels.dot4(buf, (r - r0) * cols, x, xo, cols, four);
                        for (int j = 0; j < 4; j++) {
                            out[oo + r + j] = bias == null ? four[j] : four[j] + bias[r + j];
                        }
                    }
                }
                for (; r < r1; r++) {
                    float s = dot(buf, (r - r0) * cols, x, xo, cols);
                    out[oo + r] = bias == null ? s : s + bias[r];
                }
            }
        });
    }

    /**
     * Bidirektionale Multi-Head-Attention über mehrere aneinandergehängte Sequenzen ({@code lengths}); Q, K und V
     * liegen zeilenweise mit Schrittweite {@code stride} ab Offset {@code qOff}/{@code kOff}/{@code vOff} (z.B. die
     * fusionierte QKV-Projektion). {@code window > 0} beschränkt auf {@code |i − j| ≤ window} (lokale Attention).
     *
     * @param queries Zeilen, für die das Ergebnis gebraucht wird (Index in die aneinandergehängten Zeilen), oder
     *                {@code null} = alle; {@code out} hat dann eine Zeile je Eintrag
     */
    public void attention(float[] qkv, int stride, int qOff, int kOff, int vOff, int[] lengths, int heads, int headDim,
                          int window, int[] queries, float[] out) {
        int total = 0;
        for (int l : lengths) {
            total += l;
        }
        int[] segStart = new int[total];
        int[] segLen = new int[total];
        for (int s = 0, base = 0; s < lengths.length; base += lengths[s], s++) {
            for (int i = 0; i < lengths[s]; i++) {
                segStart[base + i] = base;
                segLen[base + i] = lengths[s];
            }
        }
        int dim = heads * headDim;
        float scale = (float) (1.0 / Math.sqrt(headDim));
        int n = queries == null ? total : queries.length;
        parallelFor(n, qi -> {
            int t = queries == null ? qi : queries[qi];
            int base = segStart[t];
            int pos = t - base;
            int from = window > 0 ? Math.max(0, pos - window) : 0;
            int to = window > 0 ? Math.min(segLen[t] - 1, pos + window) : segLen[t] - 1;
            float[] scores = new float[to - from + 1];
            for (int h = 0; h < heads; h++) {
                int q = t * stride + qOff + h * headDim;
                for (int j = from; j <= to; j++) {
                    scores[j - from] = dot(qkv, q, qkv, (base + j) * stride + kOff + h * headDim, headDim) * scale;
                }
                softmax(scores, 0, scores.length);
                int o = qi * dim + h * headDim;
                java.util.Arrays.fill(out, o, o + headDim, 0f);
                for (int j = from; j <= to; j++) {
                    axpy(scores[j - from], qkv, (base + j) * stride + vOff + h * headDim, out, o, headDim);
                }
            }
        });
    }

    /** Skalarprodukt; mit Vector API als SIMD, sonst {@link #scalarDot}. */
    public static float dot(float[] a, int ao, float[] b, int bo, int n) {
        return VECTOR ? VectorKernels.dot(a, ao, b, bo, n) : scalarDot(a, ao, b, bo, n);
    }

    /** {@code y[yo + i] += alpha · x[xo + i]}. */
    public static void axpy(float alpha, float[] x, int xo, float[] y, int yo, int n) {
        if (VECTOR) {
            VectorKernels.axpy(alpha, x, xo, y, yo, n);
            return;
        }
        for (int i = 0; i < n; i++) {
            y[yo + i] += alpha * x[xo + i];
        }
    }

    /** Skalarprodukt mit acht unabhängigen Akkumulatoren (Fließkomma-Reihenfolge ⇒ der JIT darf sonst nicht bündeln). */
    static float scalarDot(float[] a, int ao, float[] b, int bo, int n) {
        float s0 = 0, s1 = 0, s2 = 0, s3 = 0, s4 = 0, s5 = 0, s6 = 0, s7 = 0;
        int i = 0;
        for (; i + 7 < n; i += 8) {
            s0 += a[ao + i] * b[bo + i];
            s1 += a[ao + i + 1] * b[bo + i + 1];
            s2 += a[ao + i + 2] * b[bo + i + 2];
            s3 += a[ao + i + 3] * b[bo + i + 3];
            s4 += a[ao + i + 4] * b[bo + i + 4];
            s5 += a[ao + i + 5] * b[bo + i + 5];
            s6 += a[ao + i + 6] * b[bo + i + 6];
            s7 += a[ao + i + 7] * b[bo + i + 7];
        }
        for (; i < n; i++) {
            s0 += a[ao + i] * b[bo + i];
        }
        return ((s0 + s1) + (s2 + s3)) + ((s4 + s5) + (s6 + s7));
    }

    /** LayerNorm je Zeile; {@code bias} darf fehlen (ModernBERT ohne Bias). */
    public static void layerNorm(float[] x, int n, int dim, float[] weight, float[] bias, float eps, float[] out) {
        for (int t = 0; t < n; t++) {
            int o = t * dim;
            double mean = 0;
            for (int i = 0; i < dim; i++) {
                mean += x[o + i];
            }
            mean /= dim;
            double var = 0;
            for (int i = 0; i < dim; i++) {
                double d = x[o + i] - mean;
                var += d * d;
            }
            var /= dim;
            float inv = (float) (1.0 / Math.sqrt(var + eps));
            float m = (float) mean;
            for (int i = 0; i < dim; i++) {
                float v = (x[o + i] - m) * inv * weight[i];
                out[o + i] = bias == null ? v : v + bias[i];
            }
        }
    }

    /** RMSNorm je Zeile (Llama/Qwen). */
    public static void rmsNorm(float[] x, int n, int dim, float[] weight, float eps, float[] out) {
        for (int t = 0; t < n; t++) {
            int o = t * dim;
            double ss = 0;
            for (int i = 0; i < dim; i++) {
                ss += (double) x[o + i] * x[o + i];
            }
            float inv = (float) (1.0 / Math.sqrt(ss / dim + eps));
            for (int i = 0; i < dim; i++) {
                out[o + i] = x[o + i] * inv * weight[i];
            }
        }
    }

    /** RMSNorm in-place über {@code v[off .. off+n)} (Qwen3: Q/K-Norm je Kopf). */
    public static void rmsNorm(float[] v, int off, int n, float[] weight, float eps) {
        double ss = 0;
        for (int i = 0; i < n; i++) {
            ss += (double) v[off + i] * v[off + i];
        }
        float inv = (float) (1.0 / Math.sqrt(ss / n + eps));
        for (int i = 0; i < n; i++) {
            v[off + i] = v[off + i] * inv * weight[i];
        }
    }

    /** GELU exakt über die Fehlerfunktion ({@code nn.GELU()}, {@code ACT2FN["gelu"]}). */
    public static float gelu(float x) {
        return (float) (0.5 * x * (1.0 + erf(x / Math.sqrt(2.0))));
    }

    public static float silu(float x) {
        return x / (1f + (float) Math.exp(-x));
    }

    /**
     * Fehlerfunktion nach W. J. Cody (rationale Approximation, relativer Fehler &lt; 1e-15) – genau genug, dass sich
     * die Logits nicht von PyTorch unterscheiden.
     */
    public static double erf(double x) {
        double ax = Math.abs(x);
        double r;
        if (ax < 0.5) {
            double t = x * x;
            double top = (((0.185777706184603153 * t + 3.16112374387056560) * t + 113.864154151050156) * t
                    + 377.485237685302021) * t + 3209.37758913846947;
            double bot = (((t + 23.6012909523441209) * t + 244.024637934444173) * t + 1282.61652607737228) * t
                    + 2844.23683343917062;
            return x * top / bot;
        }
        if (ax < 4.0) {
            double top = (((((((2.15311535474403846e-8 * ax + 5.64188496988670089e-1) * ax + 8.88314979438837594) * ax
                    + 66.1191906371416295) * ax + 298.635138197400131) * ax + 881.952221241769090) * ax
                    + 1712.04761263407058) * ax + 2051.07837782607147) * ax + 1230.33935479799725;
            double bot = (((((((ax + 15.7449261107098347) * ax + 117.693950891312499) * ax + 537.181101862009858) * ax
                    + 1621.38957456669019) * ax + 3290.79923573345963) * ax + 4362.61909014324716) * ax
                    + 3439.36767414372164) * ax + 1230.33935480374942;
            r = Math.exp(-ax * ax) * top / bot;
        } else {
            double z = 1.0 / (ax * ax);
            double top = ((((1.63153871373020978e-2 * z + 3.05326634961232344e-1) * z + 3.60344899949804439e-1) * z
                    + 1.25781726111229246e-1) * z + 1.60837851487422766e-2) * z + 6.58749161529837803e-4;
            double bot = ((((z + 2.56852019228982242) * z + 1.87295284992346725) * z + 5.27905102951428412e-1) * z
                    + 6.05183413124413191e-2) * z + 2.33520497626869185e-3;
            r = (1.0 / Math.sqrt(Math.PI) - z * top / bot) / ax * Math.exp(-ax * ax);
        }
        double erf = 1.0 - r;
        return x < 0 ? -erf : erf;
    }

    /** Softmax in-place über {@code x[off .. off+n)}. */
    public static void softmax(float[] x, int off, int n) {
        float max = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            max = Math.max(max, x[off + i]);
        }
        float sum = 0;
        for (int i = 0; i < n; i++) {
            float e = (float) Math.exp(x[off + i] - max);
            x[off + i] = e;
            sum += e;
        }
        float inv = 1f / sum;
        for (int i = 0; i < n; i++) {
            x[off + i] *= inv;
        }
    }

    /** Kosinus-/Sinus-Tabellen für RoPE: {@code [pos × half]}. */
    public record Rope(float[] cos, float[] sin, int half) {

        public static Rope of(int headDim, double theta, int positions) {
            int half = headDim / 2;
            float[] cos = new float[positions * half];
            float[] sin = new float[positions * half];
            for (int i = 0; i < half; i++) {
                double invFreq = 1.0 / Math.pow(theta, (2.0 * i) / headDim);
                for (int p = 0; p < positions; p++) {
                    // In float wie PyTorch (inv_freq und Positionen als float32 multipliziert)
                    float angle = (float) p * (float) invFreq;
                    cos[p * half + i] = (float) Math.cos(angle);
                    sin[p * half + i] = (float) Math.sin(angle);
                }
            }
            return new Rope(cos, sin, half);
        }

        public int positions() {
            return cos.length / half;
        }

        /** Dreht einen Kopf {@code v[off .. off+2·half)} um Position {@code pos}; NeoX-Stil (Hälften), wie HF. */
        public void applyHalves(float[] v, int off, int pos) {
            int c = pos * half;
            for (int i = 0; i < half; i++) {
                float x0 = v[off + i];
                float x1 = v[off + i + half];
                v[off + i] = x0 * cos[c + i] - x1 * sin[c + i];
                v[off + i + half] = x1 * cos[c + i] + x0 * sin[c + i];
            }
        }

        /** Dreht benachbarte Paare (2i, 2i+1) – GGUF-Llama mit permutierten Q/K-Gewichten. */
        public void applyPairs(float[] v, int off, int pos) {
            int c = pos * half;
            for (int i = 0; i < half; i++) {
                float x0 = v[off + 2 * i];
                float x1 = v[off + 2 * i + 1];
                v[off + 2 * i] = x0 * cos[c + i] - x1 * sin[c + i];
                v[off + 2 * i + 1] = x1 * cos[c + i] + x0 * sin[c + i];
            }
        }
    }
}
