package systems.grebe.devtools.mcp.backend.broker;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.hivemq.embedded.EmbeddedExtension;
import com.hivemq.embedded.EmbeddedHiveMQ;
import com.hivemq.extension.sdk.api.ExtensionMain;
import com.hivemq.extension.sdk.api.auth.SimpleAuthenticator;
import com.hivemq.extension.sdk.api.auth.parameter.SimpleAuthInput;
import com.hivemq.extension.sdk.api.auth.parameter.SimpleAuthOutput;
import com.hivemq.extension.sdk.api.auth.parameter.TopicPermission;
import com.hivemq.extension.sdk.api.interceptor.publish.PublishInboundInterceptor;
import com.hivemq.extension.sdk.api.packets.auth.DefaultAuthorizationBehaviour;
import com.hivemq.extension.sdk.api.packets.auth.ModifiableDefaultPermissions;
import com.hivemq.extension.sdk.api.packets.connect.ConnackReasonCode;
import com.hivemq.extension.sdk.api.parameter.ExtensionStartInput;
import com.hivemq.extension.sdk.api.parameter.ExtensionStartOutput;
import com.hivemq.extension.sdk.api.parameter.ExtensionStopInput;
import com.hivemq.extension.sdk.api.parameter.ExtensionStopOutput;
import com.hivemq.extension.sdk.api.services.Services;
import com.hivemq.extension.sdk.api.services.builder.Builders;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.api.BrokerInfo;
import systems.grebe.devtools.mcp.backend.BackendHome;
import systems.grebe.devtools.mcp.backend.account.TokenService;
import systems.grebe.devtools.mcp.modules.share.ShareTopics;

/**
 * MQTT-Broker des Backends (HiveMQ CE, eingebettet) für die Kooperation der Desktop-Apps: im Team-Server zentral für
 * alle, im eingebetteten Backend der App für diesen Rechner. Eingeschaltet mit {@code devtools.broker.enabled=true}.
 *
 * <p><b>Anmeldung:</b> Passwort = Token des Benutzers (Sitzungs- oder Desktop-Token, wie für GraphQL); der
 * Benutzername ist beliebig. Ohne gültiges Token oder ohne E-Mail im Konto lehnt der Broker ab.
 *
 * <p><b>Rechte</b> je Benutzer, Adresse = seine E-Mail ({@link ShareTopics#address}): eigenen Eingang abonnieren,
 * Anwesenheit aller lesen, nur die eigene melden, an jeden Eingang senden – sonst nichts.
 *
 * <p><b>Absender:</b> Jede Nachricht bekommt die geprüfte Adresse als User Property
 * {@link ShareTopics#SENDER_PROPERTY}; ein mitgeschickter Wert wird ersetzt. So kann niemand unter fremdem Namen
 * anbieten.
 *
 * <p>Sitzungen, aufgehobene Nachrichten und Anwesenheit liegen in {@code broker/} im Datenverzeichnis des Backends und
 * überstehen Neustarts.
 */
