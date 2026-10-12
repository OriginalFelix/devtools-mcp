package systems.grebe.devtools.mcp.remote;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import systems.grebe.devtools.mcp.api.CredentialInfo;
import systems.grebe.devtools.mcp.config.ModuleSettings;
import systems.grebe.devtools.mcp.core.CredentialsModule;
import systems.grebe.devtools.mcp.core.ModuleConfig;

import static org.assertj.core.api.Assertions.assertThat;

/** Zugangsdaten zwischen Formular und Backend sowie die Auswahl der zu erneuernden Tokens. */
class CredentialSyncTest {

    private final CredentialsModule module = new CredentialsModule();

    private static CredentialInfo oauth(long id, String name, String secret, String expires) {
        return new CredentialInfo(id, name, "https://gitlab.example.com", null, secret, null, CredentialInfo.OAUTH,
                null, "app", "client-geheim", "api", null, expires);
    }

    @Test
    void formRoundTripKeepsIdsAndLeavesOutOAuthAccessTokens() {
        List<CredentialInfo> current = List.of(new CredentialInfo(7L, "GitHub", "https://github.com", null, "ghp", null),
                oauth(8, "GitLab", "at-aktuell", "2026-10-12T12:00:00Z"));

        ModuleSettings form = BackendSettingsResolver.credentials(module, current);
        List<Map<String, String>> records = ModuleConfig.of(module.configSchema(), form.values())
                .getRecords(CredentialsModule.ENTRIES);
        assertThat(records).extracting(r -> r.get(CredentialsModule.AUTH)).containsExactly("token", "oauth");
        assertThat(records.get(1)).containsEntry(CredentialsModule.CLIENT_ID, "app")
                .containsEntry(CredentialsModule.CLIENT_SECRET, "client-geheim");

        List<CredentialInfo> saved = BackendSettingsResolver.credentials(module, form, current);
        assertThat(saved).extracting(CredentialInfo::id).containsExactly(7L, 8L);
        assertThat(saved.get(0).secret()).isEqualTo("ghp");
        // das Access-Token kann inzwischen erneuert sein – das Backend behält seins
        assertThat(saved.get(1).secret()).isNull();
        assertThat(saved.get(1).auth()).isEqualTo(CredentialInfo.OAUTH);
        assertThat(saved.get(1).clientSecret()).isEqualTo("client-geheim");
    }

    @Test
    void newEntriesHaveNoId() {
        ModuleSettings form = BackendSettingsResolver.credentials(module,
                List.of(new CredentialInfo(1L, "Neu", "https://x.example.com", null, "t", null)));
        assertThat(BackendSettingsResolver.credentials(module, form, List.of()))
                .singleElement().satisfies(c -> assertThat(c.id()).isNull());
    }

    @Test
    void refreshesLoggedInOAuthTokensShortlyBeforeTheyExpire() {
        Instant now = Instant.parse("2026-10-12T10:00:00Z");
        List<CredentialInfo> due = CredentialRefresher.due(List.of(
                oauth(1, "bald", "at", "2026-10-12T10:04:00Z"),
                oauth(2, "abgelaufen", "at", "2026-10-12T09:00:00Z"),
                oauth(3, "später", "at", "2026-10-12T11:00:00Z"),
                oauth(4, "läuft nicht ab", "at", null),
                oauth(5, "nicht angemeldet", "", "2026-10-12T10:01:00Z"),
                new CredentialInfo(6L, "Token", "https://github.com", null, "ghp", null)), now);
        assertThat(due).extracting(CredentialInfo::name).containsExactly("bald", "abgelaufen");
    }
}
