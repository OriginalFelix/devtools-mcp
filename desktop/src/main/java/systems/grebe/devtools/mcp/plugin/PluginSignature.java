package systems.grebe.devtools.mcp.plugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import systems.grebe.devtools.mcp.api.Sha256;

/**
 * Ergebnis der Prüfung von {@code plugin.jwt} ({@link PluginDescriptor#SIGNATURE_FILE_NAME}): ein JWS mit den Claims
 * {@code name}, {@code version}, {@code author}, {@code iat} (Signierdatum) und {@code sha256} (Prüfsumme des
 * Jar-Inhalts, siehe {@link #contentHash}), erzeugt von {@link PluginSigner}.
 *
 * <p>Die Prüfung blockiert nichts – ein Plugin lädt auch mit fehlender oder fehlerhafter Signatur. Was auffällt, steht
 * in {@link #warnings()}: der Plugin-Manager schreibt es beim Laden ins Log, der Tab „Plugins“ zeigt es an.
 *
 * @param name     Plugin-Name laut Token ({@code null}, wenn unsigniert oder unlesbar)
 * @param version  Version laut Token
 * @param author   Autor laut Token (optional)
 * @param signedAt Signierdatum laut Token (optional)
 * @param detail   Fehlermeldung bei {@link Status#INVALID}, sonst {@code null}
 */
