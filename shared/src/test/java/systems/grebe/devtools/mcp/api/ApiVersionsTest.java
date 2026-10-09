package systems.grebe.devtools.mcp.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class ApiVersionsTest {

    @Test
    void versionFromPath() {
        assertThat(ApiVersions.fromPath("/api/v0/graphql")).isZero();
        assertThat(ApiVersions.fromPath("/api/v12/blobs/uploads")).isEqualTo(12);
        assertThat(ApiVersions.fromPath("/api/v3")).isEqualTo(3);
        assertThat(ApiVersions.fromPath("/graphql")).isEqualTo(ApiVersions.LEGACY);
        assertThat(ApiVersions.fromPath("/blobs/abc")).isEqualTo(ApiVersions.LEGACY);
        assertThat(ApiVersions.fromPath("/api/vx/graphql")).isEqualTo(ApiVersions.LEGACY);
        assertThat(ApiVersions.fromPath(null)).isEqualTo(ApiVersions.LEGACY);
    }

    @Test
    void offeredVersionIsUsedUnderItsPath() {
        assertThat(ApiVersions.base(2)).isEqualTo("/api/v2");
        assertThat(ApiVersions.negotiate(1, new ApiVersions.Info(2, List.of(0, 1, 2)))).isEqualTo("/api/v1");
    }

    @Test
    void backendWithoutVersioningOnlySpeaksLegacy() {
        assertThat(ApiVersions.negotiate(ApiVersions.LEGACY, null)).isEmpty();
        assertThatThrownBy(() -> ApiVersions.negotiate(1, null)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Backend ist zu alt").hasMessageContaining("Team-Server aktualisieren");
    }

    @Test
    void tellsWhichSideToUpdate() {
        assertThatThrownBy(() -> ApiVersions.negotiate(3, new ApiVersions.Info(2, List.of(1, 2))))
                .hasMessageContaining("Backend ist zu alt").hasMessageContaining("[1, 2]");
        assertThatThrownBy(() -> ApiVersions.negotiate(0, new ApiVersions.Info(2, List.of(1, 2))))
                .hasMessageContaining("App ist zu alt").hasMessageContaining("Desktop-App aktualisieren");
    }
}