@Component
public class BrokerService implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(BrokerService.class);
    private static final String ADDRESS = "devtools.address";

    /** Prüft ein Token und liefert die Adresse (E-Mail) seines Benutzers. */
    @FunctionalInterface
    public interface Auth {
        Optional<String> address(String token);
    }

    /**
     * Einstellungen ({@code devtools.broker.*}).
     *
     * @param host        Host, den die Clients verwenden sollen; leer = Host der Backend-Adresse
     * @param tlsKeystore Keystore (JKS/PKCS12) für den TLS-Listener
     */
    public record Settings(boolean enabled, String bindAddress, int port, int tlsPort, int websocketPort,
                           String topicPrefix, String host, String tlsKeystore, String tlsKeystorePassword,
                           String tlsKeyPassword) {

        public Settings {
            bindAddress = bindAddress == null || bindAddress.isBlank() ? "0.0.0.0" : bindAddress.strip();
            topicPrefix = topicPrefix == null || topicPrefix.isBlank() || topicPrefix.matches(".*[+#].*")
                    ? ShareTopics.DEFAULT_PREFIX : topicPrefix.strip().replaceAll("^/+|/+$", "");
            host = host == null ? "" : host.strip();
        }
    }

    private final Settings settings;
    private final Auth auth;
    private final Path home;
    private EmbeddedHiveMQ hivemq;
    private volatile String status = "aus";

    @Autowired
    public BrokerService(BackendHome home, TokenService tokens,
                         @Value("${devtools.broker.enabled:false}") boolean enabled,
                         @Value("${devtools.broker.bind-address:0.0.0.0}") String bindAddress,
                         @Value("${devtools.broker.port:1883}") int port,
                         @Value("${devtools.broker.tls-port:0}") int tlsPort,
                         @Value("${devtools.broker.websocket-port:0}") int websocketPort,
                         @Value("${devtools.broker.topic-prefix:" + ShareTopics.DEFAULT_PREFIX + "}") String prefix,
                         @Value("${devtools.broker.host:}") String host,
                         @Value("${devtools.broker.tls.keystore:}") String keystore,
                         @Value("${devtools.broker.tls.keystore-password:}") String keystorePassword,
                         @Value("${devtools.broker.tls.key-password:}") String keyPassword) {
        this(new Settings(enabled, bindAddress, port, tlsPort, websocketPort, prefix, host, keystore, keystorePassword,
                keyPassword), token -> tokens.verify(token).map(TokenService.TokenUser::user)
                .filter(u -> u.enabled() && u.email() != null && !u.email().isBlank())
                .map(u -> ShareTopics.address(u.email())), home.resolve("broker"));
    }

    public BrokerService(Settings settings, Auth auth, Path home) {
        this.settings = settings;
        this.auth = auth;
        this.home = home;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startWhenReady() {
        if (settings.enabled()) {
            Thread.ofVirtual().name("broker-start").start(this::start);
        }
    }

    /** Startet den Broker (blockierend); ohne {@code enabled} oder wenn er schon läuft, nichts. */
    public synchronized void start() {
        if (!settings.enabled() || hivemq != null) {
            return;
        }
        Settings s = settings;
        try {
            if (s.port() <= 0 && s.tlsPort() <= 0 && s.websocketPort() <= 0) {
                throw new IllegalStateException("kein Port (devtools.broker.port, tls-port, websocket-port)");
            }
            if (s.tlsPort() > 0 && s.tlsKeystore().isBlank()) {
                throw new IllegalStateException("TLS-Port ohne devtools.broker.tls.keystore");
            }
            Path conf = Files.createDirectories(home.resolve("conf"));
            Files.writeString(conf.resolve("config.xml"), configXml(s));
            EmbeddedHiveMQ h = EmbeddedHiveMQ.builder()
                    .withConfigurationFolder(conf)
                    .withDataFolder(Files.createDirectories(home.resolve("data")))
                    .withExtensionsFolder(Files.createDirectories(home.resolve("extensions")))
                    .withEmbeddedExtension(extension())
                    .withoutLoggingBootstrap() // Logging der Anwendung nicht umkonfigurieren
                    .build();
            h.start().get(60, TimeUnit.SECONDS);
            hivemq = h;
            status = "läuft (" + listeners(s) + ", Präfix " + s.topicPrefix() + ")";
            LOG.info("MQTT-Broker (HiveMQ CE) {}", status);
        } catch (Exception e) {
            Throwable c = e.getCause() != null && e.getMessage() == null ? e.getCause() : e;
            status = "Start fehlgeschlagen: " + c.getMessage();
            LOG.warn("MQTT-Broker nicht gestartet", e);
        }
    }

    @PreDestroy
    @Override
    public synchronized void close() {
        EmbeddedHiveMQ h = hivemq;
        hivemq = null;
        if (h == null) {
            return;
        }
        try {
            h.stop().get(20, TimeUnit.SECONDS);
            h.close();
        } catch (Exception e) {
            LOG.warn("MQTT-Broker nicht sauber beendet: {}", e.toString());
        }
        status = "aus";
    }

    public synchronized boolean running() {
        return hivemq != null;
    }

    public String status() {
        return status;
    }

    /** Auskunft für die Desktop-Apps. */
    public BrokerInfo info() {
        Settings s = settings;
        boolean on = running();
        return new BrokerInfo(on, s.host().isEmpty() ? null : s.host(), on ? s.port() : 0, on ? s.tlsPort() : 0,
                on ? s.websocketPort() : 0, s.topicPrefix());
    }

    static String configXml(Settings s) {
        String bind = xml(s.bindAddress());
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\"?>\n<hivemq>\n    <listeners>\n");
        if (s.port() > 0) {
            sb.append("        <tcp-listener><port>").append(s.port()).append("</port><bind-address>").append(bind)
                    .append("</bind-address></tcp-listener>\n");
        }
        if (s.tlsPort() > 0) {
            sb.append("        <tls-tcp-listener><port>").append(s.tlsPort()).append("</port><bind-address>")
                    .append(bind).append("</bind-address><tls><keystore><path>").append(xml(s.tlsKeystore()))
                    .append("</path><password>").append(xml(s.tlsKeystorePassword()))
                    .append("</password><private-key-password>")
                    .append(xml(s.tlsKeyPassword().isEmpty() ? s.tlsKeystorePassword() : s.tlsKeyPassword()))
                    .append("</private-key-password></keystore></tls></tls-tcp-listener>\n");
        }
        if (s.websocketPort() > 0) {
            sb.append("        <websocket-listener><port>").append(s.websocketPort()).append("</port><bind-address>")
                    .append(bind).append("</bind-address><path>/mqtt</path><name>websocket</name>")
                    .append("<subprotocols><subprotocol>mqtt</subprotocol></subprotocols>")
                    .append("<allow-extensions>true</allow-extensions></websocket-listener>\n");
        }
        sb.append("    </listeners>\n")
                .append("    <anonymous-usage-statistics><enabled>false</enabled></anonymous-usage-statistics>\n")
                .append("</hivemq>\n");
        return sb.toString();
    }

    private static String listeners(Settings s) {
        StringBuilder sb = new StringBuilder(s.bindAddress());
        if (s.port() > 0) {
            sb.append(" mqtt:").append(s.port());
        }
        if (s.tlsPort() > 0) {
            sb.append(" mqtts:").append(s.tlsPort());
        }
        if (s.websocketPort() > 0) {
            sb.append(" ws:").append(s.websocketPort()).append("/mqtt");
        }
        return sb.toString();
    }

    private static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    // ------------------------------------------------------------------ Anmeldung, Rechte, Absender

    private EmbeddedExtension extension() {
        SimpleAuthenticator authenticator = this::authenticate;
        PublishInboundInterceptor stamp = (input, output) -> {
            String address = input.getConnectionInformation().getConnectionAttributeStore().getAsString(ADDRESS)
                    .orElse("");
            output.getPublishPacket().getUserProperties().removeName(ShareTopics.SENDER_PROPERTY);
            output.getPublishPacket().getUserProperties().addUserProperty(ShareTopics.SENDER_PROPERTY, address);
        };
        return EmbeddedExtension.builder().withId("devtools-broker").withName("DevTools Kooperation")
                .withVersion("1").withAuthor("DevTools MCP").withPriority(1000).withStartPriority(1000)
                .withExtensionMain(new ExtensionMain() {
                    @Override
                    public void extensionStart(ExtensionStartInput in, ExtensionStartOutput out) {
                        Services.securityRegistry().setAuthenticatorProvider(input -> authenticator);
                        Services.initializerRegistry().setClientInitializer(
                                (input, client) -> client.addPublishInboundInterceptor(stamp));
                    }

                    @Override
                    public void extensionStop(ExtensionStopInput in, ExtensionStopOutput out) {
                        // nichts zu tun
                    }
                }).build();
    }

    private void authenticate(SimpleAuthInput input, SimpleAuthOutput output) {
        String token = input.getConnectPacket().getPassword().map(BrokerService::text).orElse("");
        Optional<String> address;
        try {
            address = token.isEmpty() ? Optional.empty() : auth.address(token);
        } catch (RuntimeException e) {
            LOG.warn("MQTT-Broker: Token nicht prüfbar", e);
            address = Optional.empty();
        }
        if (address.isEmpty() || address.get().isEmpty()) {
            output.failAuthentication(ConnackReasonCode.NOT_AUTHORIZED,
                    "Anmeldung mit dem Token des Backends als Passwort (Konto mit E-Mail)");
            return;
        }
        String a = address.get();
        input.getConnectionInformation().getConnectionAttributeStore().putAsString(ADDRESS, a);
        String p = settings.topicPrefix();
        ModifiableDefaultPermissions perms = output.getDefaultPermissions();
        perms.add(permission(ShareTopics.inbox(p, a), TopicPermission.MqttActivity.SUBSCRIBE));
        perms.add(permission(p + "/presence/#", TopicPermission.MqttActivity.SUBSCRIBE));
        perms.add(permission(p + "/presence/" + a + "/+", TopicPermission.MqttActivity.PUBLISH));
        perms.add(permission(p + "/inbox/+", TopicPermission.MqttActivity.PUBLISH));
        perms.setDefaultBehaviour(DefaultAuthorizationBehaviour.DENY);
        output.authenticateSuccessfully();
    }

    private static TopicPermission permission(String filter, TopicPermission.MqttActivity activity) {
        return Builders.topicPermission().topicFilter(filter).activity(activity)
                .type(TopicPermission.PermissionType.ALLOW).build();
    }

    private static String text(ByteBuffer b) {
        byte[] out = new byte[b.remaining()];
        b.duplicate().get(out);
        return new String(out, StandardCharsets.UTF_8);
    }
}
