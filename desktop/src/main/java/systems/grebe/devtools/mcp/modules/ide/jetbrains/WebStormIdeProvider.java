package systems.grebe.devtools.mcp.modules.ide.jetbrains;

import java.util.List;

/** WebStorm. */
public class WebStormIdeProvider extends JetBrainsIdeProvider {

    public WebStormIdeProvider() {
        super(new Product("webstorm", "WebStorm", 30, "webstorm", "webstorm", "WebStorm*.app",
                List.of("C:/Program Files/JetBrains/WebStorm*")));
    }
}
