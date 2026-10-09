package systems.grebe.devtools.mcp.core;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserConfirmationTest {

    private static final UserConfirmation.Channel APP = UserConfirmation.Channel.APP;

    @Test
    void parsesTheChannelSettingAndFallsBackToAuto() {
        assertThat(UserConfirmation.Channel.parse("client")).isEqualTo(UserConfirmation.Channel.CLIENT);
        assertThat(UserConfirmation.Channel.parse(" APP ")).isEqualTo(UserConfirmation.Channel.APP);
        assertThat(UserConfirmation.Channel.parse("auto")).isEqualTo(UserConfirmation.Channel.AUTO);
        assertThat(UserConfirmation.Channel.parse("unbekannt")).isEqualTo(UserConfirmation.Channel.AUTO);
        assertThat(UserConfirmation.Channel.parse(null)).isEqualTo(UserConfirmation.Channel.AUTO);
    }

    @Test
    void requirePassesOnlyWhenTheUserAgrees() {
        UserConfirmation confirmation = new UserConfirmation();
        confirmation.setDesktopHandler((title, message) -> CompletableFuture.completedFuture(true));
        assertThatCode(() -> UserConfirmation.require(confirmation, null, APP, "T", "Frage", "Nicht gesendet", "Hinweis."))
                .doesNotThrowAnyException();

        confirmation.setDesktopHandler((title, message) -> CompletableFuture.completedFuture(false));
        assertThatThrownBy(() -> UserConfirmation.require(confirmation, null, APP, "T", "Frage", "Nicht gesendet", "Hinweis."))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Nicht gesendet: vom Nutzer abgelehnt (DevTools-App). "
                        + "Nicht erneut versuchen, ohne dass der Nutzer es ausdrücklich will.");
    }

    @Test
    void requireExplainsWhenNobodyCanBeAsked() {
        UserConfirmation noUi = new UserConfirmation();
        assertThatThrownBy(() -> UserConfirmation.require(noUi, null, APP, "T", "Frage", "Nicht gesendet", "Der Nutzer kann X."))
                .hasMessage("Nicht gesendet: keine Rückfrage möglich (die DevTools-App hat keine Oberfläche für "
                        + "Rückfragen). Der Nutzer kann X.");
        assertThatThrownBy(() -> UserConfirmation.require(null, null, APP, "T", "Frage", "Nicht gesendet", "Hinweis."))
                .hasMessage("Nicht gesendet: keine Rückfrage beim Nutzer möglich.");
    }
}
