package systems.grebe.devtools.mcp.modules.scripts;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.ai.tool.ToolCallback;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolModule;

/**
 * Modul aus einem Skript (Groovy, Java oder Gherkin), zur Laufzeit von {@link ScriptManager} in der {@code ToolRegistry}
 * registriert. In der App erscheint es wie jedes andere Modul (Schalter, Tool-Liste, Formular für die Einstellungen),
 * gekennzeichnet mit „· Skript“. Ein Skript, das sich nicht übersetzen lässt, wird trotzdem registriert: Es zeigt dann
 * seinen Fehler statt Tools an. Die Modul-ID ist immer der Skriptname.
 */
public final class ScriptToolModule implements ToolModule, AutoCloseable {

    /** Skript-Module stehen hinter den eingebauten Modulen. */
    static final int ORDER = 500;

    private final ScriptViews.Summary summary;
    private final CompiledScript compiled;
    private final String error;
    private final Supplier<Duration> timeout;

    private ScriptToolModule(ScriptViews.Summary summary, CompiledScript compiled, String error,
                             Supplier<Duration> timeout) {
        this.summary = summary;
        this.compiled = compiled;
        this.error = error;
        this.timeout = timeout;
    }

    static ScriptToolModule of(ScriptViews.Summary summary, CompiledScript compiled, Supplier<Duration> timeout) {
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
        return compiled == null ? summary.name() : compiled.displayName();
    }

    @Override
    public String description() {
        String d = compiled == null ? summary.description() : compiled.description();
        return (summary.global() ? "Globales Skript: " : "Skript: ") + d;
    }

    @Override
    public String instructions() {
        return compiled == null ? null : compiled.instructions();
    }

    @Override
    public List<ConfigField> configSchema() {
        return compiled == null ? List.of() : compiled.settings();
    }

    @Override
    public boolean enabledByDefault() {
        return compiled == null || compiled.enabledByDefault();
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
        return compiled.tools(config, timeout);
    }

    /** Gibt den ClassLoader des Skripts frei (nach dem Entfernen aus der Registry). */
    @Override
    public void close() {
        if (compiled != null) {
            compiled.close();
        }
    }
}
