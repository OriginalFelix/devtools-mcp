package systems.grebe.devtools.mcp.modules.scripts.assist;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.cucumber.gherkin.GherkinDialect;
import io.cucumber.gherkin.GherkinDialects;
import systems.grebe.devtools.mcp.modules.scripts.GherkinSteps;
import systems.grebe.devtools.mcp.modules.scripts.assist.Completion.Kind;

/**
 * Autovervollständigung für Gherkin-Skripte: Schlüsselwörter der Sprache aus {@code # language:} am Zeilenanfang, die
 * eingebauten Schritte nach {@code Wenn}/{@code Dann} … (mit Leerzeichen gefiltert, Platzhalter zum Überschreiben
 * markiert), Tool-Namen in {@code Tool "…"} und ihre Parameter in der Tabelle darunter (aus den aktiven Tools der App),
 * Platzhalter {@code <name>} und Variablen {@code ${name}} des Szenarios, Tags und Sprachen.
 */
final class GherkinAssist {

    private static final Pattern LANGUAGE_LINE = Pattern.compile("#\\s*language\\s*:\\s*([\\w-]*)");
    private static final Pattern TOOL_QUOTE = Pattern.compile("(?i).*\\btool\\s+\"([a-z0-9_]*)");
    private static final Pattern TOOL_IN_STEP = Pattern.compile("(?i)\\btool\\s+\"([a-z0-9_]+)\"");
    private static final Pattern PLACEHOLDER = Pattern.compile("<([A-Za-z][A-Za-z0-9_]*)>");
    private static final Pattern PARAM_LINE = Pattern.compile("\\s*<([A-Za-z][A-Za-z0-9_]*)>\\s*[:–-]\\s*(.*)");
    private static final Pattern VARIABLE_SET = Pattern.compile("(?:\\bals\\s+|\\bsetze\\s+)([A-Za-z][A-Za-z0-9_]*)");
    private static final Pattern WORD = Pattern.compile("[A-Za-z0-9_]*");
    /** Schlüsselwörter, die die Referenz verwendet – kommen in der Liste zuerst. */
    private static final Set<String> PREFERRED = Set.of("Funktionalität", "Szenario", "Grundlage", "Regel",
            "Angenommen ", "Wenn ", "Dann ", "Und ", "Aber ", "Feature", "Scenario", "Background", "Rule", "Given ",
            "When ", "Then ", "And ", "But ");
    private static final List<Completion> TAGS = List.of(
            Completion.of(Kind.TAG, "readOnly").withDoc("Verändert nichts – Clients dürfen ohne Rückfrage ausführen."),
            Completion.of(Kind.TAG, "destructive").withDoc("Kann Daten löschen oder überschreiben."),
            Completion.of(Kind.TAG, "idempotent").withDoc("Mehrfaches Ausführen wirkt wie einmal."));

    private final Supplier<List<ToolInfo>> tools;

    GherkinAssist(Supplier<List<ToolInfo>> tools) {
        this.tools = tools;
    }

    CompletionResult complete(String text, int caret) {
        int lineStart = text.lastIndexOf('\n', caret - 1) + 1;
        String line = text.substring(lineStart, caret);
        int indent = 0;
        while (indent < line.length() && (line.charAt(indent) == ' ' || line.charAt(indent) == '\t')) {
            indent++;
        }
        String trimmed = line.substring(indent);
        GherkinDialect dialect = SyntaxHighlighter.dialect(text);

        Matcher language = LANGUAGE_LINE.matcher(trimmed);
        if (language.matches()) {
            return languages(caret - language.group(1).length());
        }
        if (trimmed.startsWith("#")) {
            return CompletionResult.none(caret);
        }
        CompletionResult inline = placeholderOrVariable(text, caret, line, lineStart, dialect);
        if (inline != null) {
            return inline;
        }
        if (inDocString(text, lineStart)) {
            return CompletionResult.none(caret);
        }
        if (trimmed.startsWith("@")) {
            int at = line.lastIndexOf('@');
            return WORD.matcher(line.substring(at + 1)).matches()
                    ? new CompletionResult(lineStart + at + 1, TAGS, false, false) : CompletionResult.none(caret);
        }
        if (trimmed.startsWith("|")) {
            return table(text, caret, lineStart, indent);
        }
        String keyword = stepKeyword(dialect, trimmed);
        if (keyword != null) {
            int textStart = lineStart + indent + keyword.length();
            String stepText = text.substring(textStart, caret);
            Matcher tool = TOOL_QUOTE.matcher(stepText);
            if (tool.matches()) {
                return toolNames(caret - tool.group(1).length());
            }
            if (stepText.chars().filter(c -> c == '"').count() % 2 == 1) {
                return CompletionResult.none(caret); // in einer Zeichenkette
            }
            return steps(textStart, keyword, dialect);
        }
        if (trimmed.chars().allMatch(Character::isLetter)) {
            return keywords(text, lineStart + indent, dialect);
        }
        return CompletionResult.none(caret);
    }

