package systems.grebe.devtools.mcp.modules.ticket;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.modules.ticket.spi.TicketProvider;
import systems.grebe.devtools.mcp.modules.ticket.spi.TicketSystem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ServiceLoader-Erkennung, Konfigurationsschema, Tool-Liste und Auswahl des Systems je Aufruf. */
class TicketModuleTest {

    TicketProviders providers = new TicketProviders();
    TicketModule module = new TicketModule(providers);

    private TicketEnvironment env(Map<String, String> values) {
        return new TicketEnvironment(providers, ModuleConfig.of(module.configSchema(), values));
    }

    @Test
    void serviceLoaderFindsBuiltInProvidersInPriorityOrder() {
        assertThat(providers.providers()).extracting(TicketProvider::id).containsExactly("jira", "github", "gitlab", "youtrack",
                "openproject");
        assertThat(module.configSchema()).extracting(ConfigField::key)
                .contains("defaultProvider", "jira.enabled", "jira.baseUrl", "jira.user", "jira.token", "jira.deployment",
                        "jira.defaultProject", "github.token", "github.statusField", "gitlab.baseUrl", "gitlab.defaultProject",
                        "youtrack.baseUrl", "youtrack.token", "openproject.baseUrl", "openproject.token");
        assertThat(module.configSchema().getFirst().options()).containsExactly("auto", "jira", "github", "gitlab", "youtrack",
                "openproject");
        assertThat(module.configSchema()).filteredOn(f -> f.key().equals("github.token"))
                .extracting(ConfigField::label).containsExactly("GitHub: Token");
        // Systeme sind standardmäßig aus – erst Token/Server eintragen
        assertThat(env(Map.of()).entries()).isEmpty();
    }

    @Test
    void offersAllReadToolsWithModulePrefixAndShellHint() {
        List<ToolCallback> tools = module.createTools(ModuleConfig.of(module.configSchema(), Map.of()));
        assertThat(tools).extracting(t -> t.getToolDefinition().name())
                .containsExactlyInAnyOrder("providers", "boards", "board", "search", "get", "status", "links", "transitions");
        assertThat(module.instructions()).contains("`ticket_get`", "`ticket_board`", "gh issue");
    }

    @Test
    void resolvesSystemByParameterKeyOwnershipOrDefault() {
        Map<String, String> v = new HashMap<>(Map.of("jira.enabled", "true", "jira.baseUrl", "https://jira.example.com",
                "github.enabled", "true", "gitlab.enabled", "true", "gitlab.baseUrl", "https://git.example.com"));
        TicketEnvironment env = env(v);

        assertThat(env.resolve("GitHub", null).provider().id()).isEqualTo("github");
        assertThat(env.resolve(null, "ABC-123").provider().id()).isEqualTo("jira");
        assertThat(env.resolve(null, "https://jira.example.com/browse/ABC-1").provider().id()).isEqualTo("jira");
        assertThat(env.resolve(null, "https://github.com/octo/app/issues/7").provider().id()).isEqualTo("github");
        assertThat(env.resolve(null, "https://git.example.com/grp/app/-/issues/7").provider().id()).isEqualTo("gitlab");
        // mehrdeutig und kein Standard gesetzt: das LLM soll wählen
        assertThatThrownBy(() -> env.resolve(null, "#12")).hasMessageContaining("Mehrere Ticket-Systeme aktiv")
                .hasMessageContaining("provider");
        assertThatThrownBy(() -> env.resolve("redmine", null)).hasMessageContaining("nicht aktiviert")
                .hasMessageContaining("ticket_providers");

        v.put("defaultProvider", "gitlab");
        assertThat(env(v).resolve(null, "#12").provider().id()).isEqualTo("gitlab");
        assertThat(env(v).resolve(null, "ABC-1").provider().id()).isEqualTo("jira"); // Schlüssel schlägt Standard

        assertThatThrownBy(() -> env(Map.of()).resolve(null, null)).hasMessageContaining("Kein Ticket-System aktiviert");
    }

    @Test
    void youTrackSharesJiraKeysButOwnsItsUrlsAndOpenProjectOwnsItsUrls() {
        Map<String, String> v = new HashMap<>(Map.of("jira.enabled", "true", "jira.baseUrl", "https://jira.example.com",
                "youtrack.enabled", "true", "youtrack.baseUrl", "https://yt.example.com/",
                "openproject.enabled", "true", "openproject.baseUrl", "https://op.example.com/api/v3"));
        TicketEnvironment env = env(v);

        assertThat(env.resolve(null, "https://yt.example.com/issue/ABC-1/titel").provider().id()).isEqualTo("youtrack");
        assertThat(env.resolve(null, "https://op.example.com/projects/demo/work_packages/42/activity").provider().id())
                .isEqualTo("openproject");
        // ABC-1 passt zu Jira und YouTrack: ohne Standard-System muss das LLM wählen
        assertThatThrownBy(() -> env.resolve(null, "ABC-1")).hasMessageContaining("Mehrere Ticket-Systeme aktiv");
        v.put("defaultProvider", "youtrack");
        assertThat(env(v).resolve(null, "ABC-1").provider().id()).isEqualTo("youtrack");
    }

    @Test
    void defaultProjectAppliesOnlyWhenNoneGiven() {
        TicketEnvironment env = env(Map.of("github.enabled", "true", "github.defaultProject", "octo/app"));
        TicketEnvironment.Entry e = env.resolve(null, null);
        assertThat(e.project(null)).isEqualTo("octo/app");
        assertThat(e.project(" ")).isEqualTo("octo/app");
        assertThat(e.project("other/repo")).isEqualTo("other/repo");
    }

    @Test
    void pickBoardMatchesIdThenExactThenPartialName() {
        List<TicketSystem.Board> boards = List.of(
                new TicketSystem.Board("1", "Team Alpha", "scrum", "A", null),
                new TicketSystem.Board("2", "Team Beta", "kanban", "A", null),
                new TicketSystem.Board("3", "Beta Support", "kanban", "A", null));
        assertThat(TicketSystem.pickBoard(boards, "2", "X").name()).isEqualTo("Team Beta");
        assertThat(TicketSystem.pickBoard(boards, "team beta", "X").id()).isEqualTo("2");
        assertThat(TicketSystem.pickBoard(boards, "alpha", "X").id()).isEqualTo("1");
        assertThatThrownBy(() -> TicketSystem.pickBoard(boards, "beta", "X")).hasMessageContaining("nicht eindeutig")
                .hasMessageContaining("Team Beta (2)").hasMessageContaining("Beta Support (3)");
        assertThatThrownBy(() -> TicketSystem.pickBoard(boards, null, "X")).hasMessageContaining("mehrere Boards");
        assertThat(TicketSystem.pickBoard(boards.subList(0, 1), null, "X").id()).isEqualTo("1");
    }
}
