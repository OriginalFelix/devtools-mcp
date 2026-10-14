package systems.grebe.devtools.mcp.modules.ide.vscode;

import java.util.List;

/** VSCodium (VS Code ohne Telemetrie von Microsoft). */
public class VsCodiumIdeProvider extends VsCodeFamilyProvider {

    public VsCodiumIdeProvider() {
        super(new Product("vscodium", "VSCodium", 40, "codium", "VSCodium.app", "VSCodium",
                List.of("/usr/share/codium", "/opt/vscodium")));
    }
}
