package systems.grebe.devtools.mcp.modules.context;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import systems.grebe.devtools.mcp.core.ContextSettings;
import systems.grebe.devtools.mcp.core.McpRuntime;
import systems.grebe.devtools.mcp.core.ServerInstructions;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.TokenStats;
import systems.grebe.devtools.mcp.core.ToolHints;
import systems.grebe.devtools.mcp.core.ToolInvocationLog;
import systems.grebe.devtools.mcp.core.ToolRegistry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** {@code context_slice}, {@code context_digest}, {@code context_stats}, {@code context_guide}, {@code context_find}. */
public class ContextTools {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    static final int FIND_LIMIT = 12;

    private final ResultStore store;
    private final ToolInvocationLog log;
    private final ObjectProvider<ToolRegistry> registry;
    private final ObjectProvider<ServerInstructions> instructions;
    private final ContextSettings ctx;
    private final Digester.Settings digest;

    ContextTools(ResultStore store, ToolInvocationLog log, ObjectProvider<ToolRegistry> registry,
                 ObjectProvider<ServerInstructions> instructions, ContextSettings ctx, Digester.Settings digest) {
        this.store = store;
        this.log = log;
        this.registry = registry;
        this.instructions = instructions;
        this.ctx = ctx;
        this.digest = digest;
    }

    @Tool(name = "slice", description = "Liest gezielt aus einem gekürzten oder abgelegten Tool-Ergebnis (Handle wie "
            + "r12 aus dem Kürzungshinweis): mit grep die passenden Zeilen samt Zeilennummer und Umgebung, sonst den "
            + "Zeilenbereich from_line–to_line. Erst suchen, dann gezielt lesen." + ShellHints.CONTEXT)
    @ToolHints(readOnly = true, openWorld = false)
    public String slice(
            @ToolParam(description = "Handle, z.B. r12") String handle,
            @ToolParam(required = false, description = "Regex (ohne Groß/klein), ungültig = wörtlich") String grep,
            @ToolParam(required = false, description = "Zeilen Umgebung je Treffer (Standard 2)") Integer context,
            @ToolParam(required = false, description = "erste Zeile (1-basiert)") Integer from_line,
            @ToolParam(required = false, description = "letzte Zeile (inklusive)") Integer to_line) {
        ResultStore.Stored s = stored(handle);
        int budget = budget();
        if (grep != null && !grep.isBlank()) {
            return ContextText.grep(s, grep, context == null ? 2 : context, budget);
        }
        return ContextText.slice(s, from_line == null ? 1 : from_line, to_line == null ? 0 : to_line, budget);
    }

    @Tool(name = "digest", description = "Fasst ein abgelegtes Tool-Ergebnis (Handle) mit einem kleinen Modell "
            + "zusammen – für lange Logs oder Ausgaben, wenn nur Überblick oder ein Aspekt (focus) zählt. Fehler, "
            + "Pfade und IDs bleiben wörtlich. Über das LLM des Clients (Sampling) oder die Claude API." + ShellHints.CONTEXT)
    @ToolHints(readOnly = true, openWorld = true)
    public String digest(
            @ToolParam(description = "Handle, z.B. r12") String handle,
            @ToolParam(required = false, description = "worauf achten, z.B. 'Fehler beim Start' oder 'langsame "
                    + "Tests'") String focus,
            ToolContext toolContext) {
        ResultStore.Stored s = stored(handle);
        McpSyncServerExchange exchange = exchange(toolContext);
        Digester.Settings settings = digest;
        if ((settings.apiKey() == null || settings.apiKey().isBlank()) && !Digester.canSample(exchange)) {
            settings = new Digester.Settings(settings.model(), classifyKey(), null);
        }
        return "[" + s.handle() + " · " + s.tool() + " · " + s.text().length() + " Zeichen, zusammengefasst]\n"
                + new Digester(settings).digest(s.tool(), s.text(), focus, exchange);
    }

