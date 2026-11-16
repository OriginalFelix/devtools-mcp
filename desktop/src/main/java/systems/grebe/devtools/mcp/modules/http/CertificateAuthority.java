package systems.grebe.devtools.mcp.modules.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * Eigene Root-CA für das Entschlüsseln von HTTPS (wie Fiddler/mitmproxy): einmal erzeugt und im Datenverzeichnis
 * abgelegt, damit Clients ihr nur einmal vertrauen müssen; Host-Zertifikate entstehen bei Bedarf im Speicher.
 */
final class CertificateAuthority {

    /** Kennwort von {@code truststore.p12} – wie das der JDK-cacerts, Clients kennen es. */
    static final String TRUSTSTORE_PASSWORD = "changeit";
    /** Common Name der CA – darüber findet {@code http_ca_trust} sie im Vertrauensspeicher wieder. */
    static final String NAME = "DevTools MCP HTTP Proxy CA";
    private static final char[] CA_PASSWORD = "devtools-mcp".toCharArray();
    private static final Object LOCK = new Object();

    /** CA-Zertifikat allein (PEM) – für Clients, die zusätzliche CAs ergänzen (NODE_EXTRA_CA_CERTS, Browser, System). */
    final Path pem;
    /** CA plus alle CAs der JDK – für Clients, die den Vertrauensspeicher ersetzen (SSL_CERT_FILE, REQUESTS_CA_BUNDLE). */
    final Path bundle;
    /** Wie {@link #bundle} als PKCS12 für Java ({@code -Djavax.net.ssl.trustStore}). */
    final Path truststore;

    private final PrivateKey caKey;
    private final X509Certificate caCert;
    /** Ein Schlüssel für alle Host-Zertifikate: RSA-Schlüssel je Host zu erzeugen kostet spürbar Zeit. */
    private final KeyPair leafKeys;
    private final Map<String, SslContext> contexts = new ConcurrentHashMap<>();

    private CertificateAuthority(Path dir, PrivateKey caKey, X509Certificate caCert) throws GeneralSecurityException {
        this.pem = dir.resolve("ca.pem");
        this.bundle = dir.resolve("ca-bundle.pem");
        this.truststore = dir.resolve("truststore.p12");
        this.caKey = caKey;
        this.caCert = caCert;
        this.leafKeys = rsa();
    }

    /** Lädt die CA aus {@code dir} oder legt sie dort an (samt PEM, Bundle und Truststore). */
    static CertificateAuthority load(Path dir) throws IOException, GeneralSecurityException {
        synchronized (LOCK) {
            Files.createDirectories(dir);
            Path p12 = dir.resolve("ca.p12");
            KeyStore store = KeyStore.getInstance("PKCS12");
            if (Files.exists(p12)) {
                try (InputStream in = Files.newInputStream(p12)) {
                    store.load(in, CA_PASSWORD);
                }
            } else {
                KeyPair keys = rsa();
                X509Certificate cert = createCa(keys);
                store.load(null, null);
                store.setKeyEntry("ca", keys.getPrivate(), CA_PASSWORD, new Certificate[] {cert});
                try (OutputStream out = Files.newOutputStream(p12)) {
                    store.store(out, CA_PASSWORD);
                }
                ownerOnly(p12);
            }
            var ca = new CertificateAuthority(dir, (PrivateKey) store.getKey("ca", CA_PASSWORD),
                    (X509Certificate) store.getCertificate("ca"));
            ca.writeTrustFiles();
            return ca;
        }
    }

    /** TLS-Kontext für den Proxy gegenüber dem Client, mit einem Zertifikat für {@code host}. */
    SslContext serverContext(String host) {
        return contexts.computeIfAbsent(host, h -> {
            try {
                return SslContextBuilder.forServer(leafKeys.getPrivate(), leaf(h), caCert).build();
            } catch (IOException | GeneralSecurityException | OperatorCreationException e) {
                throw new IllegalStateException("Zertifikat für " + h + " nicht erzeugt: " + e.getMessage(), e);
            }
        });
    }

