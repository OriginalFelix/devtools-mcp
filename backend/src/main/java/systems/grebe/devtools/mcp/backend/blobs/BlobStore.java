package systems.grebe.devtools.mcp.backend.blobs;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.backend.BackendHome;
import systems.grebe.devtools.mcp.backend.skills.SkillOwner;

/**
 * Dateiablage für Anhänge von Skills und Memories: Der Inhalt liegt als Datei im Datenverzeichnis des Backends unter
 * {@code blobs/<e-mail>/<sha256>}, für globale Skill-Vorlagen unter {@code blobs/GLOBAL/<sha256>}; die Datenbank hält
 * nur Pfad, Größe, Medientyp und Hash. Gleicher Inhalt liegt je Eigentümer nur einmal.
 *
 * <p>Eine Größengrenze gibt es nicht: Inhalte werden gestreamt, über HTTP auch in Teilen ({@link #startUpload},
 * {@link #append}, {@link #complete}), damit Grenzen von Servlet-Containern und Proxys je Anfrage nicht greifen. Wer
 * welche Datei lesen darf, folgt aus dem Verzeichnis: jeder Benutzer seine eigenen, dazu alle die Vorlagen.
 *
 * <p>Aufräumen: {@link BlobReferences} löscht nach dem Commit, was nicht mehr verwendet wird; {@link BlobJanitor}
 * entfernt liegengebliebene Uploads und verwaiste Inhalte.
 */
@Component
public class BlobStore {

    /** Verzeichnis der globalen Skill-Vorlagen. */
    public static final String GLOBAL_DIR = "GLOBAL";

    private static final Logger LOG = LoggerFactory.getLogger(BlobStore.class);
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern UPLOAD_ID = Pattern.compile("[0-9a-f]{32}");
    private static final String UPLOADS = ".uploads";
    private static final String TEMP_PREFIX = ".tmp-";

    /** Gespeicherter Inhalt: SHA-256 (hex, klein) und Größe in Bytes. */
    public record Blob(String sha, long size) {
    }

    private final Path root;

    @Autowired
    public BlobStore(BackendHome home) {
        this(home.resolve("blobs"));
    }

