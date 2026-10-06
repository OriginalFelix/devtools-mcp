package systems.grebe.devtools.mcp;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javafx.application.Application;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import systems.grebe.devtools.mcp.config.ModuleSettings;
import systems.grebe.devtools.mcp.config.SettingsStore;
import systems.grebe.devtools.mcp.fx.FxApp;
import systems.grebe.devtools.mcp.modules.skills.SkillsModule;
import systems.grebe.devtools.mcp.plugin.PluginSigner;
import systems.grebe.devtools.mcp.remote.EmbeddedBackend;
import systems.grebe.devtools.mcp.remote.LocalUser;

/**
 * Einstiegspunkt. Die Klasse erweitert bewusst NICHT {@link Application}, damit die App auch
 * aus einem Fat-Jar (JavaFX auf dem Classpath) startet.
 *
 * <p>Mit {@code --headless} (oder {@code DEVTOOLS_MCP_HEADLESS=true}) startet nur der MCP-Server, ohne
 * JavaFX-Fenster und Tray.
 *
 * <p>Das Backend (Paket {@code systems.grebe.devtools.mcp.backend}) nimmt nicht der Component-Scan auf, sondern
 * {@link EmbeddedBackend} – nur ohne eingetragenen Team-Server.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(excludeFilters = {
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class),
        @ComponentScan.Filter(type = FilterType.REGEX, pattern = "systems\\.grebe\\.devtools\\.mcp\\.backend\\..*")})
public class DevToolsMcpApplication {

    public static final String HEADLESS_ARG = "--headless";

    /** Ohne eingebettetes Backend unnötig: Datenbanken, JPA, Flyway und der GraphQL-Server. */
    static final List<String> BACKEND_AUTO_CONFIG = List.of(
            "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
            "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration",
            "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
            "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration",
            "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
            "org.springframework.boot.graphql.autoconfigure.GraphQlAutoConfiguration",
            "org.springframework.boot.graphql.autoconfigure.servlet.GraphQlWebMvcAutoConfiguration");

    /**
     * JavaFX warnt beim Start, wenn es vom Classpath statt als Modul geladen wird – im Fat-Jar geht es nicht anders.
     * Fest referenziert, weil java.util.logging Logger nur schwach hält (sonst ginge der Level wieder verloren).
     */
    private static final Logger FX_PLATFORM_LOG = Logger.getLogger("com.sun.javafx.application.PlatformImpl");

    public static void main(String[] args) {
        if (args.length > 0 && PluginSigner.COMMAND.equals(args[0])) {
            PluginSigner.main(Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (headless(args)) {
            startSpring(args, true);
        } else {
            FX_PLATFORM_LOG.setLevel(Level.SEVERE);
            Application.launch(FxApp.class, args);
        }
    }

    static boolean headless(String[] args) {
        return Arrays.asList(args).contains(HEADLESS_ARG)
                || Boolean.parseBoolean(System.getenv("DEVTOOLS_MCP_HEADLESS"));
    }

    /** Start aus der Desktop-App (JavaFX, Tray). */
    public static ConfigurableApplicationContext startSpring(String[] args) {
        return startSpring(args, false);
    }

    /**
     * Startet den Spring-Kontext (inkl. MCP-Server). Die Einstellungen werden vorab geladen, weil der
     * HTTP-Port vor dem Start des Webservers feststehen muss und davon abhängt, ob das Backend eingebettet läuft.
     */
    static ConfigurableApplicationContext startSpring(String[] args, boolean headless) {
        SettingsStore store = new SettingsStore(SettingsStore.defaultHome());
        String[] springArgs = Arrays.stream(args).filter(a -> !HEADLESS_ARG.equals(a)).toArray(String[]::new);
        return new SpringApplicationBuilder(DevToolsMcpApplication.class)
                .headless(headless) // ohne headless: AWT-SystemTray
                .properties(properties(store))
                .initializers(ctx -> ctx.getBeanFactory().registerSingleton("settingsStore", store))
                .run(springArgs);
    }

    /** Port, eingebettetes Backend ja/nein und – falls früher eingestellt – die Verbindung der Skill-Datenbank. */
    static Map<String, Object> properties(SettingsStore store) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("server.port", store.server().port());
        boolean embedded = !store.team().configured();
        p.put(EmbeddedBackend.PROPERTY, embedded);
        if (!embedded) {
            p.put("spring.autoconfigure.exclude", String.join(",", BACKEND_AUTO_CONFIG));
            return p;
        }
        Map<String, String> skills = store.module(SkillsModule.ID).map(ModuleSettings::values).orElse(Map.of());
        putIfSet(p, "devtools.skills.datasource.url", skills.get(SkillsModule.LEGACY_JDBC_URL));
        putIfSet(p, "devtools.skills.datasource.username", skills.get(SkillsModule.LEGACY_USERNAME));
        putIfSet(p, "devtools.skills.datasource.password", skills.get(SkillsModule.LEGACY_PASSWORD));
        LocalUser.email(store).ifPresent(o -> p.put("devtools.skills.legacy-owner", o.toLowerCase(java.util.Locale.ROOT)));
        return p;
    }

    private static void putIfSet(Map<String, Object> p, String key, String value) {
        if (value != null && !value.isBlank()) {
            p.put(key, value);
        }
    }
}
