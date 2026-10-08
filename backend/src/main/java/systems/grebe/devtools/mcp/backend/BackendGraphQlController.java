package systems.grebe.devtools.mcp.backend;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.ContextValue;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SubscriptionMapping;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Flux;
import systems.grebe.devtools.mcp.backend.account.TokenService;
import systems.grebe.devtools.mcp.backend.account.UserAccount;
import systems.grebe.devtools.mcp.api.Catalog;
import systems.grebe.devtools.mcp.api.Me;
import systems.grebe.devtools.mcp.api.ModuleDescriptor;
import systems.grebe.devtools.mcp.api.ModuleOverlay;
import systems.grebe.devtools.mcp.api.Permission;
import systems.grebe.devtools.mcp.api.ProjectInfo;
import systems.grebe.devtools.mcp.api.SettingsSnapshot;
import systems.grebe.devtools.mcp.backend.catalog.ModuleCatalog;
import systems.grebe.devtools.mcp.backend.memories.Memory;
import systems.grebe.devtools.mcp.backend.memories.MemoryService;
import systems.grebe.devtools.mcp.backend.scripts.ScriptService;
import systems.grebe.devtools.mcp.backend.skills.SkillService;
import systems.grebe.devtools.mcp.modules.memories.MemoryViews;
import systems.grebe.devtools.mcp.modules.scripts.ScriptViews;
import systems.grebe.devtools.mcp.modules.skills.SkillViews;
import systems.grebe.devtools.mcp.profile.Overrides;
import systems.grebe.devtools.mcp.backend.profile.ProfileService;
import systems.grebe.devtools.mcp.backend.project.Project;
import systems.grebe.devtools.mcp.backend.project.ProjectService;

/**
 * GraphQL-API ({@code schema.graphqls}) für die Desktop-Apps: Benutzer und Profile, Modul-Katalog,
 * Einstellungs-Vorgaben, Projekte, Skills, Memories und Skripte. Jede Operation braucht einen angemeldeten Benutzer
 * ({@link GraphQlAuth}); Subscriptions liefern sofort den aktuellen Stand und danach jede Änderung ({@link ChangeBus}).
 */
@Controller
public class BackendGraphQlController {

    private static final int DEFAULT_MAX_CONTENT = 100_000;
    private static final int LIMIT_MAX_CONTENT = 1_000_000;
    private static final int DEFAULT_MAX_MEMORY = 20_000;
    private static final int OVERVIEW_LIMIT = 200;

    private final ProfileService profiles;
    private final ProjectService projects;
    private final ModuleCatalog catalog;
    private final SkillService skills;
    private final MemoryService memories;
    private final ScriptService scripts;
    private final ChangeBus bus;

    public BackendGraphQlController(ProfileService profiles, ProjectService projects, ModuleCatalog catalog,
                                    SkillService skills, MemoryService memories, ScriptService scripts,
                                    ChangeBus bus) {
        this.profiles = profiles;
        this.projects = projects;
        this.catalog = catalog;
        this.skills = skills;
        this.memories = memories;
        this.scripts = scripts;
        this.bus = bus;
    }

    // ---------------------------------------------------------------- Benutzer, Katalog, Einstellungen

    @QueryMapping
    public Me me(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user) {
        UserAccount u = GraphQlAuth.requireSignedIn(user);
        long active = profiles.activeProfile(u.id()).id();
        List<Me.ProfileInfo> list = profiles.profiles(u.id()).stream()
                .map(p -> new Me.ProfileInfo(p.id(), p.name(), p.description())).toList();
        return new Me(u.id(), u.username(), u.displayName(), u.email(), u.admin(), list, active, u.roles(),
                u.grants().list(), u.passwordChangeRequired());
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
        return snapshot(require(user));
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
        return snapshot(u);
    }

