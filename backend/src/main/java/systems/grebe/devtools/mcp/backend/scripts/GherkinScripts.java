package systems.grebe.devtools.mcp.backend.scripts;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import io.cucumber.gherkin.GherkinParser;
import io.cucumber.messages.types.Background;
import io.cucumber.messages.types.Envelope;
import io.cucumber.messages.types.Feature;
import io.cucumber.messages.types.FeatureChild;
import io.cucumber.messages.types.GherkinDocument;
import io.cucumber.messages.types.ParseError;
import io.cucumber.messages.types.Rule;
import io.cucumber.messages.types.RuleChild;
import io.cucumber.messages.types.Tag;
import io.cucumber.messages.types.TableCell;

/**
 * Liest ein Gherkin-Skript, <em>ohne</em> es auszuführen – für die Syntaxprüfung im Backend und als Grundlage, aus der
 * die Desktop-App die Tools baut ({@code GherkinScriptCompiler}).
 *
 * <ul>
 *   <li>Die {@code Funktionalität} ist das Modul: ihr Name ist der Anzeigename, der Freitext darunter die
 *       Beschreibung.</li>
 *   <li>Jedes {@code Szenario} wird ein Tool. Der Tool-Name entsteht aus dem Szenario-Namen („Branch prüfen“ →
 *       {@code branch_pruefen}), der Freitext darunter ist die Tool-Beschreibung, Zeilen {@code <name>: Text}
 *       beschreiben Parameter ({@code <name>: optional – Text} macht ihn optional).</li>
 *   <li>Platzhalter {@code <name>} in Schritten, Tabellen und DocStrings sind die Parameter des Tools (Text,
 *       Pflicht).</li>
 *   <li>Tags {@code @readOnly}, {@code @destructive} und {@code @idempotent} an Funktionalität, Regel oder Szenario
 *       werden MCP-Hinweise; andere Tags sind erlaubt und ohne Wirkung.</li>
 *   <li>Eine {@code Grundlage} läuft vor jedem Szenario (die einer {@code Regel} nach der der Funktionalität).
 *       Szenariogrundrisse mit {@code Beispiele} gibt es nicht – die Werte kommen als Parameter.</li>
 * </ul>
 *
 * <p>Ohne Kopfzeile {@code # language: …} gilt Deutsch ({@code Funktionalität}, {@code Szenario}, {@code Angenommen},
 * {@code Wenn}, {@code Dann}, {@code Und}, {@code Aber}). Fehler kommen als {@link IllegalArgumentException} mit Zeile.
 */
public final class GherkinScripts {

    /** Wie {@code DevToolsScript.TOOL_NAME}: Kleinbuchstaben, Ziffern und '_', höchstens 48 Zeichen. */
    private static final int MAX_TOOL_NAME = 48;
    private static final Pattern LANGUAGE = Pattern.compile("#\\s*language\\s*:.*");
    private static final Pattern PLACEHOLDER = Pattern.compile("<([A-Za-z][A-Za-z0-9_]*)>");
    private static final Pattern PARAM_LINE = Pattern.compile("<([A-Za-z][A-Za-z0-9_]*)>\\s*[:–-]\\s*(.*)");
    private static final Pattern OPTIONAL = Pattern.compile("(?i)\\(?optional\\)?(?![\\p{L}\\d])[\\s:,;–-]*");
    private static final Pattern ERROR_POSITION = Pattern.compile("^\\(\\d+:\\d+\\):\\s*");
    private static final Set<String> HINT_TAGS = Set.of("readonly", "destructive", "idempotent");
    private static final String NO_FEATURE = "Das Skript braucht eine Zeile „Funktionalität: …“ (englisch "
            + "„Feature: …“ mit „# language: en“) und darunter mindestens ein Szenario.";

    private GherkinScripts() {
    }

    /**
     * Das gelesene Skript.
     *
     * @param displayName Name der Funktionalität
     * @param description Freitext unter der Funktionalität, ohne ihn der Name
     */
    public record Script(String displayName, String description, List<Scenario> scenarios) {

