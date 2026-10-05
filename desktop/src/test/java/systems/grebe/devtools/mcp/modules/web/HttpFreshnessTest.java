package systems.grebe.devtools.mcp.modules.web;

import java.net.http.HttpHeaders;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HttpFreshnessTest {

    static final Instant NOW = Instant.parse("2026-10-05T10:00:00Z");

    static HttpFreshness.Result of(String... nameValue) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        for (int i = 0; i < nameValue.length; i += 2) {
            m.computeIfAbsent(nameValue[i], k -> new java.util.ArrayList<>()).add(nameValue[i + 1]);
        }
        return HttpFreshness.of(HttpHeaders.of(m, (a, b) -> true), NOW);
    }

    @Test
    void withoutHeadersUnlimited() {
        assertThat(of()).isEqualTo(HttpFreshness.Result.UNLIMITED);
        assertThat(of("Cache-Control", "public")).isEqualTo(HttpFreshness.Result.UNLIMITED);
    }

    @Test
    void maxAgeMinusAge() {
        HttpFreshness.Result r = of("Cache-Control", "public, max-age=600", "Age", "100");
        assertThat(r.store()).isTrue();
        assertThat(r.expiresAt()).isEqualTo(NOW.plusSeconds(500));
        assertThat(r.rule()).isEqualTo("max-age=600");
    }

    @Test
    void maxAgeBeatsExpiresAndIgnoresSharedMaxAge() {
        HttpFreshness.Result r = of("Cache-Control", "s-maxage=9999, max-age=60",
                "Expires", "Thu, 01 Jan 2099 00:00:00 GMT");
        assertThat(r.expiresAt()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void noStoreAndNoCache() {
        assertThat(of("Cache-Control", "no-store, max-age=600").store()).isFalse();
        HttpFreshness.Result noCache = of("Cache-Control", "No-Cache, max-age=600");
        assertThat(noCache.store()).isTrue();
        assertThat(noCache.expiresAt()).isEqualTo(NOW);
        // no-cache mit Feldnamen betrifft nur diese Header-Felder
        assertThat(of("Cache-Control", "no-cache=\"Set-Cookie\", max-age=60").expiresAt()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void expiresRelativeToDate() {
        // Server-Uhr geht eine Stunde vor: es zählt der Abstand Date → Expires
        HttpFreshness.Result r = of("Date", "Mon, 05 Oct 2026 11:00:00 GMT",
                "Expires", "Mon, 05 Oct 2026 11:30:00 GMT");
        assertThat(r.expiresAt()).isEqualTo(NOW.plusSeconds(1800));
        assertThat(r.rule()).isEqualTo("Expires");
        assertThat(of("Expires", "Mon, 05 Oct 2026 12:00:00 GMT").expiresAt()).isEqualTo(NOW.plusSeconds(7200));
    }

    @Test
    void invalidOrPastExpiresMeansStale() {
        assertThat(of("Expires", "0").expiresAt()).isEqualTo(NOW);
        assertThat(of("Expires", "Thu, 01 Jan 1970 00:00:00 GMT").expiresAt()).isEqualTo(NOW);
        assertThat(of("Cache-Control", "max-age=abc").expiresAt()).isEqualTo(NOW);
    }

    @Test
    void pragmaOnlyWithoutCacheControl() {
        assertThat(of("Pragma", "no-cache").expiresAt()).isEqualTo(NOW);
        assertThat(of("Pragma", "no-cache", "Cache-Control", "max-age=60").expiresAt()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void severalCacheControlHeadersAreJoined() {
        assertThat(of("Cache-Control", "public", "Cache-Control", "max-age=30").expiresAt())
                .isEqualTo(NOW.plusSeconds(30));
    }
}
