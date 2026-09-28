package systems.grebe.devtools.mcp.ui;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import systems.grebe.devtools.mcp.modules.skills.SkillViews;

import static org.assertj.core.api.Assertions.assertThat;

/** Filter- und Anzeigelogik der Skill-Übersicht (ohne JavaFX-Toolkit). */
class SkillsViewTest {

    private static SkillViews.Summary skill(String name, String category, String... tags) {
        return new SkillViews.Summary(name, "Beschreibung von " + name, category, List.of(tags), 1, 0, null,
                Instant.parse("2026-09-28T10:00:00Z"), 0);
    }

    @Test
    void categoryFilter() {
        SkillViews.Summary a = skill("a", "devops");
        SkillViews.Summary none = skill("b", null);
        assertThat(SkillsView.matchesCategory(a, SkillsView.ALL_CATEGORIES)).isTrue();
        assertThat(SkillsView.matchesCategory(a, null)).isTrue();
        assertThat(SkillsView.matchesCategory(a, "devops")).isTrue();
        assertThat(SkillsView.matchesCategory(a, "testing")).isFalse();
        assertThat(SkillsView.matchesCategory(none, SkillsView.NO_CATEGORY)).isTrue();
        assertThat(SkillsView.matchesCategory(a, SkillsView.NO_CATEGORY)).isFalse();
    }

    @Test
    void searchCoversNameDescriptionAndTags() {
        SkillViews.Summary s = skill("wildfly-heap-leak", "software-development", "jvm", "leak-suche");
        assertThat(SkillsView.matchesQuery(s, "")).isTrue();
        assertThat(SkillsView.matchesQuery(s, "heap")).isTrue();
        assertThat(SkillsView.matchesQuery(s, "beschreibung von wild")).isTrue();
        assertThat(SkillsView.matchesQuery(s, "leak-such")).isTrue();
        assertThat(SkillsView.matchesQuery(s, "gradle")).isFalse();
    }

    @Test
    void categoriesAreSortedAndIncludeUncategorized() {
        assertThat(SkillsView.categories(List.of(skill("a", "testing"), skill("b", null), skill("c", "devops"),
                skill("d", "testing")))).containsExactly(SkillsView.NO_CATEGORY, "devops", "testing");
    }

    @Test
    void metaLineShowsUsage() {
        SkillViews.Summary s = new SkillViews.Summary("x", "d", null, List.of("a", "b"), 3, 2,
                Instant.parse("2026-09-28T11:00:00Z"), Instant.parse("2026-09-28T10:00:00Z"), 0);
        String line = SkillsView.metaLine(new SkillViews.Details(s, "c", Instant.parse("2026-09-27T09:00:00Z"),
                List.of(), List.of()));
        assertThat(line).startsWith(SkillsView.NO_CATEGORY).contains("Tags: a, b", "Revision 3", "2× geladen", "zuletzt");
    }
}