    @MutationMapping
    public SettingsSnapshot saveOverrides(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                                          @Argument Overrides.Level level, @Argument String moduleId,
                                          @Argument ModuleOverlay input) {
        UserAccount u = require(user);
        Overrides o = new Overrides(input.enabled(), input.toolMap(), input.valueMap());
        if (level == Overrides.Level.GLOBAL) {
            GraphQlAuth.require(u, Permission.SETTINGS_GLOBAL);
            profiles.saveGlobal(moduleId, o);
        } else {
            GraphQlAuth.require(u, Permission.SETTINGS_OWN);
            profiles.saveOverrides(u.id(), level, levelId(u, level), moduleId, o);
        }
        return snapshot(u);
    }

    /** Stand mit Revision; der Zähler wird vor dem Lesen genommen, der Stand ist also mindestens so neu. */
    private SettingsSnapshot snapshot(UserAccount u) {
        long revision = bus.revision();
        return profiles.snapshot(u.id()).withRevision(revision);
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

    @QueryMapping
    public SkillViews.File skillFile(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                                     @Argument String name, @Argument String filePath) {
        return as(user, () -> skills.file(name, filePath).orElse(null));
    }

    @MutationMapping
    public String createSkill(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                              @Argument String name, @Argument String description, @Argument String content,
                              @Argument String category, @Argument List<String> tags,
                              @Argument List<String> triggers, @Argument Integer maxContentChars) {
        return asTool(user, "skills", "skills_create",
                () -> skills.create(name, description, content, category, tags, triggers,
                        max(maxContentChars)));
    }

    @MutationMapping
    public String updateSkill(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                              @Argument String name, @Argument String description, @Argument String content,
                              @Argument String category, @Argument List<String> tags,
                              @Argument List<String> triggers, @Argument String note,
                              @Argument Integer expectedRevision, @Argument Integer maxContentChars) {
        return asTool(user, "skills", "skills_update",
                () -> skills.update(name, description, content, category, tags, triggers, note,
                        expectedRevision, max(maxContentChars)));
    }

    @MutationMapping
    public String patchSkill(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                             @Argument String name, @Argument String oldString, @Argument String newString,
                             @Argument Boolean replaceAll, @Argument String filePath, @Argument String note,
                             @Argument Integer expectedRevision, @Argument Integer maxContentChars) {
        return asTool(user, "skills", "skills_patch",
                () -> skills.patch(name, oldString, newString, replaceAll, filePath, note, expectedRevision,
                        max(maxContentChars)));
    }

    @MutationMapping
    public String writeSkillFile(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                                 @Argument String name, @Argument String filePath, @Argument String content,
                                 @Argument String note, @Argument Integer maxContentChars) {
        return asTool(user, "skills", "skills_write_file",
                () -> skills.writeFile(name, filePath, content, note, max(maxContentChars)));
    }

    @MutationMapping
    public String attachSkillFile(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                                  @Argument String name, @Argument String filePath, @Argument String blob,
                                  @Argument String mediaType, @Argument String note) {
        return asTool(user, "skills", "skills_write_file",
                () -> skills.attachBlob(name, filePath, blob, mediaType, note));
    }

    @MutationMapping
    public String removeSkillFile(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                                  @Argument String name, @Argument String filePath, @Argument String note) {
        return asTool(user, "skills", "skills_remove_file", () -> skills.removeFile(name, filePath, note));
    }

    @MutationMapping
    public String deleteSkill(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                              @Argument String name) {
        return asTool(user, "skills", "skills_delete", () -> skills.delete(name));
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

    // ---------------------------------------------------------------- Memories

    @QueryMapping
    public List<MemoryViews.Entry> memories(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                                            @Argument String query, @Argument String project, @Argument String skill,
                                            @Argument Integer limit) {
        int max = limit == null ? OVERVIEW_LIMIT : Math.min(limit, OVERVIEW_LIMIT);
        return as(user, () -> memories.overview(query, project, skill, max));
    }

    @QueryMapping
    public MemoryViews.Entry memory(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                                    @Argument long id) {
        return as(user, () -> memories.details(id).orElse(null));
    }

    @QueryMapping
    public int memoryCount(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user) {
        return as(user, memories::count);
    }

    @QueryMapping
    public String memorySearch(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                               @Argument String query, @Argument String project, @Argument String skill,
                               @Argument String tag, @Argument MemoryViews.Type type, @Argument Integer days,
                               @Argument Integer limit) {
        return as(user, () -> memories.search(query, project, skill, tag, type, days, limit));
    }

    @QueryMapping
    public String memoryView(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                             @Argument long id) {
        return as(user, () -> memories.view(id));
    }

    @QueryMapping
    public MemoryViews.File memoryFile(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                                       @Argument long id, @Argument String filePath) {
        return as(user, () -> memories.file(id, filePath).orElse(null));
    }

    @QueryMapping
    public String memoryFileView(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                                 @Argument long id, @Argument String filePath) {
        return as(user, () -> memories.viewFile(id, filePath));
    }

    @QueryMapping
    public List<String> memoryReferences(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user) {
        return as(user, () -> List.copyOf(memories.references()));
    }

    @QueryMapping
    public List<MemoryViews.Entry> relatedMemories(
            @ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
            @Argument List<String> references, @Argument String skill, @Argument Integer limit) {
        return as(user, () -> memories.related(references, skill, limit == null ? 3 : limit));
    }

    @MutationMapping
    public String saveMemory(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                             @Argument String title, @Argument String content, @Argument String project,
                             @Argument String skill, @Argument String reference, @Argument List<String> tags,
                             @Argument MemoryViews.Type type, @Argument Integer maxContentChars) {
        Supplier<String> save = () -> memories.save(title, content, type, project, skill, reference, tags,
                maxMemory(maxContentChars));
        // temporäre Memories und Rückrufe ohne Recht auf das Tool
        return type != null && type.ephemeral() ? as(user, save) : asTool(user, "memories", "memories_save", save);
    }

    @MutationMapping
    public String updateMemory(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                               @Argument long id, @Argument String title, @Argument String content,
                               @Argument String append, @Argument String project, @Argument String skill,
                               @Argument String reference, @Argument List<String> tags,
                               @Argument MemoryViews.Type type, @Argument Boolean temporaryOnly,
                               @Argument Integer maxContentChars) {
        return asToolOrTemporary(user, "memories_update", id, Boolean.TRUE.equals(temporaryOnly),
                tempOnly -> memories.update(id, title, content, append, type, project, skill, reference, tags,
                        tempOnly, maxMemory(maxContentChars)));
    }

    @MutationMapping
    public String deleteMemory(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                               @Argument long id, @Argument Boolean temporaryOnly) {
        return asToolOrTemporary(user, "memories_delete", id, Boolean.TRUE.equals(temporaryOnly),
                tempOnly -> memories.delete(id, tempOnly));
    }

    /** Anhängen und Entfernen von Dateien ändern die Memory – gleiches Recht wie {@code updateMemory}. */
    @MutationMapping
    public String attachMemoryFile(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                                   @Argument long id, @Argument String filePath, @Argument String blob,
                                   @Argument String mediaType, @Argument Boolean temporaryOnly) {
        return asToolOrTemporary(user, "memories_update", id, Boolean.TRUE.equals(temporaryOnly),
                tempOnly -> memories.attachBlob(id, filePath, blob, mediaType, tempOnly));
    }

    @MutationMapping
    public String removeMemoryFile(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                                   @Argument long id, @Argument String filePath, @Argument Boolean temporaryOnly) {
        return asToolOrTemporary(user, "memories_update", id, Boolean.TRUE.equals(temporaryOnly),
                tempOnly -> memories.removeFile(id, filePath, tempOnly));
    }

    /**
     * Temporäre Memories dürfen ohne Recht auf das Tool geändert und gelöscht werden: Fehlt das Recht (oder verlangt
     * der Aufrufer es so), läuft die Operation mit {@code temporaryOnly}; eine dauerhafte Memory lehnt sie dann ab.
     */
    private <T> T asToolOrTemporary(UserAccount user, String tool, long id, boolean temporaryOnly,
                                    Function<Boolean, T> body) {
        UserAccount u = GraphQlAuth.require(user);
        boolean permitted = u.grants().tool("memories", tool);
        if (permitted && !temporaryOnly) {
            return SkillCaller.as(u, () -> body.apply(false));
        }
        return SkillCaller.as(u, () -> {
            if (!permitted && !memories.temporary(id)) {
                throw new GraphQlErrors.Forbidden("Dafür fehlt das Recht auf das Tool " + tool + ".");
            }
            return body.apply(true);
        });
    }

    private static int maxMemory(Integer requested) {
        return requested == null ? DEFAULT_MAX_MEMORY
                : Math.min(requested, Memory.CONTENT_COLUMN);
    }

    // ---------------------------------------------------------------- Skripte (Groovy, Java)

    @QueryMapping
    public List<ScriptViews.Summary> scripts(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user) {
        return as(user, scripts::overview);
    }

    @QueryMapping
    public ScriptViews.Details script(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                                      @Argument String name) {
        return as(user, () -> scripts.details(name).orElse(null));
    }

    @MutationMapping
    public String saveScript(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                             @Argument String name, @Argument ScriptViews.Language language,
                             @Argument String description, @Argument String content, @Argument String note,
                             @Argument Integer expectedRevision) {
        return asTool(user, "scripts", "scripts_save",
                () -> scripts.save(name, language, description, content, note, expectedRevision));
    }

    @MutationMapping
    public String deleteScript(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                               @Argument String name) {
        return asTool(user, "scripts", "scripts_delete", () -> scripts.delete(name));
    }

    @MutationMapping
    public String publishScript(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                                @Argument String name) {
        return as(user, () -> scripts.publish(name));
    }

    @MutationMapping
    public String unpublishScript(@ContextValue(name = GraphQlAuth.USER, required = false) UserAccount user,
                                  @Argument String name) {
        return as(user, () -> scripts.unpublish(name));
    }

    private static <T> T as(UserAccount user, Supplier<T> body) {
        return SkillCaller.as(GraphQlAuth.require(user), body);
    }

    /** Schreibende Operationen brauchen das Recht auf das gleichnamige Tool – auch aus der Oberfläche heraus. */
    private static <T> T asTool(UserAccount user, String moduleId, String tool, Supplier<T> body) {
        return SkillCaller.as(GraphQlAuth.requireTool(user, moduleId, tool), body);
    }

    private static int max(Integer requested) {
        return requested == null ? DEFAULT_MAX_CONTENT : Math.min(requested, LIMIT_MAX_CONTENT);
    }

    // ---------------------------------------------------------------- Subscriptions

    @SubscriptionMapping
    public Flux<SettingsSnapshot> settingsChanged(
            @ContextValue(name = GraphQlAuth.TOKEN, required = false) TokenService.TokenUser token) {
        return bus.stateStream(BackendChanged.Topic.SETTINGS, GraphQlAuth.require(token), this::snapshot);
    }

    @SubscriptionMapping
    public Flux<List<ProjectInfo>> projectsChanged(
            @ContextValue(name = GraphQlAuth.TOKEN, required = false) TokenService.TokenUser token) {
        return bus.stateStream(BackendChanged.Topic.PROJECTS, GraphQlAuth.require(token), this::projectsOf);
    }

    @SubscriptionMapping
    public Flux<Integer> skillsChanged(
            @ContextValue(name = GraphQlAuth.TOKEN, required = false) TokenService.TokenUser token) {
        return bus.stateStream(BackendChanged.Topic.SKILLS, GraphQlAuth.require(token),
                u -> SkillCaller.as(u, skills::visibleCount));
    }

    @SubscriptionMapping
    public Flux<Integer> memoriesChanged(
            @ContextValue(name = GraphQlAuth.TOKEN, required = false) TokenService.TokenUser token) {
        return bus.stateStream(BackendChanged.Topic.MEMORIES, GraphQlAuth.require(token),
                u -> SkillCaller.as(u, memories::count));
    }

    @SubscriptionMapping
    public Flux<Integer> scriptsChanged(
            @ContextValue(name = GraphQlAuth.TOKEN, required = false) TokenService.TokenUser token) {
        return bus.stateStream(BackendChanged.Topic.SCRIPTS, GraphQlAuth.require(token),
                u -> SkillCaller.as(u, () -> scripts.overview().size()));
    }

    private static UserAccount require(UserAccount user) {
        return GraphQlAuth.require(user);
    }
}
