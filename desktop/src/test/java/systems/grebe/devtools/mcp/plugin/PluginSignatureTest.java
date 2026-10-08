package systems.grebe.devtools.mcp.plugin;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import systems.grebe.devtools.mcp.config.SettingsStore;
import systems.grebe.devtools.mcp.plugin.PluginManager.State;
import systems.grebe.devtools.mcp.plugin.PluginSignature.Status;
import systems.grebe.devtools.mcp.plugin.store.MavenPluginResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Signatur der Plugins ({@code plugin.jwt}): Signieren, Prüfen, Warnungen beim Laden. */
class PluginSignatureTest {

    private static final Instant SIGNED = Instant.parse("2026-10-01T08:00:00Z");

    @TempDir
    Path work;

    @TempDir
    Path home;

    PluginManager manager;
    MavenPluginResolver resolver;

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.close();
        }
        if (resolver != null) {
            resolver.close();
        }
    }

    private static KeyPair ec() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        return g.generateKeyPair();
    }

    private static KeyPair rsa() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        return g.generateKeyPair();
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(der) + "\n-----END " + type + "-----\n";
    }

    private static String publicPem(KeyPair k) {
        return pem("PUBLIC KEY", k.getPublic().getEncoded());
    }

    private Path plugin(String name, String version) {
        return TestPlugins.echoPlugin(name, version, name.replace("-", ""), "", null)
                .build(work.resolve(name + "-" + version + ".jar"));
    }

    private static PluginDescriptor descriptor(String name, String version) {
        return new PluginDescriptor(name, version, "com.acme.Main", 1, null, List.of(), null, List.of(), List.of(),
                List.of());
    }

    @Test
    void signedWithTrustedKeyIsValidWithClaimsFromPluginYml() throws Exception {
        KeyPair key = ec();
        Path jar = plugin("signed", "1.2.0");

        PluginSigner.sign(jar, key.getPrivate(), null, SIGNED);
        PluginSignature s = PluginSignature.check(jar, PluginDescriptorReader.read(jar), List.of(publicPem(key)));

        assertThat(s.status()).isEqualTo(Status.VALID);
        assertThat(s.name()).isEqualTo("signed");
        assertThat(s.version()).isEqualTo("1.2.0");
        assertThat(s.author()).isEqualTo("Test"); // aus plugin.yml
        assertThat(s.signedAt()).isEqualTo(SIGNED);
        assertThat(s.warnings()).isEmpty();
        assertThat(s.summary()).startsWith("gültig · Test · ");
    }

    private static final String HASH = "ab".repeat(32);

    private static String token(PluginDescriptor d, String author, KeyPair key) {
        return PluginSigner.token(d, author, HASH, key.getPrivate(), SIGNED);
    }

    @Test
    void rsaKeysWorkToo() throws Exception {
        KeyPair key = rsa();
        String token = token(descriptor("r", "1"), "Team", key);

        assertThat(PluginSignature.check(token, descriptor("r", "1"), List.of(key.getPublic()), HASH).status())
                .isEqualTo(Status.VALID);
        assertThat(PluginSignature.check(token, descriptor("r", "1"), List.of(ec().getPublic()), HASH).status())
                .isEqualTo(Status.UNTRUSTED);
    }

    @Test
    void nameOrVersionDifferingFromPluginYmlIsWarned() throws Exception {
        KeyPair key = ec();
        String token = token(descriptor("jira", "1.0.0"), "Team", key);

        PluginSignature otherVersion = PluginSignature.check(token, descriptor("jira", "1.0.1"),
                List.of(key.getPublic()), HASH);
        PluginSignature otherName = PluginSignature.check(token, descriptor("jira2", "1.0.0"), List.of(), HASH);

        assertThat(otherVersion.status()).isEqualTo(Status.VALID); // Signatur selbst ist echt
        assertThat(otherVersion.warnings()).singleElement().asString()
                .contains("signiert für jira 1.0.0", "laut plugin.yml jira 1.0.1");
        assertThat(otherName.status()).isEqualTo(Status.UNVERIFIED);
        assertThat(otherName.warnings()).singleElement().asString().contains("jira2 1.0.0");
    }

    @Test
    void modifiedContentIsDetected() throws Exception {
        KeyPair key = ec();
        Path jar = plugin("tamper", "1.0.0");
        PluginSigner.sign(jar, key.getPrivate(), null, SIGNED);
        String signed = PluginSignature.contentHash(jar);

        // gleicher Inhalt, neu gepackt (andere Zeitstempel/Reihenfolge) → unverändert
        PluginSigner.writeToken(jar, Files.readString(extract(jar)));
        assertThat(PluginSignature.contentHash(jar)).isEqualTo(signed);
        assertThat(PluginSignature.check(jar, PluginDescriptorReader.read(jar), List.of(publicPem(key))).status())
                .isEqualTo(Status.VALID);

        // Klasse ausgetauscht, Token behalten
        String token = Files.readString(extract(jar));
        Path tampered = TestPlugins.echoPlugin("tamper", "1.0.0", "tamper", "böse ", null)
                .file(PluginDescriptor.SIGNATURE_FILE_NAME, token).build(work.resolve("tampered.jar"));
        PluginSignature s = PluginSignature.check(tampered, PluginDescriptorReader.read(tampered),
                List.of(publicPem(key)));

        assertThat(s.status()).isEqualTo(Status.MODIFIED);
        assertThat(s.warnings()).singleElement().asString().contains("nach dem Signieren verändert");
        assertThat(s.summary()).startsWith("Inhalt nach dem Signieren verändert");
    }

    @Test
    void tokenWithoutContentHashIsWarned() throws Exception {
        KeyPair key = ec();
        String token = PluginSigner.token(descriptor("old", "1"), null, null, key.getPrivate(), SIGNED);

        PluginSignature s = PluginSignature.check(token, descriptor("old", "1"), List.of(key.getPublic()), HASH);

        assertThat(s.status()).isEqualTo(Status.VALID);
        assertThat(s.warnings()).singleElement().asString().contains("ohne Prüfsumme");
    }

    private Path extract(Path jar) throws Exception {
        try (JarFile f = new JarFile(jar.toFile())) {
            Path out = work.resolve("token-" + System.nanoTime() + ".jwt");
            Files.write(out, f.getInputStream(f.getEntry(PluginDescriptor.SIGNATURE_FILE_NAME)).readAllBytes());
            return out;
        }
    }

    @Test
    void foreignKeyGarbageAndUnsigned() throws Exception {
        String token = token(descriptor("p", "1"), null, ec());

        PluginSignature untrusted = PluginSignature.check(token, descriptor("p", "1"), List.of(ec().getPublic()),
                HASH);
        assertThat(untrusted.status()).isEqualTo(Status.UNTRUSTED);
        assertThat(untrusted.warnings()).singleElement().asString().contains("keinem vertrauenswürdigen Schlüssel");
        assertThat(untrusted.author()).isNull();

        PluginSignature unverified = PluginSignature.check(token, descriptor("p", "1"), List.of(), HASH);
        assertThat(unverified.status()).isEqualTo(Status.UNVERIFIED);
        assertThat(unverified.warnings()).isEmpty();

        PluginSignature garbage = PluginSignature.check("kein.jwt", descriptor("p", "1"), List.of(), HASH);
        assertThat(garbage.status()).isEqualTo(Status.INVALID);
        assertThat(garbage.warnings()).singleElement().asString().contains("plugin.jwt", "ungültig");

        // alg=none wird nicht als signiert akzeptiert
        String none = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes())
                + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"name\":\"p\",\"version\":\"1\"}".getBytes()) + ".";
        assertThat(PluginSignature.check(none, descriptor("p", "1"), List.of(ec().getPublic()), HASH).status())
                .isEqualTo(Status.INVALID);

        assertThat(PluginSignature.check(plugin("plain", "1"), descriptor("plain", "1"), List.of()))
                .isEqualTo(PluginSignature.NONE);
    }

    @Test
    void resigningReplacesTheTokenAndKeepsTheJarIntact() throws Exception {
        KeyPair key = ec();
        Path jar = plugin("twice", "1");

        PluginSigner.sign(jar, key.getPrivate(), "Erster", SIGNED);
        PluginSigner.sign(jar, key.getPrivate(), "Zweiter", SIGNED);

        try (JarFile f = new JarFile(jar.toFile())) {
            assertThat(f.stream().filter(e -> e.getName().equals(PluginDescriptor.SIGNATURE_FILE_NAME))).hasSize(1);
            assertThat(f.getEntry(PluginDescriptor.FILE_NAME)).isNotNull();
            assertThat(f.getEntry("test/twice/Main.class")).isNotNull();
        }
        assertThat(PluginSignature.check(jar, PluginDescriptorReader.read(jar), List.of(publicPem(key))).author())
                .isEqualTo("Zweiter");
    }

    @Test
    void commandLineSignsWithPkcs8Key() throws Exception {
        KeyPair key = ec();
        Path keyFile = Files.writeString(work.resolve("key.pem"), pem("PRIVATE KEY", key.getPrivate().getEncoded()));
        Path jar = plugin("cli", "3.0");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int rc = PluginSigner.run(new String[] {"--key", keyFile.toString(), "--author", "CI", jar.toString()},
                new PrintStream(out), new PrintStream(err));

        assertThat(rc).as(err.toString()).isZero();
        assertThat(out.toString()).contains("cli 3.0 signiert");
        assertThat(PluginSignature.check(jar, PluginDescriptorReader.read(jar), List.of(publicPem(key))))
                .satisfies(s -> {
                    assertThat(s.status()).isEqualTo(Status.VALID);
                    assertThat(s.author()).isEqualTo("CI");
                });
        assertThat(PluginSigner.run(new String[] {jar.toString()}, new PrintStream(out), new PrintStream(err)))
                .isEqualTo(2);
    }

    @Test
    void keysMustBePublicPem() throws Exception {
        assertThatThrownBy(() -> PluginKeys.publicKeys("kein Schlüssel"))
                .hasMessageContaining("PEM");
        assertThatThrownBy(() -> PluginKeys.publicKeys(pem("PRIVATE KEY", ec().getPrivate().getEncoded())))
                .hasMessageContaining("BEGIN PUBLIC KEY");
        assertThat(PluginKeys.publicKeys(publicPem(ec()) + "\n" + publicPem(rsa()))).hasSize(2);
        assertThat(PluginKeys.blocks("Kommentar\n" + publicPem(ec()) + "dazwischen\n" + publicPem(ec()))).hasSize(2);
    }

    @Test
    void managerLoadsPluginWithMismatchingSignatureButWarns() throws Exception {
        KeyPair key = ec();
        SettingsStore store = new SettingsStore(home);
        store.savePlugins(store.plugins().withTrustedKeys(List.of(publicPem(key))));
        resolver = new MavenPluginResolver(home.resolve("plugins/.repository"), () -> store.plugins().repositories());
        Path dir = Files.createDirectories(PluginManager.defaultDirectory(store));
        Path good = plugin("good", "1.0.0");
        PluginSigner.sign(good, key.getPrivate(), null, SIGNED);
        Files.copy(good, dir.resolve("good.jar"));
        // Token für 1.0.0 in einem Jar mit Version 2.0.0 (Inhalt sonst passend)
        Path bumped = TestPlugins.echoPlugin("bumped", "2.0.0", "bumped", "", null).build(dir.resolve("bumped.jar"));
        PluginSigner.writeToken(bumped, PluginSigner.token(descriptor("bumped", "1.0.0"), "Team",
                PluginSignature.contentHash(bumped), key.getPrivate(), SIGNED));
        Files.copy(plugin("plain", "1.0.0"), dir.resolve("plain.jar"));

        manager = new PluginManager(dir, store, Set::of, () -> null, resolver);

        assertThat(manager.plugin("good").orElseThrow()).satisfies(p -> {
            assertThat(p.state()).isEqualTo(State.ENABLED);
            assertThat(p.signature().status()).isEqualTo(Status.VALID);
            assertThat(p.signature().warnings()).isEmpty();
        });
        assertThat(manager.plugin("bumped").orElseThrow()).satisfies(p -> {
            assertThat(p.state()).isEqualTo(State.ENABLED);
            assertThat(p.signature().warnings()).singleElement().asString()
                    .contains("signiert für bumped 1.0.0", "bumped 2.0.0");
        });
        assertThat(manager.plugin("plain").orElseThrow().signature().status()).isEqualTo(Status.UNSIGNED);
        assertThat(bumped).exists();

        // anderer Schlüssel → alle signierten Plugins nicht mehr vertrauenswürdig, ohne Neuladen
        manager.setTrustedKeys(List.of(publicPem(ec())));
        assertThat(manager.plugin("good").orElseThrow().signature().status()).isEqualTo(Status.UNTRUSTED);
        assertThat(store.plugins().trustedKeys()).hasSize(1);
        assertThatThrownBy(() -> manager.setTrustedKeys(List.of("Unsinn"))).hasMessageContaining("PEM");

        // überlebt einen Neustart der Einstellungen
        assertThat(new SettingsStore(home).plugins().trustedKeys()).isEqualTo(store.plugins().trustedKeys());
    }
}
