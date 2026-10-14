package systems.grebe.devtools.mcp.modules.ide.vscode;

import java.util.List;

/** Visual Studio Code. */
public class VsCodeIdeProvider extends VsCodeFamilyProvider {

    public VsCodeIdeProvider() {
        super(new Product("vscode", "Visual Studio Code", 10, "code", "Visual Studio Code.app", "Microsoft VS Code",
                List.of("/usr/share/code", "/usr/lib/code", "/snap/code/current/usr/share/code")));
    }
}
