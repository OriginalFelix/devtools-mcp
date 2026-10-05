package systems.grebe.devtools.mcp.modules.window;

import java.awt.Color;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import systems.grebe.devtools.mcp.modules.window.cursor.CursorImage;

/**
 * Zustand einer KI (MCP-Session, siehe {@link WindowSessions}): welcher Prozess gebunden ist, mit welchem Faktor der
 * letzte Screenshot je Fenster verkleinert wurde (Klickkoordinaten beziehen sich auf diesen Screenshot), die Farbe der
 * KI und ihre Anzeige- und Eingaberessourcen (Rahmen, eigener Zeiger).
 */
final class WindowSession {

    /**
     * Gebundener Prozess, optional samt aller Kindprozesse. Der Zugriff reicht nur nach unten im Prozessbaum: der
     * Elternprozess (und dessen Vorfahren) gehört nie dazu, Geschwister nur, wenn sie erlaubt sind.
     */
    record Binding(ProcessHandle process, String name, boolean includeChildren) {

        /**
         * Prozesse, deren Fenster angesprochen werden dürfen – bei jedem Aufruf neu ermittelt.
         *
         * @param siblings auch die anderen Kinder des Elternprozesses (mit ihren Nachfahren, wenn
         *                 {@link #includeChildren}); der Elternprozess selbst nie
         */
        Set<Long> pids(boolean siblings) {
            Set<Long> out = new LinkedHashSet<>();
            addTree(process, out);
            if (siblings) {
                process.parent().ifPresent(parent -> parent.children()
                        .filter(p -> p.pid() != process.pid())
                        .forEach(p -> addTree(p, out)));
            }
            return out;
        }

        private void addTree(ProcessHandle root, Set<Long> out) {
            out.add(root.pid());
            if (includeChildren) {
                root.descendants().forEach(p -> out.add(p.pid()));
            }
        }

        String describe() {
            return name + " (PID " + process.pid() + (includeChildren ? ", mit Kindprozessen" : "") + ")";
        }
    }

    private static final Logger LOG = Logger.getLogger(WindowSession.class.getName());

    private final String id;
    private final String client;
    private final Color color;
    private final Function<Binding, Optional<String>> conflict;
    private final Map<String, Object> resources = new ConcurrentHashMap<>();
    private volatile Binding binding;
    private final Map<Long, Double> imageScale = new ConcurrentHashMap<>();

    /** Eine einzelne KI ohne Konkurrenz – für Tests und Aufrufe ohne MCP-Session. */
    WindowSession() {
        this("local", null, CursorImage.ACCENT, b -> Optional.empty());
    }

    /**
     * @param client   Name der KI für den Hinweis, {@code null} = „KI“
     * @param conflict Grund, warum eine Bindung nicht erlaubt ist (z.B. weil eine andere KI den Prozess steuert)
     */
    WindowSession(String id, String client, Color color, Function<Binding, Optional<String>> conflict) {
        this.id = id;
        this.client = client;
        this.color = color;
        this.conflict = conflict;
    }

    String id() {
        return id;
    }

    /** Name der KI, z.B. „Claude Code“. */
    String client() {
        return client == null ? "KI" : client;
    }

    Color color() {
        return color;
    }

    /** Bindet den Prozess; wirft, wenn eine andere KI ihn (oder einen Prozess seines Baums) steuert. */
    void bind(Binding value) {
        conflict.apply(value).ifPresent(reason -> {
            throw new IllegalStateException(reason);
        });
        binding = value;
        imageScale.clear();
    }

    /** Ressource dieser KI (z.B. Rahmen, Eingabegerät) – beim ersten Zugriff mit {@code factory} erzeugt. */
    @SuppressWarnings("unchecked")
    <T> T resource(String key, Supplier<T> factory) {
        return (T) resources.computeIfAbsent(key, k -> factory.get());
    }

    /** Hebt die Bindung auf und schließt alle Ressourcen (Zeiger, Rahmen). */
    void close() {
        unbind();
        for (Object r : resources.values()) {
            if (r instanceof AutoCloseable c) {
                try {
                    c.close();
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Ressource der KI-Session " + id + " nicht geschlossen", e);
                }
            }
        }
        resources.clear();
    }

    void unbind() {
        binding = null;
        imageScale.clear();
    }

    Binding current() {
        return binding;
    }

    /** Die aktuelle Bindung; wirft mit Hinweis auf den nächsten Schritt, wenn keine besteht oder der Prozess endete. */
    Binding require() {
        Binding b = binding;
        if (b == null) {
            throw new IllegalStateException("Kein Prozess gebunden – zuerst mit window_list die Prozesse ansehen und "
                    + "mit window_bind einen binden.");
        }
        if (!b.process().isAlive()) {
            unbind();
            throw new IllegalStateException("Der gebundene Prozess " + b.describe() + " ist beendet – Bindung "
                    + "aufgehoben. Mit window_bind neu binden.");
        }
        return b;
    }

    void scale(long windowId, double factor) {
        imageScale.put(windowId, factor);
    }

    /** Faktor Bildpixel je Bildschirmpunkt des letzten Screenshots (1, wenn es keinen gab). */
    double scale(long windowId) {
        return imageScale.getOrDefault(windowId, 1.0);
    }
}
