package systems.grebe.devtools.mcp.server;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.graphql.ResponseError;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.HttpSyncGraphQlClient;
import org.springframework.graphql.client.WebSocketGraphQlClient;
import org.springframework.graphql.client.WebSocketGraphQlClientInterceptor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.socket.client.StandardWebSocketClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import systems.grebe.devtools.mcp.api.Me;
import systems.grebe.devtools.mcp.api.ModuleDescriptor;
import systems.grebe.devtools.mcp.api.ModuleOverlay;
import systems.grebe.devtools.mcp.api.ProjectInfo;
import systems.grebe.devtools.mcp.api.SettingsSnapshot;
import systems.grebe.devtools.mcp.backend.account.AccountService;
import systems.grebe.devtools.mcp.backend.account.Role;
import systems.grebe.devtools.mcp.backend.account.TokenService;
import systems.grebe.devtools.mcp.backend.account.UserAccount;
import systems.grebe.devtools.mcp.backend.profile.Profile;
import systems.grebe.devtools.mcp.backend.profile.ProfileService;
import systems.grebe.devtools.mcp.backend.project.Project;
import systems.grebe.devtools.mcp.backend.project.ProjectService;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.profile.Overrides;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * GraphQL-API des Backends im Team-Server: Anmeldung per Desktop-Token, Modul-Katalog, Einstellungs-Ebenen Global →
 * Benutzer → Profil mit Sperren, Profilwechsel, Projekte, Skills und Subscriptions über WebSocket.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GraphQlApiIntegrationTest {

    @TempDir
    static Path home;

    private static final AtomicInteger USERS = new AtomicInteger();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("devtools.server.home", () -> home.toString());
    }

    @LocalServerPort
    int port;

    @Autowired
    AccountService accounts;

    @Autowired
    TokenService tokens;

    @Autowired
    ProfileService profiles;

    @Autowired
    ProjectService projects;

    @Autowired
    @Qualifier("coreJdbc")
    JdbcClient jdbc;

    static final List<ModuleDescriptor> CATALOG = List.of(
            new ModuleDescriptor("sonar", "SonarQube", "Befunde", false, true, 10, List.of(
                    ConfigField.of("organization", "Organisation", FieldType.STRING),
                    ConfigField.of("timeoutSeconds", "Timeout", FieldType.INT),
                    ConfigField.of("token", "Token", FieldType.SECRET)),
                    List.of(new ModuleDescriptor.ToolDescriptor("sonar_issues", "Befunde"),
                            new ModuleDescriptor.ToolDescriptor("sonar_hotspots", "Hotspots"))),
            new ModuleDescriptor("git", "Git", "Repositories", true, true, 1, List.of(
                    ConfigField.records("remotes", "Remotes", ConfigField.of("name", "Name", FieldType.STRING),
                            ConfigField.of("password", "Passwort", FieldType.SECRET))),
                    List.of(new ModuleDescriptor.ToolDescriptor("git_status", "Status"))));

    static final String MODULE_FIELDS = """
            fragment F on Field { key label type required defaultValue help options }
            """;

    @BeforeEach
    void catalog() {
        String jwt = token(newUser());
        ClientGraphQlResponse r = client(jwt).document("""
                mutation($m: [ModuleInput!]!) { reportCatalog(modules: $m) }""")
                .variable("m", CATALOG).executeSync();
        assertThat(r.getErrors()).isEmpty();
    }

    @AfterEach
    void reset() {
        profiles.setLocks("git", Set.of());
        profiles.setLocks("sonar", Set.of());
        profiles.saveGlobal("git", Overrides.NONE);
        profiles.saveGlobal("sonar", Overrides.NONE);
    }

    private UserAccount newUser() {
        return accounts.create("gqluser" + USERS.incrementAndGet(), null, "u" + USERS.get() + "@example.com",
                Role.USER, "passwort-123");
    }

    private String token(UserAccount u) {
        return tokens.issue(u, "Test", null).jwt();
    }

    private HttpSyncGraphQlClient client(String jwt) {
        HttpSyncGraphQlClient.Builder<?> b = HttpSyncGraphQlClient.builder(RestClient.create(
                "http://127.0.0.1:" + port + "/graphql"));
        if (jwt != null) {
            b.header("Authorization", "Bearer " + jwt);
        }
        return b.build();
    }

    private static final String SETTINGS = """
            { settings { profileId profileName modules { moduleId enabled tools { name enabled }
              values { key value } locked } revision } }""";

    private SettingsSnapshot settings(String jwt) {
        return client(jwt).document(SETTINGS).retrieveSync("settings").toEntity(SettingsSnapshot.class);
    }

    private String mutation(String jwt, String doc, Map<String, Object> vars, String field) {
        return client(jwt).document(doc).variables(vars).retrieveSync(field).toEntity(String.class);
    }

    private static String errorType(ClientGraphQlResponse r) {
        return r.getErrors().stream().findFirst().map(ResponseError::getErrorType).map(Object::toString).orElse("");
    }

    @Test
    void withoutValidTokenOperationsAreUnauthorized() {
        ClientGraphQlResponse none = client(null).document("{ me { id } }").executeSync();
        assertThat(errorType(none)).isEqualTo("UNAUTHORIZED");
        assertThat(errorType(client("kein.gültiges.token").document("{ settings { profileId } }").executeSync()))
                .isEqualTo("UNAUTHORIZED");
        UserAccount u = newUser();
        String jwt = token(u);
        accounts.update(u.id(), null, null, Role.USER, false); // gesperrt
        assertThat(errorType(client(jwt).document("{ me { id } }").executeSync())).isEqualTo("UNAUTHORIZED");
    }

    @Test
    void meAndCatalog() {
        UserAccount u = newUser();
        String jwt = token(u);
        Me me = client(jwt).document("{ me { id username displayName email admin profiles { id name description } "
                + "activeProfileId } }").retrieveSync("me").toEntity(Me.class);
        assertThat(me.username()).isEqualTo(u.username());
        assertThat(me.profiles()).extracting(Me.ProfileInfo::name).containsExactly(ProfileService.DEFAULT_PROFILE);

        List<ModuleDescriptor> catalog = client(jwt).document(MODULE_FIELDS + """
                        { catalog { id displayName description enabledByDefault hasTools order
                          schema { ...F columns { ...F columns { ...F } } } tools { name description } } }""")
                .retrieveSync("catalog").toEntityList(ModuleDescriptor.class);
        ModuleDescriptor git = catalog.stream().filter(m -> m.id().equals("git")).findFirst().orElseThrow();
        assertThat(git.schema().getFirst().columns()).extracting(ConfigField::key).containsExactly("name", "password");
        assertThat(git.schema().getFirst().secret()).isTrue();
    }

    @Test
    void layersGlobalUserProfileWithSecrets() {
        UserAccount u = newUser();
        String jwt = token(u);
        Profile p = profiles.activeProfile(u.id());
        profiles.saveGlobal("sonar", new Overrides(true, Map.of(), Map.of("timeoutSeconds", "30")));
        mutation(jwt, """
                mutation($in: OverlayInput!) { saveOverrides(level: USER, moduleId: "sonar", input: $in) { profileId } }""",
                Map.of("in", Map.of("values", List.of(Map.of("key", "organization", "value", "user-org"),
                        Map.of("key", "timeoutSeconds", "value", "11"), Map.of("key", "token", "value", "squ_geheim")))),
                "saveOverrides.profileId");
        profiles.saveOverrides(u.id(), Overrides.Level.PROFILE, p.id(), "sonar",
                new Overrides(null, Map.of("sonar_hotspots", false), Map.of("organization", "profile-org")));

        SettingsSnapshot s = settings(jwt);
        assertThat(s.profileId()).isEqualTo(p.id());
        ModuleOverlay sonar = s.module("sonar");
        assertThat(sonar.enabled()).isTrue();
        assertThat(sonar.valueMap()).containsEntry("organization", "profile-org").containsEntry("timeoutSeconds", "11")
                .containsEntry("token", "squ_geheim");
        assertThat(sonar.toolMap()).containsEntry("sonar_hotspots", false);

        ModuleOverlay userLevel = client(jwt).document("""
                        { overrides(level: USER, moduleId: "sonar") { moduleId enabled tools { name enabled }
                          values { key value } locked } }""")
                .retrieveSync("overrides").toEntity(ModuleOverlay.class);
        assertThat(userLevel.valueMap()).containsEntry("organization", "user-org");

        String raw = jdbc.sql("SELECT setting_value FROM module_override WHERE level_id = ? AND setting_key = 'token'")
                .param(u.id()).query(String.class).single();
        assertThat(raw).startsWith("enc:v1:").doesNotContain("squ_geheim");
    }

    @Test
    void globalOnlyForAdminsAndLocksWin() {
        UserAccount u = newUser();
        String jwt = token(u);
        ClientGraphQlResponse denied = client(jwt).document("""
                mutation { saveOverrides(level: GLOBAL, moduleId: "git", input: { enabled: false }) { profileId } }""")
                .executeSync();
        assertThat(errorType(denied)).isEqualTo("FORBIDDEN");

        profiles.saveOverrides(u.id(), Overrides.Level.USER, u.id(), "git", new Overrides(false, Map.of(), Map.of()));
        profiles.saveGlobal("git", new Overrides(true, Map.of(), Map.of()));
        profiles.setLocks("git", Set.of(Overrides.ENABLED));
        ModuleOverlay git = settings(jwt).module("git");
        assertThat(git.enabled()).isTrue();
        assertThat(git.locked()).contains(Overrides.ENABLED);
        ClientGraphQlResponse locked = client(jwt).document("""
                mutation { saveOverrides(level: USER, moduleId: "git", input: { enabled: false }) { profileId } }""")
                .executeSync();
        assertThat(errorType(locked)).isEqualTo("BAD_REQUEST");
        assertThat(locked.getErrors().getFirst().getMessage()).contains("gesperrt");
    }

    @Test
    void profileSwitchAndForeignProfiles() {
        UserAccount u = newUser();
        String jwt = token(u);
        Profile work = profiles.activeProfile(u.id());
        Profile homeProfile = profiles.create(u.id(), "Home", null);
        profiles.saveOverrides(u.id(), Overrides.Level.PROFILE, homeProfile.id(), "git",
                new Overrides(false, Map.of(), Map.of()));

        SettingsSnapshot s = client(jwt).document("""
                        mutation($id: Int!) { activateProfile(profileId: $id) { profileId profileName
                          modules { moduleId enabled tools { name enabled } values { key value } locked } revision } }""")
                .variable("id", homeProfile.id()).retrieveSync("activateProfile").toEntity(SettingsSnapshot.class);
        assertThat(s.profileName()).isEqualTo("Home");
        assertThat(s.module("git").enabled()).isFalse();

        ClientGraphQlResponse foreign = client(token(newUser())).document("""
                mutation($id: Int!) { activateProfile(profileId: $id) { profileId } }""")
                .variable("id", work.id()).executeSync();
        assertThat(errorType(foreign)).isEqualTo("BAD_REQUEST");
    }

    @Test
    void projectsWithAccess() {
        UserAccount owner = newUser();
        UserAccount reader = newUser();
        UserAccount stranger = newUser();
        Project p = projects.create(owner.id(), "shop", "Webshop", "shop-key", "SHOP");
        projects.share(owner.id(), p.id(), reader.username(), Project.Access.READ);
        String q = "{ projects { id name owner toolName writable access description sonarKey ticketProject } }";

        assertThat(client(token(owner)).document(q).retrieveSync("projects").toEntityList(ProjectInfo.class))
                .singleElement().satisfies(i -> {
                    assertThat(i.toolName()).isEqualTo("shop");
                    assertThat(i.writable()).isTrue();
                });
        assertThat(client(token(reader)).document(q).retrieveSync("projects").toEntityList(ProjectInfo.class))
                .singleElement().satisfies(i -> {
                    assertThat(i.toolName()).isEqualTo("shop@" + owner.username());
                    assertThat(i.writable()).isFalse();
                });
        assertThat(client(token(stranger)).document(q).retrieveSync("projects").toEntityList(ProjectInfo.class))
                .isEmpty();
        assertThatThrownBy(() -> projects.delete(reader.id(), p.id())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void skillsBelongToTheTokenUser() {
        String ja = token(newUser());
        String jb = token(newUser());
        String create = """
                mutation($n: String!, $d: String!, $c: String!) { createSkill(name: $n, description: $d, content: $c) }""";
        assertThat(mutation(ja, create, Map.of("n", "deploy", "d", "Deployment-Ablauf", "c", "1. bauen"),
                "createSkill")).contains("angelegt");
        ClientGraphQlResponse dup = client(ja).document(create)
                .variables(Map.of("n", "deploy", "d", "x", "c", "y")).executeSync();
        assertThat(errorType(dup)).isEqualTo("BAD_REQUEST");
        assertThat(dup.getErrors().getFirst().getMessage()).contains("existiert bereits");

        assertThat(client(ja).document("{ skillList }").retrieveSync("skillList").toEntity(String.class))
                .contains("deploy");
        assertThat(client(jb).document("{ skillList }").retrieveSync("skillList").toEntity(String.class))
                .doesNotContain("deploy");
        assertThat(client(ja).document("{ skill(name: \"deploy\") { content summary { name scope } } }")
                .retrieveSync("skill.content").toEntity(String.class)).isEqualTo("1. bauen");
        assertThat(errorType(client(ja).document("mutation { publishSkill(name: \"deploy\") }").executeSync()))
                .isEqualTo("BAD_REQUEST"); // nur Administratoren
    }

    @Test
    void subscriptionDeliversCurrentStateAndChanges() {
        UserAccount u = newUser();
        String jwt = token(u);
        WebSocketGraphQlClient ws = WebSocketGraphQlClient.builder(URI.create("ws://127.0.0.1:" + port + "/graphql"),
                        new StandardWebSocketClient())
                .interceptor(new WebSocketGraphQlClientInterceptor() {
                    @Override
                    public Mono<Object> connectionInitPayload() {
                        return Mono.just(Map.of("Authorization", "Bearer " + jwt));
                    }
                }).build();
        try {
            StepVerifier.create(ws.document("subscription { settingsChanged { profileId profileName modules { moduleId "
                                    + "values { key value } } revision } }")
                            .retrieveSubscription("settingsChanged").toEntity(SettingsSnapshot.class))
                    .assertNext(s -> assertThat(s.module("sonar").valueMap()).doesNotContainKey("organization"))
                    .then(() -> profiles.saveOverrides(u.id(), Overrides.Level.USER, u.id(), "sonar",
                            new Overrides(null, Map.of(), Map.of("organization", "live"))))
                    .assertNext(s -> assertThat(s.module("sonar").valueMap()).containsEntry("organization", "live"))
                    .thenCancel()
                    .verify(Duration.ofSeconds(20));
        } finally {
            ws.stop().block(Duration.ofSeconds(5));
        }
    }

    @Test
    void subscriptionWithoutTokenIsRejected() {
        WebSocketGraphQlClient ws = WebSocketGraphQlClient.builder(URI.create("ws://127.0.0.1:" + port + "/graphql"),
                new StandardWebSocketClient()).build();
        StepVerifier.create(ws.document("subscription { skillsChanged }").retrieveSubscription("skillsChanged")
                        .toEntity(Integer.class))
                .expectError()
                .verify(Duration.ofSeconds(20));
    }
}
