package systems.grebe.devtools.mcp.backend.shares;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import systems.grebe.devtools.mcp.backend.memories.MemoryService;
import systems.grebe.devtools.mcp.backend.skills.SkillService;
import systems.grebe.devtools.mcp.backend.skills.SkillTestSupport;
import systems.grebe.devtools.mcp.modules.memories.MemoryViews;
import systems.grebe.devtools.mcp.modules.shares.ShareViews;
import systems.grebe.devtools.mcp.modules.skills.SkillViews;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Teilen von Skills und Memories zwischen Benutzern auf einer gemeinsamen Datenbank (je Benutzer ein Spring-Kontext
 * wie in {@code SkillScopingTest}): für einen Benutzer, für eine Rolle und für alle.
 */
class ShareTest {

    static final String ANNA = "anna@example.com";
    static final String BERND = "bernd@example.com";
    static final String CARLA = "carla@example.com";
    static final String DIRK = "dirk@example.com";

    @TempDir
    Path home;

    private final List<ConfigurableApplicationContext> contexts = new ArrayList<>();

    @AfterEach
    void close() {
        contexts.forEach(ConfigurableApplicationContext::close);
    }

    private ConfigurableApplicationContext app(String user, boolean admin, String... roles) {
        String url = "jdbc:h2:file:" + home.resolve("shared").toAbsolutePath() + ";AUTO_SERVER=TRUE";
        ConfigurableApplicationContext ctx = SkillTestSupport.start(home.resolve("server"), url, user, admin, null,
                List.of(roles));
        contexts.add(ctx);
        return ctx;
    }

    private static SkillService skills(ConfigurableApplicationContext ctx) {
        return ctx.getBean(SkillService.class);
    }

    private static MemoryService memories(ConfigurableApplicationContext ctx) {
        return ctx.getBean(MemoryService.class);
    }

    private static ShareViews.Request users(String... users) {
        return new ShareViews.Request(List.of(users), null, false);
    }

    private static ShareViews.Request roles(String... roles) {
        return new ShareViews.Request(null, List.of(roles), false);
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(contexts.getFirst().getBean(javax.sql.DataSource.class));
    }

    @Test
    void skillSharedWithAUserIsVisibleToHimOnlyAndReadOnly() {
        SkillService anna = skills(app(ANNA, false));
        SkillService bernd = skills(app(BERND, false));
        SkillService carla = skills(app(CARLA, false));
        anna.create("heap-leak", "Verwenden, wenn der Heap wächst.", "1. jvm_heap\n2. heap dump\n", "jvm",
                List.of("heap"), 5_000);
        anna.writeFile("heap-leak", "references/jcmd.md", "GC.class_histogram", null, 5_000);

        assertThat(anna.share("heap-leak", users(BERND), false))
                .contains("freigegeben für " + BERND, "geteilt mit: " + BERND);
        assertThat(anna.shares("heap-leak")).extracting(ShareViews.Share::target, ShareViews.Share::name)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(ShareViews.Target.USER, BERND));
        // nochmal: nichts geändert
        assertThat(anna.share("heap-leak", users(BERND.toUpperCase()), false)).startsWith("Schon freigegeben");

        assertThat(bernd.list(null, null)).contains("heap-leak+", "(von " + ANNA + ")", "+ geteilt");
        assertThat(bernd.view("heap-leak", null)).contains("geteilt von " + ANNA, "jvm_heap");
        assertThat(bernd.view("heap-leak", "references/jcmd.md")).contains("GC.class_histogram");
        assertThat(bernd.overview()).singleElement().satisfies(s -> {
            assertThat(s.scope()).isEqualTo(SkillViews.Scope.SHARED);
            assertThat(s.owner()).isEqualTo(ANNA);
        });
        assertThat(bernd.details("heap-leak")).get().extracting(d -> d.summary().scope())
                .isEqualTo(SkillViews.Scope.SHARED);
        assertThat(carla.list(null, null)).doesNotContain("heap-leak");
        assertThatThrownBy(() -> carla.view("heap-leak", null)).hasMessageContaining("gibt es nicht");

        // Löschen und weiter teilen darf nur die Eigentümerin
        assertThatThrownBy(() -> bernd.delete("heap-leak")).hasMessageContaining("von " + ANNA + " geteilt");
        assertThatThrownBy(() -> bernd.share("heap-leak", users(CARLA), false))
                .hasMessageContaining("nur eigene Skills");

        // Ändern legt eine persönliche Kopie an, Annas Skill bleibt
        assertThat(bernd.patch("heap-leak", "heap dump", "heap dump mit jcmd", null, null, null, null, 5_000))
                .contains("persönliche Kopie");
        assertThat(bernd.view("heap-leak", null)).contains("heap dump mit jcmd").doesNotContain("geteilt von");
        assertThat(bernd.view("heap-leak", "references/jcmd.md")).contains("GC.class_histogram");
        assertThat(bernd.overview()).singleElement().extracting(SkillViews.Summary::scope)
                .isEqualTo(SkillViews.Scope.OWN);
        assertThat(anna.view("heap-leak", null)).doesNotContain("mit jcmd");

