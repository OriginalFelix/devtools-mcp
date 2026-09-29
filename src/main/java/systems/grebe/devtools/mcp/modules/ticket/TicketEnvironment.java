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
    private final List<String> writeProjects;
    private final String commentSuffix;

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
        this.writeProjects = c.getList(TicketModule.WRITE_PROJECTS);
        this.commentSuffix = c.getString(TicketModule.COMMENT_SUFFIX, "");
    }

    // ------------------------------------------------------------------ Schreibfreigabe

    /**
     * Prüft vor jeder schreibenden Aktion, ob das Projekt freigegeben ist, und liefert das Projekt, in dem geschrieben
     * wird. Das Projekt kommt aus dem Ticket-Schlüssel (nicht aus dem Parameter {@code project}), damit
     * {@code owner/anderes-repo#1} die Freigabe nicht über ein freigegebenes Standardprojekt umgeht.
     *
     * @param key Ticket-Schlüssel, oder {@code null} beim Anlegen (dann zählt {@code project})
     */
    public String checkWrite(Entry e, String key, String project, String action) {
        String target;
        if (key == null) {
            target = e.project(project);
            if (target == null) {
                throw new IllegalArgumentException(action + ": kein Projekt angegeben und kein Standardprojekt für "
                        + e.provider().id() + " gesetzt.");
            }
        } else {
            target = e.system().projectOf(key.trim(), e.project(project));
        }
        if (writeProjects.isEmpty()) {
            return target;
        }
        if (target == null) {
            throw new IllegalStateException(action + " abgelehnt: Projekt von '" + key + "' nicht bestimmbar, die "
                    + "Schreibfreigabe ist aber auf Projekte eingeschränkt.");
        }
        if (!writeAllowed(e.provider().id(), target)) {
            throw new IllegalStateException(action + " in '" + target + "' (" + e.provider().id() + ") ist nicht "
                    + "freigegeben. Freigegeben: " + writeProjects + ". Der Nutzer kann das Projekt in der DevTools-App "
                    + "unter Module → Tickets → 'Schreiben nur in diesen Projekten' ergänzen.");
        }
        return target;
    }

    /**
     * Eintrag {@code [system:]projekt} bzw. {@code [system:]präfix*}; Vergleich ohne Groß-/Kleinschreibung. Projektnamen
     * (Jira-Schlüssel, owner/repo, gruppe/projekt) enthalten kein {@code :}, daher ist alles davor die System-ID.
     */
    boolean writeAllowed(String providerId, String project) {
        String p = project.toLowerCase(Locale.ROOT);
        for (String raw : writeProjects) {
            String entry = raw.trim().toLowerCase(Locale.ROOT);
            int colon = entry.indexOf(':');
            if (colon >= 0) {
                if (!entry.substring(0, colon).trim().equals(providerId)) {
                    continue;
                }
                entry = entry.substring(colon + 1).trim();
            }
            if (entry.equals("*") || entry.equals(p)
                    || entry.endsWith("*") && p.startsWith(entry.substring(0, entry.length() - 1))) {
                return true;
            }
        }
        return false;
    }

    /** Kommentartext mit optionaler Kennzeichnung. */
    public String commentBody(String body) {
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("Leerer Kommentar – Text in 'body' angeben.");
        }
        return commentSuffix.isBlank() ? body.strip() : body.strip() + "\n\n" + commentSuffix.strip();
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
