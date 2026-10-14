package systems.grebe.devtools.mcp.modules.ide.jetbrains;

import java.util.List;

/** IntelliJ IDEA (Ultimate und Community Edition). */
public class IntelliJIdeProvider extends JetBrainsIdeProvider {

    public IntelliJIdeProvider() {
        super(new Product("intellij", "IntelliJ IDEA", 10, "idea", "idea", "IntelliJ IDEA*.app",
                List.of("C:/Program Files/JetBrains/IntelliJ IDEA*")));
    }
}