    // ------------------------------------------------------------------ Schlüsselwörter

    private static String stepKeyword(GherkinDialect d, String trimmed) {
        for (String k : SyntaxHighlighter.stepKeywords(d)) {
            if (trimmed.startsWith(k) && !k.isBlank()) {
                return k;
            }
        }
        return null;
    }

    private static CompletionResult keywords(String text, int from, GherkinDialect d) {
        List<Completion> out = new ArrayList<>();
        boolean feature = false;
        for (String line : (Iterable<String>) text.substring(0, from).lines()::iterator) {
            String l = line.strip();
            if (d.getFeatureKeywords().stream().anyMatch(k -> l.startsWith(k + ":"))) {
                feature = true;
                break;
            }
        }
        if (!feature) {
            for (String k : d.getFeatureKeywords()) {
                out.add(header(k, "Das Modul: Name, darunter die Beschreibung als Freitext."));
            }
            return new CompletionResult(from, out, false, false);
        }
        for (String k : d.getScenarioKeywords()) {
            out.add(header(k, "Ein Tool: aus dem Namen wird der Tool-Name, der Freitext darunter ist die "
                    + "Beschreibung, Platzhalter <name> werden Parameter."));
        }
        for (String k : d.getBackgroundKeywords()) {
            out.add(header(k, "Schritte, die vor jedem Szenario laufen."));
        }
        for (String k : d.getRuleKeywords()) {
            out.add(header(k, "Gruppe von Szenarien mit eigener Grundlage."));
        }
        for (String k : d.getStepKeywords()) {
            if (!k.isBlank() && !k.strip().equals("*")) {
                out.add(Completion.of(Kind.KEYWORD, k.strip()).withInsert(k).retriggering()
                        .withPriority(PREFERRED.contains(k) ? 8 : 0));
            }
        }
        return new CompletionResult(from, out, false, false);
    }

    private static Completion header(String keyword, String doc) {
        return Completion.of(Kind.KEYWORD, keyword + ":").withInsert(keyword + ": ").withDoc(doc)
                .withPriority(PREFERRED.contains(keyword) ? 10 : 1);
    }

    private static CompletionResult languages(int from) {
        List<Completion> out = new ArrayList<>();
        for (String code : GherkinDialects.getLanguages()) {
            GherkinDialect d = GherkinDialects.getDialect(code).orElseThrow();
            out.add(Completion.of(Kind.KEYWORD, code).withDetail(d.getNativeName())
                    .withPriority(code.equals("de") ? 2 : code.equals("en") ? 1 : 0));
        }
        return new CompletionResult(from, out, false, false);
    }

    // ------------------------------------------------------------------ Schritte

    /** Ausgefüllter Schritt: Text zum Einfügen, Schreibmarke im ersten Platzhalter, ggf. Vorgabe markiert. */
    record Expanded(String label, String insert, int caret, int select) {
    }

    /** {@code ich warte {int} Sekunde(n)} → {@code ich warte 10 Sekunden}, die 10 markiert. */
    static Expanded expand(String pattern) {
        StringBuilder insert = new StringBuilder();
        int caret = -1;
        int select = 0;
        int i = 0;
        while (i < pattern.length()) {
            if (pattern.startsWith("{string}", i)) {
                insert.append("\"\"");
                if (caret < 0) {
                    caret = insert.length() - 1;
                }
                i += "{string}".length();
            } else if (pattern.startsWith("{int}", i)) {
                if (caret < 0) {
                    caret = insert.length();
                    select = 2;
                }
                insert.append("10");
                i += "{int}".length();
            } else if (pattern.startsWith("{word}", i)) {
                if (caret < 0) {
                    caret = insert.length();
                    select = 4;
                }
                insert.append("name");
                i += "{word}".length();
            } else if (pattern.charAt(i) == '(' && pattern.indexOf(')', i) > i) {
                int close = pattern.indexOf(')', i);
                insert.append(pattern, i + 1, close);
                i = close + 1;
            } else if (pattern.charAt(i) == '\\' && i + 1 < pattern.length()) {
                insert.append(pattern.charAt(i + 1));
                i += 2;
            } else {
                insert.append(pattern.charAt(i++));
            }
        }
        String text = insert.toString();
        return new Expanded(text.replace("\"\"", "\"…\""), text, caret, select);
    }

