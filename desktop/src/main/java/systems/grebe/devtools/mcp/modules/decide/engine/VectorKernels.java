package systems.grebe.devtools.mcp.modules.decide.engine;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * SIMD-Varianten der heißen Schleifen über die Java Vector API ({@code jdk.incubator.vector}). Wird nur geladen, wenn
 * die JVM mit {@code --add-modules jdk.incubator.vector} läuft (siehe {@link Kernels#VECTOR}); sonst rechnen die
 * skalaren Schleifen in {@link Kernels}.
 */
final class VectorKernels {

    private static final VectorSpecies<Float> S = FloatVector.SPECIES_PREFERRED;

    private VectorKernels() {
    }

    static int lanes() {
        return S.length();
    }

    /** Skalarprodukt mit vier unabhängigen Vektor-Akkumulatoren (FMA). */
    static float dot(float[] a, int ao, float[] b, int bo, int n) {
        int step = S.length();
        int i = 0;
        FloatVector acc0 = FloatVector.zero(S);
        FloatVector acc1 = FloatVector.zero(S);
        FloatVector acc2 = FloatVector.zero(S);
        FloatVector acc3 = FloatVector.zero(S);
        for (; i + 4 * step <= n; i += 4 * step) {
            acc0 = FloatVector.fromArray(S, a, ao + i).fma(FloatVector.fromArray(S, b, bo + i), acc0);
            acc1 = FloatVector.fromArray(S, a, ao + i + step).fma(FloatVector.fromArray(S, b, bo + i + step), acc1);
            acc2 = FloatVector.fromArray(S, a, ao + i + 2 * step).fma(FloatVector.fromArray(S, b, bo + i + 2 * step), acc2);
            acc3 = FloatVector.fromArray(S, a, ao + i + 3 * step).fma(FloatVector.fromArray(S, b, bo + i + 3 * step), acc3);
        }
        for (; i + step <= n; i += step) {
            acc0 = FloatVector.fromArray(S, a, ao + i).fma(FloatVector.fromArray(S, b, bo + i), acc0);
        }
        float s = acc0.add(acc1).add(acc2.add(acc3)).reduceLanes(VectorOperators.ADD);
        for (; i < n; i++) {
            s += a[ao + i] * b[bo + i];
        }
        return s;
    }

    /**
     * Vier Skalarprodukte von vier aufeinanderfolgenden Zeilen {@code w[wo + r·n …]} mit demselben Vektor {@code x}:
     * {@code x} wird nur einmal geladen. Ergebnis nach {@code out[0..3]}.
     */
    static void dot4(float[] w, int wo, float[] x, int xo, int n, float[] out) {
        int step = S.length();
        int i = 0;
        FloatVector a0 = FloatVector.zero(S);
        FloatVector a1 = FloatVector.zero(S);
        FloatVector a2 = FloatVector.zero(S);
        FloatVector a3 = FloatVector.zero(S);
        int w1 = wo + n;
        int w2 = wo + 2 * n;
        int w3 = wo + 3 * n;
        for (; i + step <= n; i += step) {
            FloatVector xv = FloatVector.fromArray(S, x, xo + i);
            a0 = FloatVector.fromArray(S, w, wo + i).fma(xv, a0);
            a1 = FloatVector.fromArray(S, w, w1 + i).fma(xv, a1);
            a2 = FloatVector.fromArray(S, w, w2 + i).fma(xv, a2);
            a3 = FloatVector.fromArray(S, w, w3 + i).fma(xv, a3);
        }
        float s0 = a0.reduceLanes(VectorOperators.ADD);
        float s1 = a1.reduceLanes(VectorOperators.ADD);
        float s2 = a2.reduceLanes(VectorOperators.ADD);
        float s3 = a3.reduceLanes(VectorOperators.ADD);
        for (; i < n; i++) {
            float xi = x[xo + i];
            s0 += w[wo + i] * xi;
            s1 += w[w1 + i] * xi;
            s2 += w[w2 + i] * xi;
            s3 += w[w3 + i] * xi;
        }
        out[0] = s0;
        out[1] = s1;
        out[2] = s2;
        out[3] = s3;
    }

    /** {@code y[yo + i] += alpha · x[xo + i]} (gewichtete Summe der Values in der Attention). */
    static void axpy(float alpha, float[] x, int xo, float[] y, int yo, int n) {
        int step = S.length();
        int i = 0;
        FloatVector av = FloatVector.broadcast(S, alpha);
        for (; i + step <= n; i += step) {
            FloatVector.fromArray(S, x, xo + i).fma(av, FloatVector.fromArray(S, y, yo + i)).intoArray(y, yo + i);
        }
        for (; i < n; i++) {
            y[yo + i] += alpha * x[xo + i];
        }
    }
}
