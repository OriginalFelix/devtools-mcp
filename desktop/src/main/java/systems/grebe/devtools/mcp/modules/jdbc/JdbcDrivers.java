package systems.grebe.devtools.mcp.modules.jdbc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.sql.Driver;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import systems.grebe.devtools.mcp.core.ToolProgress;
import systems.grebe.devtools.mcp.core.LocalFiles;

/**
 * Lädt JDBC-Treiber – so, dass ohne Zutun des Nutzers fast jede Datenbank funktioniert:
 * <ol>
 *   <li>Feld „Treiber“ leer: ein Treiber im Klassenpfad der App, der die URL annimmt (H2), sonst der bekannte Treiber
 *   zum Subprotokoll der URL aus den Maven-Repositories des Plugin-Stores ({@code jdbc:postgresql:} →
 *   {@code org.postgresql:postgresql}, neueste stabile Version).</li>
 *   <li>Maven-Koordinaten {@code groupId:artifactId[:version]}: dieser Treiber samt Laufzeit-Abhängigkeiten.</li>
 *   <li>Sonst Pfade zu JAR-Dateien oder Verzeichnissen mit JARs, getrennt durch {@code ;}.</li>
 * </ol>
 * Treiber aus JARs bekommen einen eigenen Class-Loader mit dem Plattform-Loader als Elternteil – so laufen mehrere
 * Versionen nebeneinander und keine stößt mit den Bibliotheken der App zusammen. Geladene Treiber bleiben für die
 * Laufzeit der App im Speicher (Auflösung und Download nur einmal).
 */
