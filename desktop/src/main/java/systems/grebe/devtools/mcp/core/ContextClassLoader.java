package systems.grebe.devtools.mcp.core;

import java.util.function.Supplier;

/**
 * Führt Code mit einem anderen Context-ClassLoader des Threads aus und stellt den vorigen danach wieder her - für Plugins,
 * Skripte und JDBC-Treiber, die Klassen und Ressourcen über den Context-ClassLoader suchen.
 */
public final class ContextClassLoader {

    /** Wie {@link Supplier}, darf aber eine (geprüfte) Ausnahme werfen. */
    @FunctionalInterface
    public interface ThrowingSupplier<T, E extends Throwable> {
        T get() throws E;
    }

    private ContextClassLoader() {
    }

    public static <T> T call(ClassLoader loader, Supplier<T> body) {
        return callChecked(loader, body::get);
    }

    public static void run(ClassLoader loader, Runnable body) {
        call(loader, () -> {
            body.run();
            return null;
        });
    }

    public static <T, E extends Throwable> T callChecked(ClassLoader loader, ThrowingSupplier<T, E> body) throws E {
        Thread t = Thread.currentThread();
        ClassLoader previous = t.getContextClassLoader();
        t.setContextClassLoader(loader);
        try {
            return body.get();
        } finally {
            t.setContextClassLoader(previous);
        }
    }
}
