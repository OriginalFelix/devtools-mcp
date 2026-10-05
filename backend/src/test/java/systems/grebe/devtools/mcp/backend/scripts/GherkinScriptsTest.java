package systems.grebe.devtools.mcp.backend.scripts;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import systems.grebe.devtools.mcp.modules.scripts.ScriptTemplates;
import systems.grebe.devtools.mcp.modules.scripts.ScriptViews;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Gherkin-Skripte lesen: Modul, Szenarien als Tools, Parameter, Tags, Grundlagen und Fehler mit Zeile. */
class GherkinScriptsTest {

    static final String SCHNELLCHECK = """
            # language: de
            @idempotent
            Funktionalität: Schnellcheck
              Prüft ein Repository
              lokal.

              Grundlage:
                Angenommen ich setze basis auf "master"

              @readOnly
              Szenario: Branch prüfen
                Prüft Arbeitsverzeichnis und Tests.
                <repo>: Repository-Name
                <limit>: optional – höchstens so viele Einträge

                Wenn ich das Tool "git_status" aufrufe:
                  | repository | <repo> |
                  | limit      | <limit> |
                Dann enthält das Ergebnis "<branch>"

              Regel: Aufräumen
                Grundlage:
                  Angenommen ich setze modus auf "hart"

                Szenario: Container löschen
                  Wenn ich das Tool "container_rm" aufrufe:
                    \"""
                    {"name": "<name>"}
                    \"""
            """;

    @Test
    void featureBecomesModuleAndScenariosBecomeTools() {
        GherkinScripts.Script s = GherkinScripts.parse("check", SCHNELLCHECK);
        assertThat(s.displayName()).isEqualTo("Schnellcheck");
        assertThat(s.description()).isEqualTo("Prüft ein Repository lokal.");
        assertThat(s.scenarios()).extracting(GherkinScripts.Scenario::toolName)
                .containsExactly("branch_pruefen", "container_loeschen");

        GherkinScripts.Scenario branch = s.scenarios().getFirst();
        assertThat(branch.description()).isEqualTo("Prüft Arbeitsverzeichnis und Tests.");
        assertThat(branch.params()).containsExactly(
                new GherkinScripts.Param("repo", "Repository-Name", true),
                new GherkinScripts.Param("limit", "höchstens so viele Einträge", false),
                new GherkinScripts.Param("branch", "", true));
        assertThat(branch.hints()).containsExactlyInAnyOrder("readonly", "idempotent");
        assertThat(branch.line()).isEqualTo(11);
        // Grundlage zuerst, Schlüsselwort ohne Leerzeichen, Tabelle und Zeilen wie im Quelltext
        assertThat(branch.steps()).extracting(GherkinScripts.Step::display).containsExactly(
                "Angenommen ich setze basis auf \"master\"",
                "Wenn ich das Tool \"git_status\" aufrufe:",
                "Dann enthält das Ergebnis \"<branch>\"");
        assertThat(branch.steps().get(1).line()).isEqualTo(16);
        assertThat(branch.steps().get(1).table()).containsExactly(List.of("repository", "<repo>"),
                List.of("limit", "<limit>"));

        GherkinScripts.Scenario rm = s.scenarios().get(1);
        assertThat(rm.description()).isEqualTo("Container löschen"); // ohne Freitext der Name
        assertThat(rm.hints()).containsExactly("idempotent");
        assertThat(rm.steps()).extracting(GherkinScripts.Step::text).containsExactly(
                "ich setze basis auf \"master\"", "ich setze modus auf \"hart\"", "ich das Tool \"container_rm\" aufrufe:");
        assertThat(rm.steps().get(2).docString()).isEqualTo("{\"name\": \"<name>\"}");
        assertThat(rm.params()).extracting(GherkinScripts.Param::name).containsExactly("name");
    }

