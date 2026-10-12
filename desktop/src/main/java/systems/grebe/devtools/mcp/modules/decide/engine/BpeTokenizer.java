package systems.grebe.devtools.mcp.modules.decide.engine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * BPE-Tokenizer, Ergebnis gleich dem von Hugging Face {@code tokenizers} für die hier gebrauchten Konfigurationen:
 *
 * <ul>
 *   <li>{@code tokenizer.json} mit Metaspace-Vorzerlegung und Byte-Fallback (SentencePiece-Stil, z.B. mmBERT/Gemma),</li>
 *   <li>{@code tokenizer.json} mit ByteLevel-Vorzerlegung (GPT-2-Stil, z.B. ModernBERT),</li>
 *   <li>GGUF-Vokabular {@code gpt2} mit Byte-Level und dem Regex des Modells ({@code qwen2}, {@code llama-bpe}).</li>
 * </ul>
 *
 * <p>Zusätzliche Tokens ({@code added_tokens}, in GGUF Typ control/user-defined) werden vor allem anderen im Rohtext
 * erkannt. {@link #encode} fügt keine Sondertokens hinzu – das macht der Aufrufer.
 */
public final class BpeTokenizer {

    static final String GPT2_REGEX =
            "'s|'t|'re|'ve|'m|'ll|'d| ?\\p{L}+| ?\\p{N}+| ?[^\\s\\p{L}\\p{N}]+|\\s+(?!\\S)|\\s+";
    static final String QWEN2_REGEX = "(?i:'s|'t|'re|'ve|'m|'ll|'d)|[^\\r\\n\\p{L}\\p{N}]?\\p{L}+|\\p{N}"
            + "| ?[^\\s\\p{L}\\p{N}]+[\\r\\n]*|\\s*[\\r\\n]+|\\s+(?!\\S)|\\s+";
    static final String LLAMA3_REGEX = "(?i:'s|'t|'re|'ve|'m|'ll|'d)|[^\\r\\n\\p{L}\\p{N}]?\\p{L}+|\\p{N}{1,3}"
            + "| ?[^\\s\\p{L}\\p{N}]+[\\r\\n]*|\\s*[\\r\\n]+|\\s+(?!\\S)|\\s+";

    private static final int FLAGS = Pattern.UNICODE_CHARACTER_CLASS | Pattern.UNICODE_CASE;
    private static final int WORD_CACHE_LIMIT = 50_000;

    /** Zusätzliches Token, das im Rohtext erkannt wird. */
    record Added(String content, int id, boolean lstrip, boolean rstrip) {
    }

    private enum Pre { METASPACE, BYTELEVEL }

    private final Map<String, Integer> vocab;
    private final String[] tokens;
    /** (links, rechts) → (Rang, Ergebnis). */
    private final LongLongMap merges;
    private final boolean byteFallback;
    /** Ganze Wörter aus dem Vokabular direkt nehmen statt zu mergen ({@code ignore_merges}). */
    private final boolean ignoreMerges;
    private final int unkId;
    private final int[] byteTokens = new int[256];
    private final List<UnaryOperator<String>> normalizers;
    private final Pre pre;
    private final String metaspace;
    private final String prependScheme;
    private final boolean metaspaceSplit;
    private final Pattern byteLevelRegex;
    private final boolean addPrefixSpace;
    private final Map<Character, List<Added>> addedByFirstChar = new HashMap<>();
    private final Map<String, int[]> wordCache = new HashMap<>();

    private BpeTokenizer(Map<String, Integer> vocab, List<String[]> mergePairs, boolean byteFallback, boolean ignoreMerges, String unk,
                         List<UnaryOperator<String>> normalizers, Pre pre, String metaspace, String prependScheme,
                         boolean metaspaceSplit, Pattern byteLevelRegex, boolean addPrefixSpace, List<Added> added) {
        this.vocab = vocab;
        int max = 0;
        for (int id : vocab.values()) {
            max = Math.max(max, id);
        }
        for (Added a : added) {
            max = Math.max(max, a.id());
        }
        this.tokens = new String[max + 1];
        vocab.forEach((t, id) -> tokens[id] = t);
        for (Added a : added) {
            if (tokens[a.id()] == null) {
                tokens[a.id()] = a.content();
            }
        }
        this.merges = new LongLongMap(mergePairs.size());
        for (int rank = 0; rank < mergePairs.size(); rank++) {
            String[] p = mergePairs.get(rank);
            Integer a = vocab.get(p[0]);
            Integer b = vocab.get(p[1]);
            Integer m = vocab.get(p[0] + p[1]);
            if (a != null && b != null && m != null) {
                merges.putIfAbsent(key(a, b), ((long) rank << 32) | m);
            }
        }
        this.byteFallback = byteFallback;
        this.ignoreMerges = ignoreMerges;
        this.unkId = unk == null ? -1 : vocab.getOrDefault(unk, -1);
        for (int i = 0; i < 256; i++) {
            byteTokens[i] = vocab.getOrDefault(String.format("<0x%02X>", i), -1);
        }
        this.normalizers = normalizers;
        this.pre = pre;
        this.metaspace = metaspace;
        this.prependScheme = prependScheme;
        this.metaspaceSplit = metaspaceSplit;
        this.byteLevelRegex = byteLevelRegex;
        this.addPrefixSpace = addPrefixSpace;
        for (Added a : added) {
            if (!a.content().isEmpty()) {
                addedByFirstChar.computeIfAbsent(a.content().charAt(0), c -> new ArrayList<>()).add(a);
            }
        }
        addedByFirstChar.values().forEach(l -> l.sort(Comparator.comparingInt((Added a) -> a.content().length()).reversed()));
    }

    private static long key(int a, int b) {
        return ((long) a << 32) | (b & 0xffffffffL);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Laden

    /** Aus einer Hugging-Face-{@code tokenizer.json} (Modell BPE). */
    public static BpeTokenizer fromTokenizerJson(Path file) throws IOException {
        // Gestreamt statt als Baum: vocab und merges haben zusammen fast eine Million Einträge
        JsonMapper mapper = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
        ObjectNode root = mapper.createObjectNode();
        Map<String, Integer> vocab = new HashMap<>();
        List<String[]> mergePairs = new ArrayList<>();
        ObjectNode model = mapper.createObjectNode();
        try (var in = Files.newInputStream(file); JsonParser p = mapper.createParser(in)) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                throw new IOException("Keine Tokenizer-Datei: " + file);
            }
            while (p.nextToken() == JsonToken.PROPERTY_NAME) {
                String name = p.currentName();
                p.nextToken();
                if (!name.equals("model")) {
                    root.set(name, mapper.readTree(p));
                    continue;
                }
                while (p.nextToken() == JsonToken.PROPERTY_NAME) {
                    String key = p.currentName();
                    JsonToken value = p.nextToken();
                    switch (key) {
                        case "vocab" -> {
                            while (p.nextToken() == JsonToken.PROPERTY_NAME) {
                                String token = p.currentName();
                                p.nextToken();
                                vocab.put(token, p.getIntValue());
                            }
                        }
                        case "merges" -> {
                            while (p.nextToken() != JsonToken.END_ARRAY) {
                                if (p.currentToken() == JsonToken.START_ARRAY) {
                                    p.nextToken();
                                    String a = p.getString();
                                    p.nextToken();
                                    String b = p.getString();
                                    p.nextToken(); // END_ARRAY
                                    mergePairs.add(new String[] {a, b});
                                } else {
                                    String m = p.getString();
                                    int sp = m.indexOf(' ', 1);
                                    mergePairs.add(new String[] {m.substring(0, sp), m.substring(sp + 1)});
                                }
                            }
                        }
                        default -> {
                            if (value == JsonToken.START_OBJECT || value == JsonToken.START_ARRAY) {
                                p.skipChildren();
                            } else if (value == JsonToken.VALUE_TRUE || value == JsonToken.VALUE_FALSE) {
                                model.put(key, value == JsonToken.VALUE_TRUE ? "true" : "false");
                            } else if (value != JsonToken.VALUE_NULL) {
                                model.put(key, p.getString());
                            }
                        }
                    }
                }
            }
        }
        if (!"BPE".equals(model.path("type").asString(""))) {
            throw new IOException("Tokenizer-Modell " + model.path("type").asString("?") + " wird nicht unterstützt (nur BPE): "
                    + file);
        }
        List<Added> added = new ArrayList<>();
        for (JsonNode a : root.path("added_tokens")) {
            added.add(new Added(a.path("content").asString(), a.path("id").asInt(), a.path("lstrip").asBoolean(),
                    a.path("rstrip").asBoolean()));
        }
        List<UnaryOperator<String>> normalizers = new ArrayList<>();
        normalizer(root.path("normalizer"), normalizers, file);

        JsonNode p = root.path("pre_tokenizer");
        JsonNode metaspaceNode = null;
        JsonNode byteLevelNode = null;
        for (JsonNode n : p.path("type").asString("").equals("Sequence") ? p.path("pretokenizers") : List.of(p)) {
            switch (n.path("type").asString("")) {
                case "Metaspace" -> metaspaceNode = n;
                case "ByteLevel" -> byteLevelNode = n;
                case "" -> { }
                default -> throw new IOException("Vorzerlegung " + n.path("type").asString() + " wird nicht unterstützt: " + file);
            }
        }
        boolean fallback = Boolean.parseBoolean(model.path("byte_fallback").asString("false"));
        boolean ignoreMerges = Boolean.parseBoolean(model.path("ignore_merges").asString("false"));
        String unk = model.has("unk_token") ? model.path("unk_token").asString() : null;
        if (metaspaceNode != null) {
            String scheme = metaspaceNode.has("prepend_scheme") ? metaspaceNode.path("prepend_scheme").asString()
                    : metaspaceNode.path("add_prefix_space").asBoolean(true) ? "always" : "never";
            return new BpeTokenizer(vocab, mergePairs, fallback, ignoreMerges, unk, normalizers, Pre.METASPACE,
                    metaspaceNode.path("replacement").asString("▁"), scheme, metaspaceNode.path("split").asBoolean(true),
                    null, false, added);
        }
        if (byteLevelNode != null) {
            return new BpeTokenizer(vocab, mergePairs, fallback, ignoreMerges, unk, normalizers, Pre.BYTELEVEL, null, null, false,
                    Pattern.compile(GPT2_REGEX, FLAGS), byteLevelNode.path("add_prefix_space").asBoolean(false), added);
        }
        throw new IOException("Tokenizer ohne Metaspace- oder ByteLevel-Vorzerlegung wird nicht unterstützt: " + file);
    }

    private static void normalizer(JsonNode n, List<UnaryOperator<String>> out, Path file) throws IOException {
        if (n == null || n.isMissingNode() || n.isNull()) {
            return;
        }
        switch (n.path("type").asString("")) {
            case "Sequence" -> {
                for (JsonNode c : n.path("normalizers")) {
                    normalizer(c, out, file);
                }
            }
            case "Replace" -> {
                String from = n.path("pattern").path("String").asString(null);
                if (from == null) {
                    throw new IOException("Replace-Normalizer mit Regex wird nicht unterstützt: " + file);
                }
                String to = n.path("content").asString();
                out.add(s -> s.replace(from, to));
            }
            case "Prepend" -> {
                String prefix = n.path("prepend").asString();
                out.add(s -> s.isEmpty() ? s : prefix + s);
            }
            case "NFC" -> out.add(s -> Normalizer.normalize(s, Normalizer.Form.NFC));
            case "NFKC" -> out.add(s -> Normalizer.normalize(s, Normalizer.Form.NFKC));
            case "NFD" -> out.add(s -> Normalizer.normalize(s, Normalizer.Form.NFD));
            case "NFKD" -> out.add(s -> Normalizer.normalize(s, Normalizer.Form.NFKD));
            case "Lowercase" -> out.add(s -> s.toLowerCase(java.util.Locale.ROOT));
            default -> throw new IOException("Normalizer " + n.path("type").asString() + " wird nicht unterstützt: " + file);
        }
    }

    /** Aus den Metadaten einer GGUF-Datei (Modell {@code gpt2}, Byte-Level). */
    public static BpeTokenizer fromGguf(Gguf gguf) throws IOException {
        String model = gguf.string("tokenizer.ggml.model", "");
        if (!model.equals("gpt2")) {
            throw new IOException("GGUF-Tokenizer " + model + " wird nicht unterstützt (nur gpt2/Byte-Level-BPE)");
        }
        List<String> toks = gguf.list("tokenizer.ggml.tokens");
        List<Object> types = gguf.list("tokenizer.ggml.token_type");
        Map<String, Integer> vocab = new HashMap<>(toks.size() * 2);
        List<Added> added = new ArrayList<>();
        for (int i = 0; i < toks.size(); i++) {
            vocab.putIfAbsent(toks.get(i), i);
            int type = i < types.size() ? ((Number) types.get(i)).intValue() : 1;
            if (type == 3 || type == 4) {
                added.add(new Added(toks.get(i), i, false, false));
            }
        }
        List<String[]> mergePairs = new ArrayList<>();
        for (Object m : gguf.list("tokenizer.ggml.merges")) {
            String s = (String) m;
            int sp = s.indexOf(' ', 1);
            mergePairs.add(new String[] {s.substring(0, sp), s.substring(sp + 1)});
        }
        String regex = switch (gguf.string("tokenizer.ggml.pre", "default")) {
            case "qwen2", "deepseek-r1-qwen" -> QWEN2_REGEX;
            case "llama-bpe", "llama3", "smaug-bpe" -> LLAMA3_REGEX;
            default -> GPT2_REGEX;
        };
        // llama.cpp: Llama-3-Vokabulare nehmen ganze Wörter direkt (ignore_merges), Qwen nicht
        boolean ignoreMerges = regex.equals(LLAMA3_REGEX);
        return new BpeTokenizer(vocab, mergePairs, false, ignoreMerges, null, List.of(), Pre.BYTELEVEL, null, null, false,
                Pattern.compile(regex, FLAGS), false, added);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Kodieren

    /** ID eines Tokens oder −1. */
    public int id(String token) {
        return vocab.getOrDefault(token, -1);
    }

    public int vocabSize() {
        return tokens.length;
    }

    public String token(int id) {
        return id >= 0 && id < tokens.length ? tokens[id] : null;
    }

    /** Kodiert Text ohne Sondertokens am Anfang/Ende. Zusätzliche Tokens im Text werden als solche erkannt. */
    public int[] encode(String text) {
        IntList out = new IntList();
        int segStart = 0;
        int i = 0;
        boolean first = true;
        while (i < text.length()) {
            Added match = matchAdded(text, i);
            if (match == null) {
                i++;
                continue;
            }
            int end = i;
            if (match.lstrip()) {
                while (end > segStart && Character.isWhitespace(text.charAt(end - 1))) {
                    end--;
                }
            }
            encodeSegment(text.substring(segStart, end), first, out);
            first = false;
            out.add(match.id());
            i += match.content().length();
            if (match.rstrip()) {
                while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
                    i++;
                }
            }
            segStart = i;
        }
        encodeSegment(text.substring(segStart), first, out);
        return out.toArray();
    }

    private Added matchAdded(String text, int i) {
        List<Added> candidates = addedByFirstChar.get(text.charAt(i));
        if (candidates == null) {
            return null;
        }
        for (Added a : candidates) {
            if (text.startsWith(a.content(), i)) {
                return a;
            }
        }
        return null;
    }

    private void encodeSegment(String s, boolean first, IntList out) {
        if (s.isEmpty()) {
            return;
        }
        for (UnaryOperator<String> n : normalizers) {
            s = n.apply(s);
        }
        if (s.isEmpty()) {
            return;
        }
        if (pre == Pre.METASPACE) {
            s = s.replace(" ", metaspace);
            boolean prepend = "always".equals(prependScheme) || ("first".equals(prependScheme) && first);
            if (prepend && !s.startsWith(metaspace)) {
                s = metaspace + s;
            }
            if (!metaspaceSplit) {
                word(s, out);
                return;
            }
            int start = 0;
            for (int k = 1; k < s.length(); k++) {
                if (s.startsWith(metaspace, k)) {
                    word(s.substring(start, k), out);
                    start = k;
                }
            }
            word(s.substring(start), out);
        } else {
            if (addPrefixSpace && !s.startsWith(" ")) {
                s = " " + s;
            }
            Matcher m = byteLevelRegex.matcher(s);
            while (m.find()) {
                if (m.end() > m.start()) {
                    word(ByteLevel.encode(m.group()), out);
                }
            }
        }
    }

    private void word(String w, IntList out) {
        if (w.isEmpty()) {
            return;
        }
        if (ignoreMerges) {
            Integer whole = vocab.get(w);
            if (whole != null) {
                out.add(whole);
                return;
            }
        }
        int[] cached = wordCache.get(w);
        if (cached == null) {
            cached = bpe(w);
            if (wordCache.size() >= WORD_CACHE_LIMIT) {
                wordCache.clear();
            }
            wordCache.put(w, cached);
        }
        out.addAll(cached);
    }

    /** BPE über ein Wort: immer das Paar mit dem kleinsten Rang zuerst, bei Gleichstand das linke (wie HF). */
    private int[] bpe(String w) {
        IntList sym = new IntList();
        for (int k = 0; k < w.length(); ) {
            int cp = w.codePointAt(k);
            String ch = new String(Character.toChars(cp));
            k += ch.length();
            Integer id = vocab.get(ch);
            if (id != null) {
                sym.add(id);
            } else if (byteFallback && byteTokens[0] >= 0) {
                for (byte b : ch.getBytes(StandardCharsets.UTF_8)) {
                    sym.add(byteTokens[b & 0xff]);
                }
            } else if (unkId >= 0) {
                if (sym.size() == 0 || sym.last() != unkId) {
                    sym.add(unkId);
                }
            }
        }
        int n = sym.size();
        int[] ids = sym.toArray();
        int[] next = new int[n];
        int[] prev = new int[n];
        boolean[] alive = new boolean[n];
        for (int k = 0; k < n; k++) {
            next[k] = k + 1 < n ? k + 1 : -1;
            prev[k] = k - 1;
            alive[k] = true;
        }
        // Eintrag: Rang (obere 32 Bit) | Position (untere 32 Bit) → kleinster Rang, dann linkeste Position
        PriorityQueue<Long> queue = new PriorityQueue<>();
        for (int k = 0; k + 1 < n; k++) {
            push(queue, ids, k, k + 1);
        }
        while (!queue.isEmpty()) {
            long e = queue.poll();
            int rank = (int) (e >>> 32);
            int left = (int) e;
            int right = left >= 0 && alive[left] ? next[left] : -1;
            if (right < 0) {
                continue;
            }
            long merge = merges.get(key(ids[left], ids[right]));
            if (merge == LongLongMap.MISSING || (int) (merge >>> 32) != rank) {
                continue; // veraltet: das Paar an dieser Stelle hat sich geändert
            }
            ids[left] = (int) merge;
            alive[right] = false;
            next[left] = next[right];
            if (next[right] >= 0) {
                prev[next[right]] = left;
            }
            if (prev[left] >= 0) {
                push(queue, ids, prev[left], left);
            }
            if (next[left] >= 0) {
                push(queue, ids, left, next[left]);
            }
        }
        IntList out = new IntList();
        for (int k = 0; k >= 0 && k < n; k = next[k]) {
            out.add(ids[k]);
        }
        return out.toArray();
    }

    private void push(PriorityQueue<Long> queue, int[] ids, int left, int right) {
        long merge = merges.get(key(ids[left], ids[right]));
        if (merge != LongLongMap.MISSING) {
            queue.add((merge & 0xffffffff00000000L) | left);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Dekodieren (für Tests und Diagnose)

    public String decode(int[] ids) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int id : ids) {
            String t = token(id);
            if (t == null) {
                continue;
            }
            if (pre == Pre.BYTELEVEL) {
                if (t.startsWith("<|") && t.endsWith("|>")) {
                    bytes.writeBytes(t.getBytes(StandardCharsets.UTF_8));
                } else {
                    ByteLevel.decode(t, bytes);
                }
            } else if (t.length() == 6 && t.startsWith("<0x") && t.endsWith(">")) {
                bytes.write(Integer.parseInt(t.substring(3, 5), 16));
            } else {
                bytes.writeBytes(t.replace(metaspace, " ").getBytes(StandardCharsets.UTF_8));
            }
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    /** GPT-2-Abbildung Byte ↔ druckbares Zeichen. */
    static final class ByteLevel {
        private static final char[] BYTE_TO_CHAR = new char[256];
        private static final Map<Character, Integer> CHAR_TO_BYTE = new HashMap<>();

        static {
            int n = 0;
            for (int b = 0; b < 256; b++) {
                boolean printable = (b >= '!' && b <= '~') || (b >= 0xA1 && b <= 0xAC) || (b >= 0xAE && b <= 0xFF);
                BYTE_TO_CHAR[b] = printable ? (char) b : (char) (256 + n++);
                CHAR_TO_BYTE.put(BYTE_TO_CHAR[b], b);
            }
        }

        static String encode(String s) {
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            char[] c = new char[b.length];
            for (int i = 0; i < b.length; i++) {
                c[i] = BYTE_TO_CHAR[b[i] & 0xff];
            }
            return new String(c);
        }

        static void decode(String t, ByteArrayOutputStream out) {
            for (int i = 0; i < t.length(); i++) {
                Integer b = CHAR_TO_BYTE.get(t.charAt(i));
                if (b != null) {
                    out.write(b);
                }
            }
        }
    }

    /** Hash-Tabelle long → long ohne Boxing (offene Adressierung), für die Merge-Ränge. */
    static final class LongLongMap {
        static final long MISSING = Long.MIN_VALUE;
        private final long[] keys;
        private final long[] values;
        private final boolean[] used;
        private final int mask;

        LongLongMap(int expected) {
            int cap = Integer.highestOneBit(Math.max(16, expected * 2 - 1)) << 1;
            keys = new long[cap];
            values = new long[cap];
            used = new boolean[cap];
            mask = cap - 1;
        }

        private int slot(long key) {
            long h = key * 0x9E3779B97F4A7C15L;
            int i = (int) (h ^ (h >>> 32)) & mask;
            while (used[i] && keys[i] != key) {
                i = (i + 1) & mask;
            }
            return i;
        }

        void putIfAbsent(long key, long value) {
            int i = slot(key);
            if (!used[i]) {
                used[i] = true;
                keys[i] = key;
                values[i] = value;
            }
        }

        long get(long key) {
            int i = slot(key);
            return used[i] ? values[i] : MISSING;
        }
    }

    /** Wachsende int-Liste ohne Boxing. */
    static final class IntList {
        private int[] a = new int[16];
        private int n;

        void add(int v) {
            if (n == a.length) {
                a = java.util.Arrays.copyOf(a, n * 2);
            }
            a[n++] = v;
        }

        void addAll(int[] v) {
            for (int x : v) {
                add(x);
            }
        }

        int size() {
            return n;
        }

        int last() {
            return a[n - 1];
        }

        int[] toArray() {
            return java.util.Arrays.copyOf(a, n);
        }
    }
}
