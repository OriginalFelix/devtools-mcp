package systems.grebe.devtools.mcp.core;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NamedEntriesTest {

    private static NamedEntries<String> entries() {
        return new NamedEntries<>(s -> s, new NamedEntries.Messages("keine",
                names -> "mehrere: " + names, name -> "doppelt: " + name, (name, names) -> "unbekannt " + name + " in " + names));
    }

    @Test
    void resolvesCaseInsensitivelyAndThePlainEntryWithoutName() {
        NamedEntries<String> e = entries();
        e.add("Prod");
        assertThat(e.resolve(null)).isEqualTo("Prod");
        assertThat(e.resolve("  PROD ")).isEqualTo("Prod");
        e.add("test");
        assertThatThrownBy(() -> e.resolve(" ")).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("mehrere: [Prod, test]");
        assertThatThrownBy(() -> e.resolve("x")).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("unbekannt x in [Prod, test]");
    }

    @Test
    void emptyAndDuplicateNamesAreReported() {
        NamedEntries<String> e = entries();
        assertThatThrownBy(() -> e.resolve("a")).isInstanceOf(IllegalStateException.class).hasMessage("keine");
        assertThat(e.add("a")).isTrue();
        assertThat(e.add("A")).isFalse();
        assertThat(e.duplicates()).containsExactly("A");
        assertThat(e.all()).containsExactly("a");
        assertThatThrownBy(() -> e.resolve("a")).isInstanceOf(IllegalStateException.class).hasMessage("doppelt: a");
        assertThat(e.resolve(null)).isEqualTo("a"); // ohne Name zählt der einzige Eintrag weiter
        assertThat(List.copyOf(e.names())).containsExactly("a");
    }
}