    public BlobStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    /** Verzeichnisname eines Eigentümers: seine E-Mail (klein), {@link #GLOBAL_DIR} für Vorlagen. */
    public static String dirName(String owner) {
        if (SkillOwner.GLOBAL.equals(owner)) {
            return GLOBAL_DIR;
        }
        String d = owner == null ? "" : owner.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9@._+-]", "_");
        // E-Mails enthalten immer '@' – so kann kein Benutzer GLOBAL oder ein verstecktes Verzeichnis treffen
        return d.isEmpty() || d.startsWith(".") || d.equalsIgnoreCase(GLOBAL_DIR) ? "_" + d : d;
    }

    /** Prüft die Schreibweise eines Hashes (64 Hex-Zeichen, klein). */
    public static String requireSha(String sha) {
        String s = sha == null ? "" : sha.strip().toLowerCase(Locale.ROOT);
        if (!SHA.matcher(s).matches()) {
            throw new IllegalArgumentException("Ungültige Datei-Kennung '" + sha + "' (SHA-256, 64 Hex-Zeichen).");
        }
        return s;
    }

    private Path dir(String owner) {
        return root.resolve(dirName(owner));
    }

    // ------------------------------------------------------------------ Schreiben

    /** Speichert den Inhalt des Streams (liest bis zum Ende, schließt ihn nicht). */
    public Blob put(String owner, InputStream in) throws IOException {
        Path dir = dir(owner);
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, TEMP_PREFIX, "");
        try {
            MessageDigest md = sha256();
            try (OutputStream out = Files.newOutputStream(tmp)) {
                new DigestInputStream(in, md).transferTo(out);
            }
            return finish(dir, tmp, md);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** Beginnt einen Upload in Teilen; liefert seine Kennung. */
    public String startUpload(String owner) throws IOException {
        Path uploads = dir(owner).resolve(UPLOADS);
        Files.createDirectories(uploads);
        String id = UUID.randomUUID().toString().replace("-", "");
        Files.createFile(uploads.resolve(id));
        return id;
    }

    /**
     * Hängt einen Teil an. {@code offset} ist die Position des Teils; liegt sie vor dem Ende (Teil wird nach einem
     * Abbruch wiederholt), wird ab dort überschrieben.
     *
     * @return Größe des Uploads danach
     */
    public long append(String owner, String uploadId, long offset, InputStream in) throws IOException {
        Path file = upload(owner, uploadId);
        long size = Files.size(file);
        if (offset < 0 || offset > size) {
            throw new IllegalArgumentException("Teil an Position " + offset + " passt nicht – hochgeladen sind "
                    + size + " Bytes.");
        }
        try (var channel = Files.newByteChannel(file, StandardOpenOption.WRITE)) {
            channel.truncate(offset);
            channel.position(offset);
            try (OutputStream out = java.nio.channels.Channels.newOutputStream(channel)) {
                in.transferTo(out);
            }
        }
        return Files.size(file);
    }

    /** Schließt einen Upload in Teilen ab: Inhalt prüfen (Hash) und ablegen. */
    public Blob complete(String owner, String uploadId) throws IOException {
        Path file = upload(owner, uploadId);
        MessageDigest md = sha256();
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), md)) {
            in.transferTo(OutputStream.nullOutputStream());
        }
        try {
            return finish(dir(owner), file, md);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private Path upload(String owner, String uploadId) throws IOException {
        if (uploadId == null || !UPLOAD_ID.matcher(uploadId).matches()) {
            throw new IllegalArgumentException("Ungültige Upload-Kennung '" + uploadId + "'.");
        }
        Path file = dir(owner).resolve(UPLOADS).resolve(uploadId);
        if (!Files.isRegularFile(file)) {
            throw new NoSuchFileException("Upload " + uploadId + " gibt es nicht (abgelaufen?) – neu beginnen.");
        }
        return file;
    }

    /** Legt die fertige Datei unter ihrem Hash ab; gab es den Inhalt schon, bleibt der vorhandene (frisch datiert). */
    private Blob finish(Path dir, Path tmp, MessageDigest md) throws IOException {
        String sha = HexFormat.of().formatHex(md.digest());
        long size = Files.size(tmp);
        Path target = dir.resolve(sha);
        if (Files.exists(target)) {
            touch(target);
            return new Blob(sha, size);
        }
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (FileAlreadyExistsException e) {
            touch(target); // gleichzeitig mit gleichem Inhalt hochgeladen
        }
        return new Blob(sha, size);
    }

    /** Kopiert einen Inhalt zu einem anderen Eigentümer (Vorlage übernehmen bzw. veröffentlichen). */
    public void copy(String fromOwner, String toOwner, String sha) {
        Path source = require(fromOwner, sha);
        Path targetDir = dir(toOwner);
        Path target = targetDir.resolve(sha);
        try {
            Files.createDirectories(targetDir);
            if (Files.exists(target)) {
                touch(target);
                return;
            }
            try {
                Files.createLink(target, source); // gleicher Inhalt, kein Platz
            } catch (IOException | UnsupportedOperationException e) {
                Path tmp = Files.createTempFile(targetDir, TEMP_PREFIX, "");
                try {
                    Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    Files.deleteIfExists(tmp);
                }
            }
        } catch (FileAlreadyExistsException e) {
            // gleichzeitig angelegt – Inhalt ist derselbe
        } catch (IOException e) {
            throw new UncheckedIOException("Datei " + sha + " nicht kopierbar: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ Lesen

    /** Datei eines Eigentümers, falls vorhanden. */
    public Optional<Path> find(String owner, String sha) {
        Path p = dir(owner).resolve(requireSha(sha));
        return Files.isRegularFile(p) ? Optional.of(p) : Optional.empty();
    }

    /** Datei eines Eigentümers; fehlt sie, mit Hinweis für das LLM. */
    public Path require(String owner, String sha) {
        return find(owner, sha).orElseThrow(() -> new IllegalArgumentException("Datei " + sha + " liegt nicht in der "
                + "Ablage (Upload abgelaufen?) – erneut hochladen."));
    }

    /** Größe einer vorhandenen Datei. */
    public Blob describe(String owner, String sha) {
        try {
            return new Blob(requireSha(sha), Files.size(require(owner, sha)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public InputStream open(String owner, String sha) throws IOException {
        return Files.newInputStream(require(owner, sha));
    }

    // ------------------------------------------------------------------ Löschen

    /**
     * Löscht einen Inhalt, sofern er nicht gerade erst geschrieben wurde – ein laufender Upload gleichen Inhalts soll
     * ihn noch verwenden können; liegen gebliebene räumt {@link #sweep} auf.
     *
     * @return ob gelöscht wurde
     */
    boolean deleteUnlessRecent(String owner, String sha, Instant recentAfter) {
        Path p = dir(owner).resolve(requireSha(sha));
        try {
            if (Files.getLastModifiedTime(p).toInstant().isAfter(recentAfter)) {
                return false;
            }
            return Files.deleteIfExists(p);
        } catch (NoSuchFileException e) {
            return false;
        } catch (IOException e) {
            LOG.warn("Datei {} von {} nicht löschbar: {}", sha, owner, e.getMessage());
            return false;
        }
    }

    /**
     * Entfernt Inhalte, auf die nichts verweist, sowie abgebrochene Uploads – jeweils nur, wenn sie vor
     * {@code olderThan} zuletzt geschrieben wurden.
     *
     * @param referenced verwendete Inhalte als {@code <verzeichnis>/<sha>} (siehe {@link #key})
     * @return Anzahl gelöschter Dateien
     */
    public int sweep(Set<String> referenced, Instant olderThan) {
        if (!Files.isDirectory(root)) {
            return 0;
        }
        int deleted = 0;
        try (DirectoryStream<Path> owners = Files.newDirectoryStream(root, Files::isDirectory)) {
            for (Path owner : owners) {
                deleted += sweepOwner(owner, referenced, olderThan);
            }
        } catch (IOException e) {
            LOG.warn("Dateiablage {} nicht lesbar: {}", root, e.getMessage());
        }
        return deleted;
    }

    private int sweepOwner(Path owner, Set<String> referenced, Instant olderThan) throws IOException {
        int deleted = 0;
        String dirName = owner.getFileName().toString();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(owner)) {
            for (Path f : files) {
                String name = f.getFileName().toString();
                if (Files.isDirectory(f)) {
                    if (name.equals(UPLOADS)) {
                        deleted += deleteOld(f, olderThan);
                    }
                    continue;
                }
                boolean orphan = SHA.matcher(name).matches() ? !referenced.contains(dirName + "/" + name)
                        : name.startsWith(TEMP_PREFIX);
                if (orphan && old(f, olderThan) && Files.deleteIfExists(f)) {
                    deleted++;
                }
            }
        }
        return deleted;
    }

    private static int deleteOld(Path dir, Instant olderThan) throws IOException {
        int deleted = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, Files::isRegularFile)) {
            for (Path f : files) {
                if (old(f, olderThan) && Files.deleteIfExists(f)) {
                    deleted++;
                }
            }
        }
        return deleted;
    }

    /** Schlüssel eines verwendeten Inhalts für {@link #sweep}. */
    public static String key(String owner, String sha) {
        return dirName(owner) + "/" + sha;
    }

    private static boolean old(Path f, Instant olderThan) throws IOException {
        return Files.getLastModifiedTime(f).toInstant().isBefore(olderThan);
    }

    private static void touch(Path p) throws IOException {
        Files.setLastModifiedTime(p, FileTime.from(Instant.now()));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
