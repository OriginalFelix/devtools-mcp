package systems.grebe.devtools.mcp.modules.window.cursor;

/** Griff auf einen Zeiger, den ein {@link CursorController} erzeugt hat; gilt nur für diesen Controller. */
public interface VirtualCursor {

    /** Eindeutig je Controller, fortlaufend ab 1. */
    long id();

    /** Ob der Zeiger noch existiert. */
    boolean isOpen();
}
