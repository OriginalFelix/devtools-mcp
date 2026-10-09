package systems.grebe.devtools.mcp.core;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigChangeTest {

    private static ModuleConfig config(String host) {
        return ModuleConfig.of(List.of(ConfigField.of("host", "Host", FieldType.STRING)), Map.of("host", host));
    }

    @Test
    void reportsOnlyChangedValues() {
        ConfigChange change = new ConfigChange();
        assertThat(change.changed(config("a"))).as("erster Aufruf").isTrue();
        assertThat(change.changed(config("a"))).as("gleiche Werte").isFalse();
        assertThat(change.changed(config("b"))).as("anderer Wert").isTrue();
        assertThat(change.changed(config("b"))).isFalse();
        assertThat(change.changed(config("a"))).as("zurück auf alt").isTrue();
    }
}
