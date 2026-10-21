package systems.grebe.devtools.mcp.modules.java;

import java.util.List;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import systems.grebe.devtools.mcp.core.ManagedToolCallback;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolBeans;
import systems.grebe.devtools.mcp.core.ToolModule;
import systems.grebe.devtools.mcp.modules.asprof.AsyncProfilerModule;
import systems.grebe.devtools.mcp.modules.debug.DebugModule;
import systems.grebe.devtools.mcp.modules.jfr.JfrModule;
import systems.grebe.devtools.mcp.modules.jvm.JvmModule;
import systems.grebe.devtools.mcp.modules.visualvm.VisualVmModule;
import systems.grebe.performance.mcp.PerformanceArea;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Die Module jvm, jfr, asprof, visualvm und debug sind dünne Adapter auf das SDK performance-mcp: Tool-Namen,
 * Metadaten und Hinweise müssen denen vor der Auslagerung entsprechen.
 */
class PerformanceSdkModulesTest {

    /** Die 34 Tool-Namen (mit Modulpräfix) vor der Auslagerung in das SDK. */
    private static final List<String> EXPECTED = List.of(
            "asprof_profile", "asprof_start", "asprof_status", "asprof_stop",
            "debug_attach", "debug_clear_breakpoint", "debug_detach", "debug_resume", "debug_sessions",
            "debug_set_breakpoint", "debug_stack", "debug_step", "debug_threads", "debug_variables",
            "debug_wait_for_break",
            "jfr_analyze", "jfr_dump", "jfr_flamegraph", "jfr_record", "jfr_start", "jfr_status", "jfr_stop",
            "jvm_gc_run", "jvm_heap", "jvm_heap_dump", "jvm_info", "jvm_jcmd", "jvm_native_memory", "jvm_processes",
            "jvm_threads",
            "visualvm_heap_analyze", "visualvm_open", "visualvm_open_file", "visualvm_sample_cpu");

    private final JavaEnvironmentProvider env = new JavaEnvironmentProvider(null, null);

    private List<ToolModule> modules() {
        return List.of(new JvmModule(env), new JfrModule(env), new AsyncProfilerModule(env),
                new VisualVmModule(env, null), new DebugModule(env));
    }

    private static ModuleConfig defaults(ToolModule m) {
        return ModuleConfig.of(m.configSchema(), java.util.Map.of());
    }

    @Test
    void toolNamesMatchPreviousList() {
        TreeSet<String> names = new TreeSet<>();
        for (ToolModule m : modules()) {
            for (ToolCallback cb : m.createTools(defaults(m))) {
                names.add(ManagedToolCallback.prefixed(m.id(), cb.getToolDefinition().name()));
            }
        }
        assertThat(EXPECTED).hasSize(34);
        assertThat(names).containsExactlyElementsOf(new TreeSet<>(EXPECTED));
    }

    @Test
    void moduleMetadataComesFromSdkAreas() {
        for (ToolModule m : modules()) {
            PerformanceArea area = PerformanceArea.byId(m.id());
            assertThat(area).as(m.id()).isNotNull();
            assertThat(m.displayName()).isEqualTo(area.displayName());
            assertThat(m.description()).isEqualTo(area.description());
            assertThat(m.instructions()).isEqualTo(area.instructions());
            assertThat(m.order()).isEqualTo(area.order());
            assertThat(m.enabledByDefault()).isEqualTo(area.enabledByDefault());
            assertThat(m.hasTools()).isTrue();
        }
        JavaSettingsModule java = new JavaSettingsModule();
        assertThat(java.id()).isEqualTo(PerformanceArea.JAVA.id());
        assertThat(java.order()).isEqualTo(PerformanceArea.JAVA.order());
        assertThat(java.hasTools()).isFalse();
        assertThat(java.createTools(defaults(java))).isEmpty();
    }

    @Test
    void toolHintsSurviveTheAdapter() {
        var jvm = new JvmModule(env);
        ToolCallback processes = jvm.createTools(defaults(jvm)).stream()
                .filter(cb -> cb.getToolDefinition().name().endsWith("processes")).findFirst().orElseThrow();
        ToolBeans.Hints hints = ToolBeans.hints(processes);
        assertThat(hints).isNotNull();
        assertThat(hints.readOnly()).isTrue();
    }

    @Test
    void settingsMappingKeepsDefaults() {
        var settings = JavaSettingsModule.settings(defaults(new JavaSettingsModule()));
        assertThat(settings.maxArtifacts()).isEqualTo(50);
        assertThat(settings.maxOutputLines()).isEqualTo(400);
        assertThat(settings.allowInvasive()).isTrue();
        assertThat(settings.excludeSelf()).isTrue();
        assertThat(settings.debugHosts()).isEqualTo("localhost|127\\.0\\.0\\.1");
        assertThat(settings.excludeProcesses()).contains("org\\.gradle\\.launcher\\.daemon");
        assertThat(settings.homeDir()).isEqualTo(systems.grebe.devtools.mcp.config.SettingsStore.defaultHome());
    }
}
