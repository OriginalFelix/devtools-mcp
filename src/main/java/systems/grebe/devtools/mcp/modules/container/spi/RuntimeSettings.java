package systems.grebe.devtools.mcp.modules.container.spi;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/** Sicht auf die Einstellungen genau einer Laufzeit (Schlüssel ohne Präfix). */
public final class RuntimeSettings {

    private final Function<String, Optional<String>> lookup;

    public RuntimeSettings(Function<String, Optional<String>> lookup) {
        this.lookup = lookup;
    }

    public static RuntimeSettings of(Map<String, String> values) {
        return new RuntimeSettings(k -> Optional.ofNullable(values.get(k)).map(String::trim).filter(s -> !s.isEmpty()));
    }

    public Optional<String> get(String key) {
        return lookup.apply(key);
    }

    public String getString(String key, String fallback) {
        return get(key).orElse(fallback);
    }
}
