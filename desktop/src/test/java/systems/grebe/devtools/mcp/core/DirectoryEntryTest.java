package systems.grebe.devtools.mcp.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Eine Auslegung der Zeilen "pfad" und "name=pfad" für Arbeitsverzeichnisse, Freigaben und Rechteprüfung. */
class DirectoryEntryTest {

    @Test
    void parsesNamedAndUnnamedEntries() {
        assertThat(DirectoryEntry.parse("shop=C:/src/shop")).isEqualTo(new DirectoryEntry("shop", "C:/src/shop"));
        assertThat(DirectoryEntry.parse(" mein projekt = /srv/a ")).isEqualTo(new DirectoryEntry("mein projekt", "/srv/a"));
        assertThat(DirectoryEntry.parse("C:/src/shop")).isEqualTo(new DirectoryEntry(null, "C:/src/shop"));
        // Pfade mit "=" sind keine Namen: Pfadtrenner und Doppelpunkt kommen im Namen nicht vor
        assertThat(DirectoryEntry.parse("C:/data=x").name()).isNull();
        assertThat(DirectoryEntry.parse("/srv/a=b").name()).isNull();
        assertThat(DirectoryEntry.parse(null)).isEqualTo(new DirectoryEntry(null, ""));
    }

    @Test
    void blankPathIsNoDirectory() {
        assertThat(DirectoryEntry.parse("").directory()).isEmpty();
        assertThat(DirectoryEntry.parse("a\0b").directory()).isEmpty(); // ungültiger Pfad
    }

    @Test
    void sameDirectoryIgnoresNameAndNormalization() {
        Path dir = Path.of("src/../repo").toAbsolutePath().normalize();
        assertThat(DirectoryEntry.parse("x=" + dir).sameDirectory(dir)).isTrue();
        assertThat(DirectoryEntry.parse(dir + "/.").sameDirectory(dir)).isTrue();
        assertThat(DirectoryEntry.parse(dir + "-anders").sameDirectory(dir)).isFalse();
    }

    @Test
    void validateChecksThePathNotTheName() {
        Path dir = Path.of(System.getProperty("java.io.tmpdir"));
        var field = new ConfigField("dirs", "Verzeichnisse", FieldType.DIRECTORY_LIST, false, null, null, null, null);
        var ok = ModuleConfig.of(List.of(field), Map.of("dirs", "tmp=" + dir));
        assertThat(ok.validate()).isEmpty();
        var bad = ModuleConfig.of(List.of(field), Map.of("dirs", "tmp=" + dir.resolve("gibt-es-nicht-xyz")));
        assertThat(bad.validate()).singleElement().asString().contains("existiert nicht");
    }

    @Test
    void mergeAppendsOnlyMissingDirectoriesOrLetsAdditionsWin() {
        Path a = Path.of("a-dir").toAbsolutePath();
        Path b = Path.of("b-dir").toAbsolutePath();
        // Freigaben: vorhandene Zeilen gewinnen, fehlende werden angehängt
        assertThat(DirectoryLists.merge("alt=" + a, List.of(a.toString(), b.toString()), false))
                .isEqualTo("alt=" + a + "\n" + b);
        // Projekte: das Projekt ersetzt die Zeile desselben Verzeichnisses
        assertThat(DirectoryLists.merge(a + "\n" + b, List.of("shop=" + a), true))
                .isEqualTo(b + "\n" + "shop=" + a);
        assertThat(DirectoryLists.merge(null, List.of("shop=" + a), true)).isEqualTo("shop=" + a);
    }
}
