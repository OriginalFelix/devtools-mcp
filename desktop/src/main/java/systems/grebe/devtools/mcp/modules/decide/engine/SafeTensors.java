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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Liest eine {@code .safetensors}-Datei (Hugging Face): 8 Byte Länge, JSON-Kopf mit Name → dtype/shape/Offsets, danach
 * die Rohdaten. Die Daten werden per mmap eingeblendet; {@link #close()} gibt sie frei.
 */
public final class SafeTensors implements AutoCloseable {

    private record Entry(String dtype, long[] shape, long start, long end) {
    }

    private final Arena arena;
    private final MemorySegment file;
    private final long dataStart;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    private SafeTensors(Arena arena, MemorySegment file, long dataStart) {
        this.arena = arena;
        this.file = file;
        this.dataStart = dataStart;
    }

    public static SafeTensors open(Path path) throws IOException {
        Arena arena = Arena.ofShared();
        try (FileChannel ch = FileChannel.open(path, StandardOpenOption.READ)) {
            MemorySegment seg = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size(), arena);
            long headerLen = seg.get(ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), 0);
            if (headerLen <= 0 || headerLen > 100_000_000L || 8 + headerLen > seg.byteSize()) {
                throw new IOException("Keine gültige safetensors-Datei: " + path);
            }
            byte[] header = seg.asSlice(8, headerLen).toArray(ValueLayout.JAVA_BYTE);
            SafeTensors st = new SafeTensors(arena, seg, 8 + headerLen);
            JsonNode root = JsonMapper.builder().build().readTree(new String(header, StandardCharsets.UTF_8));
            for (Map.Entry<String, JsonNode> e : root.properties()) {
                if (e.getKey().equals("__metadata__")) {
                    continue;
                }
                JsonNode v = e.getValue();
                JsonNode shape = v.get("shape");
                long[] dims = new long[shape.size()];
                for (int i = 0; i < dims.length; i++) {
                    dims[i] = shape.get(i).asLong();
                }
                JsonNode off = v.get("data_offsets");
                st.entries.put(e.getKey(), new Entry(v.get("dtype").asString(), dims, off.get(0).asLong(), off.get(1).asLong()));
            }
            return st;
        } catch (IOException | RuntimeException e) {
            arena.close();
            throw e;
        }
    }

    public Set<String> names() {
        return entries.keySet();
    }

    public boolean has(String name) {
        return entries.containsKey(name);
    }

    /** Tensor als Matrix; 1-dimensionale Tensoren werden zu einer Zeile, mehrdimensionale zu {@code [Π dims[0..n-1] × dims[n-1]]}. */
    public Tensor tensor(String name) {
        Entry e = entries.get(name);
        if (e == null) {
            throw new IllegalArgumentException("Tensor fehlt in der Modelldatei: " + name);
        }
        Tensor.Type type = switch (e.dtype) {
            case "F32" -> Tensor.Type.F32;
            case "F16" -> Tensor.Type.F16;
            case "BF16" -> Tensor.Type.BF16;
            default -> throw new IllegalArgumentException(name + ": Datentyp " + e.dtype + " wird nicht unterstützt");
        };
        long cols = e.shape.length == 0 ? 1 : e.shape[e.shape.length - 1];
        long rows = 1;
        for (int i = 0; i < e.shape.length - 1; i++) {
            rows *= e.shape[i];
        }
        return new Tensor(name, file.asSlice(dataStart + e.start, e.end - e.start), type,
                Math.toIntExact(rows), Math.toIntExact(cols));
    }

    /** Kleiner Tensor vollständig als {@code float[]}. */
    public float[] floats(String name) {
        return tensor(name).toFloats();
    }

    @Override
    public void close() {
        arena.close();
    }
}
