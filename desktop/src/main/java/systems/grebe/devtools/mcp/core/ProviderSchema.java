package systems.grebe.devtools.mcp.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Schlüssel und Schema-Bausteine der Module, die Provider bündeln (Tickets, Chat, Pull Requests, Container): die Felder
 * eines Providers liegen unter {@code <id>.<feld>}, sein Schalter unter {@code <id>.enabled}.
 */
public final class ProviderSchema {

    /** Wert des Standard-Providers, der den einzigen aktiven wählt. */
    public static final String AUTO = "auto";

    private ProviderSchema() {
    }

    /** Schlüssel eines Provider-Feldes in der Modulkonfiguration. */
    public static String key(String providerId, String field) {
        return providerId + "." + field;
    }

    /** Schlüssel des Schalters, der den Provider aktiviert. */
    public static String enabledKey(String providerId) {
        return key(providerId, "enabled");
    }

    /** Ob der Provider aktiv ist; ohne Angabe gilt {@code enabledByDefault}. */
    public static boolean enabled(ModuleConfig config, String providerId, boolean enabledByDefault) {
        return config.get(enabledKey(providerId)).map(Boolean::parseBoolean).orElse(enabledByDefault);
    }

    /** Die Einstellungen eines Providers mit seinen Feldnamen ohne Präfix. */
    public static Function<String, Optional<String>> settings(ModuleConfig config, String providerId) {
        return field -> config.get(key(providerId, field));
    }

    /** Auswahl des Standard-Providers: {@link #AUTO} oder einer der Provider. */
    public static ConfigField defaultProviderField(String key, String label, List<? extends ServiceProvider> providers,
                                                   String help) {
        List<String> options = new ArrayList<>(List.of(AUTO));
        providers.forEach(p -> options.add(p.id()));
        return ConfigField.of(key, label, FieldType.ENUM).withDefault(AUTO)
                .withOptions(options.toArray(String[]::new)).withHelp(help);
    }
}
