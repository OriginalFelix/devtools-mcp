package systems.grebe.devtools.mcp.modules.decide.engine;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/**
 * Gewichtsmatrix {@code [rows × cols]}, wie sie in der Modelldatei liegt (per mmap, nicht im Heap). Eine Zeile ist
 * zusammenhängend gespeichert und wird bei Bedarf in {@code float} entpackt – so braucht ein Modell mit 650 MB kaum
 * Heap, und jede Zeile wird pro Matrixmultiplikation nur einmal entpackt (siehe {@link Kernels#matmul}).
 */
public final class Tensor {

    /** Speicherformate; quantisierte Typen in Blöcken zu 32 Werten wie in GGML. */
    public enum Type {
        F32, F16, BF16, Q8_0, Q4_0;

        /** Bytes für {@code n} Werte. */
        long bytes(long n) {
            return switch (this) {
                case F32 -> n * 4;
                case F16, BF16 -> n * 2;
                case Q8_0 -> n / 32 * 34;
                case Q4_0 -> n / 32 * 18;
            };
        }

        boolean blockQuantized() {
            return this == Q8_0 || this == Q4_0;
        }
    }

    static final ValueLayout.OfFloat F32_LE = ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    static final ValueLayout.OfShort I16_LE = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private static final ThreadLocal<byte[]> BYTES = ThreadLocal.withInitial(() -> new byte[0]);
    private static final ThreadLocal<short[]> SHORTS = ThreadLocal.withInitial(() -> new short[0]);

    private final String name;
    private final MemorySegment data;
    private final Type type;
    private final int rows;
    private final int cols;
    private final long rowBytes;

    public Tensor(String name, MemorySegment data, Type type, int rows, int cols) {
        if (type.blockQuantized() && cols % 32 != 0) {
            throw new IllegalArgumentException(name + ": " + cols + " Spalten passen nicht in Blöcke zu 32 (" + type + ")");
        }
        this.name = name;
        this.type = type;
        this.rows = rows;
        this.cols = cols;
        this.rowBytes = type.bytes(cols);
        if (data.byteSize() < rowBytes * rows) {
            throw new IllegalArgumentException(name + ": Datei zu kurz für " + rows + "×" + cols + " " + type);
        }
        this.data = data;
    }

    public String name() {
        return name;
    }

    public Type type() {
        return type;
    }

    public int rows() {
        return rows;
    }

    public int cols() {
        return cols;
    }

    /** Entpackt Zeile {@code r} nach {@code dst[off .. off+cols)}. */
    public void row(int r, float[] dst, int off) {
        long base = r * rowBytes;
        switch (type) {
            case F32 -> MemorySegment.copy(data, F32_LE, base, dst, off, cols);
            case F16 -> {
                short[] s = shorts(cols);
                MemorySegment.copy(data, I16_LE, base, s, 0, cols);
                for (int i = 0; i < cols; i++) {
                    dst[off + i] = Float.float16ToFloat(s[i]);
                }
            }
            case BF16 -> {
                short[] s = shorts(cols);
                MemorySegment.copy(data, I16_LE, base, s, 0, cols);
                for (int i = 0; i < cols; i++) {
                    dst[off + i] = Float.intBitsToFloat(s[i] << 16);
                }
            }
            case Q8_0 -> {
                byte[] b = bytes((int) rowBytes);
                MemorySegment.copy(data, ValueLayout.JAVA_BYTE, base, b, 0, (int) rowBytes);
                int o = 0;
                for (int blk = 0; blk < cols / 32; blk++) {
                    float d = Float.float16ToFloat((short) ((b[o] & 0xff) | (b[o + 1] << 8)));
                    o += 2;
                    int dOff = off + blk * 32;
                    for (int j = 0; j < 32; j++) {
                        dst[dOff + j] = b[o + j] * d;
                    }
                    o += 32;
                }
            }
            case Q4_0 -> {
                byte[] b = bytes((int) rowBytes);
                MemorySegment.copy(data, ValueLayout.JAVA_BYTE, base, b, 0, (int) rowBytes);
                int o = 0;
                for (int blk = 0; blk < cols / 32; blk++) {
                    float d = Float.float16ToFloat((short) ((b[o] & 0xff) | (b[o + 1] << 8)));
                    o += 2;
                    int dOff = off + blk * 32;
                    for (int j = 0; j < 16; j++) {
                        int q = b[o + j] & 0xff;
                        dst[dOff + j] = ((q & 0x0f) - 8) * d;
                        dst[dOff + j + 16] = ((q >>> 4) - 8) * d;
                    }
                    o += 16;
                }
            }
        }
    }

    /** Ganze Matrix als {@code float[]} (für kleine Tensoren: Normen, Bias, Typ-Embeddings). */
    public float[] toFloats() {
        float[] out = new float[Math.multiplyExact(rows, cols)];
        for (int r = 0; r < rows; r++) {
            row(r, out, r * cols);
        }
        return out;
    }

    private static byte[] bytes(int n) {
        byte[] b = BYTES.get();
        if (b.length < n) {
            b = new byte[n];
            BYTES.set(b);
        }
        return b;
    }

    private static short[] shorts(int n) {
        short[] s = SHORTS.get();
        if (s.length < n) {
            s = new short[n];
            SHORTS.set(s);
        }
        return s;
    }

    @Override
    public String toString() {
        return name + " " + type + " [" + rows + "×" + cols + "]";
    }
}