    @Tool(name = "stats", description = "Welche Tools wie viele Tokens kosten (seit Start der App): je Tool Aufrufe, "
            + "geschätzte Tokens an das LLM und durch Kürzen gespart; dazu Umfang von Instructions und "
            + "Tool-Definitionen." + ShellHints.CONTEXT)
    @ToolHints(readOnly = true, openWorld = false)
    public String stats(@ToolParam(required = false, description = "wie viele Tools (Standard 15)") Integer limit) {
        TokenStats stats = log.stats();
        TokenStats.Entry total = stats.total();
        StringBuilder sb = new StringBuilder("Tokens (geschätzt, Zeichen/4) seit Start: ").append(total.calls())
                .append(" Aufrufe, ≈").append(fmt(total.sentTokens())).append(" an das LLM, ≈")
                .append(fmt(total.savedTokens())).append(" gespart.\n");
        ToolRegistry r = registry.getIfAvailable();
        ServerInstructions si = instructions.getIfAvailable();
        if (si != null) {
            int len = si.build().length();
            sb.append("Instructions (je Sitzung): ≈").append(fmt(TokenStats.estimate(len))).append(" Tokens")
                    .append(ctx.compactInstructions() ? " (kompakt)" : " (ausführlich)").append(".\n");
        }
        if (r != null) {
            McpRuntime.Exposed e = r.exposedTools();
            sb.append("Tool-Definitionen: ").append(e.tools()).append(" von ").append(e.activeTools())
                    .append(" aktiven Tools angeboten, ≈").append(fmt(TokenStats.estimate(e.chars())))
                    .append(" Tokens.\n");
        }
        List<TokenStats.Entry> entries = stats.snapshot();
        if (entries.isEmpty()) {
            return sb.append("Noch keine Tool-Aufrufe.").toString();
        }
        sb.append("\nTool | Aufrufe | ≈Tokens gesendet | Ø je Aufruf | ≈gespart\n");
        int n = limit == null || limit <= 0 ? 15 : limit;
        for (TokenStats.Entry e : entries.subList(0, Math.min(n, entries.size()))) {
            sb.append(e.tool()).append(" | ").append(e.calls()).append(" | ").append(fmt(e.sentTokens())).append(" | ")
                    .append(fmt(e.sentTokens() / Math.max(1, e.calls()))).append(" | ").append(fmt(e.savedTokens()))
                    .append('\n');
        }
        if (entries.size() > n) {
            sb.append("… ").append(entries.size() - n).append(" weitere Tools\n");
        }
        return sb.toString().stripTrailing();
    }

    @Tool(name = "guide", description = "Ausführliche Hinweise eines Moduls (Abläufe, Regeln, Sonderfälle) – die "
            + "Instructions enthalten nur eine Zeile je Modul. Vor der ersten Nutzung eines Moduls mit eigenen "
            + "Abläufen lesen." + ShellHints.CONTEXT)
    @ToolHints(readOnly = true, openWorld = false)
    public String guide(@ToolParam(description = "Modul-ID = Tool-Präfix, z.B. git, skills, memories") String module) {
        ServerInstructions si = instructions.getObject();
        String id = module == null ? "" : module.strip().toLowerCase(Locale.ROOT).replaceAll("_\\*?$", "");
        return si.guide(id).orElseThrow(() -> new IllegalArgumentException("Kein Modul '" + module + "'. Vorhanden: "
                + String.join(", ", si.moduleIds())));
    }

