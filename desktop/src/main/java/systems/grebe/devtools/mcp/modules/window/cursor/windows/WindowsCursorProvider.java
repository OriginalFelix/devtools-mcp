package systems.grebe.devtools.mcp.modules.window.cursor.windows;

import oshi.PlatformEnum;
import systems.grebe.devtools.mcp.modules.window.cursor.AbstractCursorProvider;
import systems.grebe.devtools.mcp.modules.window.cursor.CursorController;

/** Zweiter Zeiger unter Windows über user32/gdi32 (JNA). */
public final class WindowsCursorProvider extends AbstractCursorProvider {

    @Override
    public PlatformEnum platform() {
        return PlatformEnum.WINDOWS;
    }

    @Override
    protected CursorController createController() {
        return new WindowsCursorController();
    }
}