    private static CompletionResult steps(int from, String keyword, GherkinDialect d) {
        boolean when = d.getWhenKeywords().contains(keyword);
        boolean then = d.getThenKeywords().contains(keyword);
        List<Completion> out = new ArrayList<>();
        List<GherkinSteps.Info> catalog = GherkinSteps.catalog();
        for (int i = 0; i < catalog.size(); i++) {
            GherkinSteps.Info info = catalog.get(i);
            Expanded e = expand(info.pattern());
            // Satzstellung: „Wenn ich das Tool … aufrufe“, „Dann enthält das Ergebnis …“, sonst Hauptsatz
            boolean verbLast = e.insert().endsWith("aufrufe") || e.insert().contains("aufrufe,");
            boolean verbFirst = e.insert().matches("(enthält|passt|ist) .*");
            int priority = -i + (when && verbLast || then && verbFirst ? 40 : 0)
                    + (!when && !then && !verbLast && !verbFirst ? 20 : 0);
            Completion c = Completion.of(Kind.STEP, e.label()).withInsert(e.insert(), e.caret(), e.select())
                    .withDoc("z.B. " + info.example()).withPriority(priority);
            out.add(info.toolArg() == 0 ? c.retriggering() : c);
        }
        return new CompletionResult(from, out, false, true);
    }

    // ------------------------------------------------------------------ Tools

    private CompletionResult toolNames(int from) {
        List<Completion> out = new ArrayList<>();
        for (ToolInfo t : tools.get()) {
            out.add(Completion.of(Kind.TOOL, t.name()).withTail(shortText(t.description())).withDoc(toolDoc(t)));
        }
        return new CompletionResult(from, out, false, false);
    }

    private static String shortText(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        String first = description.strip().lines().findFirst().orElse("");
        int dot = first.indexOf(". ");
        if (dot > 0) {
            first = first.substring(0, dot);
        }
        return "  " + (first.length() > 60 ? first.substring(0, 59) + "…" : first);
    }

    private static String toolDoc(ToolInfo t) {
        StringBuilder sb = new StringBuilder(t.description());
        if (!t.params().isEmpty()) {
            sb.append("\n\nParameter:");
            for (ToolInfo.Param p : t.params()) {
                sb.append("\n• ").append(p.name()).append(" (").append(p.type())
                        .append(p.required() ? ", Pflicht" : ", optional").append(')');
                if (!p.description().isBlank()) {
                    sb.append(" – ").append(p.description());
                }
            }
        }
        return sb.toString();
    }

    /** Erste Spalte einer Tabelle unter einem Tool-Aufruf: die Parameter des Tools, die noch fehlen. */
    private CompletionResult table(String text, int caret, int lineStart, int indent) {
        String line = text.substring(lineStart, caret);
        int cell = lineStart + indent + 1;
        if (line.substring(indent + 1).indexOf('|') >= 0) {
            return CompletionResult.none(caret); // nur die erste Spalte
        }
        int from = cell;
        while (from < caret && text.charAt(from) == ' ') {
            from++;
        }
        if (!WORD.matcher(text.substring(from, caret)).matches()) {
            return CompletionResult.none(caret);
        }
        List<String> above = new ArrayList<>();
        ToolInfo tool = toolAbove(text, lineStart, above);
        if (tool == null) {
            return CompletionResult.none(caret);
        }
        int lineEnd = text.indexOf('\n', caret);
        boolean restEmpty = text.substring(caret, lineEnd < 0 ? text.length() : lineEnd).isBlank();
        List<Completion> out = new ArrayList<>();
        for (ToolInfo.Param p : tool.params()) {
            if (above.contains(p.name())) {
                continue;
            }
            Completion c = Completion.of(Kind.PARAMETER, p.name()).withDetail(p.type())
                    .withTail(p.required() ? null : " (optional)").withPriority(p.required() ? 10 : 0)
                    .withDoc(p.description().isBlank() ? null : p.description());
            out.add(restEmpty ? c.withInsert(p.name() + " | " + " |", p.name().length() + 3, 0) : c);
        }
        return new CompletionResult(from, out, false, false);
    }

    /** Tool des Schritts über den Tabellenzeilen; {@code used} bekommt die schon eingetragenen Parameter. */
    private ToolInfo toolAbove(String text, int lineStart, List<String> used) {
        int end = lineStart - 1;
        while (end > 0) {
            int start = text.lastIndexOf('\n', end - 1) + 1;
            String l = text.substring(start, end).strip();
            if (l.startsWith("|")) {
                String[] cells = l.split("\\|");
                if (cells.length > 1) {
                    used.add(cells[1].strip());
                }
                end = start - 1;
                continue;
            }
            Matcher m = TOOL_IN_STEP.matcher(l);
            if (!m.find()) {
                return null;
            }
            String name = m.group(1);
            return tools.get().stream().filter(t -> t.name().equals(name)).findFirst().orElse(null);
        }
        return null;
    }

