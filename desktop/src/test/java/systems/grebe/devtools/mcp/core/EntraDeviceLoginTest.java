package systems.grebe.devtools.mcp.core;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EntraDeviceLoginTest {

    private static final JsonMapper JSON = JsonMapper.shared();

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }

    private static final String CODE = "{\"device_code\":\"dev\",\"user_code\":\"ABC-123\","
            + "\"verification_uri\":\"https://example.test/login\",\"expires_in\":600,\"interval\":1}";

    @Test
    void startsWithTheUsersInstructionAndPollsUntilConfirmed() {
        List<String> calls = new ArrayList<>();
        EntraDeviceLogin.Endpoint endpoint = (url, form) -> {
            calls.add(url + " " + form.get("grant_type"));
            if (url.endsWith("/devicecode")) {
                assertThat(form).containsEntry("client_id", "app").containsEntry("scope", "a b");
                return json(CODE);
            }
            assertThat(form).containsEntry("device_code", "dev");
            if (calls.size() == 2) {
                throw new EntraDeviceLogin.Rejected("authorization_pending", "warten");
            }
            return json("{\"access_token\":\"tok\"}");
        };
        EntraDeviceLogin.Started flow = EntraDeviceLogin.start(endpoint, "https://x/devicecode", "app", "a b", "T: ",
                ", mit dem Konto u anmelden");
        assertThat(flow.text()).startsWith("Im Browser https://example.test/login öffnen und den Code ABC-123 eingeben (gültig bis ")
                .endsWith("), mit dem Konto u anmelden.");

        JsonNode token = flow.awaitToken(endpoint, "https://x/token", "T: ");

        assertThat(token.path("access_token").asString()).isEqualTo("tok");
        assertThat(calls).hasSize(3).last().isEqualTo("https://x/token " + EntraDeviceLogin.GRANT);
    }

    @Test
    void refusalsCarryThePrefixAndCause() {
        EntraDeviceLogin.Endpoint declined = (url, form) -> {
            if (url.endsWith("/devicecode")) {
                return json(CODE);
            }
            throw new EntraDeviceLogin.Rejected("authorization_declined", "nein");
        };
        EntraDeviceLogin.Started flow = EntraDeviceLogin.start(declined, "https://x/devicecode", "app", "s", "T: ", "");
        assertThat(flow.text()).endsWith(").");
        assertThatThrownBy(() -> flow.awaitToken(declined, "https://x/token", "T: "))
                .isInstanceOf(IllegalStateException.class).hasMessage("T: Anmeldung im Browser abgelehnt.")
                .hasCauseInstanceOf(EntraDeviceLogin.Rejected.class);

        EntraDeviceLogin.Endpoint refusing = (url, form) -> {
            throw new EntraDeviceLogin.Rejected("unauthorized_client", "AADSTS7000218");
        };
        assertThatThrownBy(() -> EntraDeviceLogin.start(refusing, "https://x/devicecode", "app", "s", "T: ", ""))
                .hasMessageStartingWith("T: Anmeldung konnte nicht starten (unauthorized_client): AADSTS7000218")
                .hasMessageContaining("Öffentliche Clientflows zulassen");

        EntraDeviceLogin.Endpoint other = (url, form) -> {
            if (url.endsWith("/devicecode")) {
                return json(CODE);
            }
            throw new EntraDeviceLogin.Rejected("server_error", "kaputt");
        };
        EntraDeviceLogin.Started again = EntraDeviceLogin.start(other, "https://x/devicecode", "app", "s", "", "");
        assertThatThrownBy(() -> again.awaitToken(other, "https://x/token", ""))
                .hasMessage("Anmeldung fehlgeschlagen: kaputt");
    }

    @Test
    void expiredCodeStopsPolling() {
        EntraDeviceLogin.Started flow = new EntraDeviceLogin.Started("t", java.time.Instant.now().minusSeconds(1), "app", "dev", 1);
        assertThatThrownBy(() -> flow.awaitToken((url, form) -> {
            throw new AssertionError("darf nicht mehr abfragen");
        }, "https://x/token", "T: ")).hasMessage("T: Code abgelaufen – Anmeldung neu starten.");
    }
}
