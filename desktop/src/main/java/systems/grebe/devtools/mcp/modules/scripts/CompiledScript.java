package systems.grebe.devtools.mcp.modules.scripts;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

import org.springframework.ai.tool.ToolCallback;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.ModuleConfig;

/**
 * Ein übersetztes Skript – Groovy ({@link ScriptCompiler}) oder Java ({@link JavaScriptCompiler}) – aus Sicht von
 * {@link ScriptToolModule}: Modul-Angaben und Tools. Hält den ClassLoader des Skripts, bis {@link #close()} ihn
 * freigibt.
 */
public interface CompiledScript extends AutoCloseable {

    String displayName();

    String description();

    /** Instructions für das LLM oder {@code null}. */
    String instructions();

    List<ConfigField> settings();

    boolean enabledByDefault();

    /** Namen der Tools ohne Modul-Präfix – für Meldungen nach dem Speichern. */
    List<String> toolNames();

    /** Tools für die Konfiguration; jeder Aufruf läuft unter dem Zeitlimit {@code timeout}. */
    List<ToolCallback> tools(ModuleConfig config, Supplier<Duration> timeout);

    @Override
    void close();
}
