package systems.grebe.devtools.mcp.core;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import systems.grebe.devtools.mcp.modules.chat.spi.ChatSettings;
import systems.grebe.devtools.mcp.modules.container.spi.RuntimeSettings;
import systems.grebe.devtools.mcp.modules.ticket.spi.ProviderSettings;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigValuesTest {

    private static final Map<String, String> VALUES = Map.of("name", "  x  ", "blank", "   ", "n", "12", "bad", "zwölf",
            "flag", "true", "lines", "a\n\n b \nc");

    private static void check(ConfigValues v) {
        assertThat(v.get("name")).contains("x");
        assertThat(v.get("blank")).isEmpty();
        assertThat(v.getString("missing", "dflt")).isEqualTo("dflt");
        assertThat(v.getInt("n", 1)).isEqualTo(12);
        assertThat(v.getInt("bad", 7)).isEqualTo(7);
        assertThat(v.getInt("missing", 3)).isEqualTo(3);
        assertThat(v.getBoolean("flag")).isTrue();
        assertThat(v.getBoolean("missing")).isFalse();
        assertThat(v.getBoolean("missing", true)).isTrue();
        assertThat(v.getList("lines")).isEqualTo(List.of("a", "b", "c"));
        assertThat(v.getList("missing")).isEmpty();
    }

    @Test
    void allSettingsClassesShareTheTypedGetters() {
        check(ProviderSettings.of(VALUES));
        check(ChatSettings.of(VALUES));
        check(RuntimeSettings.of(VALUES));
    }

    @Test
    void moduleConfigAddsSchemaDefaults() {
        ConfigField field = ConfigField.of("n", "N", FieldType.INT).withDefault("5");
        ModuleConfig config = ModuleConfig.of(List.of(field), Map.of());
        check(ModuleConfig.of(List.of(field), VALUES));
        assertThat(config.getInt("n", 1)).isEqualTo(5);
    }
}
