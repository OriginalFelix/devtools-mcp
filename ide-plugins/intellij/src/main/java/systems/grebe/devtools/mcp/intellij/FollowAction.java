package systems.grebe.devtools.mcp.intellij;

import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ToggleAction;
import com.intellij.openapi.project.DumbAware;
import org.jetbrains.annotations.NotNull;

/** „Claude folgen“: Dateien öffnen und hinscrollen, wenn die KI ihren Cursor setzt oder etwas ändert. */
public final class FollowAction extends ToggleAction implements DumbAware {

    private static final String KEY = "devtools.mcp.follow";

    static boolean enabled() {
        return PropertiesComponent.getInstance().getBoolean(KEY, true);
    }

    @Override
    public boolean isSelected(@NotNull AnActionEvent e) {
        return enabled();
    }

    @Override
    public void setSelected(@NotNull AnActionEvent e, boolean state) {
        PropertiesComponent.getInstance().setValue(KEY, state, true);
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
        return ActionUpdateThread.BGT;
    }
}
