package systems.grebe.devtools.mcp.modules.ticket.spi;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import systems.grebe.devtools.mcp.core.ConfigValues;

/** Sicht auf die Einstellungen genau eines Ticket-Systems (Schlüssel ohne Präfix) plus gemeinsamer HTTP-Timeout. */
public final class ProviderSettings implements ConfigValues {

    private final Function<String, Optional<String>> lookup;
    private final Duration timeout;

    public ProviderSettings(Function<String, Optional<String>> lookup, Duration timeout) {
        this.lookup = lookup;
        this.timeout = timeout;
    }

    public static ProviderSettings of(Map<String, String> values) {
        return new ProviderSettings(ConfigValues.lookupIn(values), Duration.ofSeconds(30));
    }

    @Override
    public Optional<String> get(String key) {
        return lookup.apply(key);
    }

    public Duration timeout() {
        return timeout;
    }
}
