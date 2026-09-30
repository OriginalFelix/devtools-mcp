package systems.grebe.devtools.mcp.web;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.VaadinServletRequest;
import jakarta.servlet.http.HttpServletRequest;

/** Kleine Helfer der Web-UI. */
final class Ui {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
            .withZone(ZoneId.systemDefault());

    private Ui() {
    }

    static String time(Instant i) {
        return i == null ? "" : TIME.format(i);
    }

    /**
     * Adresse des MCP-Endpunkts, wie der Browser den Server erreicht (hinter einem Reverse-Proxy mit
     * {@code server.forward-headers-strategy=native} die öffentliche Adresse).
     */
    static String mcpUrl(String endpoint) {
        if (!(VaadinRequest.getCurrent() instanceof VaadinServletRequest vr)) {
            return endpoint;
        }
        HttpServletRequest r = vr.getHttpServletRequest();
        int port = r.getServerPort();
        boolean defaultPort = "http".equals(r.getScheme()) && port == 80 || "https".equals(r.getScheme()) && port == 443;
        return r.getScheme() + "://" + r.getServerName() + (defaultPort ? "" : ":" + port) + r.getContextPath()
                + endpoint;
    }

    /**
     * Führt eine Aktion aus und meldet das Ergebnis. Fachliche Fehler ({@link IllegalArgumentException},
     * {@link IllegalStateException}) erscheinen als Meldung statt als Fehlerseite.
     *
     * @return ob die Aktion geklappt hat
     */
    static boolean run(Runnable action, String success) {
        try {
            action.run();
            if (success != null) {
                Notification.show(success, 3000, Notification.Position.BOTTOM_START)
                        .addThemeVariants(NotificationVariant.SUCCESS);
            }
            return true;
        } catch (IllegalArgumentException | IllegalStateException e) {
            error(e.getMessage());
            return false;
        }
    }

    static void error(String message) {
        Notification.show(message, 6000, Notification.Position.MIDDLE).addThemeVariants(NotificationVariant.ERROR);
    }
}
