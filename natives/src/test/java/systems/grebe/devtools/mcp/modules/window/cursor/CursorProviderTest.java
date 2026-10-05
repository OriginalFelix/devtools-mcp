package systems.grebe.devtools.mcp.modules.window.cursor;

import org.junit.jupiter.api.Test;
import oshi.PlatformEnum;

import static org.assertj.core.api.Assertions.assertThat;

class CursorProviderTest {

    @Test
    void findsOneProviderPerSupportedPlatform() {
        assertThat(CursorProvider.all()).extracting(CursorProvider::platform)
                .containsExactlyInAnyOrder(PlatformEnum.WINDOWS, PlatformEnum.MACOS, PlatformEnum.LINUX);
        assertThat(CursorProvider.forPlatform(PlatformEnum.AIX)).isEmpty();
    }

    @Test
    void currentMatchesThisMachine() {
        // nur auf den unterstützten Systemen gibt es einen; laden darf dabei noch keine nativen Bibliotheken
        PlatformEnum here = PlatformEnum.getCurrentPlatform();
        if (here == PlatformEnum.WINDOWS || here == PlatformEnum.MACOS || here == PlatformEnum.LINUX) {
            assertThat(CursorProvider.current().platform()).isEqualTo(here);
        }
    }
}
