package systems.grebe.devtools.mcp.modules.decompile;

import java.util.List;
import java.util.function.Supplier;

/** Wird von {@link DecompileToolsTest} dekompiliert: verschachtelte Klasse, Lambda, Record. */
public class DecompileSample {

    public String marker() {
        Supplier<String> s = () -> "decompile-" + new Inner().value();
        return s.get();
    }

    static final class Inner {
        String value() {
            return "marker";
        }
    }

    record Point(int x, int y) {
        List<Integer> coords() {
            return List.of(x, y);
        }
    }
}
