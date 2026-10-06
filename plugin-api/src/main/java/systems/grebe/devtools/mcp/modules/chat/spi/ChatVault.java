package systems.grebe.devtools.mcp.modules.chat.spi;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Verschlüsselte Ablage für Anmeldedaten, die ein Chat-System zur Laufzeit erhält (Refresh-Token nach einer
 * Device-Code-Anmeldung …). Schlüssel wählt der Provider selbst, sinnvollerweise mit seiner ID als Präfix.
 */
public interface ChatVault {

    Optional<String> get(String key);

    /** Speichert den Wert; {@code null} oder leer entfernt ihn. */
    void put(String key, String value);

    /** Nur im Speicher (Tests). */
    static ChatVault inMemory() {
        Map<String, String> values = new ConcurrentHashMap<>();
        return new ChatVault() {
            @Override
            public Optional<String> get(String key) {
                return Optional.ofNullable(values.get(key));
            }

            @Override
            public void put(String key, String value) {
                if (value == null || value.isEmpty()) {
                    values.remove(key);
                } else {
                    values.put(key, value);
                }
            }
        };
    }
}
