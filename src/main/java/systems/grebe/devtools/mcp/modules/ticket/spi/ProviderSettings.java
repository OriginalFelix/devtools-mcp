package systems.grebe.devtools.mcp.modules.ticket.spi;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/** Sicht auf die Einstellungen genau eines Ticket-Systems (Schlüssel ohne Präfix) plus gemeinsamer HTTP-Timeout. */
public final class ProviderSettings {

    private final Function<String, Optional<String>> lookup;
    private final Duration timeout;

    public ProviderSettings(Function<String, Optional<String>> lookup, Duration timeout) {
        this.lookup = lookup;
        this.timeout = timeout;
    }

    public static ProviderSettings of(Map<String, String> values) {
        return new ProviderSettings(k -> Optional.ofNullable(values.get(k)).map(String::trim).filter(s -> !s.isEmpty()),
                Duration.ofSeconds(30));
    }

    public Optional<String> get(String key) {
        return lookup.apply(key);
    }

    public String getString(String key, String fallback) {
        return get(key).orElse(fallback);
    }

    public Duration timeout() {
        return timeout;
    }
}
