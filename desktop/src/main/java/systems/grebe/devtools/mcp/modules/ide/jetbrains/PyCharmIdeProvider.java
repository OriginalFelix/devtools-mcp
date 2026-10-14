package systems.grebe.devtools.mcp.modules.ide.jetbrains;

import java.util.List;

/** PyCharm. */
public class PyCharmIdeProvider extends JetBrainsIdeProvider {

    public PyCharmIdeProvider() {
        super(new Product("pycharm", "PyCharm", 30, "pycharm", "pycharm", "PyCharm*.app",
                List.of("C:/Program Files/JetBrains/PyCharm*")));
    }
}
