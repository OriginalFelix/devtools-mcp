package systems.grebe.devtools.mcp.modules.ide.jetbrains;

import java.util.List;

/** Rider (.NET). */
public class RiderIdeProvider extends JetBrainsIdeProvider {

    public RiderIdeProvider() {
        super(new Product("rider", "Rider", 30, "rider", "rider", "Rider*.app",
                List.of("C:/Program Files/JetBrains/JetBrains Rider*")));
    }
}
