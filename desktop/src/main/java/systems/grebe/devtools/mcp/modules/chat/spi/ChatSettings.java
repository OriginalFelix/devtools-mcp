package systems.grebe.devtools.mcp.modules.chat.spi;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import systems.grebe.devtools.mcp.core.ModuleConfig;

/**
 * Sicht auf die Einstellungen genau eines Chat-Systems (Schlüssel ohne Präfix), gemeinsamer HTTP-Timeout und ein
 * verschlüsselter Ablageort für Anmeldedaten, die zur Laufzeit entstehen (z.B. OAuth-Refresh-Token).
 */
public final class ChatSettings {

    private final Function<String, Optional<String>> lookup;
    private final Duration timeout;
    private final ChatVault vault;

    public ChatSettings(Function<String, Optional<String>> lookup, Duration timeout, ChatVault vault) {
        this.lookup = lookup;
        this.timeout = timeout;
        this.vault = vault;
    }

    /** Für Tests: feste Werte, Ablage im Speicher. */
    public static ChatSettings of(Map<String, String> values) {
        return new ChatSettings(k -> Optional.ofNullable(values.get(k)).map(String::trim).filter(s -> !s.isEmpty()),
                Duration.ofSeconds(30), ChatVault.inMemory());
    }

    /** Wert; leere Werte gelten als nicht gesetzt. */
    public Optional<String> get(String key) {
        return lookup.apply(key);
    }

    public String getString(String key, String fallback) {
        return get(key).orElse(fallback);
    }

    public boolean getBoolean(String key, boolean fallback) {
        return get(key).map(Boolean::parseBoolean).orElse(fallback);
    }

    public int getInt(String key, int fallback) {
        try {
            return get(key).map(Integer::parseInt).orElse(fallback);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Mehrzeiliger Wert als Liste (eine Zeile je Eintrag, leere Zeilen ignoriert). */
    public List<String> getList(String key) {
        return get(key).map(ModuleConfig::splitLines).orElse(List.of());
    }

    public Duration timeout() {
        return timeout;
    }

    public ChatVault vault() {
        return vault;
    }
}
