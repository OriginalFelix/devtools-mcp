package systems.grebe.devtools.mcp.modules.decide.engine;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Liest eine GGUF-Datei (llama.cpp, Version 2/3): Metadaten (Schlüssel → Wert) und Tensoren, die Daten per mmap.
 * Unterstützte Tensor-Typen: F32, F16, BF16, Q8_0, Q4_0.
 */
public final class Gguf implements AutoCloseable {

    private static final int MAGIC = 0x46554747; // "GGUF" little-endian
    private static final ValueLayout.OfInt I32 = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong I64 = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfShort I16 = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfFloat F32 = ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfDouble F64 = ValueLayout.JAVA_DOUBLE_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private record Info(long[] dims, int ggmlType, long offset) {
    }

    private final Arena arena;
    private final MemorySegment file;
    private final Map<String, Object> metadata = new LinkedHashMap<>();
    private final Map<String, Info> tensors = new LinkedHashMap<>();
    private long dataStart;
    private long pos;

    private Gguf(Arena arena, MemorySegment file) {
        this.arena = arena;
        this.file = file;
    }

    public static Gguf open(Path path) throws IOException {
        Arena arena = Arena.ofShared();
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
            Gguf g = new Gguf(arena, ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size(), arena));
            g.parse(path);
            return g;
        } catch (IOException | RuntimeException e) {
            arena.close();
            throw e;
        }
    }

    private void parse(Path path) throws IOException {
        if (file.byteSize() < 24 || file.get(I32, 0) != MAGIC) {
            throw new IOException("Keine GGUF-Datei: " + path);
        }
        int version = file.get(I32, 4);
        if (version < 2 || version > 3) {
            throw new IOException("GGUF-Version " + version + " wird nicht unterstützt: " + path);
        }
        long nTensors = file.get(I64, 8);
        long nKv = file.get(I64, 16);
        pos = 24;
        for (long i = 0; i < nKv; i++) {
            String key = string();
            metadata.put(key, value(file.get(I32, advance(4))));
        }
        for (long i = 0; i < nTensors; i++) {
            String name = string();
            int nDims = file.get(I32, advance(4));
            long[] dims = new long[nDims];
            for (int d = 0; d < nDims; d++) {
                dims[d] = file.get(I64, advance(8));
            }
            int type = file.get(I32, advance(4));
            long offset = file.get(I64, advance(8));
            tensors.put(name, new Info(dims, type, offset));
        }
        long alignment = metadata.get("general.alignment") instanceof Number n ? n.longValue() : 32;
        dataStart = (pos + alignment - 1) / alignment * alignment;
    }

    private long advance(long n) {
        long p = pos;
        pos += n;
        return p;
    }

    private String string() {
        long len = file.get(I64, advance(8));
        byte[] b = file.asSlice(pos, len).toArray(ValueLayout.JAVA_BYTE);
        pos += len;
        return new String(b, StandardCharsets.UTF_8);
    }

    private Object value(int type) {
        return switch (type) {
            case 0 -> file.get(ValueLayout.JAVA_BYTE, advance(1)) & 0xff;
            case 1 -> (int) file.get(ValueLayout.JAVA_BYTE, advance(1));
            case 2 -> file.get(I16, advance(2)) & 0xffff;
            case 3 -> (int) file.get(I16, advance(2));
            case 4 -> file.get(I32, advance(4)) & 0xffffffffL;
            case 5 -> file.get(I32, advance(4));
            case 6 -> file.get(F32, advance(4));
            case 7 -> file.get(ValueLayout.JAVA_BYTE, advance(1)) != 0;
            case 8 -> string();
            case 9 -> {
                int elemType = file.get(I32, advance(4));
                long n = file.get(I64, advance(8));
                List<Object> list = new ArrayList<>((int) Math.min(n, Integer.MAX_VALUE));
                for (long i = 0; i < n; i++) {
                    list.add(value(elemType));
                }
                yield list;
            }
            case 10, 11 -> file.get(I64, advance(8));
            case 12 -> file.get(F64, advance(8));
            default -> throw new IllegalStateException("Unbekannter GGUF-Werttyp " + type);
        };
    }

    public Map<String, Object> metadata() {
        return metadata;
    }

    public String string(String key, String fallback) {
        return metadata.get(key) instanceof String s ? s : fallback;
    }

    public long number(String key, long fallback) {
        return metadata.get(key) instanceof Number n ? n.longValue() : fallback;
    }

    public double real(String key, double fallback) {
        return metadata.get(key) instanceof Number n ? n.doubleValue() : fallback;
    }

    @SuppressWarnings("unchecked")
    public <T> List<T> list(String key) {
        return metadata.get(key) instanceof List<?> l ? (List<T>) l : List.of();
    }

    public boolean has(String tensor) {
        return tensors.containsKey(tensor);
    }

    /** Tensor als Matrix {@code [dims[1..] × dims[0]]} (GGML: {@code dims[0]} ist die zusammenhängende Dimension). */
    public Tensor tensor(String name) {
        Info info = tensors.get(name);
        if (info == null) {
            throw new IllegalArgumentException("Tensor fehlt in der Modelldatei: " + name);
        }
        Tensor.Type type = switch (info.ggmlType) {
            case 0 -> Tensor.Type.F32;
            case 1 -> Tensor.Type.F16;
            case 2 -> Tensor.Type.Q4_0;
            case 8 -> Tensor.Type.Q8_0;
            case 30 -> Tensor.Type.BF16;
            default -> throw new IllegalArgumentException(name + ": GGML-Typ " + info.ggmlType + " wird nicht unterstützt "
                    + "(nur F32, F16, BF16, Q8_0, Q4_0) – bitte eine Q8_0- oder F16-Variante des Modells verwenden.");
        };
        long cols = info.dims[0];
        long rows = 1;
        for (int i = 1; i < info.dims.length; i++) {
            rows *= info.dims[i];
        }
        long bytes = type.bytes(cols) * rows;
        return new Tensor(name, file.asSlice(dataStart + info.offset, bytes), type, Math.toIntExact(rows), Math.toIntExact(cols));
    }

    @Override
    public void close() {
        arena.close();
    }
}