    // ------------------------------------------------------------------ Platzhalter und Variablen

    private static CompletionResult placeholderOrVariable(String text, int caret, String line, int lineStart,
                                                          GherkinDialect d) {
        int lt = line.lastIndexOf('<');
        int dollar = line.lastIndexOf("${");
        char next = caret < text.length() ? text.charAt(caret) : '\n';
        if (lt >= 0 && lt > dollar && WORD.matcher(line.substring(lt + 1)).matches()) {
            Map<String, String> placeholders = placeholders(text, lineStart, d);
            List<Completion> out = new ArrayList<>();
            placeholders.forEach((name, doc) -> {
                Completion c = Completion.of(Kind.PARAMETER, name).withDetail("Parameter")
                        .withDoc(doc.isBlank() ? null : doc);
                out.add(next == '>' ? c : c.withInsert(name + ">"));
            });
            return new CompletionResult(lineStart + lt + 1, out, false, false);
        }
        if (dollar >= 0 && WORD.matcher(line.substring(dollar + 2)).matches()) {
            List<Completion> out = new ArrayList<>();
            for (String name : variables(text, lineStart, d)) {
                Completion c = Completion.of(Kind.VARIABLE, name).withDetail("Variable");
                out.add(next == '}' ? c : c.withInsert(name + "}"));
            }
            return new CompletionResult(lineStart + dollar + 2, out, false, false);
        }
        return null;
    }

    /** Platzhalter des Szenarios, in dem die Zeile steht – mit der Beschreibung aus {@code <name>: …}. */
    private static Map<String, String> placeholders(String text, int lineStart, GherkinDialect d) {
        int[] block = block(text, lineStart, d);
        Map<String, String> out = new LinkedHashMap<>();
        String scenario = text.substring(block[0], block[1]);
        for (String l : (Iterable<String>) scenario.lines()::iterator) {
            Matcher p = PARAM_LINE.matcher(l);
            if (p.matches()) {
                out.put(p.group(1), p.group(2).strip());
            }
        }
        Matcher m = PLACEHOLDER.matcher(scenario);
        while (m.find()) {
            out.putIfAbsent(m.group(1), "");
        }
        return out;
    }

    /** Variablen, die vor der Zeile gesetzt werden – im Szenario und in den Grundlagen. */
    private static Set<String> variables(String text, int lineStart, GherkinDialect d) {
        Set<String> out = new LinkedHashSet<>();
        int[] block = block(text, lineStart, d);
        collectVariables(text.substring(block[0], lineStart), out);
        for (int pos = 0; pos < text.length(); ) {
            int eol = text.indexOf('\n', pos);
            String l = text.substring(pos, eol < 0 ? text.length() : eol).strip();
            if (d.getBackgroundKeywords().stream().anyMatch(k -> l.startsWith(k + ":"))) {
                int[] background = block(text, pos, d);
                if (background[0] != block[0]) {
                    collectVariables(text.substring(background[0], background[1]), out);
                }
            }
            if (eol < 0) {
                break;
            }
            pos = eol + 1;
        }
        return out;
    }

    private static void collectVariables(String part, Set<String> out) {
        Matcher m = VARIABLE_SET.matcher(part);
        while (m.find()) {
            out.add(m.group(1));
        }
    }

    /** {@code [Beginn, Ende)} des Abschnitts (Szenario, Grundlage …), in dem die Position liegt. */
    private static int[] block(String text, int pos, GherkinDialect d) {
        List<String> headers = SyntaxHighlighter.headerKeywords(d);
        int start = 0;
        int end = text.length();
        int p = 0;
        while (p < text.length()) {
            int eol = text.indexOf('\n', p);
            int lineEnd = eol < 0 ? text.length() : eol;
            String l = text.substring(p, lineEnd).strip();
            boolean header = headers.stream().anyMatch(h -> l.startsWith(h + ":"));
            if (header) {
                if (p <= pos) {
                    start = p;
                } else {
                    end = p;
                    break;
                }
            }
            if (eol < 0) {
                break;
            }
            p = eol + 1;
        }
        return new int[] {start, end};
    }

    private static boolean inDocString(String text, int lineStart) {
        int count = 0;
        for (String l : (Iterable<String>) text.substring(0, lineStart).lines()::iterator) {
            String s = l.strip();
            if (s.startsWith("\"\"\"") || s.startsWith("```")) {
                count++;
            }
        }
        return count % 2 == 1;
    }
}
