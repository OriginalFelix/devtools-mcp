package systems.grebe.devtools.mcp.core;

/** Ergebnis von {@link ToolModule#testConnection(ModuleConfig)}. */
public record ConnectionTestResult(boolean success, String message) {

    public static ConnectionTestResult ok(String message) {
        return new ConnectionTestResult(true, message);
    }

    public static ConnectionTestResult failed(String message) {
        return new ConnectionTestResult(false, message);
    }

    /**
     * Prüft die Konfiguration gegen das Schema ({@link ModuleConfig#validate()}) - der Anfang jedes Verbindungstests.
     *
     * @return das Fehlschlag-Ergebnis mit allen Fehlern (eine je Zeile) oder {@code null}, wenn die Konfiguration gültig ist
     */
    public static ConnectionTestResult invalid(ModuleConfig config) {
        java.util.List<String> errors = config.validate();
        return errors.isEmpty() ? null : failed(String.join("\n", errors));
    }
}
