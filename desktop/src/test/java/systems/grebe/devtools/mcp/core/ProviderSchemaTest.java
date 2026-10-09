package systems.grebe.devtools.mcp.core;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderSchemaTest {

    private record Fake(String id) implements ServiceProvider {
        @Override
        public String displayName() {
            return id;
        }
    }

    @Test
    void buildsKeysBelowTheProviderId() {
        assertThat(ProviderSchema.key("jira", "baseUrl")).isEqualTo("jira.baseUrl");
        assertThat(ProviderSchema.enabledKey("jira")).isEqualTo("jira.enabled");
    }

    @Test
    void enabledFallsBackToTheGivenDefaultOnlyWhenUnset() {
        ModuleConfig unset = ModuleConfig.of(List.of(), Map.of());
        assertThat(ProviderSchema.enabled(unset, "x", true)).isTrue();
        assertThat(ProviderSchema.enabled(unset, "x", false)).isFalse();
        ModuleConfig off = ModuleConfig.of(List.of(), Map.of("x.enabled", "false"));
        assertThat(ProviderSchema.enabled(off, "x", true)).isFalse();
    }

    @Test
    void settingsLooksUpFieldsWithoutPrefix() {
        ModuleConfig config = ModuleConfig.of(List.of(), Map.of("x.url", " http://a ", "x.empty", " "));
        assertThat(ProviderSchema.settings(config, "x").apply("url")).contains("http://a");
        assertThat(ProviderSchema.settings(config, "x").apply("empty")).isEmpty();
        assertThat(ProviderSchema.settings(config, "y").apply("url")).isEmpty();
    }

    @Test
    void defaultProviderFieldOffersAutoAndEveryProvider() {
        ConfigField field = ProviderSchema.defaultProviderField("defaultProvider", "Standard", List.of(new Fake("a"), new Fake("b")), "Hilfe");
        assertThat(field.type()).isEqualTo(FieldType.ENUM);
        assertThat(field.defaultValue()).isEqualTo("auto");
        assertThat(field.options()).containsExactly("auto", "a", "b");
        assertThat(field.help()).isEqualTo("Hilfe");
    }
}
