package systems.grebe.devtools.mcp.modules.ide.vscode;

import java.util.List;

/** Cursor (auf Basis von VS Code). */
public class CursorIdeProvider extends VsCodeFamilyProvider {

    public CursorIdeProvider() {
        super(new Product("cursor", "Cursor", 40, "cursor", "Cursor.app", "cursor",
                List.of("/usr/share/cursor", "/opt/cursor")));
    }
}
