package systems.grebe.devtools.mcp.modules.scripts;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import systems.grebe.devtools.mcp.backend.scripts.GherkinScripts;
import systems.grebe.devtools.mcp.core.ManagedToolCallback;
import systems.grebe.devtools.mcp.core.ToolProgress;
import tools.jackson.databind.json.JsonMapper;

/**
 * Ein Aufruf eines Gherkin-Tools: führt die Schritte des Szenarios nacheinander aus und hält dabei Parameter,
 * Variablen, das Ergebnis des letzten Tool-Aufrufs, Ausgaben und den Ablauf. Das Ergebnis des Tools ist ein Bericht
 * (Ausgaben, dann der Ablauf mit den Ergebnissen der aufgerufenen Tools); schlägt ein Schritt fehl, kommt derselbe
 * Bericht bis dahin als Fehler mit Zeile.
 */
final class ScenarioRun {

    /** {@code <parameter>} (Gruppe 1) oder {@code ${variable}} (Gruppe 2). */
    static final Pattern REFERENCE = Pattern.compile("<([A-Za-z][A-Za-z0-9_]*)>|\\$\\{([A-Za-z][A-Za-z0-9_]*)}");
    /** So viel eines Tool-Ergebnisses steht im Ablauf. */
    static final int MAX_RESULT_IN_LOG = 1500;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String scenario;
    private final Map<String, String> params;
    private final ToolCaller tools;
    private final Map<String, String> variables = new LinkedHashMap<>();
    private final List<String> output = new ArrayList<>();
    private final StringBuilder log = new StringBuilder();
    private final StringBuilder details = new StringBuilder();
    private String result;
    private String resultTool;

    ScenarioRun(String scenario, Map<String, String> params, ToolCaller tools) {
        this.scenario = scenario;
        this.params = params;
        this.tools = tools;
    }

