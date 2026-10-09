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
import org.springframework.context.ApplicationContext;
import org.springframework.graphql.ResponseError;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.HttpSyncGraphQlClient;
import org.springframework.graphql.client.WebSocketGraphQlClient;
import org.springframework.graphql.client.WebSocketGraphQlClientInterceptor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AuthenticationEventPublisher;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.socket.client.StandardWebSocketClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import systems.grebe.devtools.mcp.api.ApiVersions;
import systems.grebe.devtools.mcp.api.BrokerInfo;
import systems.grebe.devtools.mcp.api.Me;
import systems.grebe.devtools.mcp.api.ModuleDescriptor;
import systems.grebe.devtools.mcp.api.ModuleOverlay;
import systems.grebe.devtools.mcp.api.ProjectInfo;
import systems.grebe.devtools.mcp.api.SettingsSnapshot;
import systems.grebe.devtools.mcp.api.LoginResult;
import systems.grebe.devtools.mcp.api.Permission;
import systems.grebe.devtools.mcp.api.RoleInfo;
import systems.grebe.devtools.mcp.api.UserInfo;
import systems.grebe.devtools.mcp.backend.account.AccountService;
import systems.grebe.devtools.mcp.backend.account.Role;
import systems.grebe.devtools.mcp.backend.account.RoleService;
import systems.grebe.devtools.mcp.backend.account.TokenService;
import systems.grebe.devtools.mcp.backend.account.UserAccount;
import systems.grebe.devtools.mcp.backend.api.ApiSchemas;
import systems.grebe.devtools.mcp.backend.api.VersionedGraphQl;
import systems.grebe.devtools.mcp.backend.profile.Profile;
import systems.grebe.devtools.mcp.backend.profile.ProfileService;
import systems.grebe.devtools.mcp.backend.project.Project;
import systems.grebe.devtools.mcp.backend.project.ProjectService;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.modules.share.ShareTopics;
import systems.grebe.devtools.mcp.profile.Overrides;
import systems.grebe.devtools.mcp.web.WebLogin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * GraphQL-API des Backends im Team-Server: Anmeldung mit Passwort oder Desktop-Token, Rollen und Rechte, Verwaltung,
 * Modul-Katalog, Einstellungs-Ebenen Global → Benutzer → Profil mit Sperren, Profilwechsel, Projekte, Skills und
 * Subscriptions über WebSocket.
 */
