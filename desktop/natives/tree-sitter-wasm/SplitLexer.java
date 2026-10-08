import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Zerlegt die generierten Lexer einer tree-sitter-Grammatik ({@code ts_lex}, {@code ts_lex_keywords} in parser.c) in
 * viele kleine Funktionen. Aufruf aus build-tree-sitter-wasm.sh: {@code java SplitLexer.java <parser.c> <ausgabe.c>}.
 *
 * <p>Warum: Chicory übersetzt jede Wasm-Funktion in eine JVM-Methode. Der Lexer ist ein einziger {@code switch} über
 * alle Zustände – als Methode weit über 8000 Bytes Bytecode, und solche "huge methods" übersetzt HotSpot nie per JIT
 * (-XX:-DontCompileHugeMethods lässt sich bei {@code java -jar} nicht setzen). Der Lexer liefe dann im
 * Bytecode-Interpreter, gemessen etwa 10× langsamer.
 *
 * <p>Der generierte Lexer besteht aus {@code case N:}-Blöcken mit if/ADVANCE/SKIP/ACCEPT_TOKEN/END_STATE. Die Blöcke
 * landen gruppenweise in eigenen Funktionen (noinline, sonst fügt clang sie wieder zusammen), die statt zu springen
 * den Folgezustand zurückgeben: {@code (zustand << 1) | skip}, {@code -1} = Ende. Die Hauptfunktion bleibt die Schleife aus START_LEXER und ruft je
 * Zeichen die Gruppe des aktuellen Zustands auf. Verhalten und Reihenfolge der Aufrufe an {@code TSLexer} bleiben
 * gleich. Passt der Aufbau nicht zum erwarteten Muster, bleibt die Funktion unverändert.
 */
public class SplitLexer {

    /** Zeilen je Gruppe – klein genug für den JIT auch bei Zuständen mit vielen Bedingungen. */
    private static final int LINES_PER_PART = 120;

    private static final Pattern HEADER = Pattern.compile(
            "(?m)^static bool (ts_lex(?:_keywords)?)\\(TSLexer \\*lexer, TSStateId state\\) \\{\\n"
                    + "  START_LEXER\\(\\);\\n  eof = lexer->eof\\(lexer\\);\\n  switch \\(state\\) \\{\\n");
    private static final String FOOTER = "    default:\n      return false;\n  }\n}\n";
    private static final Pattern CASE = Pattern.compile("^    case (\\d+):$");
    /** Sprünge, Rücksprünge, Zuweisungen an state außerhalb der Makros (Token-Namen wie anon_sym_return zählen nicht). */
    private static final Pattern FORBIDDEN = Pattern.compile("\\b(goto|return|next_state|start)\\b|\\bstate\\s*=[^=]");

    public static void main(String[] args) throws IOException {
        String src = Files.readString(Path.of(args[0]), StandardCharsets.UTF_8).replace("\r\n", "\n");
        StringBuilder out = new StringBuilder(src.length() + 4096);
        int pos = 0;
        Matcher m = HEADER.matcher(src);
        while (m.find(pos)) {
            int bodyStart = m.end();
            int end = src.indexOf(FOOTER, bodyStart);
            if (end < 0) {
                break;
            }
            String name = m.group(1);
            String split = split(name, src.substring(bodyStart, end));
            out.append(src, pos, m.start());
            if (split == null) {
                System.err.println("SplitLexer: " + name + " in " + args[0] + " hat unerwarteten Aufbau, unverändert");
                out.append(src, m.start(), end + FOOTER.length());
            } else {
                out.append(split);
            }
            pos = end + FOOTER.length();
        }
        out.append(src, pos, src.length());
        Files.writeString(Path.of(args[1]), out, StandardCharsets.UTF_8);
    }

    /** Ein Zustand: Nummer und Zeilen ab {@code case N:}. */
    private record State(int id, List<String> lines) {
    }

