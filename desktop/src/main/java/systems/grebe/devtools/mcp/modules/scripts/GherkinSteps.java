package systems.grebe.devtools.mcp.modules.scripts;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import io.cucumber.cucumberexpressions.Argument;
import io.cucumber.cucumberexpressions.Expression;
import io.cucumber.cucumberexpressions.ExpressionFactory;
import io.cucumber.cucumberexpressions.ParameterTypeRegistry;
import systems.grebe.devtools.mcp.backend.scripts.GherkinScripts;

/**
 * Die eingebauten Schritte für Gherkin-Skripte, als Cucumber Expressions ({@code {string}}, {@code {int}},
 * {@code {word}}; {@code (…)} = optional). Ein Schritt passt unabhängig vom Schlüsselwort ({@code Angenommen},
 * {@code Wenn}, {@code Dann}, {@code Und}, {@code Aber}); Platzhalter {@code <name>} und Variablen {@code ${name}}
 * stehen in Anführungszeichen, Tabellen oder DocStrings und werden erst beim Aufruf eingesetzt – so lässt sich jeder
 * Schritt schon beim Speichern zuordnen.
 */
final class GherkinSteps {

    /** Name einer Variablen ({@code ich setze name auf …}). */
    static final Pattern VARIABLE = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");
    /** Voller Tool-Name, wenn er fest im Skript steht. */
    static final Pattern TOOL_NAME = Pattern.compile("[a-z][a-z0-9_]*");

    private GherkinSteps() {
    }

    /** Was ein Schritt beim Ausführen tut; Text-Argumente kommen mit eingesetzten Platzhaltern und Variablen. */
    @FunctionalInterface
    interface Action {
        void run(ScenarioRun run, List<Object> args, GherkinScripts.Step step);
    }

    /**
     * Ein eingebauter Schritt.
     *
     * @param example        Beispiel für Referenz und Fehlermeldungen
     * @param toolArg        Index des Arguments mit dem Tool-Namen oder -1
     * @param variableArg    Index des Arguments, das eine Variable setzt, oder -1
     * @param regexArg       Index des Arguments mit einem regulären Ausdruck oder -1
     * @param needsResult    braucht das Ergebnis eines vorherigen Tool-Aufrufs
     * @param callsTool      ruft ein Tool auf und setzt so das Ergebnis
     */
    record Definition(String pattern, Expression expression, String example, int toolArg, int variableArg,
                      int regexArg, boolean needsResult, boolean callsTool, Action action) {
    }

    /** Ein Schritt des Skripts mit dem passenden eingebauten Schritt und den Argumenten (noch ohne Platzhalter). */
    record Bound(GherkinScripts.Step step, Definition definition, List<Object> args) {

        String string(int index) {
            return (String) args.get(index);
        }
    }

    private static final ExpressionFactory EXPRESSIONS = new ExpressionFactory(new ParameterTypeRegistry(Locale.GERMAN));

    static final List<Definition> BUILT_IN = List.of(
            // Tools aufrufen – Argumente als Tabelle (| name | wert |) oder DocString mit JSON-Objekt
            def("ich rufe das Tool {string} auf", "Angenommen ich rufe das Tool \"git_status\" auf",
                    0, -1, -1, false, true, (run, a, s) -> run.callTool((String) a.get(0), s)),
            def("ich das Tool {string} aufrufe", "Wenn ich das Tool \"build_test\" aufrufe:\n  | project | <repo> |",
                    0, -1, -1, false, true, (run, a, s) -> run.callTool((String) a.get(0), s)),
            def("ich rufe das Tool {string} alle {int} Sekunde(n) auf, bis das Ergebnis {string} enthält",
                    "Und ich rufe das Tool \"branchcheck_status\" alle 30 Sekunden auf, bis das Ergebnis \"fertig\" enthält",
                    0, -1, -1, false, true,
                    (run, a, s) -> run.pollTool((String) a.get(0), ((Number) a.get(1)).intValue(), (String) a.get(2), s)),
            def("ich das Tool {string} alle {int} Sekunde(n) aufrufe, bis das Ergebnis {string} enthält",
                    "Wenn ich das Tool \"branchcheck_status\" alle 30 Sekunden aufrufe, bis das Ergebnis \"fertig\" enthält",
                    0, -1, -1, false, true,
                    (run, a, s) -> run.pollTool((String) a.get(0), ((Number) a.get(1)).intValue(), (String) a.get(2), s)),

            // Prüfen – schlägt eine Prüfung fehl, bricht das Szenario mit Ablauf und Ergebnis ab
            def("enthält das Ergebnis {string}", "Dann enthält das Ergebnis \"BUILD ERFOLGREICH\"",
                    -1, -1, -1, true, false, (run, a, s) -> run.expectContains((String) a.get(0), true)),
            def("das Ergebnis enthält {string}", "Und das Ergebnis enthält \"<branch>\"",
                    -1, -1, -1, true, false, (run, a, s) -> run.expectContains((String) a.get(0), true)),
            def("enthält das Ergebnis nicht {string}", "Dann enthält das Ergebnis nicht \"BUILD FEHLGESCHLAGEN\"",
                    -1, -1, -1, true, false, (run, a, s) -> run.expectContains((String) a.get(0), false)),
            def("das Ergebnis enthält {string} nicht", "Aber das Ergebnis enthält \"Konflikt\" nicht",
                    -1, -1, -1, true, false, (run, a, s) -> run.expectContains((String) a.get(0), false)),
            def("passt das Ergebnis zu {string}", "Dann passt das Ergebnis zu \"Tests: \\d+ gesamt, 0 fehlgeschlagen\"",
                    -1, -1, 0, true, false, (run, a, s) -> run.expectMatches((String) a.get(0))),
            def("das Ergebnis passt zu {string}", "Und das Ergebnis passt zu \"(?i)sauber\"",
                    -1, -1, 0, true, false, (run, a, s) -> run.expectMatches((String) a.get(0))),
            def("ist das Ergebnis {string}", "Dann ist das Ergebnis \"OK\"",
                    -1, -1, -1, true, false, (run, a, s) -> run.expectEquals((String) a.get(0))),
            def("das Ergebnis ist {string}", "Und das Ergebnis ist \"OK\"",
                    -1, -1, -1, true, false, (run, a, s) -> run.expectEquals((String) a.get(0))),

            // Variablen – danach als ${name} verwendbar
            def("ich merke (mir )das Ergebnis als {word}", "Und ich merke mir das Ergebnis als status",
                    -1, 0, -1, true, false, (run, a, s) -> run.setVariable((String) a.get(0), run.result())),
            def("ich merke (mir ){string} aus dem Ergebnis als {word}",
                    "Und ich merke mir \"Job-ID: (\\w+)\" aus dem Ergebnis als job",
                    -1, 1, 0, true, false, (run, a, s) -> run.extract((String) a.get(0), (String) a.get(1))),
            def("ich setze {word} auf {string}", "Angenommen ich setze projekt auf \"web-core\"",
                    -1, 0, -1, false, false, (run, a, s) -> run.setVariable((String) a.get(0), (String) a.get(1))),

            // Ausgabe und Warten
            def("ich gebe {string} aus", "Und ich gebe \"Branch <branch> ist sauber.\" aus",
                    -1, -1, -1, false, false, (run, a, s) -> run.output((String) a.get(0))),
            def("ich gebe das Ergebnis aus", "Und ich gebe das Ergebnis aus",
                    -1, -1, -1, true, false, (run, a, s) -> run.output(run.result())),
            def("ich warte {int} Sekunde(n)", "Und ich warte 10 Sekunden",
                    -1, -1, -1, false, false, (run, a, s) -> run.sleep(((Number) a.get(0)).intValue())));

