package systems.grebe.devtools.mcp.modules.window.cursor.x11;

import java.util.Optional;

import oshi.PlatformEnum;
import systems.grebe.devtools.mcp.modules.window.cursor.AbstractCursorProvider;
import systems.grebe.devtools.mcp.modules.window.cursor.CursorController;

/** Zweiter Zeiger unter Linux über libX11, libXi und libXtst (JNA); braucht X11 oder XWayland. */
public final class X11CursorProvider extends AbstractCursorProvider {

    @Override
    public PlatformEnum platform() {
        return PlatformEnum.LINUX;
    }

    @Override
    public Optional<String> unsupportedReason() {
        String display = System.getenv("DISPLAY");
        if (display != null && !display.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(System.getenv("WAYLAND_DISPLAY") != null
                ? "Wayland ohne X-Server (XWayland) wird nicht unterstützt – ein zweiter Zeiger braucht X11."
                : "Kein X-Server (Umgebungsvariable DISPLAY fehlt).");
    }

    @Override
    protected CursorController createController() {
        return new X11CursorController();
    }
}
