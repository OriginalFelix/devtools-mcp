package systems.grebe.devtools.mcp.ui;

import java.awt.AWTException;
import java.awt.EventQueue;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;

import javafx.application.Platform;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** System-Tray-Symbol (AWT) mit Menü „Öffnen“/„Beenden“. */
public class TrayManager {

    private static final Logger LOG = LoggerFactory.getLogger(TrayManager.class);

    private final Stage stage;
    private final Runnable exitAction;
    private TrayIcon icon;
    private boolean minimizedHintShown;

    public TrayManager(Stage stage, Runnable exitAction) {
        this.stage = stage;
        this.exitAction = exitAction;
    }

    /** @return {@code true}, wenn das Tray-Symbol installiert wurde */
    public boolean install() {
        if (!SystemTray.isSupported()) {
            LOG.info("System-Tray wird nicht unterstützt");
            return false;
        }
        try {
            SystemTray tray = SystemTray.getSystemTray();
            int size = tray.getTrayIconSize().width;
            PopupMenu menu = new PopupMenu();
            MenuItem open = new MenuItem("Öffnen");
            open.addActionListener(e -> showStage());
            MenuItem quit = new MenuItem("Beenden");
            quit.addActionListener(e -> exitAction.run());
            menu.add(open);
            menu.addSeparator();
            menu.add(quit);
            icon = new TrayIcon(AppIcons.awtIcon(Math.max(16, size)), "DevTools MCP", menu);
            icon.setImageAutoSize(true);
            icon.addActionListener(e -> showStage()); // Doppelklick
            tray.add(icon);
            return true;
        } catch (AWTException | RuntimeException e) {
            LOG.warn("Tray-Symbol konnte nicht installiert werden", e);
            return false;
        }
    }

    public void notifyMinimized() {
        if (icon != null && !minimizedHintShown) {
            minimizedHintShown = true;
            EventQueue.invokeLater(() -> icon.displayMessage("DevTools MCP",
                    "Läuft im Hintergrund weiter. Beenden über das Tray-Menü.", TrayIcon.MessageType.INFO));
        }
    }

    public void uninstall() {
        if (icon != null) {
            TrayIcon i = icon;
            icon = null;
            EventQueue.invokeLater(() -> SystemTray.getSystemTray().remove(i));
        }
    }

    private void showStage() {
        Platform.runLater(() -> {
            stage.show();
            stage.setIconified(false);
            stage.toFront();
            stage.requestFocus();
        });
    }
}
