package systems.grebe.devtools.mcp.modules.java;

import java.util.List;

import org.springframework.ai.tool.ToolCallback;
import systems.grebe.devtools.mcp.core.ToolBeans;

/**
 * Brücke zum SDK performance-mcp: dessen Tool-Callbacks tragen ihre Hinweise (schreibgeschützt, destruktiv …) in
 * einer eigenen Hülle; hier werden sie in die {@link ToolBeans.Hints} der Plugin-API übersetzt, damit die App sie als
 * MCP-Tool-Annotations meldet. Die Tool-Namen bleiben unpräfixiert – das Präfix {@code <modul>_} setzt die App.
 */
public final class SdkTools {

    private SdkTools() {
    }

    public static List<ToolCallback> adapt(List<ToolCallback> sdkTools) {
        return sdkTools.stream().map(SdkTools::adapt).toList();
    }

    public static ToolCallback adapt(ToolCallback cb) {
        var h = systems.grebe.performance.mcp.tool.ToolBeans.hints(cb);
        return h == null ? cb : ToolBeans.withHints(cb,
                new ToolBeans.Hints(h.title(), h.readOnly(), h.destructive(), h.idempotent(), h.openWorld()));
    }
}
