package systems.grebe.devtools.mcp.modules.ide;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** Programmsuche und Start ohne Warten auf die IDE. */
class IdeProgramsTest {

    @TempDir
    Path tmp;

    @Test
    void globMatchesWildcardsInAnySegmentSorted() throws Exception {
        Files.createDirectories(tmp.resolve("IntelliJ IDEA 2025.1.app/Contents/MacOS"));
        Files.createDirectories(tmp.resolve("IntelliJ IDEA 2025.2.app/Contents/MacOS"));
        Files.createDirectories(tmp.resolve("PyCharm.app/Contents/MacOS"));
        Files.writeString(tmp.resolve("IntelliJ IDEA 2025.1.app/Contents/MacOS/idea"), "");
        Files.writeString(tmp.resolve("IntelliJ IDEA 2025.2.app/Contents/MacOS/idea"), "");

        List<Path> hits = IdePrograms.glob(tmp + "/IntelliJ IDEA*.app/Contents/MacOS/idea");
        assertThat(hits).containsExactly(tmp.resolve("IntelliJ IDEA 2025.1.app/Contents/MacOS/idea"),
                tmp.resolve("IntelliJ IDEA 2025.2.app/Contents/MacOS/idea"));
        assertThat(IdePrograms.glob(tmp + "/Rider*.app")).isEmpty();
        assertThat(IdePrograms.glob("relativ/*")).isEmpty();

        // neueste Version gewinnt; eingetragener Pfad hat Vorrang und muss existieren
        assertThat(IdePrograms.find(null, List.of("devtools-gibt-es-nicht"),
                List.of(tmp + "/Rider*.app", tmp + "/IntelliJ IDEA*.app/Contents/MacOS/idea")))
                .contains(tmp.resolve("IntelliJ IDEA 2025.2.app/Contents/MacOS/idea"));
        assertThat(IdePrograms.find(tmp.resolve("PyCharm.app").toString(), List.of(), List.of()))
                .contains(tmp.resolve("PyCharm.app"));
        assertThatThrownBy(() -> IdePrograms.find(tmp.resolve("fehlt").toString(), List.of(), List.of()))
                .hasMessageContaining("nicht gefunden");
        assertThat(IdePrograms.find(" ", List.of("devtools-gibt-es-nicht"), List.of())).isEmpty();
    }

    @Test
    void launchReturnsOutputFailsOnErrorAndLeavesLongRunningProcess() {
        assumeFalse(IdePrograms.WINDOWS);
        assertThat(IdePrograms.launch(List.of("sh", "-c", "echo weitergereicht"), Duration.ofSeconds(10)))
                .isEqualTo("weitergereicht");
        assertThatThrownBy(() -> IdePrograms.launch(List.of("sh", "-c", "echo kaputt; exit 2"), Duration.ofSeconds(10)))
                .hasMessageContaining("Exit-Code 2").hasMessageContaining("kaputt");
        // läuft länger als die Wartezeit: gilt als gestartete IDE und bleibt unberührt
        assertThat(IdePrograms.launch(List.of("sleep", "2"), Duration.ofMillis(200))).isNull();
        assertThatThrownBy(() -> IdePrograms.launch(List.of(tmp.resolve("fehlt").toString()), Duration.ofSeconds(1)))
                .hasMessageContaining("nicht startbar");
    }
}
