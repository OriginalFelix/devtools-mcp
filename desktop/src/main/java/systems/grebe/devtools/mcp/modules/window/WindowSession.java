package systems.grebe.devtools.mcp.modules.window;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Zustand eines Benutzers ({@code ToolScope}): welcher Prozess gebunden ist und mit welchem Faktor der letzte
 * Screenshot je Fenster verkleinert wurde (Klickkoordinaten beziehen sich auf diesen Screenshot).
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

    private volatile Binding binding;
    private final Map<Long, Double> imageScale = new ConcurrentHashMap<>();

    void bind(Binding value) {
        binding = value;
        imageScale.clear();
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
