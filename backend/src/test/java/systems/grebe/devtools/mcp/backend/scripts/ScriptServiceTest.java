package systems.grebe.devtools.mcp.backend.scripts;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;
import systems.grebe.devtools.mcp.backend.skills.SkillTestSupport;
import systems.grebe.devtools.mcp.modules.scripts.ScriptViews;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Skript-Ablage gegen eine echte H2-Datei: Historie, Eigentümer, globale Vorlagen und Änderungsmeldungen. */
class ScriptServiceTest {

    private static final String SOURCE = "module { description 'Demo' }\ntool('hello') { description 'Hallo'; run { 'hi' } }";

    @TempDir
    Path home;

    private ConfigurableApplicationContext start(String email, boolean admin) {
        return SkillTestSupport.start(home, null, email, admin, null);
    }

    @Test
    void saveKeepsHistoryAndRejectsStaleRevisions() {
        try (ConfigurableApplicationContext ctx = start("anna@example.com", false)) {
            ScriptService scripts = ctx.getBean(ScriptService.class);
            AtomicInteger changes = new AtomicInteger();
            scripts.addChangeListener(changes::incrementAndGet);

            assertThat(scripts.save("demo", "Demo", SOURCE, null, null)).contains("angelegt (Revision 1)");
            assertThat(scripts.save("demo", "Demo", SOURCE, null, null)).contains("Keine Änderung");
            assertThat(scripts.save("demo", "Demo 2", SOURCE + "\n", "zweite", 1)).contains("Revision 2");
            assertThatThrownBy(() -> scripts.save("demo", "Demo 3", SOURCE, null, 1))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Revision 2 statt 1");
            assertThat(changes).hasValue(2);

            ScriptViews.Details d = scripts.details("demo").orElseThrow();
            assertThat(d.summary().description()).isEqualTo("Demo 2");
            assertThat(d.summary().scope()).isEqualTo(ScriptViews.Scope.OWN);
            assertThat(d.summary().updatedBy()).isEqualTo("anna@example.com");
            assertThat(d.revisions()).extracting(ScriptViews.Revision::action).containsExactly("update", "create");
            assertThat(d.revisions().getFirst().note()).isEqualTo("zweite");
            assertThat(d.revisions().getLast().content()).isEqualTo(SOURCE);

            assertThat(scripts.delete("demo")).contains("gelöscht");
            assertThat(scripts.overview()).isEmpty();
            assertThatThrownBy(() -> scripts.delete("demo")).hasMessageContaining("gibt es nicht");
        }
    }

    @Test
    void namesAreModuleIds() {
        try (ConfigurableApplicationContext ctx = start("anna@example.com", false)) {
            ScriptService scripts = ctx.getBean(ScriptService.class);
            for (String bad : new String[] {"Jira", "j", "my-tools", "1abc", "a_b"}) {
                assertThatThrownBy(() -> scripts.save(bad, "d", SOURCE, null, null))
                        .as(bad).hasMessageContaining("Ungültiger Skriptname");
            }
            assertThatThrownBy(() -> scripts.save("demo", " ", "tool('a') { run { 1 } }", null, null))
                    .hasMessageContaining("Beschreibung fehlt");
        }
    }

