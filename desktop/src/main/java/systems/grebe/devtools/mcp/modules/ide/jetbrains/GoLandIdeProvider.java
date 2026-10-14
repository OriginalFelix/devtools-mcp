package systems.grebe.devtools.mcp.modules.ide.jetbrains;

import java.util.List;

/** GoLand. */
public class GoLandIdeProvider extends JetBrainsIdeProvider {

    public GoLandIdeProvider() {
        super(new Product("goland", "GoLand", 30, "goland", "goland", "GoLand*.app",
                List.of("C:/Program Files/JetBrains/GoLand*")));
    }
}