        public Script {
            scenarios = List.copyOf(scenarios);
        }
    }

    /**
     * Ein Szenario = ein Tool.
     *
     * @param name        Name wie im Skript
     * @param toolName    daraus abgeleiteter Tool-Name ohne Modul-Präfix
     * @param description Tool-Beschreibung (Freitext unter dem Szenario, ohne ihn der Name)
     * @param params      Parameter in der Reihenfolge des ersten Vorkommens
     * @param hints       MCP-Hinweise aus den Tags ({@code readonly}, {@code destructive}, {@code idempotent})
     * @param steps       Schritte samt der Grundlage(n) davor
     * @param line        Zeile des Szenarios
     */
    public record Scenario(String name, String toolName, String description, List<Param> params,
                           Set<String> hints, List<Step> steps, int line) {

        public Scenario {
            params = List.copyOf(params);
            hints = Set.copyOf(hints);
            steps = List.copyOf(steps);
        }
    }

    /**
     * Parameter eines Tools aus einem Platzhalter {@code <name>}; immer Text.
     *
     * @param description aus der Zeile {@code <name>: Text} unter dem Szenario, sonst leer
     * @param required    {@code false}, wenn die Beschreibung mit „optional“ beginnt – fehlt er, ist der Wert leer
     */
    public record Param(String name, String description, boolean required) {
    }

    /**
     * Ein Schritt.
     *
     * @param keyword   Schlüsselwort ohne Leerzeichen am Ende, z.B. {@code Angenommen}
     * @param table     Datentabelle (Zeilen mit Zellen) oder {@code null}
     * @param docString Inhalt eines DocStrings ({@code """ … """}) oder {@code null}
     */
    public record Step(String keyword, String text, int line, List<List<String>> table, String docString) {

        public Step {
            table = table == null ? null : table.stream().map(List::copyOf).toList();
        }

        /** Wie im Skript, für Meldungen: {@code Wenn ich das Tool "git_status" aufrufe}. */
        public String display() {
            return keyword + " " + text;
        }
    }

    /** Dateiname für Meldungen. */
    public static String fileName(String scriptName) {
        return "script_" + scriptName + ".feature";
    }

