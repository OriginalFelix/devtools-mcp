package systems.grebe.devtools.mcp.modules.container.spi;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import systems.grebe.devtools.mcp.core.ConfigValues;

/** Sicht auf die Einstellungen genau einer Laufzeit (Schlüssel ohne Präfix). */
public final class RuntimeSettings implements ConfigValues {

    private final Function<String, Optional<String>> lookup;

    public RuntimeSettings(Function<String, Optional<String>> lookup) {
        this.lookup = lookup;
    }

    public static RuntimeSettings of(Map<String, String> values) {
        return new RuntimeSettings(ConfigValues.lookupIn(values));
    }

    @Override
    public Optional<String> get(String key) {
        return lookup.apply(key);
    }
}
