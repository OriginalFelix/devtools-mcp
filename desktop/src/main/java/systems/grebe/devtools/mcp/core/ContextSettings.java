package systems.grebe.devtools.mcp.core;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import systems.grebe.devtools.mcp.config.ModuleSettings;

/**
 * Einstellungen des Moduls „Kontext sparen“ ({@code context}), die über das Modul hinaus wirken: wie Ergebnisse gekürzt
 * werden, welche Tools der Server anbietet und wie ausführlich die Instructions sind. Liegt im Kern, weil
 * {@link ToolRegistry}, {@link McpRuntime} und {@link ServerInstructions} sie brauchen.
 *
 * @param enabled             Modul an – sonst gilt nichts davon
 * @param maxChars            Höchstlänge eines Ergebnisses, darüber wird gekürzt (0 = nie)
 * @param compactOutput       Logs und Befehlsausgaben zusammenfassen (Stacktraces, Wiederholungen …)
 * @param verbatimTools       Tools, deren Ausgabe wortgetreu bleibt (Dateien, Diffs) – Muster mit {@code *}
 * @param exemptTools         Tools, die nie gekürzt werden – Muster mit {@code *}
 * @param dedupe              gleiche Ergebnisse lesender Tools in derselben Session nicht erneut senden
 * @param dedupeMinutes       wie lange ein Ergebnis als „schon gesehen“ gilt
 * @param compactInstructions Instructions nur mit einer Zeile je Modul; Details über {@code context_guide}
 * @param shellHintsOnce      Shell-Hinweise nur einmal je Modul in den Instructions statt in jeder Tool-Beschreibung
 * @param lazyTools           nur die {@code context_*}-Tools anbieten; alle übrigen über {@code context_find}/{@code
 *                            context_call}
 * @param lazyKeep            Tools, die bei {@code lazyTools} trotzdem direkt angeboten werden – Muster mit {@code *}
 */
public record ContextSettings(boolean enabled, int maxChars, boolean compactOutput, List<String> verbatimTools,
                              List<String> exemptTools, boolean dedupe, int dedupeMinutes, boolean compactInstructions,
                              boolean shellHintsOnce, boolean lazyTools, List<String> lazyKeep) {

    public static final String ID = "context";

    public static final String MAX_CHARS = "maxChars";
    public static final String COMPACT_OUTPUT = "compactOutput";
    public static final String VERBATIM_TOOLS = "verbatimTools";
    public static final String EXEMPT_TOOLS = "exemptTools";
    public static final String DEDUPE = "dedupe";
    public static final String DEDUPE_MINUTES = "dedupeMinutes";
    public static final String COMPACT_INSTRUCTIONS = "compactInstructions";
    public static final String SHELL_HINTS_ONCE = "shellHintsOnce";
    public static final String LAZY_TOOLS = "lazyTools";
    public static final String LAZY_KEEP = "lazyKeep";

    public static final int DEFAULT_MAX_CHARS = 12_000;
    public static final String DEFAULT_VERBATIM = String.join("\n", "*_view", "*_read*", "*file*", "git_diff",
            "git_show_commit", "git_blame", "git_compare", "decompile_*", "graph_read", "jdbc_*");
    public static final String DEFAULT_EXEMPT = String.join("\n", "skills_view", "scripts_view", "memories_view");

    /** Alles aus – so verhält sich der Server ohne das Modul. */
    public static final ContextSettings OFF = new ContextSettings(false, 0, false, List.of(), List.of(), false, 0,
            false, false, false, List.of());

    public ContextSettings {
        verbatimTools = List.copyOf(verbatimTools);
        exemptTools = List.copyOf(exemptTools);
        lazyKeep = List.copyOf(lazyKeep);
    }

    /** Aus den Werten des Moduls; fehlende Werte nehmen die Vorgaben. */
    public static ContextSettings of(boolean enabled, ModuleConfig c) {
        if (!enabled) {
            return OFF;
        }
        return new ContextSettings(true, Math.max(0, c.getInt(MAX_CHARS, DEFAULT_MAX_CHARS)),
                bool(c, COMPACT_OUTPUT, true), list(c, VERBATIM_TOOLS, DEFAULT_VERBATIM),
                list(c, EXEMPT_TOOLS, DEFAULT_EXEMPT), bool(c, DEDUPE, true),
                Math.max(1, c.getInt(DEDUPE_MINUTES, 10)), bool(c, COMPACT_INSTRUCTIONS, true),
                bool(c, SHELL_HINTS_ONCE, false), bool(c, LAZY_TOOLS, false), list(c, LAZY_KEEP, ""));
    }

    /** Aus gespeicherten Einstellungen ({@code null} = nie gespeichert: Modul an, Vorgaben). */
    public static ContextSettings of(ModuleSettings stored) {
        Map<String, String> values = stored == null ? Map.of() : stored.values();
        return of(stored == null || stored.enabled(), ModuleConfig.of(List.of(), values));
    }

    private static boolean bool(ModuleConfig c, String key, boolean fallback) {
        return c.get(key).map(v -> Boolean.parseBoolean(v.strip())).orElse(fallback);
    }

    private static List<String> list(ModuleConfig c, String key, String fallback) {
        return c.get(key).isPresent() ? c.getList(key) : ModuleConfig.splitLines(fallback);
    }

    public boolean verbatim(String tool) {
        return matches(verbatimTools, tool);
    }

    public boolean exempt(String tool) {
        return matches(exemptTools, tool);
    }

    /** Ob das Tool dem Client direkt angeboten wird (bei {@link #lazyTools} nur {@code context_*} und {@link #lazyKeep}). */
    public boolean exposes(String moduleId, String tool) {
        return !lazyTools || ID.equals(moduleId) || matches(lazyKeep, tool);
    }

    /** Ob {@code name} auf eines der Muster passt ({@code *} = beliebig viele Zeichen, sonst exakt, ohne Groß/klein). */
    public static boolean matches(List<String> patterns, String name) {
        String n = name.toLowerCase(Locale.ROOT);
        for (String p : patterns) {
            String q = p.strip().toLowerCase(Locale.ROOT);
            if (q.isEmpty()) {
                continue;
            }
            if (q.indexOf('*') < 0 ? q.equals(n) : glob(q).matcher(n).matches()) {
                return true;
            }
        }
        return false;
    }

    private static Pattern glob(String p) {
        return Pattern.compile(java.util.Arrays.stream(p.split("\\*", -1)).map(Pattern::quote)
                .collect(java.util.stream.Collectors.joining(".*")));
    }
}
