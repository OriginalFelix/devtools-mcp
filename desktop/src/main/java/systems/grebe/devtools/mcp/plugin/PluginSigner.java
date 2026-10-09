package systems.grebe.devtools.mcp.plugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import systems.grebe.devtools.mcp.config.AtomicFiles;

/**
 * Signiert Plugin-Jars: schreibt {@code plugin.jwt} ({@link PluginDescriptor#SIGNATURE_FILE_NAME}) mit Name und
 * Version aus der {@code plugin.yml}, dem Autor, dem Signierdatum ({@code iat}) und der Prüfsumme des Inhalts
 * ({@code sha256}, siehe {@link PluginSignature#contentHash}) ins Jar. Ein EC-Schlüssel ergibt
 * ES256/ES384/ES512 (je nach Kurve), ein RSA-Schlüssel RS256.
 *
 * <pre>{@code
 * java -jar devtools-mcp.jar sign-plugin --key plugin-signing.pem [--author "Team Tools"] build/libs/jira-plugin.jar
 * }</pre>
 */
public final class PluginSigner {

    /** Erstes Argument der App, das statt des Starts das Signieren aufruft. */
    public static final String COMMAND = "sign-plugin";

    private PluginSigner() {
    }

    /**
     * Signiertes Token für ein Plugin.
     *
     * @param sha256 {@link PluginSignature#contentHash} des Jars
     */
    public static String token(PluginDescriptor descriptor, String author, String sha256, PrivateKey key,
                               Instant signedAt) {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .claim("name", descriptor.name())
                .claim("version", descriptor.version())
                .claim("sha256", sha256)
                .issueTime(Date.from(signedAt));
        if (author != null && !author.isBlank()) {
            claims.claim("author", author.strip());
        }
        try {
            JWSSigner signer;
            JWSAlgorithm algorithm;
            switch (key) {
                case ECPrivateKey ec -> {
                    signer = new ECDSASigner(ec);
                    algorithm = switch (ec.getParams().getCurve().getField().getFieldSize()) {
                        case 256 -> JWSAlgorithm.ES256;
                        case 384 -> JWSAlgorithm.ES384;
                        case 521 -> JWSAlgorithm.ES512;
                        default -> throw new IllegalArgumentException("EC-Kurve nicht unterstützt – P-256, P-384 "
                                + "oder P-521 verwenden.");
                    };
                }
                case RSAPrivateKey rsa -> {
                    signer = new RSASSASigner(rsa);
                    algorithm = JWSAlgorithm.RS256;
                }
                default -> throw new IllegalArgumentException("Schlüssel muss RSA oder EC sein.");
            }
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(algorithm).type(JOSEObjectType.JWT).build(),
                    claims.build());
            jwt.sign(signer);
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalArgumentException("Signieren fehlgeschlagen: " + e.getMessage(), e);
        }
    }

    /**
     * Signiert ein Plugin-Jar an Ort und Stelle; eine vorhandene Signatur wird ersetzt.
     *
     * @param author {@code null} = Autoren aus der {@code plugin.yml}
     * @return die geschriebene Beschreibung
     */
    public static PluginDescriptor sign(Path jar, PrivateKey key, String author, Instant signedAt) {
        PluginDescriptor d = PluginDescriptorReader.read(jar);
        String by = author != null ? author : d.authors().isEmpty() ? null : String.join(", ", d.authors());
        // die Prüfsumme lässt plugin.jwt aus – sie bleibt beim Einfügen des Tokens gleich
        writeToken(jar, token(d, by, PluginSignature.contentHash(jar), key, signedAt));
        return d;
    }

    /** Legt {@code token} als {@code plugin.jwt} ins Jar (ersetzt eine vorhandene Signatur), sonst unverändert. */
    static void writeToken(Path jar, String jwt) {
        byte[] token = jwt.getBytes(StandardCharsets.UTF_8);
        Path tmp = jar.resolveSibling("." + jar.getFileName() + ".signing");
        try {
            try (ZipFile in = new ZipFile(jar.toFile());
                 ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(tmp))) {
                Enumeration<? extends ZipEntry> entries = in.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry e = entries.nextElement();
                    if (e.getName().equals(PluginDescriptor.SIGNATURE_FILE_NAME)) {
                        continue;
                    }
                    ZipEntry copy = new ZipEntry(e.getName());
                    copy.setTime(e.getTime());
                    out.putNextEntry(copy);
                    try (InputStream data = in.getInputStream(e)) {
                        data.transferTo(out);
                    }
                    out.closeEntry();
                }
                out.putNextEntry(new ZipEntry(PluginDescriptor.SIGNATURE_FILE_NAME));
                out.write(token);
                out.closeEntry();
            }
            AtomicFiles.replace(tmp, jar);
        } catch (IOException e) {
            throw new UncheckedIOException("Jar nicht signierbar: " + jar, e);
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    /** Kommandozeile: {@code sign-plugin --key <privat.pem> [--author <name>] <plugin.jar> …}. */
    public static void main(String[] args) {
        int rc = run(args, System.out, System.err);
        if (rc != 0) {
            System.exit(rc);
        }
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        Path keyFile = null;
        String author = null;
        List<Path> jars = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--key" -> keyFile = i + 1 < args.length ? Path.of(args[++i]) : null;
                case "--author" -> author = i + 1 < args.length ? args[++i] : null;
                default -> jars.add(Path.of(args[i]));
            }
        }
        if (keyFile == null || jars.isEmpty()) {
            err.println("Aufruf: " + COMMAND + " --key <privat.pem> [--author <name>] <plugin.jar> …");
            err.println("Schlüssel (PKCS#8, EC oder RSA): openssl genpkey -algorithm EC "
                    + "-pkeyopt ec_paramgen_curve:P-256 -out plugin-signing.pem");
            return 2;
        }
        try {
            PrivateKey key = PluginKeys.privateKey(Files.readString(keyFile));
            Instant now = Instant.now();
            for (Path jar : jars) {
                PluginDescriptor d = sign(jar, key, author, now);
                out.println(jar + ": " + d.name() + " " + d.version() + " signiert");
            }
            return 0;
        } catch (IOException | RuntimeException e) {
            err.println("Fehler: " + e.getMessage());
            return 1;
        }
    }
}
