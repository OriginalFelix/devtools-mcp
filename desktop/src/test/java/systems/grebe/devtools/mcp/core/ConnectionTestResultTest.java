package systems.grebe.devtools.mcp.core;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionTestResultTest {

    @Test
    void invalidReportsAllSchemaErrorsOnePerLine() {
        List<ConfigField> schema = List.of(
                ConfigField.of("host", "Host", FieldType.STRING).asRequired(),
                ConfigField.of("port", "Port", FieldType.INT).asRequired());

        ConnectionTestResult result = ConnectionTestResult.invalid(ModuleConfig.of(schema, Map.of()));

        assertThat(result).isNotNull();
        assertThat(result.success()).isFalse();
        assertThat(result.message().lines()).hasSize(2).allMatch(l -> !l.isBlank());
    }

    @Test
    void invalidIsNullForAValidConfiguration() {
        List<ConfigField> schema = List.of(ConfigField.of("host", "Host", FieldType.STRING).asRequired());

        assertThat(ConnectionTestResult.invalid(ModuleConfig.of(schema, Map.of("host", "x")))).isNull();
    }
}
