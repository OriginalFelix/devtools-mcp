package systems.grebe.devtools.mcp.modules.mail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Stand der Überwachung in {@code mail-state.json}: je Konto und Ordner UIDVALIDITY und die höchste gemeldete UID. So
 * meldet die Überwachung nach einem Neustart genau die Mails, die seitdem eingegangen sind. Ohne Datei (Tests) nur im
 * Speicher.
 */
final class MailState {

    private static final Logger LOG = LoggerFactory.getLogger(MailState.class);
    private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    record Position(long uidValidity, long lastUid) {
    }

    private final Path file;
    private final Map<String, Position> positions = new LinkedHashMap<>();

    MailState(Path file) {
        this.file = file;
        load();
    }

    static MailState inMemory() {
        return new MailState(null);
    }

    /** Schlüssel: Konto mit Benutzer@Host (eine umbenannte oder umgezogene Verbindung beginnt neu) und Ordner. */
    static String key(MailAccount a, String folder) {
        return a.name() + "|" + a.target() + "|" + folder;
    }

    synchronized Position get(String key) {
        return positions.get(key);
    }

    synchronized void put(String key, Position p) {
        if (p.equals(positions.get(key))) {
            return;
        }
        positions.put(key, p);
        save();
    }

    private void load() {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            JsonNode root = JSON.readTree(Files.readString(file));
            root.path("folders").properties().forEach(e -> positions.put(e.getKey(),
                    new Position(e.getValue().path("uidValidity").asLong(), e.getValue().path("lastUid").asLong())));
        } catch (IOException | RuntimeException e) {
            LOG.warn("Mail-Zustand nicht lesbar ({}): {}", file, e.getMessage());
        }
    }

    private void save() {
        if (file == null) {
            return;
        }
        ObjectNode root = JSON.createObjectNode();
        ObjectNode folders = root.putObject("folders");
        positions.forEach((k, p) -> {
            ObjectNode n = folders.putObject(k);
            n.put("uidValidity", p.uidValidity());
            n.put("lastUid", p.lastUid());
        });
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, JSON.writeValueAsString(root));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            LOG.warn("Mail-Zustand nicht gespeichert ({}): {}", file, e.getMessage());
        }
    }
}
