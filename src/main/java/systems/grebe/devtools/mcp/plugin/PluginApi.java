package systems.grebe.devtools.mcp.plugin;

/** Versionsstand der Plugin-API. */
public final class PluginApi {

    /**
     * Wird erhöht, wenn die API um Methoden wächst, die ältere Apps nicht kennen. Plugins mit höherer
     * {@code api-version} als diese werden abgewiesen, ältere laufen weiter.
     */
    public static final int VERSION = 1;

    private PluginApi() {
    }
}
