package systems.grebe.devtools.mcp.modules.window.cursor.macos;

import oshi.PlatformEnum;
import systems.grebe.devtools.mcp.modules.window.cursor.AbstractCursorProvider;
import systems.grebe.devtools.mcp.modules.window.cursor.CursorController;

/** Zweiter Zeiger unter macOS über AppKit und CoreGraphics (JNA). */
public final class MacCursorProvider extends AbstractCursorProvider {

    @Override
    public PlatformEnum platform() {
        return PlatformEnum.MACOS;
    }

    @Override
    protected CursorController createController() {
        return new MacCursorController();
    }
}