// Kontext nach der Klasse schließen: die Graph-Datenbank des Backends hält sonst Dateien im temporären Ordner offen
@DirtiesContext
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
    RoleService roles;

    @Autowired
    ProfileService profiles;

    @Autowired
    ProjectService projects;

    @Autowired
    @Qualifier("coreJdbc")
    JdbcClient jdbc;

    @Autowired
    ApplicationContext context;

    @Autowired
    VersionedGraphQl api;

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
        return newUser(Role.USER);
    }

    private UserAccount newUser(String... roleNames) {
        return accounts.create("gqluser" + USERS.incrementAndGet(), null, "u" + USERS.get() + "@example.com",
                List.of(roleNames), "passwort-123", false);
    }

    private String token(UserAccount u) {
        return tokens.issue(u, "Test", null).jwt();
    }

    private HttpSyncGraphQlClient client(String jwt) {
        return client(jwt, "");
    }

    /** Client für die API unter {@code base} ({@code /api/v<n>}, leer = Pfad ohne Version). */
    private HttpSyncGraphQlClient client(String jwt, String base) {
        HttpSyncGraphQlClient.Builder<?> b = HttpSyncGraphQlClient.builder(RestClient.create(
                "http://127.0.0.1:" + port + base + "/graphql"));
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
        accounts.update(u.id(), null, null, null, false); // gesperrt
        assertThat(errorType(client(jwt).document("{ me { id } }").executeSync())).isEqualTo("UNAUTHORIZED");
    }

    @Test
    void brokerIsOffUnlessConfigured() {
        assertThat(errorType(client(null).document("{ broker { enabled } }").executeSync())).isEqualTo("UNAUTHORIZED");
        BrokerInfo b = client(token(newUser())).document("{ broker { enabled host port tlsPort websocketPort "
                + "topicPrefix } }").retrieveSync("broker").toEntity(BrokerInfo.class);
        assertThat(b.enabled()).isFalse();
        assertThat(b.port()).isZero();
        assertThat(b.topicPrefix()).isEqualTo(ShareTopics.DEFAULT_PREFIX);
    }

    private static final String LOGIN = """
            mutation($u: String!, $p: String!) { login(username: $u, password: $p, client: "Test") {
              token expiresAt passwordChangeRequired } }""";

    private LoginResult login(String username, String password) {
        return client(null).document(LOGIN).variables(Map.of("u", username, "p", password))
                .retrieveSync("login").toEntity(LoginResult.class);
    }

    @Test
    void loginWithPasswordAndLogout() {
        UserAccount u = newUser();
        ClientGraphQlResponse wrong = client(null).document(LOGIN)
                .variables(Map.of("u", u.username(), "p", "falsch")).executeSync();
        assertThat(errorType(wrong)).isEqualTo("BAD_REQUEST");
        assertThat(wrong.getErrors().getFirst().getMessage()).contains("falsch");

        LoginResult r = login(u.username().toUpperCase(), "passwort-123");
        assertThat(r.passwordChangeRequired()).isFalse();
        assertThat(r.expiresAt()).isNotBlank();
        Me me = client(r.token()).document("{ me { id username admin roles permissions passwordChangeRequired "
                + "profiles { id } activeProfileId } }").retrieveSync("me").toEntity(Me.class);
        assertThat(me.username()).isEqualTo(u.username());
        assertThat(me.admin()).isFalse();
        assertThat(me.roles()).containsExactly(Role.USER);
        assertThat(me.grants().allModules()).isTrue();
        assertThat(me.grants().has(Permission.USERS_MANAGE)).isFalse();
        assertThat(tokens.tokens(u.id())).singleElement().satisfies(t -> assertThat(t.name()).isEqualTo("Test"));

        assertThat(client(r.token()).document("mutation { logout }").retrieveSync("logout").toEntity(Boolean.class))
                .isTrue();
        assertThat(errorType(client(r.token()).document("{ me { id } }").executeSync())).isEqualTo("UNAUTHORIZED");
        assertThat(tokens.tokens(u.id())).isEmpty(); // beendete Anmeldungen bleiben nicht stehen
    }

    @Test
    void passwordSetByAnAdministratorMustBeChangedFirst() {
        UserAccount u = accounts.create("gqlnew" + USERS.incrementAndGet(), null, null, List.of(Role.USER),
                "start-passwort", true);
        LoginResult r = login(u.username(), "start-passwort");
        assertThat(r.passwordChangeRequired()).isTrue();
        assertThat(errorType(client(r.token()).document(SETTINGS).executeSync())).isEqualTo("FORBIDDEN");
        assertThat(client(r.token()).document("{ me { passwordChangeRequired } }")
                .retrieveSync("me.passwordChangeRequired").toEntity(Boolean.class)).isTrue();

        assertThat(client(r.token()).document("""
                        mutation { changePassword(currentPassword: "start-passwort", newPassword: "mein-passwort") }""")
                .retrieveSync("changePassword").toEntity(Boolean.class)).isTrue();
        assertThat(settings(r.token()).profileName()).isEqualTo(ProfileService.DEFAULT_PROFILE);
    }

    @Test
    void failedWebLoginsBlockTheDesktopLoginToo() {
        // Spring Security meldet Fehlversuche der Formular-Anmeldung als Ereignis – dafür braucht es den Publisher
        assertThat(context.getBeansOfType(AuthenticationEventPublisher.class)).isNotEmpty();
        UserAccount u = newUser();
        WebLogin web = context.getBean(WebLogin.class);
        assertThat(web.loadUserByUsername(u.username()).isAccountNonLocked()).isTrue();
        for (int i = 0; i < 5; i++) {
            web.onFailure(new AuthenticationFailureBadCredentialsEvent(
                    UsernamePasswordAuthenticationToken.unauthenticated(u.username(), "falsch"),
                    new BadCredentialsException("falsch")));
        }
        assertThat(web.loadUserByUsername(u.username()).isAccountNonLocked()).isFalse();
        ClientGraphQlResponse r = client(null).document(LOGIN)
                .variables(Map.of("u", u.username(), "p", "passwort-123")).executeSync();
        assertThat(r.getErrors()).singleElement()
                .satisfies(e -> assertThat(e.getMessage()).contains("Zu viele Fehlversuche"));
    }

    @Test
    void administrationNeedsThePermission() {
        String plain = token(newUser());
        assertThat(errorType(client(plain).document("{ users { id } }").executeSync())).isEqualTo("FORBIDDEN");
        assertThat(errorType(client(plain).document("""
                mutation { createRole(name: "x", permissions: []) { id } }""").executeSync())).isEqualTo("FORBIDDEN");

        String admin = token(newUser(Role.ADMINISTRATOR));
        RoleInfo reviewer = client(admin).document("""
                        mutation { createRole(name: "Reviewer", description: "nur lesen",
                          permissions: ["module:git", "tool:sonar_issues"]) {
                          id name description builtin permissions users } }""")
                .retrieveSync("createRole").toEntity(RoleInfo.class);
        assertThat(reviewer.permissions()).containsExactly("module:git", "tool:sonar_issues");
        try {
            UserInfo created = client(admin).document("""
                            mutation($n: String!) { createUser(username: $n, roles: ["Reviewer"],
                              password: "passwort-123") { id username enabled passwordChangeRequired roles } }""")
                    .variable("n", "gqlrev" + USERS.incrementAndGet())
                    .retrieveSync("createUser").toEntity(UserInfo.class);
            assertThat(created.passwordChangeRequired()).isTrue(); // Standard beim Anlegen durch die Verwaltung
            assertThat(created.roles()).containsExactly("Reviewer");

            List<RoleInfo> all = client(admin).document("{ roles { id name builtin permissions users } }")
                    .retrieveSync("roles").toEntityList(RoleInfo.class);
            assertThat(all).filteredOn(RoleInfo::builtin).singleElement()
                    .satisfies(r -> assertThat(r.permissions()).containsExactly("*"));
            assertThat(all).filteredOn(r -> r.name().equals("Reviewer")).singleElement()
                    .satisfies(r -> assertThat(r.users()).isEqualTo(1));

            // Rechte des Reviewers: keinen Skill schreiben, kein Projekt anlegen
            accounts.resetPassword(created.id(), "passwort-456", false);
            String rev = login(created.username(), "passwort-456").token();
            ClientGraphQlResponse skill = client(rev).document("""
                    mutation { createSkill(name: "x", description: "d", content: "c") }""").executeSync();
            assertThat(errorType(skill)).isEqualTo("FORBIDDEN");
            assertThat(skill.getErrors().getFirst().getMessage()).contains("skills_create");
            assertThat(errorType(client(rev).document("""
                    mutation { createProject(name: "p") { id } }""").executeSync())).isEqualTo("BAD_REQUEST");
            // ohne Recht auf memories_*: dauerhafte Memories nicht, temporäre schon (Memories brauchen eine E-Mail)
            String rev2 = login(newUser("Reviewer").username(), "passwort-123").token();
            assertThat(errorType(client(rev2).document("""
                    mutation { saveMemory(title: "t", content: "c") }""").executeSync())).isEqualTo("FORBIDDEN");
            String temp = mutation(rev2, """
                    mutation { saveMemory(title: "t", content: "c", type: TEMPORARY) }""", Map.of(), "saveMemory");
            long tempId = Long.parseLong(temp.replaceAll("\\D", ""));
            assertThat(mutation(rev2, "mutation($id: Int!) { updateMemory(id: $id, append: \"weiter\") }",
                    Map.of("id", tempId), "updateMemory")).contains("Nachtrag");
            assertThat(errorType(client(rev2).document("""
                    mutation($id: Int!) { updateMemory(id: $id, type: PERMANENT) }""").variable("id", tempId)
                    .executeSync())).isEqualTo("BAD_REQUEST");
            assertThat(mutation(rev2, "mutation($id: Int!) { deleteMemory(id: $id) }", Map.of("id", tempId),
                    "deleteMemory")).contains("gelöscht");

            long adminId = client(admin).document("{ me { id } }").retrieveSync("me.id").toEntity(Long.class);
            ClientGraphQlResponse self = client(admin).document("mutation($id: Int!) { deleteUser(id: $id) }")
                    .variable("id", adminId).executeSync();
            assertThat(errorType(self)).isEqualTo("BAD_REQUEST");
            assertThat(client(admin).document("mutation($id: Int!) { deleteUser(id: $id) }")
                    .variable("id", created.id()).retrieveSync("deleteUser").toEntity(Boolean.class)).isTrue();
        } finally {
            roles.delete(reviewer.id());
        }
    }

    @Test
    void meAndCatalog() {
        UserAccount u = newUser();
        String jwt = token(u);
        Me me = client(jwt).document("{ me { id username displayName email admin profiles { id name description } "
                + "activeProfileId roles permissions passwordChangeRequired } }").retrieveSync("me").toEntity(Me.class);
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
    void overridesAreCheckedForTheirFieldType() {
        UserAccount u = newUser();
        String jwt = token(u);
        ClientGraphQlResponse bad = client(jwt).document("""
                mutation($in: OverlayInput!) { saveOverrides(level: USER, moduleId: "sonar", input: $in) { profileId } }""")
                .variable("in", Map.of("values", List.of(Map.of("key", "timeoutSeconds", "value", "abc"))))
                .executeSync();
        assertThat(errorType(bad)).isEqualTo("BAD_REQUEST");
        assertThat(bad.getErrors().getFirst().getMessage()).contains("Timeout", "ganze Zahl");
        assertThat(settings(jwt).module("sonar").valueMap()).doesNotContainKey("timeoutSeconds");

        // leere Werte (Überschreibung mit „nicht gesetzt“) und gültige Zahlen bleiben möglich
        profiles.saveOverrides(u.id(), Overrides.Level.USER, u.id(), "sonar",
                new Overrides(null, Map.of(), Map.of("timeoutSeconds", "15", "organization", "")));
        assertThat(settings(jwt).module("sonar").valueMap()).containsEntry("timeoutSeconds", "15");

        // auch die globalen Vorgaben werden geprüft
        assertThatThrownBy(() -> profiles.saveGlobal("sonar",
                new Overrides(null, Map.of(), Map.of("timeoutSeconds", "1x"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ganze Zahl");
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
    void blobsAreUploadedInPartsAndOnlyVisibleToTheirOwner() throws Exception {
        String ja = token(newUser());
        String jb = token(newUser());
        java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
        java.util.function.BiFunction<String, String, java.net.http.HttpRequest.Builder> req = (path, jwt) -> {
            var b = java.net.http.HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/blobs" + path));
            return jwt == null ? b : b.header("Authorization", "Bearer " + jwt);
        };
        var string = java.net.http.HttpResponse.BodyHandlers.ofString();
        var noBody = java.net.http.HttpRequest.BodyPublishers.noBody();

        var start = http.send(req.apply("/uploads", ja).POST(noBody).build(), string);
        assertThat(start.statusCode()).isEqualTo(200);
        String id = start.body().replaceAll(".*\"upload\"\\s*:\\s*\"([0-9a-f]+)\".*", "$1");
        assertThat(http.send(req.apply("/uploads/" + id + "?offset=0", ja)
                .PUT(java.net.http.HttpRequest.BodyPublishers.ofString("Teil 1|")).build(), string).body())
                .contains("\"size\":7");
        http.send(req.apply("/uploads/" + id + "?offset=7", ja)
                .PUT(java.net.http.HttpRequest.BodyPublishers.ofString("Teil 2")).build(), string);
        var done = http.send(req.apply("/uploads/" + id + "/complete", ja).POST(noBody).build(), string);
        String blob = done.body().replaceAll(".*\"blob\"\\s*:\\s*\"([0-9a-f]{64})\".*", "$1");
        assertThat(blob).hasSize(64);

        mutation(ja, """
                mutation($n: String!, $d: String!, $c: String!) { createSkill(name: $n, description: $d, content: $c) }""",
                Map.of("n", "mit-datei", "d", "Mit Datei", "c", "siehe assets/teile.txt"), "createSkill");
        assertThat(mutation(ja, """
                mutation($n: String!, $f: String!, $b: String!) { attachSkillFile(name: $n, filePath: $f, blob: $b) }""",
                Map.of("n", "mit-datei", "f", "assets/teile.bin", "b", blob), "attachSkillFile"))
                .contains("Anhang 'assets/teile.bin'", "13 B");
        assertThat(client(ja).document("{ skillFile(name: \"mit-datei\", filePath: \"assets/teile.bin\") { blob } }")
                .retrieveSync("skillFile.blob").toEntity(String.class)).isEqualTo(blob);

        assertThat(http.send(req.apply("/" + blob, ja).GET().build(), string).body()).isEqualTo("Teil 1|Teil 2");
        assertThat(http.send(req.apply("/" + blob, jb).GET().build(), string).statusCode()).isEqualTo(400);
        // ohne Token: 401 statt Umleitung auf die Anmeldeseite der Web-UI
        assertThat(http.send(req.apply("/" + blob, null).GET().build(), string).statusCode()).isEqualTo(401);
        // fremder Inhalt lässt sich nicht anhängen
        ClientGraphQlResponse foreign = client(jb).document("""
                mutation { createSkill(name: "b", description: "d", content: "c") }""").executeSync();
        assertThat(foreign.getErrors()).isEmpty();
        assertThat(client(jb).document("mutation($b: String!) { attachSkillFile(name: \"b\", filePath: "
                + "\"assets/x\", blob: $b) }").variable("b", blob).executeSync().getErrors().getFirst().getMessage())
                .contains("erneut hochladen");
    }

    @Test
    void skillsAndMemoriesAreSharedWithUsersRolesAndEveryone() throws Exception {
        roles.create("Freigabe-Team", null, List.of("module:*"));
        roles.create("Ohne-Teilen", null, List.of("module:*", Permission.TOKENS_CREATE.key()));
        UserAccount a = newUser();
        UserAccount b = newUser(Role.USER, "Freigabe-Team");
        UserAccount c = newUser();
        UserAccount d = newUser("Ohne-Teilen");
        String ja = token(a);
        String jb = token(b);
        String jc = token(c);
        String jd = token(d);
        String share = """
                mutation($n: String!, $u: [String!], $r: [String!], $all: Boolean, $revoke: Boolean) { \
                shareSkill(name: $n, users: $u, roles: $r, everyone: $all, revoke: $revoke) }""";

        mutation(ja, "mutation { createSkill(name: \"geteilt\", description: \"Geteilter Ablauf\", content: \"1. tun\") }",
                Map.of(), "createSkill");
        String blob = upload(ja, "Anhang von A");
        mutation(ja, "mutation($b: String!) { attachSkillFile(name: \"geteilt\", filePath: \"assets/a.bin\", blob: $b) }",
                Map.of("b", blob), "attachSkillFile");

        // Ziele: andere Benutzer, Rollen und – mit shares.all (Rolle Benutzer) – alle
        List<Map<String, Object>> targets = client(ja).document("{ shareTargets { target name label } }")
                .retrieveSync("shareTargets").toEntityList(new org.springframework.core.ParameterizedTypeReference<>() {
                });
        assertThat(targets).extracting(t -> t.get("target") + ":" + t.get("name"))
                .contains("USER:" + b.email(), "ROLE:Freigabe-Team", "ALL:*").doesNotContain("USER:" + a.email());

        assertThat(mutation(ja, share, Map.of("n", "geteilt", "u", List.of(b.username())), "shareSkill"))
                .contains("freigegeben für " + b.email());
        assertThat(client(jb).document("{ skillList }").retrieveSync("skillList").toEntity(String.class))
                .contains("geteilt+", "(von " + a.email() + ")");
        assertThat(client(jb).document("{ skill(name: \"geteilt\") { summary { scope owner } } }")
                .retrieveSync("skill.summary.scope").toEntity(String.class)).isEqualTo("SHARED");
        assertThat(client(jc).document("{ skillList }").retrieveSync("skillList").toEntity(String.class))
                .doesNotContain("geteilt");
        assertThat(client(ja).document("{ skillShares(name: \"geteilt\") { target name } }")
                .retrieveSync("skillShares[0].name").toEntity(String.class)).isEqualTo(b.email());

        // Anhang des geteilten Skills: B darf ihn laden, C nicht
        java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
        var get = (java.util.function.Function<String, java.net.http.HttpResponse<String>>) jwt -> {
            try {
                return http.send(java.net.http.HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                                + "/blobs/" + blob)).header("Authorization", "Bearer " + jwt).GET().build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        assertThat(get.apply(jb).body()).isEqualTo("Anhang von A");
        assertThat(get.apply(jc).statusCode()).isEqualTo(400);

        // Für alle nur mit dem Recht shares.all
        ClientGraphQlResponse denied = client(jd).document(share).variables(Map.of("n", "x", "all", true)).executeSync();
        assertThat(denied.getErrors()).isNotEmpty();
        mutation(jd, "mutation { createSkill(name: \"von-d\", description: \"D\", content: \"d\") }", Map.of(),
                "createSkill");
        assertThat(client(jd).document(share).variables(Map.of("n", "von-d", "all", true)).executeSync().getErrors()
                .getFirst().getMessage()).contains("Mit allen teilen");
        assertThat(mutation(ja, share, Map.of("n", "geteilt", "all", true), "shareSkill")).contains("alle");
        assertThat(client(jc).document("{ skillList }").retrieveSync("skillList").toEntity(String.class))
                .contains("geteilt+");

        // Memory an eine Rolle; Umbenennen der Rolle behält die Freigabe, Löschen entfernt sie
        String saved = mutation(ja, "mutation { saveMemory(title: \"Ticket SHR-1 analysiert\", content: \"Ursache X\") }",
                Map.of(), "saveMemory");
        long id = Long.parseLong(saved.replaceAll("\\D", ""));
        assertThat(mutation(ja, "mutation($id: Int!) { shareMemory(id: $id, roles: [\"Freigabe-Team\"]) }",
                Map.of("id", id), "shareMemory")).contains("Rolle Freigabe-Team");
        String search = "{ memorySearch(query: \"shr-1\") }";
        assertThat(client(jb).document(search).retrieveSync("memorySearch").toEntity(String.class))
                .contains("Ticket SHR-1", "geteilt von " + a.email());
        assertThat(client(jc).document(search).retrieveSync("memorySearch").toEntity(String.class))
                .contains("Keine Memories");
        assertThat(errorType(client(jb).document("mutation($id: Int!) { deleteMemory(id: $id) }")
                .variables(Map.of("id", id)).executeSync())).isEqualTo("BAD_REQUEST");
        Role team = roles.roles().stream().filter(r -> r.name().equals("Freigabe-Team")).findFirst().orElseThrow();
        roles.update(team.id(), "Freigabe-Team-Neu", null, List.of("module:*"));
        String jb2 = token(accounts.user(b.id()).orElseThrow());
        assertThat(client(jb2).document(search).retrieveSync("memorySearch").toEntity(String.class))
                .contains("Ticket SHR-1");
        roles.delete(team.id());
        assertThat(client(ja).document("query($id: Int!) { memoryShares(id: $id) { name } }")
                .variables(Map.of("id", id)).retrieveSync("memoryShares").toEntityList(Object.class)).isEmpty();
    }

    /** Lädt Text in einem Teil in die Dateiablage des Benutzers und liefert den SHA-256. */
    private String upload(String jwt, String text) throws Exception {
        return upload(jwt, text, "");
    }

    /** Wie {@link #upload(String, String)} über die API unter {@code base} ({@code /api/v<n>}, leer = ohne Version). */
    private String upload(String jwt, String text, String base) throws Exception {
        java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
        java.util.function.Function<String, java.net.http.HttpRequest.Builder> req = path -> java.net.http.HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:" + port + base + "/blobs" + path))
                .header("Authorization", "Bearer " + jwt);
        var string = java.net.http.HttpResponse.BodyHandlers.ofString();
        var noBody = java.net.http.HttpRequest.BodyPublishers.noBody();
        String id = http.send(req.apply("/uploads").POST(noBody).build(), string).body()
                .replaceAll(".*\"upload\"\\s*:\\s*\"([0-9a-f]+)\".*", "$1");
        http.send(req.apply("/uploads/" + id + "?offset=0")
                .PUT(java.net.http.HttpRequest.BodyPublishers.ofString(text)).build(), string);
        return http.send(req.apply("/uploads/" + id + "/complete").POST(noBody).build(), string).body()
                .replaceAll(".*\"blob\"\\s*:\\s*\"([0-9a-f]{64})\".*", "$1");
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
    void memoriesBelongToTheTokenUser() {
        String ja = token(newUser());
        String jb = token(newUser());
        String saved = mutation(ja, """
                mutation($t: String!, $c: String!, $r: String) { saveMemory(title: $t, content: $c, reference: $r) }""",
                Map.of("t", "Ticket GQL-1 reviewt", "c", "Freigegeben.", "r", "GQL-1"), "saveMemory");
        assertThat(saved).matches("Memory #\\d+ gespeichert\\.");
        long id = Long.parseLong(saved.replaceAll("\\D", ""));

        assertThat(client(ja).document("{ memorySearch(query: \"gql-1\") }").retrieveSync("memorySearch")
                .toEntity(String.class)).contains("Ticket GQL-1 reviewt");
        assertThat(client(jb).document("{ memorySearch(query: \"gql-1\") }").retrieveSync("memorySearch")
                .toEntity(String.class)).contains("Keine Memories");
        assertThat(client(ja).document("query($id: Int!) { memory(id: $id) { title reference tags } }")
                .variables(Map.of("id", id)).retrieveSync("memory.reference").toEntity(String.class))
                .isEqualTo("GQL-1");
        assertThat(errorType(client(jb).document("mutation($id: Int!) { deleteMemory(id: $id) }")
                .variables(Map.of("id", id)).executeSync())).isEqualTo("BAD_REQUEST");
        assertThat(client(jb).document("{ memoryCount }").retrieveSync("memoryCount").toEntity(Integer.class))
                .isZero();

        String temp = mutation(ja, """
                mutation($t: String!, $c: String!) { saveMemory(title: $t, content: $c, type: TEMPORARY) }""",
                Map.of("t", "Zwischenstand", "c", "Halb fertig."), "saveMemory");
        long tempId = Long.parseLong(temp.replaceAll("\\D", ""));
        assertThat(client(ja).document("query($id: Int!) { memory(id: $id) { type } }").variables(Map.of("id", tempId))
                .retrieveSync("memory.type").toEntity(String.class)).isEqualTo("TEMPORARY");
        // temporaryOnly: dauerhafte abgelehnt, temporäre gelöscht
        assertThat(errorType(client(ja).document("mutation($id: Int!) { deleteMemory(id: $id, temporaryOnly: true) }")
                .variables(Map.of("id", id)).executeSync())).isEqualTo("BAD_REQUEST");
        assertThat(mutation(ja, "mutation($id: Int!) { deleteMemory(id: $id, temporaryOnly: true) }",
                Map.of("id", tempId), "deleteMemory")).contains("gelöscht");
    }

    @Test
    void scriptsBelongToTheTokenUserAndAreSyntaxChecked() {
        String ja = token(newUser());
        String jb = token(newUser());
        String save = """
                mutation($n: String!, $c: String!) { saveScript(name: $n, content: $c) }""";
        // ohne description: fester Text aus module { description '…' }; ausgeführt wird auf dem Server nichts
        assertThat(mutation(ja, save, Map.of("n", "jira", "c", "module { description 'Jira-Abfragen' }\n"
                + "tool('a') { description 'x'; run { System.exit(1) } }"), "saveScript")).contains("angelegt");
        ClientGraphQlResponse broken = client(ja).document(save)
                .variables(Map.of("n", "jira", "c", "tool(")).executeSync();
        assertThat(errorType(broken)).isEqualTo("BAD_REQUEST");
        assertThat(broken.getErrors().getFirst().getMessage()).contains("Zeile 1");

        assertThat(client(ja).document("{ scripts { name description scope revision } }")
                .retrieveSync("scripts[0].description").toEntity(String.class)).isEqualTo("Jira-Abfragen");
        assertThat(client(jb).document("{ scripts { name } }").retrieveSync("scripts").toEntityList(Object.class))
                .isEmpty();
        assertThat(errorType(client(ja).document("mutation { publishScript(name: \"jira\") }").executeSync()))
                .isEqualTo("BAD_REQUEST"); // nur Administratoren
    }

    private WebSocketGraphQlClient webSocket(String jwt) {
        return WebSocketGraphQlClient.builder(URI.create("ws://127.0.0.1:" + port + "/graphql"),
                        new StandardWebSocketClient())
                .interceptor(new WebSocketGraphQlClientInterceptor() {
                    @Override
                    public Mono<Object> connectionInitPayload() {
                        return Mono.just(Map.of("Authorization", "Bearer " + jwt));
                    }
                }).build();
    }

    @Test
    void subscriptionDeliversCurrentStateAndChanges() {
        UserAccount u = newUser();
        WebSocketGraphQlClient ws = webSocket(token(u));
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
    void subscriptionEndsWhenTheUserIsDisabled() {
        UserAccount u = newUser();
        WebSocketGraphQlClient ws = webSocket(token(u));
        try {
            StepVerifier.create(ws.document("subscription { settingsChanged { profileId profileName modules { moduleId values { key value } } revision } }")
                            .retrieveSubscription("settingsChanged").toEntity(SettingsSnapshot.class))
                    .assertNext(s -> assertThat(s.profileId()).isPositive())
                    .then(() -> {
                        accounts.update(u.id(), null, null, null, false); // gesperrt
                        profiles.saveGlobal("sonar", new Overrides(null, Map.of(), Map.of("organization", "geheim")));
                    })
                    .expectErrorSatisfies(e -> assertThat(e).hasMessageContaining("Nicht angemeldet"))
                    .verify(Duration.ofSeconds(20));
        } finally {
            ws.stop().block(Duration.ofSeconds(5));
        }
    }

    @Test
    void subscriptionEndsWhenTheTokenIsRevoked() {
        UserAccount u = newUser();
        TokenService.IssuedToken issued = tokens.issue(u, "Test", null);
        WebSocketGraphQlClient ws = webSocket(issued.jwt());
        try {
            StepVerifier.create(ws.document("subscription { skillsChanged }").retrieveSubscription("skillsChanged")
                            .toEntity(Integer.class))
                    .assertNext(n -> assertThat(n).isNotNegative())
                    .then(() -> tokens.revoke(u.id(), issued.token().id()))
                    .expectErrorSatisfies(e -> assertThat(e).hasMessageContaining("Nicht angemeldet"))
                    .verify(Duration.ofSeconds(20));
        } finally {
            ws.stop().block(Duration.ofSeconds(5));
        }
    }

    @Test
    void queryOverAnOpenSocketIsUnauthorizedAfterTheUserIsDisabled() {
        UserAccount u = newUser();
        WebSocketGraphQlClient ws = webSocket(token(u));
        try {
            ClientGraphQlResponse before = ws.document("{ me { id } }").execute().block(Duration.ofSeconds(20));
            assertThat(before.getErrors()).isEmpty();
            accounts.update(u.id(), null, null, null, false); // gesperrt
            ClientGraphQlResponse after = ws.document("{ me { id } }").execute().block(Duration.ofSeconds(20));
            assertThat(errorType(after)).isEqualTo("UNAUTHORIZED");
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

    // ---------------------------------------------------------------- API-Versionen

    @Test
    void versionsAreListedWithoutSignIn() throws Exception {
        var r = java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + ApiVersions.VERSIONS_PATH)).GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(200);
        ApiVersions.Info info = tools.jackson.databind.json.JsonMapper.builder().build()
                .readValue(r.body(), ApiVersions.Info.class);
        assertThat(info).isEqualTo(ApiSchemas.info());
        assertThat(info.current()).isEqualTo(ApiVersions.CURRENT);
    }

    @Test
    void everyVersionIsServedUnderItsPathAndLegacyPathsAreVersionZero() {
        String jwt = token(newUser());
        for (int version : ApiSchemas.versions()) {
            assertThat(client(jwt, ApiVersions.base(version)).document("{ me { username } }")
                    .retrieveSync("me.username").toEntity(String.class)).startsWith("gqluser");
        }
        assertThat(client(jwt).document("{ me { username } }").retrieveSync("me.username").toEntity(String.class))
                .startsWith("gqluser");
    }

    /** Versioniert ist nur die Schnittstelle: was über eine Version gespeichert wird, sehen alle anderen. */
    @Test
    void allVersionsShareOneDatabase() {
        String jwt = token(newUser());
        String create = """
                mutation($n: String!, $d: String!, $c: String!) { createSkill(name: $n, description: $d, content: $c) }""";
        client(jwt).document(create).variables(Map.of("n", "eine-db", "d", "Eine Datenbank", "c", "über /graphql"))
                .executeSync();
        for (int version : ApiSchemas.versions()) {
            assertThat(client(jwt, ApiVersions.base(version)).document("{ skill(name: \"eine-db\") { content } }")
                    .retrieveSync("skill.content").toEntity(String.class)).isEqualTo("über /graphql");
        }
        client(jwt, ApiVersions.base(ApiVersions.CURRENT)).document(create)
                .variables(Map.of("n", "eine-db-2", "d", "Eine Datenbank", "c", "über /api")).executeSync();
        assertThat(client(jwt).document("{ skill(name: \"eine-db-2\") { content } }")
                .retrieveSync("skill.content").toEntity(String.class)).isEqualTo("über /api");
    }

    @Test
    void unknownVersionIsNotFound() throws Exception {
        var http = java.net.http.HttpClient.newHttpClient();
        int next = ApiSchemas.current() + 1;
        var graphQl = http.send(java.net.http.HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                                + ApiVersions.base(next) + "/graphql")).header("Content-Type", "application/json")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{\"query\":\"{ __typename }\"}"))
                        .build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(graphQl.statusCode()).isEqualTo(404);
        var blobs = http.send(java.net.http.HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                        + ApiVersions.base(next) + "/blobs/uploads")).header("Authorization", "Bearer "
                        + token(newUser())).POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(blobs.statusCode()).isEqualTo(404);
    }

    @Test
    void blobsUnderTheVersionedPath() throws Exception {
        String jwt = token(newUser());
        String sha = upload(jwt, "versioniert", ApiVersions.base(ApiVersions.CURRENT));
        var r = java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + port + ApiVersions.base(ApiVersions.CURRENT) + "/blobs/" + sha))
                .header("Authorization", "Bearer " + jwt).GET().build(), java.net.http.HttpResponse.BodyHandlers
                .ofString());
        assertThat(r.body()).isEqualTo("versioniert");
        assertThat(upload(jwt, "versioniert")).isEqualTo(sha); // Pfad ohne Version: dieselbe Ablage
    }

    @Test
    void subscriptionUnderTheVersionedPath() {
        String jwt = token(newUser());
        WebSocketGraphQlClient ws = WebSocketGraphQlClient.builder(URI.create("ws://127.0.0.1:" + port
                        + ApiVersions.base(ApiVersions.CURRENT) + "/graphql"), new StandardWebSocketClient())
                .interceptor(new WebSocketGraphQlClientInterceptor() {
                    @Override
                    public Mono<Object> connectionInitPayload() {
                        return Mono.just(Map.of("Authorization", "Bearer " + jwt));
                    }
                }).build();
        try {
            StepVerifier.create(ws.document("subscription { settingsChanged { profileId profileName modules { "
                                    + "moduleId values { key value } } revision } }")
                            .retrieveSubscription("settingsChanged").toEntity(SettingsSnapshot.class))
                    .assertNext(s -> assertThat(s).isNotNull())
                    .thenCancel()
                    .verify(Duration.ofSeconds(20));
        } finally {
            ws.stop().block(Duration.ofSeconds(5));
        }
    }

    /**
     * Typen, die die Controller als {@code Map} liefern – deren Felder kann der Abgleich Schema ↔ Controller nicht
     * prüfen, er meldet sie immer als ungebunden.
     */
    static final Set<String> MAP_TYPES = Set.of("GraphHead", "GraphState", "GraphCount", "GraphQueryResult");

    /** Jedes Feld jeder angebotenen Version hat einen Controller – auch, wenn neuere Versionen es nicht mehr haben. */
    @Test
    void everyFieldOfEveryVersionIsServed() {
        for (int version : ApiSchemas.versions()) {
            assertThat(api.report(version).unmappedFields()).as("Felder ohne Controller in Version " + version)
                    .allMatch(f -> MAP_TYPES.contains(f.getTypeName()));
        }
    }
}
