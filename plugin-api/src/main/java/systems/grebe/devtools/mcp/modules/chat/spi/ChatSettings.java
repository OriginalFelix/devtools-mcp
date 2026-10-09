package systems.grebe.devtools.mcp.modules.chat.spi;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import systems.grebe.devtools.mcp.core.ConfigValues;

/**
 * Sicht auf die Einstellungen genau eines Chat-Systems (Schlüssel ohne Präfix), gemeinsamer HTTP-Timeout und ein
 * verschlüsselter Ablageort für Anmeldedaten, die zur Laufzeit entstehen (z.B. OAuth-Refresh-Token).
 */
public final class ChatSettings implements ConfigValues {

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
        return new ChatSettings(ConfigValues.lookupIn(values), Duration.ofSeconds(30), ChatVault.inMemory());
    }

    /** Wert; leere Werte gelten als nicht gesetzt. */
    @Override
    public Optional<String> get(String key) {
        return lookup.apply(key);
    }

    public Duration timeout() {
        return timeout;
    }

    public ChatVault vault() {
        return vault;
    }
}