    @Test
    void syntaxIsCheckedWithoutRunningAndDescriptionIsReadFromTheScript() {
        try (ConfigurableApplicationContext ctx = start("anna@example.com", false)) {
            ScriptService scripts = ctx.getBean(ScriptService.class);
            assertThatThrownBy(() -> scripts.save("demo", "d", "module {\n description 'x'\n\ntool('a') {", null, null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Zeile");
            // ohne description (Web-UI): fester Text aus module { description '…' }; ausgeführt wird nichts
            scripts.save("demo", null, "System.exit(1)\nmodule { description 'Aus dem Skript' }", null, null);
            assertThat(scripts.details("demo").orElseThrow().summary().description()).isEqualTo("Aus dem Skript");
            // nicht ermittelbar (GString) → die bisherige bleibt
            scripts.save("demo", null, "def x = 1\nmodule { description \"Nr ${x}\" }", null, null);
            assertThat(scripts.details("demo").orElseThrow().summary().description()).isEqualTo("Aus dem Skript");
            // @Grab lädt beim Prüfen nichts herunter
            assertThat(ScriptSyntax.check("t.groovy", "@Grab('org.example:gibtsnicht:1.0')\n"
                    + "import org.example.X\nmodule { description 'grab' }")).contains("grab");
        }
    }

    @Test
    void globalTemplatesAreSharedAndShadowedByOwnScripts() {
        try (ConfigurableApplicationContext admin = start("admin@example.com", true)) {
            ScriptService scripts = admin.getBean(ScriptService.class);
            scripts.save("shared", "Geteilt", SOURCE, null, null);
            assertThat(scripts.publish("shared")).contains("veröffentlicht (Vorlage Revision 1)");
            assertThat(scripts.publish("shared")).contains("entspricht schon");
        }
        try (ConfigurableApplicationContext ctx = start("bob@example.com", false)) {
            ScriptService scripts = ctx.getBean(ScriptService.class);
            assertThat(scripts.overview()).singleElement().satisfies(s -> {
                assertThat(s.name()).isEqualTo("shared");
                assertThat(s.global()).isTrue();
            });
            assertThatThrownBy(() -> scripts.delete("shared")).hasMessageContaining("globale Vorlage");
            assertThatThrownBy(() -> scripts.publish("shared")).hasMessageContaining("Administratoren");
            assertThatThrownBy(() -> scripts.unpublish("shared")).hasMessageContaining("Administratoren");

            assertThat(scripts.save("shared", "Meins", SOURCE + "\n// eigen", null, 1)).contains("verdeckt");
            assertThat(scripts.overview()).singleElement().satisfies(s -> {
                assertThat(s.scope()).isEqualTo(ScriptViews.Scope.OWN);
                assertThat(s.description()).isEqualTo("Meins");
            });
            assertThat(scripts.delete("shared")).contains("Vorlage 'shared' gilt wieder");
            assertThat(scripts.overview()).singleElement().extracting(ScriptViews.Summary::global).isEqualTo(true);
        }
        try (ConfigurableApplicationContext admin = start("admin@example.com", true)) {
            ScriptService scripts = admin.getBean(ScriptService.class);
            assertThat(scripts.unpublish("shared")).contains("zurückgezogen");
            assertThat(scripts.overview()).extracting(ScriptViews.Summary::scope).containsExactly(ScriptViews.Scope.OWN);
        }
    }

    @Test
    void javaScriptsKeepTheirLanguageAndAreParsedWithoutTypes() {
        try (ConfigurableApplicationContext ctx = start("anna@example.com", false)) {
            ScriptService scripts = ctx.getBean(ScriptService.class);
            // nur geparst: unbekannte Typen (ToolModule liegt in der Desktop-App) sind hier kein Fehler
            String java = "public class Hello implements ToolModule {\n"
                    + "    public String description() { return \"Aus Java\"; }\n}\n";
            scripts.save("hello", ScriptViews.Language.JAVA, null, java, null, null);
            ScriptViews.Summary s = scripts.details("hello").orElseThrow().summary();
            assertThat(s.language()).isEqualTo(ScriptViews.Language.JAVA);
            assertThat(s.description()).isEqualTo("Aus Java");
            // ohne Sprachangabe bleibt Java
            scripts.save("hello", null, java.replace("Aus Java", "Neu"), null, null);
            assertThat(scripts.details("hello").orElseThrow().summary().language()).isEqualTo(ScriptViews.Language.JAVA);
            assertThatThrownBy(() -> scripts.save("hello", null, "public class Hello { void x( }", null, null))
                    .hasMessageContaining("Java-Skript").hasMessageContaining("Zeile 1");
            // Groovy-Skripte ohne Angabe bleiben Groovy
            scripts.save("demo", "Demo", SOURCE, null, null);
            assertThat(scripts.details("demo").orElseThrow().summary().language()).isEqualTo(ScriptViews.Language.GROOVY);
        }
    }
}
