package systems.grebe.devtools.mcp.modules.ticket;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.modules.ticket.spi.ProviderSettings;
import systems.grebe.devtools.mcp.modules.ticket.spi.TicketProvider;
import systems.grebe.devtools.mcp.modules.ticket.spi.TicketSystem;

/** Ausgewertete Konfiguration des Ticket-Moduls: aktive Systeme, Standardprojekte und Auswahl des Systems je Aufruf. */
public final class TicketEnvironment {

    /** Ein aktives System mit seinem Provider und dem Standardprojekt ({@code null} = keines). */
    public record Entry(TicketProvider provider, TicketSystem system, String defaultProject) {
        /** Projekt aus dem Aufruf oder das Standardprojekt. */
        public String project(String given) {
            return given != null && !given.isBlank() ? given.trim() : defaultProject;
        }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final String defaultProvider;
    private final int maxDescription;
    private final int comments;
    private final int maxPerColumn;
    private final int maxLines;

    public TicketEnvironment(TicketProviders providers, ModuleConfig c) {
        Duration timeout = Duration.ofSeconds(Math.max(5, c.getInt(TicketModule.TIMEOUT, 30)));
        for (TicketProvider p : providers.providers()) {
            if (!c.getBoolean(TicketModule.enabledKey(p.id()))) {
                continue;
            }
            ProviderSettings ps = new ProviderSettings(k -> c.get(TicketModule.key(p.id(), k)), timeout);
            entries.put(p.id(), new Entry(p, p.create(ps),
                    c.get(TicketModule.key(p.id(), TicketModule.DEFAULT_PROJECT)).orElse(null)));
        }
        this.defaultProvider = c.getString(TicketModule.DEFAULT_PROVIDER, "auto");
        this.maxDescription = Math.max(500, c.getInt(TicketModule.MAX_DESCRIPTION, 8000));
        this.comments = Math.max(0, c.getInt(TicketModule.COMMENTS, 5));
        this.maxPerColumn = Math.max(1, c.getInt(TicketModule.MAX_PER_COLUMN, 15));
        this.maxLines = Math.max(50, c.getInt(TicketModule.MAX_LINES, 400));
    }

    public List<Entry> entries() {
        return List.copyOf(entries.values());
    }

    /**
     * Wählt das System: ausdrücklich angegeben → das; sonst das einzige, das den Schlüssel als seinen erkennt
     * (URL seines Hosts, Jira-Schlüssel); sonst das Standard-System; sonst das einzige aktive.
     */
    public Entry resolve(String provider, String key) {
        if (entries.isEmpty()) {
            throw new IllegalStateException("Kein Ticket-System aktiviert – in der DevTools-App unter Module → Tickets "
                    + "z.B. 'Jira: aktiv' einschalten und Server/Token eintragen.");
        }
        if (provider != null && !provider.isBlank()) {
            Entry e = entries.get(provider.trim().toLowerCase(Locale.ROOT));
            if (e == null) {
                throw new IllegalArgumentException("Ticket-System '" + provider + "' ist nicht aktiviert. Aktiv: "
                        + entries.keySet() + " (siehe ticket_providers).");
            }
            return e;
        }
        if (key != null && !key.isBlank()) {
            List<Entry> owners = entries.values().stream().filter(e -> owns(e, key)).toList();
            if (owners.size() == 1) {
                return owners.getFirst();
            }
        }
        if (!"auto".equals(defaultProvider) && entries.containsKey(defaultProvider)) {
            return entries.get(defaultProvider);
        }
        if (entries.size() == 1) {
            return entries.values().iterator().next();
        }
        throw new IllegalArgumentException("Mehrere Ticket-Systeme aktiv " + entries.keySet()
                + " – 'provider' angeben (oder in der App ein Standard-System wählen).");
    }

    private static boolean owns(Entry e, String key) {
        try {
            return e.system().ownsKey(key.trim());
        } catch (RuntimeException ex) {
            return false;
        }
    }

    public int maxDescription() {
        return maxDescription;
    }

    public int comments() {
        return comments;
    }

    public int maxPerColumn() {
        return maxPerColumn;
    }

    public int maxLines() {
        return maxLines;
    }
}
