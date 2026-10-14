package systems.grebe.devtools.mcp.modules.ide.jetbrains;

import java.util.List;

/** CLion (C/C++). */
public class CLionIdeProvider extends JetBrainsIdeProvider {

    public CLionIdeProvider() {
        super(new Product("clion", "CLion", 30, "clion", "clion", "CLion*.app",
                List.of("C:/Program Files/JetBrains/CLion*")));
    }
}
