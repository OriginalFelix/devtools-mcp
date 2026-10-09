package systems.grebe.devtools.mcp.modules.skills;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import systems.grebe.devtools.mcp.core.UserConfirmation;
import systems.grebe.devtools.mcp.modules.shares.ShareViews;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@code skills_share}: Freigeben erst nach Zustimmung des Nutzers, Anzeigen und Zurücknehmen ohne Rückfrage. */
class SkillShareToolsTest {

    private final SkillBackend backend = Mockito.mock(SkillBackend.class);
    private final UserConfirmation confirmation = new UserConfirmation();
    private final List<String> asked = new ArrayList<>();

    private void answer(boolean granted) {
        confirmation.setDesktopHandler((title, message) -> {
            asked.add(title + " | " + message);
            return CompletableFuture.completedFuture(granted);
        });
    }

    @Test
    void sharingAsksTheUserFirst() {
        when(backend.share(eq("heap-leak"), any(), eq(false))).thenReturn("freigegeben");
        answer(true);
        SkillShareTools tools = new SkillShareTools(backend, confirmation);

        assertThat(tools.share("heap-leak", List.of("anna"), List.of("Entwickler"), null, null, null))
                .isEqualTo("freigegeben");
        assertThat(asked).singleElement().asString()
                .contains("Skill 'heap-leak' teilen?", "anna", "Rolle Entwickler");
        verify(backend).share("heap-leak", new ShareViews.Request(List.of("anna"), List.of("Entwickler"), false),
                false);
    }

    @Test
    void declinedOrUnaskableSharingIsRefused() {
        answer(false);
        assertThatThrownBy(() -> new SkillShareTools(backend, confirmation)
                .share("heap-leak", null, null, true, null, null)).hasMessageContaining("vom Nutzer abgelehnt");
        assertThatThrownBy(() -> new SkillShareTools(backend, null)
                .share("heap-leak", List.of("anna"), null, null, null, null))
                .hasMessageContaining("keine Rückfrage");
        verify(backend, never()).share(any(), any(), anyBoolean());
    }

    @Test
    void listingAndRevokingNeedNoConfirmation() {
        when(backend.share(any(), any(), anyBoolean())).thenReturn("ok");
        SkillShareTools tools = new SkillShareTools(backend, null);

        assertThat(tools.share("heap-leak", null, null, null, null, null)).isEqualTo("ok");
        assertThat(tools.share("heap-leak", List.of("anna"), null, null, true, null)).isEqualTo("ok");
        assertThat(asked).isEmpty();
    }
}