    @Test
    void withoutLanguageHeaderGermanAppliesAndLinesStayRight() {
        String source = "Funktionalität: Demo\n\n  Szenario: Eins\n    Wenn ich das Tool \"x_y\" aufrufe\n";
        GherkinScripts.Script s = GherkinScripts.parse("demo", source);
        assertThat(s.description()).isEqualTo("Demo");
        assertThat(s.scenarios().getFirst().line()).isEqualTo(3);
        assertThat(s.scenarios().getFirst().steps().getFirst().line()).isEqualTo(4);

        assertThatThrownBy(() -> GherkinScripts.parse("demo", "Funktionalität: Demo\n  Szenario: Eins\n    Wenn x\n  kaputt\n"))
                .hasMessageContaining("lässt sich nicht lesen").hasMessageContaining("Zeile 4");
        // englisch mit Kopfzeile
        assertThat(GherkinScripts.parse("demo", "# language: en\nFeature: Demo\n  Scenario: One\n    When x\n")
                .scenarios()).extracting(GherkinScripts.Scenario::toolName).containsExactly("one");
    }

    @Test
    void rulesForScriptsAreChecked() {
        assertThatThrownBy(() -> GherkinScripts.parse("d", "# nur ein Kommentar\n"))
                .hasMessageContaining("Funktionalität: …");
        assertThatThrownBy(() -> GherkinScripts.parse("d", "Funktionalitaet: D\n  Szenario: S\n    Wenn x\n"))
                .hasMessageContaining("Funktionalität: …").hasMessageContaining("Zeile 1");
        assertThatThrownBy(() -> GherkinScripts.parse("d", "Funktionalität: D\n"))
                .hasMessageContaining("kein Szenario");
        assertThatThrownBy(() -> GherkinScripts.parse("d", "Funktionalität: D\n  Szenario: Leer\n"))
                .hasMessageContaining("Zeile 2").hasMessageContaining("keine Schritte");
        assertThatThrownBy(() -> GherkinScripts.parse("d", """
                Funktionalität: D
                  Szenario: Prüfen
                    Wenn a
                  Szenario: prüfen!
                    Wenn b
                """)).hasMessageContaining("Zeile 4").hasMessageContaining("pruefen").hasMessageContaining("Zeile 2");
        assertThatThrownBy(() -> GherkinScripts.parse("d", """
                Funktionalität: D
                  Szenariogrundriss: Mit Beispielen
                    Wenn ich "<x>" nehme
                    Beispiele:
                      | x |
                      | 1 |
                """)).hasMessageContaining("Zeile 2").hasMessageContaining("Beispiele");
        assertThatThrownBy(() -> GherkinScripts.parse("d", """
                Funktionalität: D
                  Szenario: Tippfehler
                    <rpeo>: Repository
                    Wenn ich "<repo>" nehme
                """)).hasMessageContaining("<rpeo>").hasMessageContaining("in keinem Schritt");
    }

    @Test
    void toolNamesFromScenarioNames() {
        assertThat(GherkinScripts.toolName("Branch prüfen")).isEqualTo("branch_pruefen");
        assertThat(GherkinScripts.toolName("  Größe & Maße – zählen ")).isEqualTo("groesse_masse_zaehlen");
        assertThat(GherkinScripts.toolName("Café")).isEqualTo("cafe");
        assertThat(GherkinScripts.toolName("42 Dinge")).isEqualTo("s_42_dinge");
        assertThat(GherkinScripts.toolName("!!!")).isEmpty();
        assertThat(GherkinScripts.toolName("a".repeat(60))).hasSize(48);
    }

    @Test
    void syntaxCheckOfTheBackendReturnsTheDescription() {
        assertThat(ScriptSyntax.check(ScriptViews.Language.GHERKIN, "check", SCHNELLCHECK))
                .isEqualTo(Optional.of("Prüft ein Repository lokal."));
        assertThat(ScriptSyntax.check(ScriptViews.Language.GHERKIN, "neu", ScriptTemplates.GHERKIN))
                .isEqualTo(Optional.of(ScriptTemplates.PLACEHOLDER_DESCRIPTION));
        assertThatThrownBy(() -> ScriptSyntax.check(ScriptViews.Language.GHERKIN, "x", "Funktionalität: X\n  kaputt:\n"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
