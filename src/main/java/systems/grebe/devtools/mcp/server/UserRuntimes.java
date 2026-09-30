package systems.grebe.devtools.mcp.server;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;
import systems.grebe.devtools.mcp.account.AccountService;
import systems.grebe.devtools.mcp.account.UserAccount;
import systems.grebe.devtools.mcp.core.LiveInstructionsTransport;
import systems.grebe.devtools.mcp.core.McpRuntime;
import systems.grebe.devtools.mcp.core.ServerInstructions;
import systems.grebe.devtools.mcp.core.ToolRegistry;
import systems.grebe.devtools.mcp.core.ToolScope;
import systems.grebe.devtools.mcp.profile.ProfileService;
import systems.grebe.devtools.mcp.project.ProjectService;

/**
 * Ein eigener MCP-Server (Streamable HTTP) je angemeldetem Benutzer, lazy beim ersten Request angelegt. Server-Info,
 * Capabilities und Timeout entsprechen dem lokalen Server der Autokonfiguration; Instructions werden wie dort bei
 * jedem {@code initialize} neu gebaut.
 *
 * <p>Wird ein Benutzer geändert (Rolle, gesperrt, gelöscht, E-Mail), schließt die Runtime: seine Clients bekommen
 * „Session unbekannt“, verbinden sich neu und laufen dann mit dem aktuellen Stand – oder bekommen 401.
 *
 * <p>Profilwechsel und geänderte Einstellungen bauen dagegen nur die Tools neu auf ({@code tools/list_changed}); die
 * Client-Sessions bleiben. Beim Profilwechsel wird der Zustand des alten Profils geschlossen (SSH-Sitzungen …).
 */
@Component
public class UserRuntimes {

    private record Entry(WebMvcStreamableServerTransportProvider transport, McpRuntime runtime) {
    }

    private final ToolRegistry registry;
    private final ProfileService profiles;
    private final ProjectService projects;
    private final AccountService accounts;
    private final ServerInstructions instructions;
    private final McpSyncServer localServer;
    private final String endpoint;
    private final Duration requestTimeout;
    private final Map<Long, Entry> entries = new ConcurrentHashMap<>();

    public UserRuntimes(ToolRegistry registry, ProfileService profiles, ProjectService projects,
                        AccountService accounts,
                        ServerInstructions instructions, McpSyncServer localServer,
                        @Value("${spring.ai.mcp.server.streamable-http.mcp-endpoint:/mcp}") String endpoint,
                        @Value("${spring.ai.mcp.server.request-timeout:15m}") Duration requestTimeout) {
        this.registry = registry;
        this.profiles = profiles;
        this.projects = projects;
        this.accounts = accounts;
        this.instructions = instructions;
        this.localServer = localServer;
        this.endpoint = endpoint;
        this.requestTimeout = requestTimeout;
    }

    /** Router des MCP-Servers dieses Benutzers (legt ihn beim ersten Aufruf an). */
    public RouterFunction<ServerResponse> router(UserAccount user) {
        return entries.computeIfAbsent(user.id(), id -> create(user)).transport().getRouterFunction();
    }

    public static String scopeId(long userId) {
        return "user:" + userId;
    }

    private Entry create(UserAccount user) {
        WebMvcStreamableServerTransportProvider transport = WebMvcStreamableServerTransportProvider.builder()
                .mcpEndpoint(endpoint).build();
        McpRuntime runtime = registry.runtime(scope(user), () -> McpServer
                .sync(new LiveInstructionsTransport(transport, instructions::build))
                .serverInfo(localServer.getServerInfo())
                .capabilities(localServer.getServerCapabilities())
                .instructions(instructions.build())
                .requestTimeout(requestTimeout)
                .immediateExecution(true)
                .build());
        return new Entry(transport, runtime);
    }

    /** Scope des Benutzers mit seinem aktiven Profil. */
    ToolScope scope(UserAccount user) {
        return new ToolScope(scopeId(user.id()), Long.toString(user.id()), user.username(), user.email(),
                Long.toString(profiles.activeProfile(user.id()).id()), user.admin(),
                root -> projects.canWrite(user.id(), root));
    }

    /** Anderes Profil aktiv → gleiche Runtime, neuer Scope, Tools neu. */
    @EventListener
    public void onProfileSwitched(ProfileService.ProfileSwitchedEvent e) {
        if (entries.containsKey(e.userId())) {
            accounts.user(e.userId()).ifPresent(u -> registry.switchScope(scope(u)));
        }
    }

    /** Überschreibungen oder Sperren geändert → Tools der betroffenen Runtime(s) neu. */
    @EventListener
    public void onSettingsChanged(ProfileService.SettingsChangedEvent e) {
        if (e.userId() == null) {
            registry.refreshAllRuntimes();
        } else {
            registry.refreshRuntime(scopeId(e.userId()));
        }
    }

    /** Projekte oder Freigaben geändert → Projektlisten der betroffenen Benutzer neu. */
    @EventListener
    public void onProjectsChanged(ProjectService.ProjectsChangedEvent e) {
        e.userIds().forEach(id -> registry.refreshRuntime(scopeId(id)));
    }

    /**
     * Geänderter Benutzer → Runtime schließen; die nächste Anfrage baut sie mit dem neuen Stand neu. Die übrigen
     * Runtimes bauen neu auf, weil mit einem gelöschten Benutzer auch seine Projekte samt Freigaben verschwinden.
     */
    @EventListener
    public void onAccountChanged(AccountService.AccountChangedEvent e) {
        close(e.userId());
        registry.refreshAllRuntimes();
    }

    public void close(long userId) {
        Entry removed = entries.remove(userId);
        if (removed != null) {
            registry.closeRuntime(scopeId(userId));
        }
    }
}