    @Tool(name = "find", description = "Sucht unter allen aktiven DevTools-Tools nach Name und Zweck – auch unter "
            + "denen, die nicht direkt angeboten werden. Mit name: Beschreibung und Parameter eines Tools. Ohne "
            + "query: alle aktiven Tools je Modul." + ShellHints.CONTEXT)
    @ToolHints(readOnly = true, openWorld = false)
    public String find(
            @ToolParam(required = false, description = "Stichworte, z.B. 'container logs' oder 'heap dump'") String query,
            @ToolParam(required = false, description = "genauer Tool-Name für Details") String name) {
        List<ToolDefinition> tools = registry.getObject().activeToolDefinitions();
        String call = ctx.lazyTools() ? "\nAufruf: context_call(name=\"…\", arguments={…})." : "";
        if (name != null && !name.isBlank()) {
            String n = name.strip();
            ToolDefinition d = tools.stream().filter(t -> t.name().equals(n)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Tool '" + n + "' ist nicht aktiv – "
                            + "context_find(query=…) sucht."));
            return d.name() + "\n" + ShellHints.strip(d.description()).strip() + "\nParameter:\n" + params(d, true)
                    + call;
        }
        if (query == null || query.isBlank()) {
            Map<String, List<String>> byModule = new TreeMap<>();
            for (ToolDefinition d : tools) {
                String m = d.name().contains("_") ? d.name().substring(0, d.name().indexOf('_')) : d.name();
                byModule.computeIfAbsent(m, k -> new ArrayList<>()).add(d.name().substring(m.length() + 1));
            }
            StringBuilder sb = new StringBuilder(tools.size() + " aktive Tools:\n");
            byModule.forEach((m, names) -> sb.append(m).append("_: ").append(String.join(", ", names)).append('\n'));
            return sb.append("Details: context_find(name=…)").append(call).toString();
        }
        Set<String> terms = new LinkedHashSet<>();
        for (String t : query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (t.length() >= 2) {
                terms.add(t);
            }
        }
        record Hit(ToolDefinition d, int score) {
        }
        List<Hit> hits = new ArrayList<>();
        for (ToolDefinition d : tools) {
            String nm = d.name().toLowerCase(Locale.ROOT);
            String desc = ShellHints.strip(d.description()).toLowerCase(Locale.ROOT);
            int score = 0;
            for (String t : terms) {
                score += (nm.contains(t) ? 3 : 0) + (desc.contains(t) ? 1 : 0);
            }
            if (score > 0) {
                hits.add(new Hit(d, score));
            }
        }
        if (hits.isEmpty()) {
            return "Kein aktives Tool passt auf „" + query + "“. context_find() ohne query listet alle.";
        }
        hits.sort(Comparator.comparingInt(Hit::score).reversed().thenComparing(h -> h.d().name()));
        StringBuilder sb = new StringBuilder();
        for (Hit h : hits.subList(0, Math.min(FIND_LIMIT, hits.size()))) {
            sb.append("- ").append(h.d().name()).append('(').append(params(h.d(), false)).append(") – ")
                    .append(ServerInstructions.firstSentenceOf(ShellHints.strip(h.d().description()))).append('\n');
        }
        if (hits.size() > FIND_LIMIT) {
            sb.append("… ").append(hits.size() - FIND_LIMIT).append(" weitere Treffer\n");
        }
        return sb.append("* = Pflicht. Details: context_find(name=…)").append(call).toString();
    }

    /** Parameter aus dem Eingabeschema: kurz {@code a*, b} oder ausführlich je Zeile mit Typ und Beschreibung. */
    static String params(ToolDefinition d, boolean detailed) {
        JsonNode schema;
        try {
            schema = JSON.readTree(d.inputSchema());
        } catch (RuntimeException e) {
            return detailed ? "(Schema nicht lesbar)" : "…";
        }
        Set<String> required = new LinkedHashSet<>();
        schema.path("required").forEach(n -> required.add(n.asString()));
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, JsonNode> p : schema.path("properties").properties()) {
            String n = p.getKey() + (required.contains(p.getKey()) ? "*" : "");
            if (detailed) {
                String type = text(p.getValue().path("type"));
                String desc = text(p.getValue().path("description"));
                out.add("- " + n + (type.isEmpty() ? "" : " (" + type + ")") + (desc.isEmpty() ? "" : ": " + desc));
            } else {
                out.add(n);
            }
        }
        if (out.isEmpty()) {
            return detailed ? "(keine)" : "";
        }
        return String.join(detailed ? "\n" : ", ", out);
    }

    private static String text(JsonNode n) {
        return n != null && n.isString() ? n.asString() : "";
    }

    private ResultStore.Stored stored(String handle) {
        return store.get(handle).orElseThrow(() -> new IllegalArgumentException("Handle '" + handle + "' gibt es "
                + "nicht (mehr) – die Ablage hält nur die letzten Ergebnisse und leert sich beim Neustart der App. "
                + "Das Tool erneut aufrufen."));
    }

    private int budget() {
        return ctx.maxChars() <= 0 ? ContextSettings.DEFAULT_MAX_CHARS : ctx.maxChars();
    }

    private String classifyKey() {
        try {
            ToolRegistry r = registry.getIfAvailable();
            return r == null || !r.hasModule("classify") ? null : r.config("classify").get(ContextModule.API_KEY)
                    .orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static McpSyncServerExchange exchange(ToolContext toolContext) {
        Object e = toolContext == null ? null : toolContext.getContext().get(McpToolUtils.TOOL_CONTEXT_MCP_EXCHANGE_KEY);
        return e instanceof McpSyncServerExchange x ? x : null;
    }

    private static String fmt(long n) {
        return String.format(Locale.GERMANY, "%,d", n);
    }
}
