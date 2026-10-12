package systems.grebe.devtools.mcp.core;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SignatureTest {

    @Test
    void appendsRenderedTemplateWithBlankLine() {
        assertThat(Signature.sign("  Hallo\n", Signature.DEFAULT_TEMPLATE, "Claude Opus 5.5"))
                .isEqualTo("Hallo\n\n🤖 Generated with Claude Opus 5.5");
        assertThat(Signature.sign("Hallo", "_via {AI_MODEL_NAME} (DevTools)_", null))
                .isEqualTo("Hallo\n\n_via AI (DevTools)_");
        assertThat(Signature.sign(null, Signature.DEFAULT_TEMPLATE, "X")).isEqualTo("🤖 Generated with X");
    }

    @Test
    void emptyTemplateLeavesTextAlone() {
        assertThat(Signature.sign(" Hallo ", "", "X")).isEqualTo("Hallo");
        assertThat(Signature.sign(null, null, "X")).isEmpty();
    }

    @Test
    void replacesExistingSignatureInsteadOfDuplicating() {
        String own = "Beschreibung\n\n🤖 Generated with [Claude Code](https://claude.com/claude-code)\n";
        assertThat(Signature.sign(own, Signature.DEFAULT_TEMPLATE, "Claude Opus 5.5"))
                .isEqualTo("Beschreibung\n\n🤖 Generated with Claude Opus 5.5");
        String signed = Signature.sign("Text", "(via DevTools)", null);
        assertThat(Signature.sign(signed, "(via DevTools)", null)).isEqualTo(signed);
        // nur am Ende – im Text bleibt eine gleichlautende Zeile stehen
        assertThat(Signature.sign("🤖 Generated with Hand\nweiter", Signature.DEFAULT_TEMPLATE, "Y"))
                .isEqualTo("🤖 Generated with Hand\nweiter\n\n🤖 Generated with Y");
    }

    @Test
    void readableClientNames() {
        assertThat(Signature.clientName("claude-code")).isEqualTo("Claude Code");
        assertThat(Signature.clientName("claude-ai")).isEqualTo("Claude");
        assertThat(Signature.clientName("Visual Studio Code")).isEqualTo("Visual Studio Code");
        assertThat(Signature.clientName(" ")).isNull();
        assertThat(SignatureModule.modelName(" Claude Sonnet 4.5 ", null)).isEqualTo("Claude Sonnet 4.5");
        assertThat(SignatureModule.modelName(null, null)).isNull();
    }

    @Test
    void moduleSwitchAndDefault() {
        SignatureModule m = new SignatureModule();
        assertThat(SignatureModule.template(ModuleConfig.of(m.configSchema(), Map.of())))
                .isEqualTo(Signature.DEFAULT_TEMPLATE);
        assertThat(SignatureModule.template(ModuleConfig.of(m.configSchema(), Map.of("template", "x {AI_MODEL_NAME}"))))
                .isEqualTo("x {AI_MODEL_NAME}");
        assertThat(SignatureModule.template(ModuleConfig.of(m.configSchema(), Map.of("active", "false")))).isEmpty();
    }
}
