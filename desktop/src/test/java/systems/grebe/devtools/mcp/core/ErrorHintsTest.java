package systems.grebe.devtools.mcp.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ErrorHintsTest {

    @Test
    void addsNextStepForPermissionErrors() {
        assertThat(ErrorHints.hint("Pfad C:\\x ist nicht freigegeben.")).startsWith("Pfad C:\\x ist nicht freigegeben.\n→ ")
                .contains("permissions_check");
    }

    @Test
    void noHintWhenMessageAlreadyNamesTheWay() {
        assertThat(ErrorHints.hint("Nicht freigegeben – mit permissions_request anfragen.")).isNull();
    }

    @Test
    void noHintForUnrelatedErrors() {
        assertThat(ErrorHints.hint("Datei nicht gefunden")).isNull();
        assertThat(ErrorHints.hint(null)).isNull();
    }
}
