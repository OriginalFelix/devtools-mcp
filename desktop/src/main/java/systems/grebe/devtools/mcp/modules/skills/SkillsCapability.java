package systems.grebe.devtools.mcp.modules.skills;

import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.cfg.ContextAttributes;
import tools.jackson.databind.annotation.JsonAppend;
import tools.jackson.databind.json.JsonMapper;

/**
 * Deklariert die Skills-Extension in den Server-Capabilities: {@code "extensions":
 * {"io.modelcontextprotocol/skills": {}}} im {@code initialize}-Ergebnis (umgesetzt von {@link SkillResources}).
 *
 * <p>shortcut: {@code ServerCapabilities} des SDK (2.0) kennt kein Feld {@code extensions}, deshalb hängt ein Jackson-Mixin
 * es beim Serialisieren an – auf jedem JsonMapper-Bean: die {@code initialize}-Antwort schreibt der WebMvc-Transport
 * als Body über den Message-Converter von Spring MVC (Boot-Mapper), andere Nachrichten über {@code mcpServerJsonMapper}.
 * Außerhalb von {@code ServerCapabilities} wirkt das Mixin nicht. Nach einem SDK-Update mit {@code extensions} das
 * Mixin entfernen und die Capability direkt setzen.
 */
@Component
public class SkillsCapability implements BeanPostProcessor {

    static final String EXTENSION = "io.modelcontextprotocol/skills";
    static final String ATTRIBUTE = "devtools.mcp.extensions";

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        return bean instanceof JsonMapper mapper ? withExtensions(mapper) : bean;
    }

    static JsonMapper withExtensions(JsonMapper mapper) {
        return mapper.rebuild().addMixIn(McpSchema.ServerCapabilities.class, Mixin.class)
                .defaultAttributes(ContextAttributes.getEmpty().withSharedAttribute(ATTRIBUTE,
                        Map.of(EXTENSION, Map.of())))
                .build();
    }

    @JsonAppend(attrs = @JsonAppend.Attr(value = ATTRIBUTE, propName = "extensions"))
    interface Mixin {
    }
}