    private static String split(String name, String body) {
        List<State> states = new ArrayList<>();
        for (String line : body.split("\n", -1)) {
            Matcher c = CASE.matcher(line);
            if (c.matches()) {
                states.add(new State(Integer.parseInt(c.group(1)), new ArrayList<>()));
            } else if (states.isEmpty()) {
                if (!line.isBlank()) {
                    return null;
                }
                continue;
            }
            states.getLast().lines().add(line);
        }
        if (states.isEmpty()) {
            return null;
        }
        int max = 0;
        for (State s : states) {
            for (String l : s.lines()) {
                // Nur bekannte Bausteine: Sprünge, Labels oder Rücksprünge außerhalb der Makros gäbe es so nicht mehr
                if (FORBIDDEN.matcher(l).find()) {
                    return null;
                }
            }
            max = Math.max(max, s.id());
        }

        List<List<State>> parts = new ArrayList<>();
        List<State> current = new ArrayList<>();
        int lines = 0;
        for (State s : states) {
            if (!current.isEmpty() && lines + s.lines().size() > LINES_PER_PART) {
                parts.add(current);
                current = new ArrayList<>();
                lines = 0;
            }
            current.add(s);
            lines += s.lines().size();
        }
        parts.add(current);

        StringBuilder sb = new StringBuilder();
        sb.append("// ").append(name).append(": in ").append(parts.size())
                .append(" Teile zerlegt (natives/tree-sitter-wasm/SplitLexer.java)\n");
        sb.append("#pragma push_macro(\"ADVANCE\")\n#pragma push_macro(\"ADVANCE_MAP\")\n#pragma push_macro(\"SKIP\")\n")
                .append("#pragma push_macro(\"ACCEPT_TOKEN\")\n#pragma push_macro(\"END_STATE\")\n")
                .append("#undef ADVANCE\n#undef ADVANCE_MAP\n#undef SKIP\n#undef ACCEPT_TOKEN\n#undef END_STATE\n")
                .append("#define ADVANCE(state_value) return (int32_t) (state_value) << 1;\n")
                .append("#define SKIP(state_value) return ((int32_t) (state_value) << 1) | 1;\n")
                .append("#define ADVANCE_MAP(...) { \\\n")
                .append("    static const uint16_t map[] = { __VA_ARGS__ }; \\\n")
                .append("    for (uint32_t i = 0; i < sizeof(map) / sizeof(map[0]); i += 2) { \\\n")
                .append("      if (map[i] == lookahead) return (int32_t) map[i + 1] << 1; \\\n")
                .append("    } \\\n  }\n")
                .append("#define ACCEPT_TOKEN(symbol_value) *result = true; lexer->result_symbol = symbol_value; ")
                .append("lexer->mark_end(lexer);\n")
                .append("#define END_STATE() return -1;\n\n");

        int[] partOf = new int[max + 1];
        java.util.Arrays.fill(partOf, -1);
        for (int p = 0; p < parts.size(); p++) {
            sb.append("__attribute__((noinline)) static int32_t ").append(name).append("_part").append(p)
                    .append("(TSLexer *lexer, TSStateId state, int32_t lookahead, bool eof, bool *result) {\n")
                    .append("  (void) lexer; (void) lookahead; (void) eof; (void) result;\n")
                    .append("  switch (state) {\n");
            for (State s : parts.get(p)) {
                partOf[s.id()] = p;
                for (String l : s.lines()) {
                    sb.append(l).append('\n');
                }
            }
            sb.append("    default:\n      return -1;\n  }\n}\n\n");
        }

        sb.append("static const int16_t ").append(name).append("_parts[").append(max + 1).append("] = {");
        for (int i = 0; i <= max; i++) {
            sb.append(i % 32 == 0 ? "\n  " : " ").append(partOf[i]).append(',');
        }
        sb.append("\n};\n\n");

        // Schleife wie START_LEXER: advance (außer beim ersten Zeichen), lookahead und eof lesen, Zustand auswerten
        sb.append("static bool ").append(name).append("(TSLexer *lexer, TSStateId state) {\n")
                .append("  bool result = false;\n  bool skip = false;\n  bool first = true;\n")
                .append("  for (;;) {\n")
                .append("    if (!first) lexer->advance(lexer, skip);\n    first = false;\n")
                .append("    int32_t lookahead = lexer->lookahead;\n    bool eof = lexer->eof(lexer);\n")
                .append("    if (state > ").append(max).append(" || ").append(name).append("_parts[state] < 0) return false;\n")
                .append("    int32_t next;\n    switch (").append(name).append("_parts[state]) {\n");
        for (int p = 0; p < parts.size(); p++) {
            sb.append("      case ").append(p).append(": next = ").append(name).append("_part").append(p)
                    .append("(lexer, state, lookahead, eof, &result); break;\n");
        }
        sb.append("      default: return false;\n    }\n")
                .append("    if (next < 0) return result;\n")
                .append("    state = (TSStateId) (next >> 1);\n    skip = (next & 1) != 0;\n  }\n}\n\n");

        sb.append("#pragma pop_macro(\"ADVANCE\")\n#pragma pop_macro(\"ADVANCE_MAP\")\n#pragma pop_macro(\"SKIP\")\n")
                .append("#pragma pop_macro(\"ACCEPT_TOKEN\")\n#pragma pop_macro(\"END_STATE\")\n");
        return sb.toString();
    }
}
