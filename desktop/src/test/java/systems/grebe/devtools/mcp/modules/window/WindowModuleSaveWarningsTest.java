package systems.grebe.devtools.mcp.modules.window;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import systems.grebe.devtools.mcp.core.ModuleConfig;

import static org.assertj.core.api.Assertions.assertThat;

class WindowModuleSaveWarningsTest {

    private final WindowModule module = new WindowModule();

    private List<String> warnings(Path shared, String exclude) {
        return module.saveWarnings(ModuleConfig.of(module.configSchema(),
                Map.of(WindowModule.EXCLUDE, exclude, WindowModule.SHARED, shared.toString())));
    }

    @Test
    void warnsWhenSharedDirectoriesOverrideExclusions(@TempDir Path root) throws Exception {
        Path foo = SharedProgramScanTest.program(root.resolve("sub"), "foo");
        Path keepass = SharedProgramScanTest.program(root, "KeePass");

        String w = String.join("\n", warnings(root, "foo|keepass"));

        assertThat(w).contains("Prozesse ausschließen", foo.toString(), "1 Programm(e)", "bleiben gesperrt");
        assertThat(w).doesNotContain(keepass.toString());
    }

    @Test
    void noWarningWithoutConflict(@TempDir Path root) throws Exception {
        SharedProgramScanTest.program(root, "KeePass");

        assertThat(warnings(root, "")).isEmpty();
        assertThat(warnings(root, "bar")).isEmpty();
        assertThat(warnings(root, "keepass")).isEmpty(); // nur gesperrte Programme: kein Konflikt
    }
}
