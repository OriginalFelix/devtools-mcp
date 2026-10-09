package systems.grebe.devtools.mcp.modules.window;

import java.awt.event.KeyEvent;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeySpecTest {

    @Test
    void parsesCombosAndSequences() {
        List<KeySpec.Combo> combos = KeySpec.parse("ctrl+shift+s  enter alt+F4 ctrl++");
        assertThat(combos).extracting(KeySpec.Combo::modifiers).containsExactly(
                List.of(KeyEvent.VK_CONTROL, KeyEvent.VK_SHIFT), List.of(), List.of(KeyEvent.VK_ALT), List.of(KeyEvent.VK_CONTROL));
        assertThat(combos).extracting(KeySpec.Combo::key).containsExactly(KeyEvent.VK_S, KeyEvent.VK_ENTER,
                KeyEvent.VK_F4, KeyEvent.VK_PLUS);
    }

    @Test
    void unknownNamesAreExplained() {
        assertThatThrownBy(() -> KeySpec.parse("hyper+a")).hasMessageContaining("Unbekannter Modifier");
        assertThatThrownBy(() -> KeySpec.parse("ctrl+frobnicate")).hasMessageContaining("Unbekannte Taste");
    }

    @Test
    void systemKeyDependsOnPlatform() {
        assertThat(KeySpec.parse("win+r").getFirst().usesSystemKey(false)).isTrue();
        assertThat(KeySpec.parse("cmd+s").getFirst().usesSystemKey(true)).isFalse();
        assertThat(KeySpec.parse("cmd+s").getFirst().usesSystemKey(false)).isTrue();
    }

    @Test
    void simpleKeysAreLayoutIndependent() {
        assertThat(KeySpec.simpleKey('q')).isEqualTo(KeyEvent.VK_Q);
        assertThat(KeySpec.simpleKey('Z')).isEqualTo(KeyEvent.VK_Z);
        assertThat(KeySpec.simpleKey('\n')).isEqualTo(KeyEvent.VK_ENTER);
        assertThat(KeySpec.simpleKey('@')).isEqualTo(KeyEvent.VK_UNDEFINED);
        assertThat(KeySpec.simpleKey('ä')).isEqualTo(KeyEvent.VK_UNDEFINED);
    }
}