        // Zurücknehmen
        assertThat(anna.share("heap-leak", users(BERND), true)).contains("nicht mehr freigegeben", "nicht geteilt");
        assertThat(anna.shares("heap-leak")).isEmpty();
    }

    @Test
    void skillSharedWithARoleReachesItsMembers() {
        SkillService anna = skills(app(ANNA, false));
        SkillService bernd = skills(app(BERND, false, "Entwickler"));
        SkillService carla = skills(app(CARLA, false, "Support"));
        anna.create("release", "Verwenden für ein Release.", "1. tag\n2. push\n", null, null, 5_000);

        assertThatThrownBy(() -> anna.share("release", roles("unbekannt"), false))
                .hasMessageContaining("Rolle 'unbekannt' gibt es nicht");
        assertThatThrownBy(() -> anna.share("release", users("bernd"), false))
                .hasMessageContaining("Benutzer 'bernd' gibt es nicht");
        assertThatThrownBy(() -> anna.share("release", users(ANNA), false)).hasMessageContaining("eigene Benutzer");

        assertThat(anna.share("release", roles("Entwickler"), false)).contains("Rolle Entwickler");
        assertThat(bernd.view("release", null)).contains("geteilt von " + ANNA);
        assertThat(carla.list(null, null)).doesNotContain("release");
        assertThat(skills(contexts.get(1)).visibleCount()).isEqualTo(1);
    }

    @Test
    void sharingWithEveryoneNeedsThePermissionAndOwnOrGlobalSkillsWin() {
        SkillService anna = skills(app(ANNA, false));
        SkillService admin = skills(app(DIRK, true));
        SkillService bernd = skills(app(BERND, false));
        ShareViews.Request all = new ShareViews.Request(null, null, true);
        anna.create("gradle-jdk", "Verwenden, wenn Gradle am JDK scheitert.", "Annas Weg", null, null, 5_000);
        admin.create("deploy", "Verwenden fürs Deployment.", "Dirks Weg", null, null, 5_000);

        assertThatThrownBy(() -> anna.share("gradle-jdk", all, false)).hasMessageContaining("Mit allen teilen");
        assertThat(admin.share("deploy", all, false)).contains("freigegeben für alle");
        assertThat(anna.view("deploy", null)).contains("Dirks Weg");
        assertThat(bernd.view("deploy", null)).contains("Dirks Weg");

        // Ein eigener Skill gleichen Namens verdeckt den geteilten
        bernd.create("deploy", "Verwenden fürs Deployment.", "Bernds Weg", null, null, 5_000);
        assertThat(bernd.view("deploy", null)).contains("Bernds Weg");
        assertThat(bernd.overview()).extracting(SkillViews.Summary::name).containsExactly("deploy");
        // Eigener Skill gelöscht → geteilter wieder sichtbar
        bernd.delete("deploy");
        assertThat(bernd.view("deploy", null)).contains("Dirks Weg");

        // Löscht der Eigentümer seinen Skill, entfallen die Freigaben
        admin.delete("deploy");
        assertThat(jdbc().queryForObject("select count(*) from item_share", Integer.class)).isZero();
        assertThatThrownBy(() -> bernd.view("deploy", null)).hasMessageContaining("gibt es nicht");
    }

    @Test
    void sharedMemoriesAreFoundButReadOnly() throws Exception {
        MemoryService anna = memories(app(ANNA, false));
        MemoryService bernd = memories(app(BERND, false, "Entwickler"));
        MemoryService carla = memories(app(CARLA, false));
        String saved = anna.save("Ticket ABC-123 reviewt", "Kriterien fehlen", null, "shop", "ticket-review",
                "ABC-123", List.of("review"), 5_000);
        long id = Long.parseLong(saved.replaceAll("\\D*#(\\d+).*", "$1"));
        Path log = home.resolve("server.log");
        java.nio.file.Files.writeString(log, "ERROR bei der Prüfung");
        anna.attachFile(id, "server.log", log, null, false);
        String callback = anna.save("Rückruf", "weiter mit X", MemoryViews.Type.INVOCATION, null, null, null, null,
                5_000);
        long invocation = Long.parseLong(callback.replaceAll("\\D*#(\\d+).*", "$1"));

        assertThat(anna.share(id, roles("Entwickler"), false)).contains("Memory #" + id, "Rolle Entwickler");
        assertThatThrownBy(() -> anna.share(invocation, users(BERND), false)).hasMessageContaining("Rückruf");

        assertThat(bernd.search("ABC-123", null, null, null, null, null, null))
                .contains("#" + id, "geteilt von " + ANNA, "Kriterien fehlen");
        assertThat(bernd.view(id)).contains("geteilt von " + ANNA + " (schreibgeschützt)");
        assertThat(bernd.viewFile(id, "server.log")).contains("ERROR bei der Prüfung");
        assertThat(bernd.details(id)).get().satisfies(e -> {
            assertThat(e.shared()).isTrue();
            assertThat(e.owner()).isEqualTo(ANNA);
        });
        assertThat(bernd.overview(null, null, null, 10)).extracting(MemoryViews.Entry::id).containsExactly(id);
        assertThat(bernd.count()).isZero();
        assertThatThrownBy(() -> bernd.update(id, null, null, "Nachtrag", null, null, null, null, null, false, 5_000))
                .hasMessageContaining("schreibgeschützt");
        assertThatThrownBy(() -> bernd.delete(id, false)).hasMessageContaining("schreibgeschützt");
        assertThatThrownBy(() -> bernd.share(id, users(CARLA), false)).hasMessageContaining("schreibgeschützt");
        assertThat(carla.search("ABC-123", null, null, null, null, null, null)).contains("Keine Memories");
        assertThatThrownBy(() -> carla.view(id)).hasMessageContaining("gibt es nicht");

        assertThat(anna.shares(id)).extracting(ShareViews.Share::label).containsExactly("Rolle Entwickler");
        anna.delete(id, false);
        assertThat(jdbc().queryForObject("select count(*) from item_share", Integer.class)).isZero();
    }
}
