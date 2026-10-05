package systems.grebe.devtools.mcp.modules.window.platform;

import java.awt.Rectangle;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScreenMapperTest {

    // Hauptmonitor 4K mit 150 % (User-Space 2560×1440), rechts daneben Full HD mit 100 %
    private static final List<ScreenMapper.Screen> SCREENS = List.of(
            new ScreenMapper.Screen(new Rectangle(0, 0, 2560, 1440), 1.5, 1.5),
            new ScreenMapper.Screen(new Rectangle(3840, 0, 1920, 1080), 1.0, 1.0));

    @Test
    void windowsKeepsTheMonitorOriginAndScalesRelativeToIt() {
        ScreenMapper m = new ScreenMapper(ScreenMapper.Mode.ANCHORED, SCREENS);
        assertThat(m.toUser(new Rectangle(300, 150, 1500, 900))).isEqualTo(new Rectangle(200, 100, 1000, 600));
        assertThat(m.toUser(new Rectangle(4000, 100, 800, 600))).isEqualTo(new Rectangle(4000, 100, 800, 600));
    }

    @Test
    void windowSpanningTwoMonitorsUsesTheOneWithMostArea() {
        ScreenMapper m = new ScreenMapper(ScreenMapper.Mode.ANCHORED, SCREENS);
        // überwiegend auf dem rechten Monitor
        assertThat(m.toUser(new Rectangle(3740, 0, 1000, 500)).x).isEqualTo(3740);
    }

    @Test
    void x11ScalesEverything() {
        ScreenMapper m = new ScreenMapper(ScreenMapper.Mode.SCALED,
                List.of(new ScreenMapper.Screen(new Rectangle(0, 0, 1920, 1080), 2.0, 2.0)));
        assertThat(m.toUser(new Rectangle(200, 100, 800, 600))).isEqualTo(new Rectangle(100, 50, 400, 300));
    }

    @Test
    void macIsIdentity() {
        Rectangle r = new Rectangle(10, 20, 30, 40);
        assertThat(new ScreenMapper(ScreenMapper.Mode.IDENTITY, SCREENS).toUser(r)).isEqualTo(r);
    }
}
