package systems.grebe.devtools.mcp.modules.skills;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Gespeicherte Skills als Agent Skills: SKILL.md mit Frontmatter, Manifest mit Größe und SHA-256, Verzeichnisse. */
class SkillCatalogTest {

    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0, 0, 0, 13};
    static final String PNG_SHA = SkillCatalog.digest(PNG).substring("sha256:".length());

    @TempDir
    Path temp;

    SkillBackend backend = mock(SkillBackend.class);
    SkillCatalog catalog;

    static SkillViews.Summary summary(String name, String description) {
        return new SkillViews.Summary(name, description, "ops", List.of("deploy", "prod"), 3, 0, null,
                Instant.EPOCH, 2, SkillViews.Scope.OWN, null, null, List.of("git_push", "pr_*"));
    }

    static SkillViews.Details details(String name, String description) {
        return new SkillViews.Details(summary(name, description), "## Schritte\n1. bauen\n2. `references/check.md` lesen\n",
                Instant.EPOCH, List.of(
                new SkillViews.File("references/check.md", "# Liste\n- Tests grün\n", Instant.EPOCH),
                new SkillViews.File("assets/logo.png", null, Instant.EPOCH, PNG.length, "image/png", PNG_SHA)),
                List.of());
    }

    @BeforeEach
    void setUp() throws Exception {
        catalog = new SkillCatalog(backend, temp.resolve("tmp"));
        SkillViews.Details d = details("deploy", "Verwenden, wenn deployt wird: \"prod\" zuerst prüfen");
        when(backend.overview()).thenReturn(List.of(d.summary()));
        when(backend.details("deploy")).thenReturn(Optional.of(d));
        when(backend.file("deploy", "references/check.md")).thenReturn(Optional.of(d.files().get(0)));
        when(backend.file("deploy", "assets/logo.png")).thenReturn(Optional.of(d.files().get(1)));
        when(backend.exportFile(eq("deploy"), eq("assets/logo.png"), any())).thenAnswer(inv -> {
            Files.write(inv.<Path>getArgument(2), PNG);
            return "ok";
        });
    }

    @Test
    void skillMdCarriesFrontmatterThatParsesToTheListedFields() {
        SkillCatalog.Entry e = catalog.list().getFirst();
        assertThat(e.uri()).isEqualTo("skill://deploy/SKILL.md");
        assertThat(e.skillMd()).startsWith("---\nname: \"deploy\"\n")
                .contains("\n---\n\n## Schritte\n1. bauen\n", "## Zusatzdateien",
                        "- [references/check.md](references/check.md)");
        String yaml = e.skillMd().substring(4, e.skillMd().indexOf("\n---\n"));
        Map<String, Object> parsed = new Yaml().load(yaml);
        // Hosts vergleichen das Frontmatter der Datei Feld für Feld mit dem aus skills/list
        assertThat(parsed).isEqualTo(e.frontmatter());
        assertThat(e.frontmatter()).containsEntry("description", "Verwenden, wenn deployt wird: \"prod\" zuerst prüfen");
        assertThat(e.frontmatter().get("metadata")).isEqualTo(Map.of("category", "ops", "tags", "deploy, prod",
                "triggers", "git_push, pr_*", "revision", "3", "scope", "own"));
    }

    @Test
    void manifestMatchesTheBytesServed() {
        SkillCatalog.Entry e = catalog.list().getFirst();
        assertThat(e.resources()).extracting(SkillCatalog.Resource::uri).containsExactly("skill://deploy/SKILL.md",
                "skill://deploy/references/check.md", "skill://deploy/assets/logo.png");
        for (SkillCatalog.Resource r : e.resources()) {
            SkillCatalog.Content c = catalog.read(r.uri());
            byte[] bytes = c.text() != null ? c.text().getBytes(StandardCharsets.UTF_8) : c.bytes();
            assertThat(bytes).as(r.uri()).hasSize((int) r.size());
            assertThat(SkillCatalog.digest(bytes)).as(r.uri()).isEqualTo(r.digest());
            assertThat(c.mimeType()).isEqualTo(r.mimeType());
        }
        assertThat(catalog.read("skill://deploy/assets/logo.png").bytes()).isEqualTo(PNG);
        assertThat(catalog.read("skill://deploy/SKILL.md").mimeType()).isEqualTo("text/markdown");
        assertThat(temp.resolve("tmp")).isEmptyDirectory(); // ausgelesene Anhänge wieder weg
    }

    @Test
    void directoriesListDirectChildrenOnly() {
        assertThat(catalog.directory("skill://deploy")).containsExactly(
                new SkillCatalog.Child("skill://deploy/SKILL.md", "SKILL.md", "text/markdown"),
                new SkillCatalog.Child("skill://deploy/references", "references", "inode/directory"),
                new SkillCatalog.Child("skill://deploy/assets", "assets", "inode/directory"));
        assertThat(catalog.directory("skill://deploy/references")).containsExactly(
                new SkillCatalog.Child("skill://deploy/references/check.md", "check.md", "text/markdown"));
        assertThatThrownBy(() -> catalog.directory("skill://deploy/nix")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> catalog.directory("skill://fremd")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownSkillsAndFilesAreInvalidParams() {
        assertThat(catalog.byUri("skill://fremd/SKILL.md")).isEmpty();
        assertThatThrownBy(() -> catalog.byUri("skill://deploy/references/check.md"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> catalog.read("skill://deploy/geheim.txt")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("keine Datei");
        assertThatThrownBy(() -> catalog.read("skill://deploy/references/../SKILL.md"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> catalog.read("file:///etc/passwd")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pathsArePercentEncodedInUris() {
        assertThat(SkillCatalog.uri("deploy", "references/zwei wörter.md"))
                .isEqualTo("skill://deploy/references/zwei%20w%C3%B6rter.md");
        assertThat(SkillCatalog.parse("skill://deploy/references/zwei%20w%C3%B6rter.md"))
                .isEqualTo(new SkillCatalog.Location("deploy", "references/zwei wörter.md"));
        assertThat(SkillCatalog.parse("skill://deploy/")).isEqualTo(new SkillCatalog.Location("deploy", ""));
        assertThat(SkillCatalog.parse("skill://a/b+c.md").path()).isEqualTo("b+c.md");
    }

    @Test
    void yamlScalarsStayText() {
        Map<String, Object> parsed = new Yaml().load("a: " + SkillCatalog.yaml("1.0") + "\nb: "
                + SkillCatalog.yaml("true") + "\nc: " + SkillCatalog.yaml("x: y # z\n\t\"q\" \\ \u0007"));
        assertThat(parsed).isEqualTo(Map.of("a", "1.0", "b", "true", "c", "x: y # z\n\t\"q\" \\ \u0007"));
    }
}
