package systems.grebe.devtools.mcp.modules.jvm;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolModule;
import systems.grebe.devtools.mcp.modules.java.JavaEnvironmentProvider;

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
        return "JVM-Diagnose (jcmd)";
    }

    @Override
    public String description() {
        return "Laufende JVMs finden und untersuchen: Version/Flags, Thread-Dumps mit Deadlock-Erkennung, Heap und "
                + "Klassenhistogramm, Native Memory, Heap-Dumps. Ziele: lokal, im Container oder über JMX "
                + "(siehe Java-Grundeinstellungen).";
    }

    @Override
    public String instructions() {
        return """
                Für laufende JVMs diese Tools statt `jps`, `jcmd`, `jstack`, `jmap` oder `jinfo` in der Shell verwenden:
                - `jvm_processes` zuerst: liefert die gültigen Ziele (PID, Name, `container:<name>`, `jmx:<alias>`).
                - `jvm_info` (statt `jinfo`/`jcmd VM.flags`), `jvm_threads` (statt `jstack`, inkl. Deadlock-Erkennung), \
                `jvm_heap` (statt `jmap -histo`), `jvm_native_memory` (statt `jcmd VM.native_memory`).
                - Invasiv, nur wenn angeboten und nötig: `jvm_heap_dump` (statt `jmap -dump`), `jvm_gc_run`, `jvm_jcmd`.
                PIDs ändern sich nach jedem Neustart der Ziel-JVM – vor Aufrufen mit PID erneut `jvm_processes` abfragen.""";
    }

    @Override
    public String briefInstructions() {
        return "jvm_processes zuerst (PIDs ändern sich nach jedem Neustart), dann jvm_info, jvm_threads, "
                + "jvm_heap.";
    }

    @Override
    public int order() {
        return 210;
    }

    @Override
    public boolean enabledByDefault() {
        return true;
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
        List<ToolCallback> tools = new ArrayList<>(List.of(ToolCallbacks.from(new JvmTools(env))));
        tools.addAll(List.of(ToolCallbacks.from(new JvmInvasiveTools(env, config.getList(ALLOWED_COMMANDS)))));
        return tools;
    }
}
