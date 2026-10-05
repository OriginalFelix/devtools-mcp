package systems.grebe.devtools.mcp.modules.window.cursor;

import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;

import oshi.PlatformEnum;

/**
 * SPI für einen zweiten Mauszeiger: je Betriebssystem eine Implementierung, gefunden per {@link ServiceLoader}
 * ({@code META-INF/services}). Provider sind billig zu erzeugen – native Bibliotheken lädt erst {@link #controller()}.
 */
public interface CursorProvider {

    /** Das Betriebssystem, für das dieser Provider gedacht ist. */
    PlatformEnum platform();

    /** Warum der Provider hier nicht nutzbar ist (z.B. Wayland ohne X-Server); leer, wenn er nutzbar ist. */
    default Optional<String> unsupportedReason() {
        return Optional.empty();
    }

    /**
     * Der Controller dieses Providers – je Provider genau einer, beim ersten Aufruf erzeugt.
     *
     * @throws UnsupportedOperationException wenn {@link #unsupportedReason()} nicht leer ist
     */
    CursorController controller();

    /** Alle registrierten Provider. */
    static List<CursorProvider> all() {
        return ServiceLoader.load(CursorProvider.class, CursorProvider.class.getClassLoader()).stream()
                .map(ServiceLoader.Provider::get)
                .toList();
    }

    /** Der Provider für dieses Betriebssystem, falls es einen gibt. */
    static Optional<CursorProvider> forPlatform(PlatformEnum platform) {
        return all().stream().filter(p -> p.platform() == platform).findFirst();
    }

    /**
     * Der Provider dieses Rechners (einmal ermittelt).
     *
     * @throws UnsupportedOperationException wenn es für dieses Betriebssystem keinen gibt
     */
    static CursorProvider current() {
        CursorProvider p = Holder.CURRENT;
        if (p == null) {
            throw new UnsupportedOperationException("Kein zweiter Zeiger für " + PlatformEnum.getCurrentPlatform()
                    + " verfügbar.");
        }
        return p;
    }

    final class Holder {
        static final CursorProvider CURRENT = forPlatform(PlatformEnum.getCurrentPlatform()).orElse(null);

        private Holder() {
        }
    }
}
