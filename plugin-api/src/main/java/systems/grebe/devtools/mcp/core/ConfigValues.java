package systems.grebe.devtools.mcp.core;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Lesesicht auf Einstellungswerte mit den typisierten Zugriffen, die alle Einstellungsklassen brauchen
 * ({@link ModuleConfig}, die Einstellungen der Ticket-, Chat- und Container-Provider). Leere Werte gelten als nicht
 * gesetzt; ein nicht lesbarer Zahlenwert ergibt den Fallback.
 */
public interface ConfigValues {

    /** Wert (bei {@link ModuleConfig} inkl. Default aus dem Schema); leere Werte gelten als nicht gesetzt. */
    Optional<String> get(String key);

    default String getString(String key, String fallback) {
        return get(key).orElse(fallback);
    }

    default boolean getBoolean(String key) {
        return getBoolean(key, false);
    }

    default boolean getBoolean(String key, boolean fallback) {
        return get(key).map(Boolean::parseBoolean).orElse(fallback);
    }

    default int getInt(String key, int fallback) {
        try {
            return get(key).map(Integer::parseInt).orElse(fallback);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Mehrzeiliger Wert als Liste (eine Zeile je Eintrag, leere Zeilen ignoriert). */
    default List<String> getList(String key) {
        return get(key).map(ModuleConfig::splitLines).orElse(List.of());
    }

    /** Nachschlagen in festen Werten (Tests): getrimmt, leere Werte gelten als nicht gesetzt. */
    static Function<String, Optional<String>> lookupIn(Map<String, String> values) {
        return k -> Optional.ofNullable(values.get(k)).map(String::trim).filter(s -> !s.isEmpty());
    }
}