public record PluginSignature(Status status, String name, String version, String author, Instant signedAt,
                              String detail, List<String> warnings) {

    private static final Logger LOG = LoggerFactory.getLogger(PluginSignature.class);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
            .withZone(ZoneId.systemDefault());

    public enum Status {
        /** Keine {@code plugin.jwt} im Jar. */
        UNSIGNED,
        /** {@code plugin.jwt} ist kein signiertes JWT oder es fehlen Pflicht-Claims. */
        INVALID,
        /** Signiert, aber nicht geprüft: es ist kein vertrauenswürdiger Schlüssel eingetragen. */
        UNVERIFIED,
        /** Signiert, aber mit keinem der vertrauenswürdigen Schlüssel. */
        UNTRUSTED,
        /** Signatur mit einem vertrauenswürdigen Schlüssel bestätigt, Inhalt unverändert. */
        VALID,
        /** Signatur echt, aber der Inhalt des Jars wurde nach dem Signieren verändert ({@code sha256}). */
        MODIFIED
    }

    public static final PluginSignature NONE = new PluginSignature(Status.UNSIGNED, null, null, null, null, null,
            List.of());

    public PluginSignature {
        Objects.requireNonNull(status, "status");
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
    }

    /**
     * Prüft die Signatur eines Plugin-Jars.
     *
     * @param trustedKeys PEM-Texte mit öffentlichen Schlüsseln; unlesbare werden übersprungen (mit Log-Eintrag)
     */
    public static PluginSignature check(Path jar, PluginDescriptor descriptor, List<String> trustedKeys) {
        String token;
        String hash;
        try (JarFile file = new JarFile(jar.toFile())) {
            ZipEntry entry = file.getEntry(PluginDescriptor.SIGNATURE_FILE_NAME);
            if (entry == null) {
                return NONE;
            }
            try (InputStream in = file.getInputStream(entry)) {
                token = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            hash = contentHash(file);
        } catch (IOException e) {
            return invalid("Jar nicht lesbar: " + e.getMessage());
        }
        return check(token, descriptor, keys(trustedKeys), hash);
    }

    /**
     * Prüft ein Token gegen die {@code plugin.yml}, die Schlüssel und den Inhalt des Jars.
     *
     * @param contentHash {@link #contentHash} des Jars
     */
    public static PluginSignature check(String token, PluginDescriptor descriptor, List<PublicKey> trustedKeys,
                                        String contentHash) {
        SignedJWT jwt;
        JWTClaimsSet claims;
        String name;
        String version;
        String author;
        String sha256;
        try {
            jwt = SignedJWT.parse(token.strip());
            claims = jwt.getJWTClaimsSet();
            name = claims.getStringClaim("name");
            version = claims.getStringClaim("version");
            author = claims.getStringClaim("author");
            sha256 = claims.getStringClaim("sha256");
        } catch (ParseException e) {
            return invalid("kein signiertes JWT (" + e.getMessage() + ")");
        }
        if (name == null || version == null) {
            return invalid("Claim „" + (name == null ? "name" : "version") + "“ fehlt.");
        }
        Instant signedAt = claims.getIssueTime() == null ? null : claims.getIssueTime().toInstant();

        Status status;
        if (trustedKeys.isEmpty()) {
            status = Status.UNVERIFIED;
        } else {
            status = trustedKeys.stream().anyMatch(k -> verifies(jwt, k)) ? Status.VALID : Status.UNTRUSTED;
        }
        List<String> warnings = new ArrayList<>();
        if (sha256 == null) {
            warnings.add("Signatur ohne Prüfsumme (sha256) – sie deckt den Inhalt des Jars nicht ab; neu signieren.");
        } else if (!sha256.equalsIgnoreCase(contentHash)) {
            warnings.add("Inhalt des Jars passt nicht zur Signatur (sha256) – nach dem Signieren verändert.");
            if (status == Status.VALID) {
                status = Status.MODIFIED;
            }
        }
        if (!name.equals(descriptor.name()) || !version.equals(descriptor.version())) {
            warnings.add("Signatur passt nicht zum Plugin: signiert für " + name + " " + version
                    + ", laut plugin.yml " + descriptor.name() + " " + descriptor.version() + ".");
        }
        if (status == Status.UNTRUSTED) {
            warnings.add("Signatur stammt von keinem vertrauenswürdigen Schlüssel – Plugin aus unbekannter Quelle "
                    + "oder verändert.");
        }
        return new PluginSignature(status, name, version, author, signedAt, null, warnings);
    }

    /** Einzeiler für die Oberfläche. */
    public String summary() {
        String by = (author == null ? "" : " · " + author) + (signedAt == null ? "" : " · " + DATE.format(signedAt));
        return switch (status) {
            case UNSIGNED -> "nicht signiert";
            case INVALID -> "ungültig – " + detail;
            case UNVERIFIED -> "signiert, nicht geprüft (kein vertrauenswürdiger Schlüssel eingetragen)" + by;
            case UNTRUSTED -> "signiert, Schlüssel nicht vertrauenswürdig" + by;
            case VALID -> "gültig" + by;
            case MODIFIED -> "Inhalt nach dem Signieren verändert" + by;
        };
    }

    /**
     * Prüfsumme des Jar-Inhalts (SHA-256, hex): alle Dateien außer {@code plugin.jwt}, sortiert nach Name, je Eintrag
     * Name (UTF-8), ein Null-Byte, die Länge (8 Byte) und der Inhalt. Unabhängig von Zeitstempeln, Kompression und
     * Reihenfolge im Zip – erneutes Packen mit gleichem Inhalt ändert sie nicht.
     */
    public static String contentHash(Path jar) {
        try (JarFile file = new JarFile(jar.toFile())) {
            return contentHash(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Jar nicht lesbar: " + jar, e);
        }
    }

    private static String contentHash(JarFile file) throws IOException {
        MessageDigest sha = Sha256.newDigest();
        List<JarEntry> entries = file.stream()
                .filter(e -> !e.isDirectory() && !e.getName().equals(PluginDescriptor.SIGNATURE_FILE_NAME))
                .sorted(Comparator.comparing(ZipEntry::getName)).toList();
        for (JarEntry e : entries) {
            byte[] data;
            try (InputStream in = file.getInputStream(e)) {
                data = in.readAllBytes();
            }
            sha.update(e.getName().getBytes(StandardCharsets.UTF_8));
            sha.update((byte) 0);
            sha.update(ByteBuffer.allocate(Long.BYTES).putLong(data.length).array());
            sha.update(data);
        }
        return Sha256.hex(sha);
    }

    private static PluginSignature invalid(String detail) {
        return new PluginSignature(Status.INVALID, null, null, null, null, detail,
                List.of("Signatur (" + PluginDescriptor.SIGNATURE_FILE_NAME + ") ungültig: " + detail));
    }

    private static boolean verifies(SignedJWT jwt, PublicKey key) {
        try {
            JWSVerifier verifier = switch (key) {
                case RSAPublicKey rsa -> new RSASSAVerifier(rsa);
                case ECPublicKey ec -> new ECDSAVerifier(ec);
                default -> null;
            };
            return verifier != null && verifier.supportedJWSAlgorithms().contains(jwt.getHeader().getAlgorithm())
                    && jwt.verify(verifier);
        } catch (JOSEException | IllegalStateException e) {
            return false;
        }
    }

    private static List<PublicKey> keys(List<String> pems) {
        List<PublicKey> keys = new ArrayList<>();
        for (String pem : pems) {
            try {
                keys.addAll(PluginKeys.publicKeys(pem));
            } catch (IllegalArgumentException e) {
                LOG.warn("Vertrauenswürdiger Plugin-Schlüssel übersprungen: {}", e.getMessage());
            }
        }
        return keys;
    }
}
