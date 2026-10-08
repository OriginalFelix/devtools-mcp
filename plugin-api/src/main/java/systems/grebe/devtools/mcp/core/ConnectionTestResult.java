package systems.grebe.devtools.mcp.core;

/** Ergebnis von {@link ToolModule#testConnection(ModuleConfig)}. */
public record ConnectionTestResult(boolean success, String message) {

    public static ConnectionTestResult ok(String message) {
        return new ConnectionTestResult(true, message);
    }

    public static ConnectionTestResult failed(String message) {
        return new ConnectionTestResult(false, message);
    }
}
