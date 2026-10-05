package systems.grebe.devtools.mcp.modules.scripts;

/** Vorlagen für neue Skripte – in der Desktop-App und in der Web-UI des Team-Servers. */
public final class ScriptTemplates {

    private ScriptTemplates() {
    }

    /** Fester Text der Beschreibung in den Vorlagen – wer ihn nicht ändert, hat noch nichts angepasst. */
    public static final String PLACEHOLDER_DESCRIPTION = "Was die Tools dieses Skripts können";

    public static final String GROOVY = """
            // devtools: compileStatic
            module {
                description '%s'
            }

            tool('hello') {
                description 'Begrüßt jemanden'
                param 'who', String, 'Wen begrüßen'
                readOnly true
                execute { args ->
                    "Hallo ${args.who}!"
                }
            }
            """.formatted(PLACEHOLDER_DESCRIPTION);

    public static final String JAVA = """
            import java.util.List;

            import org.springframework.ai.tool.ToolCallback;
            import org.springframework.ai.tool.annotation.Tool;
            import org.springframework.ai.tool.annotation.ToolParam;
            import systems.grebe.devtools.mcp.core.ConfigField;
            import systems.grebe.devtools.mcp.core.FieldType;
            import systems.grebe.devtools.mcp.core.ModuleConfig;
            import systems.grebe.devtools.mcp.core.ToolBeans;
            import systems.grebe.devtools.mcp.core.ToolHints;
            import systems.grebe.devtools.mcp.core.ToolModule;

            public class Hello implements ToolModule {

                @Override
                public String id() {
                    return "hello"; // wird durch den Skriptnamen ersetzt
                }

                @Override
                public String displayName() {
                    return "Begrüßungen";
                }

                @Override
                public String description() {
                    return "%s";
                }

                @Override
                public List<ConfigField> configSchema() {
                    return List.of(ConfigField.of("greeting", "Gruß", FieldType.STRING).withDefault("Hallo"));
                }

                @Override
                public List<ToolCallback> createTools(ModuleConfig config) {
                    return ToolBeans.callbacks(new Tools(config.getString("greeting", "Hallo")));
                }

                public static class Tools {
                    private final String greeting;

                    Tools(String greeting) {
                        this.greeting = greeting;
                    }

                    @Tool(name = "hello", description = "Begrüßt jemanden")
                    @ToolHints(readOnly = true)
                    public String hello(@ToolParam(description = "Wen begrüßen") String who) {
                        return greeting + " " + who + "!";
                    }
                }
            }
            """.formatted(PLACEHOLDER_DESCRIPTION);

    public static String of(ScriptViews.Language language) {
        return language == ScriptViews.Language.JAVA ? JAVA : GROOVY;
    }
}
