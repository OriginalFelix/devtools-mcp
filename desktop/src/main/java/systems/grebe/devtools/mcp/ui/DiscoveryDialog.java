package systems.grebe.devtools.mcp.ui;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.ComboBox;
import javafx.stage.Window;
import javafx.util.StringConverter;
import systems.grebe.devtools.mcp.discovery.BackendDiscovery;

/**
 * „Im Netzwerk suchen…“: fragt Backends mit eingeschaltetem Advertise-Endpunkt im lokalen Netzwerk ab (UDP, im
 * Hintergrund) und lässt eines auswählen.
 */
final class DiscoveryDialog {

    /** Wartezeit auf Antworten. */
    static final Duration TIMEOUT = Duration.ofMillis(1500);

    private DiscoveryDialog() {
    }

    /**
     * Sucht und zeigt die Auswahl; {@code chosen} bekommt die Adresse des gewählten Backends.
     *
     * @param busy wird vor der Suche mit {@code true}, danach mit {@code false} aufgerufen
     */
    static void search(Window owner, Consumer<Boolean> busy, Consumer<String> chosen) {
        busy.accept(true);
        CompletableFuture.supplyAsync(() -> {
            try {
                return BackendDiscovery.search(TIMEOUT);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }).whenComplete((found, e) -> Platform.runLater(() -> {
            busy.accept(false);
            if (e != null) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                alert(owner, Alert.AlertType.ERROR, "Suche fehlgeschlagen: " + cause.getMessage());
            } else if (found.isEmpty()) {
                alert(owner, Alert.AlertType.INFORMATION, "Im lokalen Netzwerk antwortet kein Backend. Beim "
                        + "Team-Server bzw. der anderen Desktop-App muss der Advertise-Endpunkt eingeschaltet sein; "
                        + "Firewalls müssen UDP-Port " + BackendDiscovery.PORT + " durchlassen.");
            } else {
                choose(owner, found, chosen);
            }
        }));
    }

    private static void choose(Window owner, List<BackendDiscovery.Endpoint> found, Consumer<String> chosen) {
        ChoiceDialog<BackendDiscovery.Endpoint> d = new ChoiceDialog<>(found.getFirst(), found);
        if (owner != null) {
            d.initOwner(owner);
        }
        d.setTitle("Backend im Netzwerk");
        d.setHeaderText(found.size() == 1 ? "1 Backend gefunden" : found.size() + " Backends gefunden");
        d.setContentText("Backend");
        d.getDialogPane().lookupAll(".combo-box").forEach(n -> {
            @SuppressWarnings("unchecked")
            var box = (ComboBox<BackendDiscovery.Endpoint>) n;
            box.setConverter(new StringConverter<>() {
                @Override
                public String toString(BackendDiscovery.Endpoint e) {
                    return e == null ? "" : e.label();
                }

                @Override
                public BackendDiscovery.Endpoint fromString(String s) {
                    return null;
                }
            });
        });
        d.showAndWait().map(BackendDiscovery.Endpoint::url).ifPresent(chosen);
    }

    private static void alert(Window owner, Alert.AlertType type, String text) {
        Alert a = new Alert(type, text);
        if (owner != null) {
            a.initOwner(owner);
        }
        a.setHeaderText("Backend im Netzwerk suchen");
        a.showAndWait();
    }
}
