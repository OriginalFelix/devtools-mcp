package systems.grebe.devtools.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import systems.grebe.devtools.mcp.account.AccountService;
import systems.grebe.devtools.mcp.account.Role;
import systems.grebe.devtools.mcp.account.TokenService;
import systems.grebe.devtools.mcp.account.UserAccount;
import systems.grebe.devtools.mcp.api.Catalog;
import systems.grebe.devtools.mcp.api.Me;
import systems.grebe.devtools.mcp.api.ModuleDescriptor;
import systems.grebe.devtools.mcp.api.ModuleOverlay;
import systems.grebe.devtools.mcp.api.ProjectInfo;
import systems.grebe.devtools.mcp.api.SettingsSnapshot;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.profile.Overrides;
import systems.grebe.devtools.mcp.profile.Profile;
import systems.grebe.devtools.mcp.profile.ProfileService;
import systems.grebe.devtools.mcp.project.Project;
import systems.grebe.devtools.mcp.project.ProjectService;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * REST-API für die Desktop-Apps: Anmeldung per Desktop-Token, Modul-Katalog, Einstellungs-Ebenen Global → Benutzer →
 * Profil mit Sperren, Profilwechsel und sichtbare Projekte.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DesktopApiIntegrationTest {

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

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    static final Catalog CATALOG = new Catalog(List.of(
            new ModuleDescriptor("sonar", "SonarQube", "Befunde", false, true, 10, List.of(
                    ConfigField.of("organization", "Organisation", FieldType.STRING),
                    ConfigField.of("timeoutSeconds", "Timeout", FieldType.INT),
                    ConfigField.of("token", "Token", FieldType.SECRET)),
                    List.of(new ModuleDescriptor.ToolDescriptor("sonar_issues", "Befunde"),
                            new ModuleDescriptor.ToolDescriptor("sonar_hotspots", "Hotspots"))),
            new ModuleDescriptor("git", "Git", "Repositories", true, true, 1, List.of(
                    ConfigField.of("repositories", "Repositories", FieldType.DIRECTORY_LIST)),
                    List.of(new ModuleDescriptor.ToolDescriptor("git_status", "Status")))));

    @BeforeEach
    void catalog() throws Exception {
        UserAccount u = newUser();
        assertThat(send("PUT", "/api/catalog", token(u), CATALOG).statusCode()).isEqualTo(204);
    }

    @AfterEach
    void unlock() {
        profiles.setLocks("git", Set.of());
        profiles.setLocks("sonar", Set.of());
        profiles.saveGlobal("git", Overrides.NONE);
        profiles.saveGlobal("sonar", Overrides.NONE);
    }

    private UserAccount newUser() {
        return accounts.create("apiuser" + USERS.incrementAndGet(), null, "u" + USERS.get() + "@example.com",
                Role.USER, "passwort-123");
    }

    private String token(UserAccount u) {
        return tokens.issue(u, "Test", null).jwt();
    }

    private HttpResponse<String> send(String method, String path, String jwt, Object body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json");
        if (jwt != null) {
            b.header("Authorization", "Bearer " + jwt);
        }
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private <T> T get(String path, String jwt, Class<T> type) throws Exception {
        HttpResponse<String> r = send("GET", path, jwt, null);
        assertThat(r.statusCode()).isEqualTo(200);
        return json.readValue(r.body(), type);
    }

    @Test
    void withoutValidTokenEverythingIs401() throws Exception {
        assertThat(send("GET", "/api/me", null, null).statusCode()).isEqualTo(401);
        assertThat(send("GET", "/api/settings", "kein.gültiges.token", null).statusCode()).isEqualTo(401);
        UserAccount u = newUser();
        String jwt = token(u);
        accounts.update(u.id(), null, null, Role.USER, false); // gesperrt
        assertThat(send("GET", "/api/me", jwt, null).statusCode()).isEqualTo(401);
        // es gibt keinen MCP-Endpunkt mehr
        assertThat(send("POST", "/mcp", null, Map.of()).statusCode()).isNotEqualTo(200);
    }

    @Test
    void meListsProfilesAndCatalogIsKnown() throws Exception {
        UserAccount u = newUser();
        Me me = get("/api/me", token(u), Me.class);
        assertThat(me.username()).isEqualTo(u.username());
        assertThat(me.email()).isEqualTo(u.email());
        assertThat(me.profiles()).extracting(Me.ProfileInfo::name).containsExactly(ProfileService.DEFAULT_PROFILE);
        assertThat(me.activeProfileId()).isEqualTo(me.profiles().getFirst().id());
    }

    @Test
    void layersGlobalUserProfileWithSecretsDecrypted() throws Exception {
        UserAccount u = newUser();
        Profile p = profiles.activeProfile(u.id());
        profiles.saveGlobal("sonar", new Overrides(true, Map.of(), Map.of("timeoutSeconds", "30")));
        profiles.saveOverrides(u.id(), Overrides.Level.USER, u.id(), "sonar",
                new Overrides(null, Map.of(), Map.of("organization", "user-org", "timeoutSeconds", "11",
                        "token", "squ_geheim")));
        profiles.saveOverrides(u.id(), Overrides.Level.PROFILE, p.id(), "sonar",
                new Overrides(null, Map.of("sonar_hotspots", false), Map.of("organization", "profile-org")));

        SettingsSnapshot s = get("/api/settings", token(u), SettingsSnapshot.class);
        assertThat(s.profileId()).isEqualTo(p.id());
        ModuleOverlay sonar = s.module("sonar");
        assertThat(sonar.enabled()).isTrue();
        assertThat(sonar.values()).containsEntry("organization", "profile-org").containsEntry("timeoutSeconds", "11")
                .containsEntry("token", "squ_geheim");
        assertThat(sonar.tools()).containsEntry("sonar_hotspots", false);
        assertThat(s.modules()).doesNotContainKey("git"); // nichts vorgegeben

        String raw = jdbc.sql("SELECT setting_value FROM module_override WHERE level_id = ? AND setting_key = 'token'")
                .param(u.id()).query(String.class).single();
        assertThat(raw).startsWith("enc:v1:").doesNotContain("squ_geheim");
    }

    @Test
    void locksKeepTheGlobalValueAndRejectNewOverrides() throws Exception {
        UserAccount u = newUser();
        profiles.saveOverrides(u.id(), Overrides.Level.USER, u.id(), "git", new Overrides(false, Map.of(), Map.of()));
        assertThat(get("/api/settings", token(u), SettingsSnapshot.class).module("git").enabled()).isFalse();

        profiles.saveGlobal("git", new Overrides(true, Map.of(), Map.of()));
        profiles.setLocks("git", Set.of(Overrides.ENABLED));
        ModuleOverlay git = get("/api/settings", token(u), SettingsSnapshot.class).module("git");
        assertThat(git.enabled()).isTrue(); // alte Überschreibung wirkt nicht mehr
        assertThat(git.locked()).contains(Overrides.ENABLED);
        assertThatThrownBy(() -> profiles.saveOverrides(u.id(), Overrides.Level.USER, u.id(), "git",
                new Overrides(false, Map.of(), Map.of()))).hasMessageContaining("gesperrt");
    }

    @Test
    void unknownModulesAndFieldsAreRejected() {
        UserAccount u = newUser();
        assertThatThrownBy(() -> profiles.saveOverrides(u.id(), Overrides.Level.USER, u.id(), "gibtsnicht",
                Overrides.NONE)).hasMessageContaining("Unbekanntes Modul");
        assertThatThrownBy(() -> profiles.saveOverrides(u.id(), Overrides.Level.USER, u.id(), "git",
                new Overrides(null, Map.of(), Map.of("x", "1")))).hasMessageContaining("Unbekanntes Feld");
    }

    @Test
    void settingsHaveEtagAndProfileSwitchChangesThem() throws Exception {
        UserAccount u = newUser();
        String jwt = token(u);
        Profile work = profiles.activeProfile(u.id());
        Profile homeProfile = profiles.create(u.id(), "Home", null);
        profiles.saveOverrides(u.id(), Overrides.Level.PROFILE, homeProfile.id(), "git",
                new Overrides(false, Map.of(), Map.of()));

        HttpResponse<String> first = send("GET", "/api/settings", jwt, null);
        String etag = first.headers().firstValue("ETag").orElseThrow();
        HttpRequest again = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/settings"))
                .header("Authorization", "Bearer " + jwt).header("If-None-Match", etag).GET().build();
        assertThat(http.send(again, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(304);

        assertThat(send("PUT", "/api/profile/active", jwt, Map.of("profileId", homeProfile.id())).statusCode())
                .isEqualTo(204);
        SettingsSnapshot s = get("/api/settings", jwt, SettingsSnapshot.class);
        assertThat(s.profileName()).isEqualTo("Home");
        assertThat(s.module("git").enabled()).isFalse();
        assertThat(http.send(again, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);

        // fremdes Profil
        UserAccount other = newUser();
        assertThat(send("PUT", "/api/profile/active", token(other), Map.of("profileId", work.id())).statusCode())
                .isEqualTo(400);
    }

    @Test
    void skillsBelongToTheTokenUserAndTemplatesToAdmins() throws Exception {
        UserAccount a = newUser();
        UserAccount b = newUser();
        UserAccount admin = accounts.create("skilladmin" + USERS.incrementAndGet(), null, "admin" + USERS.get()
                + "@example.com", Role.ADMIN, "passwort-123");
        String ja = token(a);
        String jb = token(b);

        HttpResponse<String> created = send("POST", "/api/skills/create", ja, Map.of("name", "deploy",
                "description", "Deployment-Ablauf", "content", "1. bauen\n2. ausrollen", "tags", List.of("ops")));
        assertThat(created.statusCode()).isEqualTo(200);
        assertThat(json.readTree(created.body()).get("text").asString()).contains("angelegt");
        HttpResponse<String> dup = send("POST", "/api/skills/create", ja, Map.of("name", "deploy",
                "description", "x", "content", "y"));
        assertThat(dup.statusCode()).isEqualTo(400);
        assertThat(dup.body()).contains("existiert bereits");

        assertThat(send("POST", "/api/skills/list", ja, Map.of()).body()).contains("deploy");
        assertThat(send("POST", "/api/skills/list", jb, Map.of()).body()).doesNotContain("deploy");
        assertThat(send("POST", "/api/skills/count", ja, Map.of()).body()).contains("\"count\":1");
        assertThat(send("POST", "/api/skills/details", ja, Map.of("name", "deploy")).body())
                .contains("1. bauen");

        // Vorlagen: nur Administratoren veröffentlichen
        assertThat(send("POST", "/api/skills/publish", ja, Map.of("name", "deploy")).statusCode()).isEqualTo(400);
        String jadmin = token(admin);
        send("POST", "/api/skills/create", jadmin, Map.of("name", "review", "description", "Code-Review",
                "content", "Checkliste"));
        assertThat(send("POST", "/api/skills/publish", jadmin, Map.of("name", "review")).statusCode())
                .isEqualTo(200);
        assertThat(send("POST", "/api/skills/list", jb, Map.of()).body()).contains("review");
    }

    @Test
    void projectsAreVisibleToOwnerAndSharedUsersWithAccess() throws Exception {
        UserAccount owner = newUser();
        UserAccount reader = newUser();
        UserAccount writer = newUser();
        UserAccount stranger = newUser();
        Project p = projects.create(owner.id(), "shop", "Webshop", "shop-key", "SHOP");
        projects.share(owner.id(), p.id(), reader.username(), Project.Access.READ);
        projects.share(owner.id(), p.id(), writer.username(), Project.Access.WRITE);

        TypeReference<List<ProjectInfo>> list = new TypeReference<>() {
        };
        List<ProjectInfo> own = json.readValue(send("GET", "/api/projects", token(owner), null).body(), list);
        assertThat(own).singleElement().satisfies(i -> {
            assertThat(i.toolName()).isEqualTo("shop");
            assertThat(i.writable()).isTrue();
            assertThat(i.sonarKey()).isEqualTo("shop-key");
        });
        List<ProjectInfo> read = json.readValue(send("GET", "/api/projects", token(reader), null).body(), list);
        assertThat(read).singleElement().satisfies(i -> {
            assertThat(i.toolName()).isEqualTo("shop@" + owner.username());
            assertThat(i.writable()).isFalse();
        });
        List<ProjectInfo> write = json.readValue(send("GET", "/api/projects", token(writer), null).body(), list);
        assertThat(write).singleElement().satisfies(i -> assertThat(i.writable()).isTrue());
        assertThat(json.readValue(send("GET", "/api/projects", token(stranger), null).body(), list)).isEmpty();

        assertThatThrownBy(() -> projects.delete(reader.id(), p.id())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> projects.share(stranger.id(), p.id(), stranger.username(), Project.Access.WRITE))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
