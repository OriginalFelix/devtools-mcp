package systems.grebe.devtools.mcp.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Größenbegrenzte Map: Über {@code max} Einträge fliegt der älteste hinaus. Mit {@link #lru} zählt der letzte Zugriff
 * (z.B. Sitzungen), mit {@link #fifo} nur die Reihenfolge des Einfügens. Nicht thread-sicher - der Aufrufer
 * synchronisiert (oder nimmt {@link Collections#synchronizedMap}).
 */
public final class BoundedMap<K, V> extends LinkedHashMap<K, V> {

    private final int max;

    private BoundedMap(int max, boolean accessOrder) {
        super(16, 0.75f, accessOrder);
        this.max = max;
    }

    /** Der am längsten nicht benutzte Eintrag fliegt zuerst hinaus. */
    public static <K, V> BoundedMap<K, V> lru(int max) {
        return new BoundedMap<>(max, true);
    }

    /** Der zuerst eingefügte Eintrag fliegt zuerst hinaus. */
    public static <K, V> BoundedMap<K, V> fifo(int max) {
        return new BoundedMap<>(max, false);
    }

    /** Menge mit den zuletzt eingefügten {@code max} Elementen. */
    public static <E> Set<E> fifoSet(int max) {
        return Collections.newSetFromMap(new BoundedMap<E, Boolean>(max, false));
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
        return size() > max;
    }
}