    /** Führt die Schritte aus; Fehler als {@link IllegalStateException} mit Zeile und Ablauf. */
    String run(List<GherkinSteps.Bound> steps) {
        int index = 0;
        for (GherkinSteps.Bound b : steps) {
            index++;
            GherkinScripts.Step step = b.step();
            String display = resolveQuietly(step.display());
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("Szenario „" + scenario + "“ vor Zeile " + step.line() + " abgebrochen.");
            }
            ToolProgress.report("Schritt " + index + "/" + steps.size() + ": " + display);
            details.setLength(0);
            try {
                b.definition().action().run(this, resolvedArgs(b), step);
            } catch (RuntimeException e) {
                log.append("✗ Zeile ").append(step.line()).append(": ").append(display).append('\n').append(details);
                throw new IllegalStateException("Szenario „" + scenario + "“ in Zeile " + step.line()
                        + " fehlgeschlagen: " + display + " – " + ManagedToolCallback.describe(e) + "\n\nAblauf:\n"
                        + log.toString().stripTrailing(), e);
            }
            log.append("✓ Zeile ").append(step.line()).append(": ").append(display).append('\n').append(details);
        }
        StringBuilder sb = new StringBuilder("Szenario „").append(scenario).append("“ erfolgreich (")
                .append(steps.size()).append(steps.size() == 1 ? " Schritt).\n" : " Schritte).\n");
        if (!output.isEmpty()) {
            sb.append("\nAusgabe:\n").append(String.join("\n", output)).append('\n');
        }
        return sb.append("\nAblauf:\n").append(log.toString().stripTrailing()).toString();
    }

    // ------------------------------------------------------------------ Platzhalter und Variablen

    private List<Object> resolvedArgs(GherkinSteps.Bound b) {
        List<Object> out = new ArrayList<>(b.args().size());
        for (int i = 0; i < b.args().size(); i++) {
            Object a = b.args().get(i);
            out.add(a instanceof String s && i != b.definition().variableArg()
                    ? resolve(s, i == b.definition().regexArg()) : a);
        }
        return out;
    }

    /** Setzt {@code <parameter>} und {@code ${variable}} ein. */
    String resolve(String text) {
        return resolve(text, false);
    }

    /** @param quote eingesetzte Werte für einen regulären Ausdruck maskieren (sie sind Text, kein Muster) */
    private String resolve(String text, boolean quote) {
        if (text == null) {
            return null;
        }
        Matcher m = REFERENCE.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String value;
            boolean literal = false;
            if (m.group(1) != null) {
                value = params.get(m.group(1));
                if (value == null) { // kein Parameter (kommt nicht vor – alle <…> sind Parameter): stehen lassen
                    value = m.group();
                    literal = true;
                }
            } else {
                String name = m.group(2);
                value = variables.containsKey(name) ? variables.get(name) : params.get(name);
                if (value == null) {
                    throw new IllegalArgumentException("Variable ${" + name + "} ist nicht gesetzt.");
                }
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(quote && !literal ? Pattern.quote(value) : value));
        }
        return m.appendTail(sb).toString();
    }

    private String resolveQuietly(String text) {
        try {
            return resolve(text);
        } catch (RuntimeException e) {
            return text;
        }
    }

    // ------------------------------------------------------------------ Schritte

    /** Ergebnis des letzten Tool-Aufrufs. */
    String result() {
        if (result == null) {
            throw new IllegalStateException("Es wurde noch kein Tool aufgerufen.");
        }
        return result;
    }

    void callTool(String name, GherkinScripts.Step step) {
        result = tools.call(name, toolArgs(step));
        resultTool = name;
        details.append(indent("→ " + name + ": " + abbreviate(result, MAX_RESULT_IN_LOG)));
    }

    void pollTool(String name, int seconds, String expected, GherkinScripts.Step step) {
        Map<String, Object> args = toolArgs(step);
        int attempts = 0;
        while (true) {
            attempts++;
            result = tools.call(name, args);
            resultTool = name;
            if (result.contains(expected)) {
                break;
            }
            ToolProgress.report("Warte auf „" + expected + "“ von " + name + " (Aufruf " + attempts + ")");
            sleep(Math.max(1, seconds));
        }
        details.append(indent("→ " + name + " (" + attempts + (attempts == 1 ? " Aufruf" : " Aufrufe") + "): "
                + abbreviate(result, MAX_RESULT_IN_LOG)));
    }

    void expectContains(String text, boolean expected) {
        if (result().contains(text) != expected) {
            throw new IllegalStateException("Das Ergebnis von " + resultTool + (expected ? " enthält „" + text
                    + "“ nicht." : " enthält „" + text + "“."));
        }
    }

    void expectMatches(String regex) {
        if (!Pattern.compile(regex, Pattern.MULTILINE).matcher(result()).find()) {
            throw new IllegalStateException("Das Ergebnis von " + resultTool + " passt nicht zu „" + regex + "“.");
        }
    }

    void expectEquals(String text) {
        if (!result().strip().equals(text.strip())) {
            throw new IllegalStateException("Das Ergebnis von " + resultTool + " ist nicht „" + text + "“, sondern „"
                    + abbreviate(result().strip(), 200) + "“.");
        }
    }

    /** Erste Fundstelle des Musters im Ergebnis – Gruppe 1, wenn es eine gibt – als Variable. */
    void extract(String regex, String variable) {
        Matcher m = Pattern.compile(regex, Pattern.MULTILINE).matcher(result());
        if (!m.find()) {
            throw new IllegalStateException("„" + regex + "“ kommt im Ergebnis von " + resultTool + " nicht vor.");
        }
        setVariable(variable, m.groupCount() >= 1 && m.group(1) != null ? m.group(1) : m.group());
    }

    void setVariable(String name, String value) {
        variables.put(name, value);
        details.append(indent("→ ${" + name + "} = " + abbreviate(value, 200)));
    }

    void output(String text) {
        output.add(text);
    }

    void sleep(int seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Beim Warten abgebrochen.");
        }
    }

    // ------------------------------------------------------------------ Hilfen

    /** Argumente eines Tool-Aufrufs: Tabelle {@code | name | wert |} (leerer Wert = weglassen) oder JSON-DocString. */
    private Map<String, Object> toolArgs(GherkinScripts.Step step) {
        Map<String, Object> args = new LinkedHashMap<>();
        if (step.table() != null) {
            for (List<String> row : step.table()) {
                String value = resolve(row.get(1));
                if (!value.isEmpty()) {
                    args.put(resolve(row.get(0)).strip(), value);
                }
            }
        } else if (step.docString() != null) {
            Map<?, ?> json = JSON.readValue(step.docString(), Map.class);
            json.forEach((k, v) -> args.put(String.valueOf(k), resolveDeep(v)));
        }
        return args;
    }

    private Object resolveDeep(Object v) {
        if (v instanceof String s) {
            return resolve(s);
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, x) -> out.put(String.valueOf(k), resolveDeep(x)));
            return out;
        }
        if (v instanceof List<?> l) {
            return l.stream().map(this::resolveDeep).toList();
        }
        return v;
    }

    private static String abbreviate(String s, int max) {
        String t = s == null ? "" : s.strip();
        return t.length() <= max ? t : t.substring(0, max) + " … (gekürzt, " + t.length() + " Zeichen)";
    }

    private static String indent(String text) {
        StringBuilder sb = new StringBuilder();
        text.lines().forEach(l -> sb.append("    ").append(l).append('\n'));
        return sb.toString();
    }
}
