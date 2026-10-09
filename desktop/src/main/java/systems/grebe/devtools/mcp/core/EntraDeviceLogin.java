package systems.grebe.devtools.mcp.core;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import tools.jackson.databind.JsonNode;

/**
 * Device-Code-Flow (RFC 8628) gegen Entra ID als öffentlicher Client: Code anfordern, dem Nutzer Adresse und Code nennen,
 * dann im vorgegebenen Abstand abfragen, bis er im Browser bestätigt hat. Gemeinsam für Mail (Exchange Online) und Teams;
 * Token-Ablage, Zustand und Nebenläufigkeit regeln die Aufrufer.
 */
public final class EntraDeviceLogin {

    public static final String GRANT = "urn:ietf:params:oauth:grant-type:device_code";
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    /** Fehlerantwort eines Entra-Endpunkts mit dem Fehlercode ({@code error}). */
    public static class Rejected extends IllegalStateException {
        private final String code;

        public Rejected(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** POST mit Formular an einen Entra-Endpunkt; Fehlerantworten kommen als {@link Rejected}. */
    @FunctionalInterface
    public interface Endpoint {
        JsonNode post(String url, Map<String, String> form);
    }

    /**
     * Ein gestarteter Ablauf.
     *
     * @param text     Anweisung für den Nutzer (Adresse, Code, gültig bis)
     * @param deadline bis wann der Code gilt
     */
    public record Started(String text, Instant deadline, String clientId, String deviceCode, long interval) {

        /**
         * Fragt ab, bis der Nutzer bestätigt hat. Blockiert; ein Interrupt bricht ab.
         *
         * @param prefix wird jeder Meldung vorangestellt (z.B. {@code "Teams: "})
         * @return die Token-Antwort
         */
        public JsonNode awaitToken(Endpoint endpoint, String tokenUrl, String prefix) {
            Map<String, String> form = Map.of("client_id", clientId, "grant_type", GRANT, "device_code", deviceCode);
            long wait = interval;
            while (true) {
                try {
                    Thread.sleep(wait * 1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(prefix + "Anmeldung abgebrochen.", e);
                }
                if (Instant.now().isAfter(deadline)) {
                    throw new IllegalStateException(prefix + "Code abgelaufen – Anmeldung neu starten.");
                }
                try {
                    return endpoint.post(tokenUrl, form);
                } catch (Rejected e) {
                    switch (e.code()) {
                        case "authorization_pending" -> { }
                        case "slow_down" -> wait += 5; // RFC 8628
                        case "authorization_declined" -> throw new IllegalStateException(prefix
                                + "Anmeldung im Browser abgelehnt.", e);
                        case "expired_token", "bad_verification_code" -> throw new IllegalStateException(prefix
                                + "Code abgelaufen oder ungültig – Anmeldung neu starten.", e);
                        default -> throw new IllegalStateException(prefix + "Anmeldung fehlgeschlagen: "
                                + e.getMessage(), e);
                    }
                }
            }
        }
    }

    private EntraDeviceLogin() {
    }

    /**
     * Fordert den Code an.
     *
     * @param prefix      wird jeder Meldung vorangestellt (z.B. {@code "Teams: "})
     * @param accountHint Zusatz zur Anweisung, etwa {@code ", mit dem Konto x anmelden"}; leer = keiner
     */
    public static Started start(Endpoint endpoint, String deviceCodeUrl, String clientId, String scopes, String prefix,
                                String accountHint) {
        JsonNode code;
        try {
            code = endpoint.post(deviceCodeUrl, Map.of("client_id", clientId, "scope", scopes));
        } catch (Rejected e) {
            // häufig: AADSTS7000218 = „Öffentliche Clientflows zulassen“ ist in der App-Registrierung aus
            throw new IllegalStateException(prefix + "Anmeldung konnte nicht starten (" + e.code() + "): " + e.getMessage()
                    + " – Client-ID, Tenant und „Öffentliche Clientflows zulassen“ in der App-Registrierung prüfen.", e);
        }
        String uri = code.path("verification_uri").asString("https://microsoft.com/devicelogin");
        String userCode = code.path("user_code").asString("");
        long expiresIn = code.path("expires_in").asLong(900);
        long interval = Math.max(1, code.path("interval").asLong(5));
        Instant deadline = Instant.now().plusSeconds(expiresIn);
        String text = "Im Browser " + uri + " öffnen und den Code " + userCode + " eingeben (gültig bis "
                + LocalTime.ofInstant(deadline, ZoneId.systemDefault()).format(HHMM) + ")" + accountHint + ".";
        return new Started(text, deadline, clientId, code.path("device_code").asString(""), interval);
    }
}