final class JdbcDrivers {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcDrivers.class);

    /** Lädt Maven-Artefakte samt Laufzeit-Abhängigkeiten; ohne Version die neueste stabile. */
    @FunctionalInterface
    interface MavenLookup {
        List<Path> resolve(String coordinates);
    }

    /** Bekannte Treiber je Subprotokoll ({@code jdbc:<subprotokoll>:…}). */
    static final Map<String, String> KNOWN = Map.ofEntries(
            Map.entry("postgresql", "org.postgresql:postgresql"),
            Map.entry("mysql", "com.mysql:mysql-connector-j"),
            Map.entry("mariadb", "org.mariadb.jdbc:mariadb-java-client"),
            Map.entry("sqlserver", "com.microsoft.sqlserver:mssql-jdbc"),
            Map.entry("jtds", "net.sourceforge.jtds:jtds"),
            Map.entry("oracle", "com.oracle.database.jdbc:ojdbc11"),
            Map.entry("db2", "com.ibm.db2:jcc"),
            Map.entry("as400", "net.sf.jt400:jt400"),
            Map.entry("informix-sqli", "com.ibm.informix:jdbc"),
            Map.entry("sap", "com.sap.cloud.db.jdbc:ngdbc"),
            Map.entry("sqlite", "org.xerial:sqlite-jdbc"),
            Map.entry("h2", "com.h2database:h2"),
            Map.entry("hsqldb", "org.hsqldb:hsqldb"),
            Map.entry("firebirdsql", "org.firebirdsql.jdbc:jaybird"),
            Map.entry("duckdb", "org.duckdb:duckdb_jdbc"),
            Map.entry("clickhouse", "com.clickhouse:clickhouse-jdbc"),
            Map.entry("redshift", "com.amazon.redshift:redshift-jdbc42"),
            Map.entry("snowflake", "net.snowflake:snowflake-jdbc"),
            Map.entry("trino", "io.trino:trino-jdbc"),
            Map.entry("exa", "com.exasol:exasol-jdbc"));

    private static final Pattern COORDINATES = Pattern.compile("[\\w.-]+:[\\w.-]+(?::[\\w.+-]+)?");

    private final MavenLookup maven;
    private final Map<String, Driver> cache = new ConcurrentHashMap<>();

    JdbcDrivers(MavenLookup maven) {
        this.maven = maven;
    }

    /** Treiber für die Verbindung; wirft eine verständliche {@link IllegalStateException}, wenn keiner passt. */
    Driver driver(JdbcConnection c) {
        String spec = c.driver();
        if (spec.isEmpty()) {
            Driver d = fromClasspath(c);
            if (d != null) {
                return d;
            }
            String known = KNOWN.get(c.subprotocol());
            if (known == null) {
                throw new IllegalStateException("Verbindung '" + c.name() + "': kein JDBC-Treiber für "
                        + (c.subprotocol().isEmpty() ? "diese URL" : "jdbc:" + c.subprotocol() + ":")
                        + " bekannt. Der Nutzer kann in der DevTools-App unter „Treiber“ Maven-Koordinaten "
                        + "(groupId:artifactId[:version]) oder den Pfad einer JAR-Datei eintragen.");
            }
            spec = known;
        }
        String key = spec + "|" + c.driverClass() + "|" + c.subprotocol();
        Driver cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        String finalSpec = spec;
        // computeIfAbsent sperrt nur diesen Schlüssel: derselbe Treiber wird nicht doppelt heruntergeladen
        return cache.computeIfAbsent(key, k -> fromJars(jars(c, finalSpec), c));
    }

    /** Ob ein Treiber für die Verbindung im Klassenpfad der App liegt (ohne Download). */
    static boolean onClasspath(JdbcConnection c) {
        return c.driver().isEmpty() && fromClasspath(c) != null;
    }

    // ------------------------------------------------------------------ intern

    private List<Path> jars(JdbcConnection c, String spec) {
        if (COORDINATES.matcher(spec).matches()) {
            if (maven == null) {
                throw new IllegalStateException("Verbindung '" + c.name() + "': Treiber " + spec + " kann nicht "
                        + "geladen werden – kein Maven-Zugriff. Der Nutzer kann den Pfad einer JAR-Datei eintragen.");
            }
            ToolProgress.report("Lade JDBC-Treiber " + spec + " …");
            LOG.info("Lade JDBC-Treiber {} für Verbindung {}", spec, c.name());
            try {
                return maven.resolve(spec);
            } catch (RuntimeException e) {
                throw new IllegalStateException("Verbindung '" + c.name() + "': JDBC-Treiber " + spec + " nicht "
                        + "ladbar: " + e.getMessage() + " – ohne Netzwerk die Version fest angeben (nach einmaligem "
                        + "Download liegt sie im Cache) oder den Pfad einer JAR-Datei unter „Treiber“ eintragen.", e);
            }
        }
        return paths(c, spec);
    }

    /** JAR-Dateien und alle {@code *.jar} in Verzeichnissen, getrennt durch {@code ;} oder Zeilenumbrüche. */
    static List<Path> paths(JdbcConnection c, String spec) {
        List<Path> out = new ArrayList<>();
        for (String part : spec.split("[;\\n]")) {
            String raw = part.strip();
            if (raw.isEmpty()) {
                continue;
            }
            Path p;
            try {
                p = Path.of(LocalFiles.expandHome(raw));
            } catch (InvalidPathException e) {
                throw new IllegalStateException("Verbindung '" + c.name() + "': ungültiger Treiber-Pfad " + raw);
            }
            if (Files.isDirectory(p)) {
                try (Stream<Path> files = Files.list(p)) {
                    files.filter(f -> f.getFileName().toString().endsWith(".jar")).sorted().forEach(out::add);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            } else if (Files.isRegularFile(p)) {
                out.add(p);
            } else {
                throw new IllegalStateException("Verbindung '" + c.name() + "': Treiber-Datei " + p + " nicht gefunden.");
            }
        }
        if (out.isEmpty()) {
            throw new IllegalStateException("Verbindung '" + c.name() + "': unter „Treiber“ keine JAR-Datei gefunden ("
                    + spec + ").");
        }
        return out;
    }

    private static Driver fromClasspath(JdbcConnection c) {
        ClassLoader loader = JdbcDrivers.class.getClassLoader();
        if (!c.driverClass().isEmpty()) {
            try {
                return instantiate(Class.forName(c.driverClass(), true, loader), c);
            } catch (ClassNotFoundException e) {
                return null;
            }
        }
        return accepting(ServiceLoader.load(Driver.class, loader), c, null);
    }

    private static Driver fromJars(List<Path> jars, JdbcConnection c) {
        URL[] urls = new URL[jars.size()];
        for (int i = 0; i < urls.length; i++) {
            try {
                urls[i] = jars.get(i).toUri().toURL();
            } catch (MalformedURLException e) {
                throw new IllegalStateException("Ungültiger Treiber-Pfad " + jars.get(i), e);
            }
        }
        URLClassLoader loader = new URLClassLoader("jdbc-" + c.subprotocol(), urls,
                ClassLoader.getPlatformClassLoader());
        try {
            Driver d;
            if (!c.driverClass().isEmpty()) {
                try {
                    d = instantiate(Class.forName(c.driverClass(), true, loader), c);
                } catch (ClassNotFoundException e) {
                    throw new IllegalStateException("Verbindung '" + c.name() + "': Treiberklasse " + c.driverClass()
                            + " nicht in " + jars + ".");
                }
            } else {
                d = accepting(ServiceLoader.load(Driver.class, loader), c, loader);
            }
            if (d == null) {
                throw new IllegalStateException("Verbindung '" + c.name() + "': kein Treiber in " + jars.stream()
                        .map(p -> p.getFileName().toString()).toList() + " nimmt die URL an – URL prüfen oder unter "
                        + "„Treiberklasse“ die Klasse angeben.");
            }
            LOG.info("JDBC-Treiber {} {}.{} aus {}", d.getClass().getName(), d.getMajorVersion(), d.getMinorVersion(),
                    jars);
            return d;
        } catch (RuntimeException e) {
            try {
                loader.close();
            } catch (IOException ignored) {
                // nichts zu tun
            }
            throw e;
        }
    }

    /** Erster Treiber, der die URL annimmt; mit {@code only} nur Treiber aus diesem Loader. */
    private static Driver accepting(ServiceLoader<Driver> services, JdbcConnection c, ClassLoader only) {
        Iterator<Driver> it = services.iterator();
        // Der Iterator macht nach einem defekten Eintrag mit dem nächsten weiter
        for (int i = 0; i < 1000; i++) {
            try {
                if (!it.hasNext()) {
                    return null;
                }
                Driver d = it.next();
                if ((only == null || d.getClass().getClassLoader() == only) && d.acceptsURL(c.url())) {
                    return d;
                }
            } catch (ServiceConfigurationError | SQLException | LinkageError e) {
                LOG.debug("JDBC-Treiber übersprungen: {}", e.toString());
            }
        }
        return null;
    }

    private static Driver instantiate(Class<?> type, JdbcConnection c) {
        if (!Driver.class.isAssignableFrom(type)) {
            throw new IllegalStateException("Verbindung '" + c.name() + "': " + type.getName()
                    + " ist kein java.sql.Driver.");
        }
        try {
            return (Driver) type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Verbindung '" + c.name() + "': Treiber " + type.getName()
                    + " nicht instanziierbar: " + e, e);
        }
    }
}