    /**
     * Liest und prüft das Skript.
     *
     * @throws IllegalArgumentException bei Syntaxfehlern und Verstößen gegen die Regeln oben (mit Zeile)
     */
    public static Script parse(String scriptName, String source) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("Der Quelltext darf nicht leer sein.");
        }
        // Ohne Sprachangabe Deutsch: Kopfzeile davor, die Zeilennummern unten gleichen das wieder aus
        int offset = declaresLanguage(source) ? 0 : 1;
        String text = offset == 0 ? source : "# language: de\n" + source;
        GherkinParser parser = GherkinParser.builder().includeSource(false).includePickles(false)
                .includeGherkinDocument(true).build();
        List<Envelope> envelopes = parser.parse(fileName(scriptName), text.getBytes(StandardCharsets.UTF_8)).toList();
        List<String> errors = envelopes.stream().map(Envelope::getParseError).flatMap(Optional::stream).limit(5)
                .map(e -> describe(e, offset)).toList();
        if (!errors.isEmpty()) {
            boolean noFeature = errors.stream().anyMatch(e -> e.contains("#FeatureLine"));
            throw new IllegalArgumentException((noFeature ? NO_FEATURE + " " : "")
                    + "Das Gherkin-Skript lässt sich nicht lesen: " + String.join("; ", errors));
        }
        Feature feature = envelopes.stream().map(Envelope::getGherkinDocument).flatMap(Optional::stream)
                .map(GherkinDocument::getFeature).flatMap(Optional::stream).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(NO_FEATURE));
        return new Reader(offset).feature(feature);
    }

    /** Ob vor der ersten Zeile mit Inhalt eine Kommentarzeile {@code # language: …} steht. */
    private static boolean declaresLanguage(String source) {
        for (String line : source.lines().map(String::strip).toList()) {
            if (line.isEmpty()) {
                continue;
            }
            if (!line.startsWith("#")) {
                return false;
            }
            if (LANGUAGE.matcher(line).matches()) {
                return true;
            }
        }
        return false;
    }

    private static String describe(ParseError e, int offset) {
        String msg = ERROR_POSITION.matcher(e.getMessage() == null ? "" : e.getMessage()).replaceFirst("");
        return e.getSource().getLocation().map(l -> "Zeile " + (l.getLine() - offset)
                        + l.getColumn().map(c -> ", Spalte " + c).orElse("") + ": " + msg)
                .orElse(msg);
    }

    /**
     * Tool-Name aus einem Szenario-Namen: Kleinbuchstaben, Umlaute ausgeschrieben, alles andere zu {@code _}.
     *
     * @return leer, wenn nichts Brauchbares übrig bleibt
     */
    public static String toolName(String scenarioName) {
        String s = scenarioName.strip().toLowerCase(Locale.ROOT)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
        s = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        s = s.replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (!s.isEmpty() && !Character.isLetter(s.charAt(0))) {
            s = "s_" + s;
        }
        if (s.length() > MAX_TOOL_NAME) {
            s = s.substring(0, MAX_TOOL_NAME).replaceAll("_+$", "");
        }
        return s;
    }

    /** Liest den Dokumentbaum; {@code offset} = Zeilen, die vor den Quelltext gesetzt wurden. */
    private static final class Reader {
        private final int offset;
        private final List<Scenario> scenarios = new ArrayList<>();
        private final Map<String, Integer> toolLines = new LinkedHashMap<>();

        Reader(int offset) {
            this.offset = offset;
        }

        Script feature(Feature feature) {
            Set<String> tags = tags(feature.getTags());
            List<Step> background = List.of();
            for (FeatureChild child : feature.getChildren()) {
                if (child.getBackground().isPresent()) {
                    background = steps(child.getBackground().get());
                } else if (child.getScenario().isPresent()) {
                    scenario(child.getScenario().get(), tags, background);
                } else if (child.getRule().isPresent()) {
                    rule(child.getRule().get(), tags, background);
                }
            }
            String name = feature.getName() == null ? "" : feature.getName().strip();
            String description = text(lines(feature.getDescription()));
            if (description.isEmpty()) {
                description = name;
            }
            if (description.isEmpty()) {
                throw new IllegalArgumentException("Zeile " + line(feature.getLocation().getLine()) + ": Die "
                        + "Funktionalität braucht einen Namen oder darunter eine Beschreibung.");
            }
            if (scenarios.isEmpty()) {
                throw new IllegalArgumentException("Das Skript hat kein Szenario – jedes Szenario wird ein Tool.");
            }
            return new Script(name.isEmpty() ? description : name, description, scenarios);
        }

        private void rule(Rule rule, Set<String> featureTags, List<Step> featureBackground) {
            Set<String> tags = new LinkedHashSet<>(featureTags);
            tags.addAll(tags(rule.getTags()));
            List<Step> background = featureBackground;
            for (RuleChild child : rule.getChildren()) {
                if (child.getBackground().isPresent()) {
                    background = new ArrayList<>(featureBackground);
                    background.addAll(steps(child.getBackground().get()));
                } else if (child.getScenario().isPresent()) {
                    scenario(child.getScenario().get(), tags, background);
                }
            }
        }

        private void scenario(io.cucumber.messages.types.Scenario s, Set<String> inherited, List<Step> background) {
            int line = line(s.getLocation().getLine());
            String name = s.getName() == null ? "" : s.getName().strip();
            if (!s.getExamples().isEmpty()) {
                throw new IllegalArgumentException("Zeile " + line + ": Beispiele (Szenariogrundriss) gibt es in "
                        + "Skripten nicht – Platzhalter <name> in einem normalen Szenario werden zu Parametern des "
                        + "Tools.");
            }
            String toolName = toolName(name);
            if (toolName.isEmpty()) {
                throw new IllegalArgumentException("Zeile " + line + ": Das Szenario braucht einen Namen – aus ihm "
                        + "entsteht der Tool-Name (z.B. „Branch prüfen“ → branch_pruefen).");
            }
            Integer other = toolLines.putIfAbsent(toolName, line);
            if (other != null) {
                throw new IllegalArgumentException("Zeile " + line + ": Das Szenario ergibt denselben Tool-Namen '"
                        + toolName + "' wie das in Zeile " + other + " – Namen unterscheidbar machen.");
            }
            List<Step> steps = new ArrayList<>(background);
            steps.addAll(s.getSteps().stream().map(this::step).toList());
            if (steps.isEmpty()) {
                throw new IllegalArgumentException("Zeile " + line + ": Das Szenario „" + name + "“ hat keine Schritte.");
            }

            // Freitext: Zeilen „<name>: …“ beschreiben Parameter, der Rest ist die Tool-Beschreibung
            Map<String, String> described = new LinkedHashMap<>();
            List<String> text = new ArrayList<>();
            for (String l : lines(s.getDescription())) {
                Matcher m = PARAM_LINE.matcher(l);
                if (m.matches()) {
                    described.put(m.group(1), m.group(2).strip());
                } else {
                    text.add(l);
                }
            }
            Set<String> used = new LinkedHashSet<>();
            steps.forEach(step -> used.addAll(placeholders(step)));
            for (String p : described.keySet()) {
                if (!used.contains(p)) {
                    throw new IllegalArgumentException("Zeile " + line + ": Der Parameter <" + p + "> ist im Szenario „"
                            + name + "“ beschrieben, kommt aber in keinem Schritt vor.");
                }
            }
            List<Param> params = used.stream().map(p -> {
                String d = described.getOrDefault(p, "");
                Matcher optional = OPTIONAL.matcher(d);
                return optional.lookingAt() ? new Param(p, d.substring(optional.end()).strip(), false)
                        : new Param(p, d, true);
            }).toList();
            Set<String> tags = new LinkedHashSet<>(inherited);
            tags.addAll(tags(s.getTags()));
            tags.retainAll(HINT_TAGS);
            String description = text(text);
            scenarios.add(new Scenario(name, toolName, description.isEmpty() ? name : description, params, tags, steps,
                    line));
        }

        private List<Step> steps(Background b) {
            return b.getSteps().stream().map(this::step).toList();
        }

        private Step step(io.cucumber.messages.types.Step s) {
            List<List<String>> table = s.getDataTable().map(t -> t.getRows().stream()
                    .map(r -> r.getCells().stream().map(TableCell::getValue).toList()).toList()).orElse(null);
            return new Step(s.getKeyword().strip(), s.getText().strip(), line(s.getLocation().getLine()), table,
                    s.getDocString().map(d -> d.getContent()).orElse(null));
        }

        private int line(Integer line) {
            return line == null ? 0 : line - offset;
        }
    }

    /** Platzhalter {@code <name>} eines Schritts (Text, Tabelle, DocString) in Reihenfolge des Auftretens. */
    public static Set<String> placeholders(Step step) {
        Set<String> out = new LinkedHashSet<>();
        collect(step.text(), out);
        if (step.table() != null) {
            step.table().forEach(row -> row.forEach(cell -> collect(cell, out)));
        }
        collect(step.docString(), out);
        return out;
    }

    private static void collect(String text, Set<String> out) {
        if (text == null) {
            return;
        }
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) {
            out.add(m.group(1));
        }
    }

    private static Set<String> tags(List<Tag> tags) {
        return tags.stream().map(t -> t.getName().replaceFirst("^@", "").toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static List<String> lines(String description) {
        return description == null ? List.of()
                : description.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
    }

    private static String text(List<String> lines) {
        return String.join(" ", lines).strip();
    }
}
