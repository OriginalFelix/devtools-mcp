package systems.grebe.devtools.mcp.modules.window.platform;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/** Platzhalter auf Plattformen ohne Fensterzugriff. */
final class UnsupportedWindowSystem implements WindowSystem {

    private final String reason;

    UnsupportedWindowSystem(String reason) {
        this.reason = reason;
    }

    @Override
    public String name() {
        return "nicht unterstützt";
    }

    @Override
    public Optional<String> unsupportedReason() {
        return Optional.of(reason);
    }

    @Override
    public List<NativeWindow> windows() {
        throw new IllegalStateException(reason);
    }

    @Override
    public OptionalLong foreground() {
        throw new IllegalStateException(reason);
    }

    @Override
    public void activate(long id) {
        throw new IllegalStateException(reason);
    }
}
