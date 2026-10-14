package systems.grebe.devtools.mcp.modules.ide.jetbrains;

import java.util.List;

/** Android Studio (auf Basis von IntelliJ IDEA). */
public class AndroidStudioIdeProvider extends JetBrainsIdeProvider {

    public AndroidStudioIdeProvider() {
        super(new Product("android-studio", "Android Studio", 20, "studio", "studio", "Android Studio*.app",
                List.of("C:/Program Files/Android/Android Studio*")));
    }
}
