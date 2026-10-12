package systems.grebe.devtools.mcp.modules.decide.engine;

import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** SIMD- und skalare Rechenkerne liefern dasselbe. */
class KernelsTest {

    @Test
    void vectorKernelsMatchScalar() {
        assumeTrue(Kernels.VECTOR, "JVM ohne --add-modules jdk.incubator.vector");
        Random rnd = new Random(7);
        for (int n : new int[] {1, 3, 15, 64, 100, 768, 1153}) {
            float[] a = random(rnd, 4 * n + 5);
            float[] b = random(rnd, n + 3);
            float scalar = Kernels.scalarDot(a, 2, b, 1, n);
            assertThat(VectorKernels.dot(a, 2, b, 1, n)).isCloseTo(scalar, within(1e-3f * (1 + Math.abs(scalar))));
            float[] four = new float[4];
            VectorKernels.dot4(a, 1, b, 2, n, four);
            for (int r = 0; r < 4; r++) {
                float s = Kernels.scalarDot(a, 1 + r * n, b, 2, n);
                assertThat(four[r]).isCloseTo(s, within(1e-3f * (1 + Math.abs(s))));
            }
            float[] y1 = random(rnd, n);
            float[] y2 = y1.clone();
            VectorKernels.axpy(0.37f, a, 3, y1, 0, n);
            for (int i = 0; i < n; i++) {
                y2[i] += 0.37f * a[3 + i];
            }
            for (int i = 0; i < n; i++) {
                assertThat(y1[i]).isCloseTo(y2[i], within(1e-5f));
            }
        }
    }

    @Test
    void matmulUsesBiasAndAllRows() {
        float[] w = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};
        Tensor t = new Tensor("w", java.lang.foreign.MemorySegment.ofArray(w), Tensor.Type.F32, 5, 3);
        float[] x = {1, 0, -1, 2, 1, 0};
        float[] out = new float[10];
        Kernels k = new Kernels(2);
        try {
            k.matmul(t, x, 2, out, new float[] {0, 0, 0, 0, 100});
        } finally {
            k.shutdown();
        }
        assertThat(out).containsExactly(-2, -2, -2, -2, 98, 4, 13, 22, 31, 140);
    }

    static float[] random(Random rnd, int n) {
        float[] a = new float[n];
        for (int i = 0; i < n; i++) {
            a[i] = (float) rnd.nextGaussian();
        }
        return a;
    }
}
