package systems.grebe.devtools.mcp.modules.scripts;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.ai.tool.ToolCallback;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolBeans;
import systems.grebe.devtools.mcp.core.ToolModule;

/**
 * Modul aus einem Groovy-Skript, zur Laufzeit von {@link ScriptManager} in der {@code ToolRegistry} registriert. In
 * der App erscheint es wie jedes andere Modul (Schalter, Tool-Liste, Formular für die {@code setting}s), gekennzeichnet
 * mit „· Skript“. Ein Skript, das sich nicht übersetzen lässt, wird trotzdem registriert: Es zeigt dann seinen Fehler
 * statt Tools an.
 */
public final class ScriptToolModule implements ToolModule, AutoCloseable {

    /** Skript-Module stehen hinter den eingebauten Modulen. */
    static final int ORDER = 500;

    private final ScriptViews.Summary summary;
    private final ScriptCompiler.Compiled compiled;
    private final String error;
    private final Supplier<Duration> timeout;

    private ScriptToolModule(ScriptViews.Summary summary, ScriptCompiler.Compiled compiled, String error,
                             Supplier<Duration> timeout) {
        this.summary = summary;
        this.compiled = compiled;
        this.error = error;
        this.timeout = timeout;
    }

    static ScriptToolModule of(ScriptViews.Summary summary, ScriptCompiler.Compiled compiled,
                               Supplier<Duration> timeout) {
        return new ScriptToolModule(summary, compiled, null, timeout);
    }

    static ScriptToolModule broken(ScriptViews.Summary summary, String error) {
        return new ScriptToolModule(summary, null, error, () -> Duration.ZERO);
    }

    /** Das Skript, falls das Modul aus einem stammt. */
    public static Optional<ScriptToolModule> scriptOf(ToolModule module) {
        return module instanceof ScriptToolModule s ? Optional.of(s) : Optional.empty();
    }

    /** Stand des Skripts, aus dem das Modul gebaut wurde. */
    public ScriptViews.Summary summary() {
        return summary;
    }

    /** Übersetzungs- oder Definitionsfehler. */
    public Optional<String> error() {
        return Optional.ofNullable(error);
    }

    @Override
    public String id() {
        return summary.name();
    }

    @Override
    public String displayName() {
        return compiled == null ? summary.name() : compiled.definition().displayName();
    }

    @Override
    public String description() {
        String d = compiled == null ? summary.description() : compiled.definition().description();
        return (summary.global() ? "Globales Skript: " : "Skript: ") + d;
    }

    @Override
    public String instructions() {
        return compiled == null ? null : compiled.definition().instructions();
    }

    @Override
    public List<ConfigField> configSchema() {
        return compiled == null ? List.of() : compiled.definition().settings();
    }

    @Override
    public boolean enabledByDefault() {
        return compiled == null || compiled.definition().enabledByDefault();
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public List<ToolCallback> createTools(ModuleConfig config) {
        if (compiled == null) {
            throw new IllegalStateException(error);
        }
        Map<String, Object> settings = settings(compiled.definition().settings(), config);
        return compiled.definition().tools().stream()
                .map(t -> ToolBeans.withAnnotations(new ScriptToolCallback(id(), t, settings, timeout),
                        t.annotations()))
                .toList();
    }

    /** Einstellungen typgerecht für {@code cfg} in den Closures: Zahlen, Wahrheitswerte, Listen, Texte. */
    static Map<String, Object> settings(List<ConfigField> fields, ModuleConfig config) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (ConfigField f : fields) {
            Object value = switch (f.type()) {
                case BOOLEAN -> config.getBoolean(f.key());
                case INT -> config.get(f.key()).map(v -> {
                    try {
                        return (Object) Long.parseLong(v);
                    } catch (NumberFormatException e) {
                        return null;
                    }
                }).orElse(null);
                case DIRECTORY_LIST, STRING_LIST -> config.getList(f.key());
                case RECORD_LIST -> config.getRecords(f.key());
                default -> config.get(f.key()).orElse(null);
            };
            out.put(f.key(), value);
        }
        return Collections.unmodifiableMap(out);
    }

    /** Gibt den ClassLoader des Skripts frei (nach dem Entfernen aus der Registry). */
    @Override
    public void close() {
        if (compiled != null) {
            compiled.close();
        }
    }
}
