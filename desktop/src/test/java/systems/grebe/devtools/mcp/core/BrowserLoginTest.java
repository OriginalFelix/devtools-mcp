package systems.grebe.devtools.mcp.core;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BrowserLoginTest {

    @Test
    void findsTheFirstAddressWithoutTrailingPunctuation() {
        assertThat(BrowserLogin.firstUrl("Im Browser https://microsoft.com/devicelogin öffnen und Code AB eingeben."))
                .isEqualTo("https://microsoft.com/devicelogin");
        assertThat(BrowserLogin.firstUrl("Adresse (https://example.test/x), dann Code")).isEqualTo("https://example.test/x");
        assertThat(BrowserLogin.firstUrl("keine Adresse")).isNull();
    }

    @Test
    void returnsThePromptWhileTheLoginKeepsRunning() throws Exception {
        CountDownLatch finish = new CountDownLatch(1);
        try {
            String prompt = BrowserLogin.start("test-login", report -> {
                report.accept("Code ABC");
                try {
                    finish.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "angemeldet";
            });
            assertThat(prompt).isEqualTo("Code ABC");
        } finally {
            finish.countDown();
        }
    }

    @Test
    void failuresBeforeThePromptAreThrownAsTheyAre() {
        assertThatThrownBy(() -> BrowserLogin.start("test-login", report -> {
            throw new IllegalArgumentException("Client-ID fehlt");
        })).isInstanceOf(IllegalArgumentException.class).hasMessage("Client-ID fehlt");
    }

    @Test
    void anImmediateResultIsReturnedWhenThereWasNoPrompt() {
        assertThat(BrowserLogin.start("test-login", report -> "schon angemeldet")).isEqualTo("schon angemeldet");
    }
}
