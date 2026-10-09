package systems.grebe.devtools.mcp.modules.ticket;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import systems.grebe.devtools.mcp.config.AtomicFiles;

/**
 * Verzeichnis der über die Tools angelegten Tickets und Kommentare – Grundlage für „nur selbst angelegte löschen“
 * (wie das Label {@code devtools-mcp} beim Container-Modul). Einträge sind {@code provider|instanz|schlüssel} bzw.
 * {@code …|kommentar-id}; gespeichert in {@code tickets-own.json} im DevTools-Ordner, damit die Zuordnung einen
 * Neustart übersteht. Ohne Datei (Tests) nur im Speicher.
 */
public final class TicketOwnership {

    private static final Logger LOG = LoggerFactory.getLogger(TicketOwnership.class);
    private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    private final Path file;
    private final Set<String> tickets = new LinkedHashSet<>();
    private final Set<String> comments = new LinkedHashSet<>();

    public TicketOwnership(Path file) {
        this.file = file;
        load();
    }

    /** Nur im Speicher (Tests, Module ohne Einstellungsordner). */
    public static TicketOwnership inMemory() {
        return new TicketOwnership(null);
    }

    static String ticketId(String provider, String instance, String key) {
        return provider + "|" + instance + "|" + key;
    }

    static String commentId(String provider, String instance, String key, String commentId) {
        return ticketId(provider, instance, key) + "|" + commentId;
    }

    public synchronized void addTicket(String provider, String instance, String key) {
        if (tickets.add(ticketId(provider, instance, key))) {
            save();
        }
    }

    public synchronized void addComment(String provider, String instance, String key, String commentId) {
        if (commentId != null && comments.add(commentId(provider, instance, key, commentId))) {
            save();
        }
    }

    public synchronized boolean ownsTicket(String provider, String instance, String key) {
        return tickets.contains(ticketId(provider, instance, key));
    }

    public synchronized boolean ownsComment(String provider, String instance, String key, String commentId) {
        return comments.contains(commentId(provider, instance, key, commentId));
    }

    /** Nach dem Löschen: Ticket samt seiner Kommentare austragen. */
    public synchronized void removeTicket(String provider, String instance, String key) {
        String id = ticketId(provider, instance, key);
        boolean changed = tickets.remove(id);
        changed |= comments.removeIf(c -> c.startsWith(id + "|"));
        if (changed) {
            save();
        }
    }

    public synchronized void removeComment(String provider, String instance, String key, String commentId) {
        if (comments.remove(commentId(provider, instance, key, commentId))) {
            save();
        }
    }

    private void load() {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            JsonNode root = JSON.readTree(Files.readString(file));
            root.path("tickets").forEach(n -> tickets.add(n.asString()));
            root.path("comments").forEach(n -> comments.add(n.asString()));
        } catch (IOException | RuntimeException e) {
            // defekte Datei: lieber nichts als „eigen“ ansehen als falsch zuordnen
            LOG.warn("Verzeichnis selbst angelegter Tickets nicht lesbar ({}): {}", file, e.getMessage());
        }
    }

    private void save() {
        if (file == null) {
            return;
        }
        ObjectNode root = JSON.createObjectNode();
        var t = root.putArray("tickets");
        tickets.forEach(t::add);
        var c = root.putArray("comments");
        comments.forEach(c::add);
        try {
            AtomicFiles.writeString(file, JSON.writeValueAsString(root));
        } catch (IOException | RuntimeException e) {
            LOG.warn("Verzeichnis selbst angelegter Tickets nicht gespeichert ({}): {}", file, e.getMessage());
        }
    }
}
