package systems.grebe.devtools.mcp.backend;

import java.util.List;
import java.util.function.Supplier;

import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.ContextValue;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SubscriptionMapping;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import systems.grebe.devtools.mcp.backend.account.UserAccount;
import systems.grebe.devtools.mcp.api.Catalog;
import systems.grebe.devtools.mcp.api.Me;
import systems.grebe.devtools.mcp.api.ModuleDescriptor;
import systems.grebe.devtools.mcp.api.ModuleOverlay;
import systems.grebe.devtools.mcp.api.ProjectInfo;
import systems.grebe.devtools.mcp.api.SettingsSnapshot;
import systems.grebe.devtools.mcp.backend.catalog.ModuleCatalog;
import systems.grebe.devtools.mcp.backend.skills.SkillService;
import systems.grebe.devtools.mcp.modules.skills.SkillViews;
import systems.grebe.devtools.mcp.profile.Overrides;
import systems.grebe.devtools.mcp.backend.profile.ProfileService;
import systems.grebe.devtools.mcp.backend.project.Project;
import systems.grebe.devtools.mcp.backend.project.ProjectService;

/**
 * GraphQL-API ({@code schema.graphqls}) für die Desktop-Apps: Benutzer und Profile, Modul-Katalog,
 * Einstellungs-Vorgaben, Projekte und Skills. Jede Operation braucht einen angemeldeten Benutzer
 * ({@link GraphQlAuth}); Subscriptions liefern sofort den aktuellen Stand und danach jede Änderung ({@link ChangeBus}).
 */
@Controller
public class BackendGraphQlController {

    private static final int DEFAULT_MAX_CONTENT = 100_000;
    private static final int LIMIT_MAX_CONTENT = 1_000_000;

    private final ProfileService profiles;
    private final ProjectService projects;
    private final ModuleCatalog catalog;
    private final SkillService skills;
    private final ChangeBus bus;

    public BackendGraphQlController(ProfileService profiles, ProjectService projects, ModuleCatalog catalog,
                                    SkillService skills, ChangeBus bus) {
        this.profiles = profiles;
        this.projects = projects;
        this.catalog = catalog;
        this.skills = skills;
        this.bus = bus;
    }

    // ---------------------------------------------------------------- Benutzer, Katalog, Einstellungen

    @QueryMapping
    public Me me(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user) {
        UserAccount u = require(user);
        long active = profiles.activeProfile(u.id()).id();
        List<Me.ProfileInfo> list = profiles.profiles(u.id()).stream()
                .map(p -> new Me.ProfileInfo(p.id(), p.name(), p.description())).toList();
        return new Me(u.id(), u.username(), u.displayName(), u.email(), u.admin(), list, active);
    }

    @QueryMapping
    public List<ModuleDescriptor> catalog(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user) {
        require(user);
        return catalog.modules();
    }

    @MutationMapping
    public boolean reportCatalog(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                                 @Argument List<ModuleDescriptor> modules) {
        require(user);
        catalog.report(new Catalog(modules));
        return true;
    }

    @QueryMapping
    public SettingsSnapshot settings(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user) {
        return profiles.snapshot(require(user).id());
    }

    @QueryMapping
    public ModuleOverlay overrides(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                                   @Argument Overrides.Level level, @Argument String moduleId) {
        UserAccount u = require(user);
        Overrides o = profiles.overrides(level, levelId(u, level), moduleId);
        return ModuleOverlay.of(moduleId, o.enabled(), o.tools(), o.values(), profiles.locks(moduleId));
    }

    @MutationMapping
    public SettingsSnapshot activateProfile(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                                            @Argument long profileId) {
        UserAccount u = require(user);
        profiles.activate(u.id(), profileId);
        return profiles.snapshot(u.id());
    }

    @MutationMapping
    public SettingsSnapshot saveOverrides(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                                          @Argument Overrides.Level level, @Argument String moduleId,
                                          @Argument ModuleOverlay input) {
        UserAccount u = require(user);
        Overrides o = new Overrides(input.enabled(), input.toolMap(), input.valueMap());
        if (level == Overrides.Level.GLOBAL) {
            if (!u.admin()) {
                throw new GraphQlErrors.Forbidden("Globale Einstellungen ändern nur Administratoren.");
            }
            profiles.saveGlobal(moduleId, o);
        } else {
            profiles.saveOverrides(u.id(), level, levelId(u, level), moduleId, o);
        }
        return profiles.snapshot(u.id());
    }

    private long levelId(UserAccount u, Overrides.Level level) {
        return switch (level) {
            case GLOBAL -> 0;
            case USER -> u.id();
            case PROFILE -> profiles.activeProfile(u.id()).id();
        };
    }

    // ---------------------------------------------------------------- Projekte

    @QueryMapping
    public List<ProjectInfo> projects(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user) {
        return projectsOf(require(user));
    }

    @MutationMapping
    public ProjectInfo createProject(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                                     @Argument String name, @Argument String description, @Argument String sonarKey,
                                     @Argument String ticketProject) {
        UserAccount u = require(user);
        Project p = projects.create(u.id(), name, description, sonarKey, ticketProject);
        return info(new Project.Visible(p, Project.Access.OWNER));
    }

