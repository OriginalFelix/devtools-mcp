package systems.grebe.devtools.mcp.intellij;

import java.awt.datatransfer.StringSelection;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.DumbAware;
import org.jetbrains.annotations.NotNull;

/** Kopiert den Befehl, mit dem Claude Code den MCP-Endpunkt des Plugins einträgt. */
public final class CopyMcpAction extends AnAction implements DumbAware {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        LinkServer s = LinkServer.getInstance();
        String cmd = "claude mcp add --transport http intellij http://127.0.0.1:" + s.port()
                + "/mcp --header \"Authorization: Bearer " + s.token() + "\"";
        CopyPasteManager.getInstance().setContents(new StringSelection(cmd));
        Notifications.Bus.notify(new Notification("DevTools MCP", "MCP-Verbindung kopiert",
                "Den Befehl im Terminal ausführen. Das Token gewährt Zugriff auf den Editor – nicht weitergeben.",
                NotificationType.INFORMATION), e.getProject());
    }
}
