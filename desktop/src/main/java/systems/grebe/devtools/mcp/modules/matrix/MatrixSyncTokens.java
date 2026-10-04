package systems.grebe.devtools.mcp.modules.matrix;

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
 * Letzter {@code /sync}-Stand ({@code next_batch}) je Konto ({@code homeserver|@benutzer:server}), gespeichert in
 * {@code matrix-sync.json} im DevTools-Ordner. So liefert {@code matrix_receive} nach einem Neustart genau die
 * Nachrichten, die seitdem eingegangen sind. Ohne Datei (Tests) nur im Speicher.
 */
final class MatrixSyncTokens {

    private static final Logger LOG = LoggerFactory.getLogger(MatrixSyncTokens.class);
    private static final JsonMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    private final Path file;
    private final Map<String, String> tokens = new LinkedHashMap<>();

    MatrixSyncTokens(Path file) {
        this.file = file;
        load();
    }

    static MatrixSyncTokens inMemory() {
        return new MatrixSyncTokens(null);
    }

    synchronized String get(String account) {
        return tokens.get(account);
    }

    synchronized void put(String account, String token) {
        if (token == null || token.isBlank() || token.equals(tokens.get(account))) {
            return;
        }
        tokens.put(account, token);
        save();
    }

    private void load() {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            JsonNode root = JSON.readTree(Files.readString(file));
            root.path("accounts").properties().forEach(e -> tokens.put(e.getKey(), e.getValue().asString()));
        } catch (IOException | RuntimeException e) {
            // ohne Stand beginnt der Eingang neu – nichts geht verloren, was nicht ohnehin im Verlauf steht
            LOG.warn("Matrix-Sync-Stand nicht lesbar ({}): {}", file, e.getMessage());
        }
    }

    private void save() {
        if (file == null) {
            return;
        }
        ObjectNode root = JSON.createObjectNode();
        ObjectNode accounts = root.putObject("accounts");
        tokens.forEach(accounts::put);
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, JSON.writeValueAsString(root));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            LOG.warn("Matrix-Sync-Stand nicht gespeichert ({}): {}", file, e.getMessage());
        }
    }
}