    private static Definition def(String pattern, String example, int toolArg, int variableArg, int regexArg,
                                  boolean needsResult, boolean callsTool, Action action) {
        return new Definition(pattern, EXPRESSIONS.createExpression(pattern), example, toolArg, variableArg, regexArg,
                needsResult, callsTool, action);
    }

    /**
     * Ordnet einen Schritt dem passenden eingebauten Schritt zu.
     *
     * @throws IllegalArgumentException mit Zeile, wenn kein oder mehr als ein Schritt passt
     */
    static Bound bind(GherkinScripts.Step step) {
        // Doppelpunkt vor Tabelle oder DocString („… aufrufe:“) gehört nicht zum Schritt
        String text = step.text().endsWith(":") ? step.text().substring(0, step.text().length() - 1).strip()
                : step.text();
        List<Bound> matches = new ArrayList<>();
        for (Definition d : BUILT_IN) {
            Optional<List<Argument<?>>> m = d.expression().match(text);
            m.ifPresent(args -> matches.add(new Bound(step, d, args.stream().map(a -> (Object) a.getValue()).toList())));
        }
        if (matches.size() == 1) {
            return matches.getFirst();
        }
        if (matches.size() > 1) {
            throw new IllegalArgumentException("Zeile " + step.line() + ": Der Schritt „" + step.display()
                    + "“ ist mehrdeutig: " + String.join(" / ", matches.stream().map(b -> b.definition().pattern())
                    .toList()) + ".");
        }
        String hint = outsideQuotes(step.text()).matches(".*(<[A-Za-z]|\\$\\{).*")
                ? " Platzhalter <name> und Variablen ${name} gehören in Anführungszeichen, Tabellen oder DocStrings."
                : "";
        throw new IllegalArgumentException("Zeile " + step.line() + ": Unbekannter Schritt „" + step.display() + "“."
                + hint + " Bekannte Schritte (nach Angenommen/Wenn/Dann/Und/Aber): " + String.join("; ",
                BUILT_IN.stream().map(Definition::pattern).toList()) + ".");
    }

    /** Text ohne die Teile in Anführungszeichen. */
    private static String outsideQuotes(String text) {
        return text.replaceAll("\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'", "\"\"");
    }

    /** Prüft einen regulären Ausdruck aus dem Skript schon beim Speichern (sofern er keine Platzhalter enthält). */
    static void requireRegex(Bound b, int index) {
        String regex = b.string(index);
        if (regex.contains("<") || regex.contains("${")) {
            return;
        }
        try {
            Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("Zeile " + b.step().line() + ": Ungültiger regulärer Ausdruck „" + regex
                    + "“: " + e.getDescription() + ".");
        }
    }

    /** Variablen {@code ${name}}, die ein Text verwendet. */
    static List<String> variablesIn(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        Matcher m = ScenarioRun.REFERENCE.matcher(text);
        while (m.find()) {
            if (m.group(2) != null) {
                out.add(m.group(2));
            }
        }
        return out;
    }

    /** Kurzreferenz der eingebauten Schritte mit Beispielen – für die Referenz in App und {@code scripts_view}. */
    static String reference() {
        StringBuilder sb = new StringBuilder();
        for (Definition d : BUILT_IN) {
            sb.append("- `").append(d.pattern()).append("` – z.B. `").append(d.example().lines().findFirst().orElse(""))
                    .append("`\n");
        }
        return sb.toString();
    }
}