    X509Certificate certificate() {
        return caCert;
    }

    private X509Certificate leaf(String host) throws GeneralSecurityException, OperatorCreationException, IOException {
        Instant now = Instant.now();
        boolean ip = host.contains(":") || host.matches("[\\d.]+");
        X509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(caCert, serial(),
                Date.from(now.minus(Duration.ofDays(1))), Date.from(now.plus(Duration.ofDays(365))),
                new X500NameBuilder(BCStyle.INSTANCE).addRDN(BCStyle.CN, host).build(), leafKeys.getPublic());
        var ext = new JcaX509ExtensionUtils();
        b.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        b.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
        b.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
        b.addExtension(Extension.subjectAlternativeName, false,
                new GeneralNames(new GeneralName(ip ? GeneralName.iPAddress : GeneralName.dNSName, host)));
        b.addExtension(Extension.authorityKeyIdentifier, false, ext.createAuthorityKeyIdentifier(caCert));
        b.addExtension(Extension.subjectKeyIdentifier, false, ext.createSubjectKeyIdentifier(leafKeys.getPublic()));
        return sign(b, caKey);
    }

    private static X509Certificate createCa(KeyPair keys) throws GeneralSecurityException, IOException {
        Instant now = Instant.now();
        X500Name name = new X500Name("CN=" + NAME + ", O=DevTools MCP");
        X509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(name, serial(),
                Date.from(now.minus(Duration.ofDays(1))), Date.from(now.plus(Duration.ofDays(3650))), name,
                keys.getPublic());
        b.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        b.addExtension(Extension.keyUsage, true,
                new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign | KeyUsage.digitalSignature));
        b.addExtension(Extension.subjectKeyIdentifier, false,
                new JcaX509ExtensionUtils().createSubjectKeyIdentifier(keys.getPublic()));
        try {
            return sign(b, keys.getPrivate());
        } catch (OperatorCreationException e) {
            throw new GeneralSecurityException(e);
        }
    }

    private static X509Certificate sign(X509v3CertificateBuilder b, PrivateKey key)
            throws OperatorCreationException, GeneralSecurityException {
        return new JcaX509CertificateConverter().getCertificate(b.build(new JcaContentSignerBuilder("SHA256withRSA")
                .build(key)));
    }

    /** Schreibt PEM, Bundle und Truststore neu, damit sie zur CA und zu den CAs der aktuellen JDK passen. */
    private void writeTrustFiles() throws IOException, GeneralSecurityException {
        KeyStore jdk = KeyStore.getInstance(Path.of(System.getProperty("java.home"), "lib", "security", "cacerts")
                .toFile(), (char[]) null);
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        StringBuilder all = new StringBuilder(pem(caCert));
        trust.setCertificateEntry("devtools-mcp-http-proxy", caCert);
        for (String alias : Collections.list(jdk.aliases())) {
            if (jdk.getCertificate(alias) instanceof X509Certificate c) {
                trust.setCertificateEntry(alias, c);
                all.append(pem(c));
            }
        }
        Files.writeString(pem, pem(caCert));
        Files.writeString(bundle, all);
        try (OutputStream out = Files.newOutputStream(truststore)) {
            trust.store(out, TRUSTSTORE_PASSWORD.toCharArray());
        }
    }

    private static String pem(X509Certificate c) throws GeneralSecurityException {
        return "-----BEGIN CERTIFICATE-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(c.getEncoded())
                + "\n-----END CERTIFICATE-----\n";
    }

    private static KeyPair rsa() throws GeneralSecurityException {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        return g.generateKeyPair();
    }

    private static BigInteger serial() {
        return new BigInteger(64, new SecureRandom());
    }

    /** Der private Schlüssel der CA erlaubt, jeden HTTPS-Verkehr des Nutzers mitzulesen – nur für ihn lesbar. */
    private static void ownerOnly(Path file) throws IOException {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            // Windows: liegt im Benutzerprofil, dessen ACL nur den Benutzer zulässt
        }
    }
}