    private List<ProjectInfo> projectsOf(UserAccount u) {
        return projects.visible(u.id()).stream().map(BackendGraphQlController::info).toList();
    }

    static ProjectInfo info(Project.Visible v) {
        Project p = v.project();
        return new ProjectInfo(p.id(), p.name(), p.ownerName(), v.toolName(), v.access().canWrite(),
                v.access().name(), p.description(), p.sonarKey(), p.ticketProject());
    }

    // ---------------------------------------------------------------- Skills

    @QueryMapping
    public List<SkillViews.Summary> skills(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user) {
        return as(user, skills::overview);
    }

    @QueryMapping
    public SkillViews.Details skill(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                                   @Argument String name) {
        return as(user, () -> skills.details(name).orElse(null));
    }

    @QueryMapping
    public int skillCount(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user) {
        return as(user, skills::visibleCount);
    }

    @QueryMapping
    public String skillList(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                            @Argument String query, @Argument String category) {
        return as(user, () -> skills.list(query, category));
    }

    @QueryMapping
    public String skillView(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                            @Argument String name, @Argument String filePath) {
        return as(user, () -> skills.view(name, filePath));
    }

    @QueryMapping
    public String skillHistory(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                               @Argument String name, @Argument Integer revision) {
        return as(user, () -> skills.history(name, revision));
    }

    @MutationMapping
    public String createSkill(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                              @Argument String name, @Argument String description, @Argument String content,
                              @Argument String category, @Argument List<String> tags,
                              @Argument Integer maxContentChars) {
        return as(user, () -> skills.create(name, description, content, category, tags, max(maxContentChars)));
    }

    @MutationMapping
    public String updateSkill(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                              @Argument String name, @Argument String description, @Argument String content,
                              @Argument String category, @Argument List<String> tags, @Argument String note,
                              @Argument Integer expectedRevision, @Argument Integer maxContentChars) {
        return as(user, () -> skills.update(name, description, content, category, tags, note, expectedRevision,
                max(maxContentChars)));
    }

    @MutationMapping
    public String patchSkill(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                             @Argument String name, @Argument String oldString, @Argument String newString,
                             @Argument Boolean replaceAll, @Argument String filePath, @Argument String note,
                             @Argument Integer expectedRevision, @Argument Integer maxContentChars) {
        return as(user, () -> skills.patch(name, oldString, newString, replaceAll, filePath, note, expectedRevision,
                max(maxContentChars)));
    }

    @MutationMapping
    public String writeSkillFile(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                                 @Argument String name, @Argument String filePath, @Argument String content,
                                 @Argument String note, @Argument Integer maxContentChars) {
        return as(user, () -> skills.writeFile(name, filePath, content, note, max(maxContentChars)));
    }

    @MutationMapping
    public String removeSkillFile(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                                  @Argument String name, @Argument String filePath, @Argument String note) {
        return as(user, () -> skills.removeFile(name, filePath, note));
    }

    @MutationMapping
    public String deleteSkill(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                              @Argument String name) {
        return as(user, () -> skills.delete(name));
    }

    @MutationMapping
    public String publishSkill(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                               @Argument String name) {
        return as(user, () -> skills.publish(name));
    }

    @MutationMapping
    public String unpublishSkill(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                                 @Argument String name) {
        return as(user, () -> skills.unpublish(name));
    }

    private static <T> T as(UserAccount user, Supplier<T> body) {
        return SkillCaller.as(require(user), body);
    }

    private static int max(Integer requested) {
        return requested == null ? DEFAULT_MAX_CONTENT : Math.min(requested, LIMIT_MAX_CONTENT);
    }

    // ---------------------------------------------------------------- Subscriptions

    @SubscriptionMapping
    public Flux<SettingsSnapshot> settingsChanged(
            @ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user) {
        UserAccount u = require(user);
        return stream(bus.changes(BackendChanged.Topic.SETTINGS, u.id()), () -> profiles.snapshot(u.id()));
    }

    @SubscriptionMapping
    public Flux<List<ProjectInfo>> projectsChanged(
            @ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user) {
        UserAccount u = require(user);
        return stream(bus.changes(BackendChanged.Topic.PROJECTS, u.id()), () -> projectsOf(u));
    }

    @SubscriptionMapping
    public Flux<Integer> skillsChanged(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user) {
        UserAccount u = require(user);
        return stream(bus.changes(BackendChanged.Topic.SKILLS, u.id()),
                () -> SkillCaller.as(u, skills::visibleCount));
    }

    /** Aktueller Stand, danach bei jedem Ereignis neu gelesen (Datenbankzugriffe außerhalb der Event-Threads). */
    private static <T> Flux<T> stream(Flux<BackendChanged> events, Supplier<T> load) {
        return Flux.concat(Mono.just(true), events.map(e -> true))
                .publishOn(Schedulers.boundedElastic())
                .map(x -> load.get());
    }

    private static UserAccount require(UserAccount user) {
        if (user == null) {
            throw new GraphQlErrors.Unauthorized();
        }
        return user;
    }
}
