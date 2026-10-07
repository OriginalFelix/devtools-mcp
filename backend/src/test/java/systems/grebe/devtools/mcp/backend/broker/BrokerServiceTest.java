package systems.grebe.devtools.mcp.backend.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.MqttGlobalPublishFilter;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt5.Mqtt5BlockingClient;
import com.hivemq.client.mqtt.mqtt5.datatypes.Mqtt5UserProperty;
import com.hivemq.client.mqtt.mqtt5.exceptions.Mqtt5SubAckException;
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish;
import com.hivemq.client.mqtt.mqtt5.message.subscribe.suback.Mqtt5SubAckReasonCode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import systems.grebe.devtools.mcp.modules.share.ShareTopics;

/**
 * Broker des Backends (HiveMQ CE, über die Embedded-API gestartet): Anmeldung mit Token, Rechte je Adresse und der
 * Stempel mit dem geprüften Absender.
 */
class BrokerServiceTest {

    private static final Map<String, String> TOKENS = Map.of("t-felix", "felix@example.com", "t-anna",
            "Anna@Firma.de");

    @TempDir
    static Path dir;
    private static BrokerService broker;
    private static int port;

    @BeforeAll
    static void start() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        broker = new BrokerService(new BrokerService.Settings(true, "127.0.0.1", port, 0, 0, "team", "", "", "", ""),
                token -> Optional.ofNullable(TOKENS.get(token)).map(ShareTopics::address), dir);
        broker.start();
        assertThat(broker.running()).as(broker.status()).isTrue();
    }

    @AfterAll
    static void stop() {
        broker.close();
    }

    @Test
    void reportsItselfToTheDesktopApps() {
        assertThat(broker.info()).satisfies(i -> {
            assertThat(i.enabled()).isTrue();
            assertThat(i.port()).isEqualTo(port);
            assertThat(i.tlsPort()).isZero();
            assertThat(i.topicPrefix()).isEqualTo("team");
            assertThat(i.host()).isNull();
        });
        assertThat(broker.status()).contains("läuft", "mqtt:" + port, "Präfix team");
    }

    @Test
    void rejectsUnknownOrMissingToken() {
        assertThatThrownBy(() -> client("x", "falsch").connect()).hasMessageContaining("NOT_AUTHORIZED");
        assertThatThrownBy(() -> MqttClient.builder().useMqttVersion5().serverHost("127.0.0.1").serverPort(port)
                .buildBlocking().connect()).hasMessageContaining("NOT_AUTHORIZED");
    }

    @Test
    void everyoneReadsOnlyTheirOwnInboxAndAnnouncesOnlyThemselves() {
        Mqtt5BlockingClient anna = client("anna", "t-anna");
        anna.connect();
        try {
            assertThat(subscribe(anna, "team/inbox/anna@firma.de")).isEqualTo(Mqtt5SubAckReasonCode.GRANTED_QOS_1);
            assertThat(subscribe(anna, "team/inbox/felix@example.com")).isEqualTo(Mqtt5SubAckReasonCode.NOT_AUTHORIZED);
            assertThat(subscribe(anna, "team/inbox/+")).isEqualTo(Mqtt5SubAckReasonCode.NOT_AUTHORIZED);
            assertThat(subscribe(anna, "andere/#")).isEqualTo(Mqtt5SubAckReasonCode.NOT_AUTHORIZED);
            assertThat(subscribe(anna, "team/presence/+/+")).isEqualTo(Mqtt5SubAckReasonCode.GRANTED_QOS_1);
            assertThat(anna.publishWith().topic("team/presence/anna@firma.de/x").payload(new byte[]{1})
                    .qos(MqttQos.AT_LEAST_ONCE).send().getError()).isEmpty();
            // fremde Anwesenheit melden: HiveMQ lehnt ab und trennt den Client
            boolean refused;
            try {
                refused = anna.publishWith().topic("team/presence/felix@example.com/x").payload(new byte[]{1})
                        .qos(MqttQos.AT_LEAST_ONCE).send().getError().isPresent();
            } catch (RuntimeException e) {
                refused = true;
            }
            long deadline = System.currentTimeMillis() + 5_000;
            while (!refused && anna.getState().isConnected() && System.currentTimeMillis() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(refused || !anna.getState().isConnected()).isTrue();
        } finally {
            try {
                anna.disconnect();
            } catch (RuntimeException ignored) {
                // vom Broker schon getrennt
            }
        }
    }

    @Test
    void stampsTheVerifiedSenderAndReplacesAForgedOne() throws Exception {
        Mqtt5BlockingClient felix = client("felix", "t-felix");
        Mqtt5BlockingClient anna = client("anna", "t-anna");
        felix.connect();
        anna.connect();
        BlockingQueue<Mqtt5Publish> received = new LinkedBlockingQueue<>();
        felix.toAsync().publishes(MqttGlobalPublishFilter.ALL, received::add);
        try {
            felix.subscribeWith().topicFilter("team/inbox/felix@example.com").qos(MqttQos.AT_LEAST_ONCE).send();

            anna.publishWith().topic("team/inbox/felix@example.com").payload("hallo".getBytes(StandardCharsets.UTF_8))
                    .qos(MqttQos.AT_LEAST_ONCE).userProperties().add(ShareTopics.SENDER_PROPERTY, "chef@firma.de")
                    .applyUserProperties().send();

            Mqtt5Publish p = received.poll(10, TimeUnit.SECONDS);
            assertThat(p).isNotNull();
            List<String> senders = p.getUserProperties().asList().stream()
                    .filter(u -> u.getName().toString().equals(ShareTopics.SENDER_PROPERTY))
                    .map(Mqtt5UserProperty::getValue).map(Object::toString).toList();
            assertThat(senders).containsExactly("anna@firma.de");
        } finally {
            felix.disconnect();
            anna.disconnect();
        }
    }

    @Test
    void writesListenersIntoTheConfiguration() {
        String xml = BrokerService.configXml(new BrokerService.Settings(true, "0.0.0.0", 1883, 8883, 8000, null, null,
                "/etc/keys/broker.p12", "geh<eim", ""));
        assertThat(xml).contains("<tcp-listener><port>1883</port><bind-address>0.0.0.0</bind-address>",
                "<tls-tcp-listener><port>8883</port>", "<path>/etc/keys/broker.p12</path>",
                "<password>geh&lt;eim</password>", "<private-key-password>geh&lt;eim</private-key-password>",
                "<websocket-listener><port>8000</port>", "<path>/mqtt</path>",
                "<anonymous-usage-statistics><enabled>false</enabled>");
        assertThat(new BrokerService.Settings(true, null, 1883, 0, 0, "a/+", null, "", "", "").topicPrefix())
                .isEqualTo(ShareTopics.DEFAULT_PREFIX);
    }

    /** Reason-Code des Abos; der Blocking-Client wirft bei Ablehnung. */
    private static Mqtt5SubAckReasonCode subscribe(Mqtt5BlockingClient c, String filter) {
        try {
            return c.subscribeWith().topicFilter(filter).qos(MqttQos.AT_LEAST_ONCE).send().getReasonCodes().getFirst();
        } catch (Mqtt5SubAckException e) {
            return e.getMqttMessage().getReasonCodes().getFirst();
        }
    }

    private static Mqtt5BlockingClient client(String id, String token) {
        return MqttClient.builder().useMqttVersion5().identifier(id + "-" + System.nanoTime())
                .serverHost("127.0.0.1").serverPort(port)
                .simpleAuth().username(id).password(token.getBytes(StandardCharsets.UTF_8)).applySimpleAuth()
                .buildBlocking();
    }
}
