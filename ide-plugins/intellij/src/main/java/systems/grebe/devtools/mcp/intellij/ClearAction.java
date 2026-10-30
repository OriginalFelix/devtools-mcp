package systems.grebe.devtools.mcp.intellij;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAware;
import org.jetbrains.annotations.NotNull;

/** Blendet den Cursor der KI aus (er folgt dann auch keinen Änderungen mehr). */
public final class ClearAction extends AnAction implements DumbAware {

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
        AgentCursor.getInstance().clear();
    }
}
