package systems.grebe.devtools.mcp.modules.jvm;

import java.util.List;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolModule;
import systems.grebe.devtools.mcp.modules.java.JavaEnvironmentProvider;
import systems.grebe.devtools.mcp.modules.java.SdkTools;
import systems.grebe.performance.mcp.PerformanceArea;
import systems.grebe.performance.mcp.PerformanceTools;
import systems.grebe.performance.mcp.settings.JvmSettings;

/** JVM-Diagnose über jcmd bzw. die DiagnosticCommand-MBean (lokal, Container, JMX). */
@Component
public class JvmModule implements ToolModule {

    static final String ALLOWED_COMMANDS = "allowedCommands";

    private final JavaEnvironmentProvider env;

    public JvmModule(JavaEnvironmentProvider env) {
        this.env = env;
    }

    @Override
    public String id() {
        return "jvm";
    }

    @Override
    public String displayName() {
        return PerformanceArea.JVM.displayName();
    }

    @Override
    public String description() {
        return PerformanceArea.JVM.description();
    }

    @Override
    public String instructions() {
        return PerformanceArea.JVM.instructions();
    }

    @Override
    public String briefInstructions() {
        return PerformanceArea.JVM.briefInstructions();
    }

    @Override
    public int order() {
        return PerformanceArea.JVM.order();
    }

    @Override
    public boolean enabledByDefault() {
        return PerformanceArea.JVM.enabledByDefault();
    }

    @Override
    public List<ConfigField> configSchema() {
        return List.of(ConfigField.of(ALLOWED_COMMANDS, "Erlaubte jcmd-Befehle für jvm_jcmd", FieldType.STRING_LIST)
                .withDefault("VM.version\nVM.flags\nVM.command_line\nVM.system_properties\nVM.uptime\nVM.info\n"
                        + "VM.classloader_stats\nVM.metaspace\nVM.native_memory\nVM.dynlibs\nThread.print\n"
                        + "GC.heap_info\nGC.class_histogram\nGC.finalizer_info\nCompiler.codecache\nCompiler.queue\n"
                        + "JFR.check\nVM.log\nCompiler.CodeHeap_Analytics\nSystem.map\nVM.events\nThread.dump_to_file")
                .withHelp("Nur diese Befehle darf das LLM über jvm_jcmd frei aufrufen (erfordert invasive Operationen)."));
    }

    @Override
    public List<ToolCallback> createTools(ModuleConfig config) {
        return SdkTools.adapt(PerformanceTools.jvmTools(env, new JvmSettings(config.getList(ALLOWED_COMMANDS))));
    }
}
