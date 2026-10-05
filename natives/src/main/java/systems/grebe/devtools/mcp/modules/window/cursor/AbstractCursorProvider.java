package systems.grebe.devtools.mcp.modules.window.cursor;

/** Erzeugt den Controller beim ersten Aufruf von {@link #controller()} – erst dann werden native Bibliotheken geladen. */
public abstract class AbstractCursorProvider implements CursorProvider {

    private CursorController controller;

    /** Erzeugt den Controller; wird höchstens einmal je Provider aufgerufen. */
    protected abstract CursorController createController();

    @Override
    public final synchronized CursorController controller() {
        unsupportedReason().ifPresent(reason -> {
            throw new UnsupportedOperationException(reason);
        });
        if (controller == null) {
            controller = createController();
        }
        return controller;
    }
}
