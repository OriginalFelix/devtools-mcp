package systems.grebe.devtools.mcp.api;

/**
 * MQTT-Broker des Backends für die Kooperation der Desktop-Apps (Query {@code broker}). Angemeldet wird mit dem Token
 * des Benutzers als Passwort.
 *
 * @param enabled       läuft der Broker
 * @param host          Host für die Clients, {@code null} = Host der Backend-Adresse
 * @param port          MQTT über TCP, 0 = aus
 * @param tlsPort       MQTT über TLS, 0 = aus
 * @param websocketPort MQTT über WebSocket ({@code /mqtt}), 0 = aus
 * @param topicPrefix   Präfix aller Topics der Kooperation
 */
public record BrokerInfo(boolean enabled, String host, int port, int tlsPort, int websocketPort, String topicPrefix) {
}
