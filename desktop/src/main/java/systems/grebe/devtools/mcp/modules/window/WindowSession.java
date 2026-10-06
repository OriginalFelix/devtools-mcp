package systems.grebe.devtools.mcp.modules.window;

import java.awt.Color;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
     * Elternprozess (und dessen Vorfahren) gehört nie dazu, Geschwister nur, wenn sie erlaubt sind. Shell- und
     * Systemprozesse ({@link #SHELLS}) zählen nie mit ihren Kindprozessen.
     */
    record Binding(ProcessHandle process, String name, boolean includeChildren) {

        /**
         * Prozesse, unter denen praktisch alle Programme des Nutzers laufen (macOS {@code launchd}, Dock, Finder;
         * Windows {@code explorer.exe} bzw. Dienste; Linux {@code init}/{@code systemd} und Desktop-Shells) – ihre
         * Kinder gehören nicht zusammen, weder als Kindprozesse noch als Geschwister.
         */
        private static final Set<String> SHELLS = Set.of("launchd", "init", "systemd", "explorer", "services",
                "svchost", "wininit", "winlogon", "userinit", "sihost", "runtimebroker", "kernel_task", "finder", "dock",
                "gnome-shell", "plasmashell", "kwin_x11", "xfce4-panel", "xfce4-session", "lxsession", "mate-panel",
                "cinnamon");

        /**
         * Prozesse, deren Fenster angesprochen werden dürfen – bei jedem Aufruf neu ermittelt.
         *
         * @param siblings auch die anderen Kinder des Elternprozesses (mit ihren Nachfahren, wenn
         *                 {@link #includeChildren}); der Elternprozess selbst nie. Ist der Elternprozess ein Shell- oder
         *                 Systemprozess ({@link #systemParent}) oder unbekannt, gibt es keine Geschwister.
         */
        Set<Long> pids(boolean siblings) {
            Set<Long> out = new LinkedHashSet<>();
            addTree(process, out);
            if (siblings) {
                process.parent().filter(parent -> !systemParent(parent)).ifPresent(parent -> parent.children()
                        .filter(p -> p.pid() != process.pid())
                        .forEach(p -> addTree(p, out)));
            }
            return out;
        }

        /** Ein Shell-Prozess ({@link #shell}) – oder einer, dessen Programm sich nicht lesen lässt. */
        static boolean systemParent(ProcessHandle parent) {
            return shell(parent) || parent.info().command().isEmpty();
        }

        /** PID 1 oder ein Prozess aus {@link #SHELLS}: seine Kinder sind beliebige Programme des Nutzers. */
        static boolean shell(ProcessHandle p) {
            return p.pid() <= 1 || p.info().command()
                    .map(c -> SHELLS.contains(ProcessFilter.name(c).toLowerCase(Locale.ROOT)))
                    .orElse(false);
        }

        private void addTree(ProcessHandle root, Set<Long> out) {
            out.add(root.pid());
            if (includeChildren && !shell(root)) {
                root.descendants().forEach(p -> out.add(p.pid()));
            }
        }

        String describe() {
            String children = !includeChildren ? "" : shell(process) ? ", ohne Kindprozesse (Shell-Prozess)"
                    : ", mit Kindprozessen";
            return name + " (PID " + process.pid() + children + ")";
        }
    }

    private static final Logger LOG = Logger.getLogger(WindowSession.class.getName());

    private final String id;
    private final String client;
    private final Color color;
    private final Peers peers;
    private final Map<String, Object> resources = new ConcurrentHashMap<>();
    private volatile Binding binding;
    /** Warum die Bindung weg ist, wenn eine andere KI sie übernommen hat; sonst {@code null}. */
    private volatile String lost;
    private final Map<Long, Double> imageScale = new ConcurrentHashMap<>();

    /** Die anderen KIs: welche Prozesse sie steuern ({@link WindowSessions}). */
    interface Peers {

        /** Keine anderen KIs. */
        Peers NONE = new Peers() {
            @Override
            public Optional<String> bind(Binding wanted, boolean siblings, Runnable commit) {
                commit.run();
                return Optional.empty();
            }

            @Override
            public Set<Long> claimed() {
                return Set.of();
            }
        };

        /**
         * Prüft, ob eine andere KI einen der Prozesse steuert, und führt sonst {@code commit} aus – beides atomar
         * gegenüber den Bindungen der anderen KIs.
         *
         * @return Grund, warum die Bindung nicht erlaubt ist; leer, wenn {@code commit} ausgeführt wurde
         */
        Optional<String> bind(Binding wanted, boolean siblings, Runnable commit);

        /** Prozesse, die andere KIs direkt gebunden haben (samt Kindprozessen) – nie für diese KI erreichbar. */
        Set<Long> claimed();
    }

    /** Eine einzelne KI ohne Konkurrenz – für Tests und Aufrufe ohne MCP-Session. */
    WindowSession() {
        this("local", null, CursorImage.ACCENT, Peers.NONE);
    }

    /** @param client Name der KI für den Hinweis, {@code null} = „KI“ */
    WindowSession(String id, String client, Color color, Peers peers) {
        this.id = id;
        this.client = client;
        this.color = color;
        this.peers = peers;
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

    /**
     * Bindet den Prozess; wirft, wenn eine andere KI ihn, einen Prozess seines Baums oder – mit {@code siblings} – einen
     * seiner Geschwister steuert.
     */
    void bind(Binding value, boolean siblings) {
        peers.bind(value, siblings, () -> {
            binding = value;
            lost = null;
            imageScale.clear();
        }).ifPresent(reason -> {
            throw new IllegalStateException(reason);
        });
    }

    /**
     * Eine andere KI hat den Prozess übernommen, weil diese zu lange kein Fenster-Tool aufgerufen hat: Bindung weg,
     * beim nächsten Aufruf ein Hinweis.
     */
    void takenOver(String reason) {
        binding = null;
        lost = reason;
        imageScale.clear();
    }

    /** Blendet Rahmen, Hinweis und Zeiger dieser KI aus (z.B. nach einer Übernahme). */
    void releaseDevices() {
        for (Object r : resources.values()) {
            if (r instanceof InputDevice d) {
                try {
                    d.release();
                } catch (RuntimeException e) {
                    LOG.log(Level.FINE, "Anzeige der KI-Session " + id + " nicht ausgeblendet", e);
                }
            }
        }
    }

    /** Erlaubte Prozesse der Bindung ({@link Binding#pids}) ohne die, die eine andere KI steuert. */
    Set<Long> pids(Binding b, boolean siblings) {
        Set<Long> out = b.pids(siblings);
        out.removeAll(peers.claimed());
        out.add(b.process().pid());
        return out;
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
        lost = null;
        imageScale.clear();
    }

    Binding current() {
        return binding;
    }

    /** Die aktuelle Bindung; wirft mit Hinweis auf den nächsten Schritt, wenn keine besteht oder der Prozess endete. */
    Binding require() {
        Binding b = binding;
        if (b == null) {
            String reason = lost;
            throw new IllegalStateException(reason != null ? reason
                    : "Kein Prozess gebunden – zuerst mit window_list die Prozesse ansehen und mit window_bind einen "
                    + "binden.");
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
