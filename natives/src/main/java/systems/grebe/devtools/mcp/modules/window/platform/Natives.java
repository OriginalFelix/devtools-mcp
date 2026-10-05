package systems.grebe.devtools.mcp.modules.window.platform;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

/** Kleine Helfer für die FFM-Aufrufe der Fenstersysteme. */
final class Natives {

    private Natives() {
    }

    static MethodHandle bind(Linker linker, SymbolLookup lib, String name, FunctionDescriptor fd) {
        return linker.downcallHandle(lib.findOrThrow(name), fd);
    }

    /** Wie {@link #bind}, aber {@code null}, wenn es die Funktion (in dieser Systemversion) nicht gibt. */
    static MethodHandle bindOptional(Linker linker, SymbolLookup lib, String name, FunctionDescriptor fd) {
        return lib.find(name).map(addr -> linker.downcallHandle(addr, fd)).orElse(null);
    }

    /** Ruft eine native Funktion auf; Fehler werden mit dem Funktionsnamen gemeldet. */
    static Object call(MethodHandle handle, String name, Object... args) {
        try {
            return handle.invokeWithArguments(args);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("Nativer Aufruf " + name + " fehlgeschlagen: " + t.getMessage(), t);
        }
    }
}
