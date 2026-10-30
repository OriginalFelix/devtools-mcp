package systems.grebe.devtools.mcp.intellij;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.ProjectActivity;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import org.jetbrains.annotations.NotNull;

/** Startet den {@link LinkServer} mit dem ersten geöffneten Projekt (Services entstehen sonst erst bei Bedarf). */
public final class LinkStartup implements ProjectActivity {

    @Override
    public Object execute(@NotNull Project project, @NotNull Continuation<? super Unit> continuation) {
        LinkServer.getInstance();
        return Unit.INSTANCE;
    }
}
