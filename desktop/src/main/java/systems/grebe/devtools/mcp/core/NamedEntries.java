package systems.grebe.devtools.mcp.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Benannte Einträge einer Konfiguration (Konten, Verbindungen): Name ohne Groß-/Kleinschreibung, doppelte Namen werden
 * gemerkt statt still überschrieben, {@link #resolve} liefert den einzigen Eintrag, wenn kein Name angegeben ist, und
 * sonst verständliche Fehler.
 */
public final class NamedEntries<T> {

    /**
     * Meldungen von {@link #resolve}.
     *
     * @param none      keine Einträge konfiguriert
     * @param several   mehrere Einträge, aber kein Name angegeben (bekommt die Namen)
     * @param duplicate der Name ist mehrfach vergeben (bekommt den angegebenen Namen)
     * @param unknown   unbekannter Name (bekommt Name und die konfigurierten Namen)
     */
    public record Messages(String none, Function<List<String>, String> several, Function<String, String> duplicate,
                           BiFunction<String, List<String>, String> unknown) {
    }

    private final Function<T, String> nameOf;
    private final Messages messages;
    private final Map<String, T> byKey = new LinkedHashMap<>();
    private final List<String> duplicates = new ArrayList<>();

    public NamedEntries(Function<T, String> nameOf, Messages messages) {
        this.nameOf = nameOf;
        this.messages = messages;
    }

    /** Trägt den Eintrag ein; {@code false} (und als Duplikat gemerkt), wenn der Name schon vergeben ist. */
    public boolean add(T entry) {
        String name = nameOf.apply(entry);
        if (byKey.putIfAbsent(key(name), entry) != null) {
            duplicates.add(name);
            return false;
        }
        return true;
    }

    public List<T> all() {
        return List.copyOf(byKey.values());
    }

    public List<String> duplicates() {
        return List.copyOf(duplicates);
    }

    public List<String> names() {
        return byKey.values().stream().map(nameOf).toList();
    }

    /** Eintrag nach Name (ohne Groß-/Kleinschreibung); ohne Name der einzige konfigurierte. */
    public T resolve(String name) {
        if (byKey.isEmpty()) {
            throw new IllegalStateException(messages.none());
        }
        if (name == null || name.isBlank()) {
            if (byKey.size() == 1) {
                return byKey.values().iterator().next();
            }
            throw new IllegalArgumentException(messages.several().apply(names()));
        }
        String key = key(name);
        if (duplicates.stream().anyMatch(d -> key(d).equals(key))) {
            throw new IllegalStateException(messages.duplicate().apply(name));
        }
        T entry = byKey.get(key);
        if (entry == null) {
            throw new IllegalArgumentException(messages.unknown().apply(name, names()));
        }
        return entry;
    }

    private static String key(String name) {
        return name.strip().toLowerCase(Locale.ROOT);
    }
}
