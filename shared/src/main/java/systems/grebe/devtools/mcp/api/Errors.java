package systems.grebe.devtools.mcp.api;

/** Fehlermeldungen für Nutzer und LLM. */
public final class Errors {

    private Errors() {
    }

    /**
     * Meldung der eigentlichen Ursache: folgt der Ursachenkette bis zum Ende (Spring, Reflection und Futures verpacken
     * Ausnahmen) und nimmt deren Meldung; fehlt sie oder ist sie leer, den Klassennamen.
     */
    public static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String msg = root.getMessage();
        return msg == null || msg.isBlank() ? root.getClass().getSimpleName() : msg;
    }
}
