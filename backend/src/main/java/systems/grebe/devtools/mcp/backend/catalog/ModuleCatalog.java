package systems.grebe.devtools.mcp.backend.catalog;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import systems.grebe.devtools.mcp.api.Catalog;
import systems.grebe.devtools.mcp.api.ModuleDescriptor;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Die Module der Desktop-Apps (Tabelle {@code module_catalog}). Der Server führt selbst keine Module aus; die
 * Desktop-Apps melden beim Verbinden ihre Module samt Feldern und Tools, und die Web-UI bearbeitet damit die
 * Einstellungen. Die zuletzt gemeldete Beschreibung eines Moduls gilt; Module, die keine App mehr meldet, bleiben
 * stehen (andere Desktops haben sie womöglich noch, etwa als Plugin).
 *
 * <p>Deshalb kann der Katalog Beschreibungen anderer App-Versionen enthalten: Unbekannte Feldtypen werden als Text
 * gelesen, eine ganz unlesbare Beschreibung wird übersprungen – sie darf nie die übrigen Module blockieren.
 */
@Service
public class ModuleCatalog {

    private static final Logger LOG = LoggerFactory.getLogger(ModuleCatalog.class);

    private static final Comparator<ModuleDescriptor> ORDER = Comparator.comparingInt(ModuleDescriptor::order)
            .thenComparing(ModuleDescriptor::displayName, String.CASE_INSENSITIVE_ORDER);

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;
    private final JsonMapper json = JsonMapper.builder()
            .enable(EnumFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE)
            .build();
    private final Clock clock = Clock.systemUTC();
    private final AtomicReference<List<ModuleDescriptor>> cache = new AtomicReference<>();

    public ModuleCatalog(@Qualifier("coreJdbc") JdbcClient jdbc, @Qualifier("coreTransactions") TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /** Alle bekannten Module, sortiert wie in der Desktop-App. */
    public List<ModuleDescriptor> modules() {
        List<ModuleDescriptor> list = cache.get();
        if (list == null) {
            list = jdbc.sql("SELECT module_id, descriptor FROM module_catalog")
                    .query((rs, n) -> read(rs.getString("module_id"), rs.getString("descriptor"))).list().stream()
                    .filter(Objects::nonNull).sorted(ORDER).toList();
            cache.set(list);
        }
        return list;
    }

    /** Eine gespeicherte Beschreibung oder {@code null}, wenn sie nicht lesbar ist. */
    private ModuleDescriptor read(String moduleId, String descriptor) {
        try {
            return json.readValue(descriptor, ModuleDescriptor.class);
        } catch (JacksonException e) {
            LOG.warn("Beschreibung des Moduls {} im Katalog nicht lesbar (andere App-Version?) – übersprungen: {}",
                    moduleId, e.getOriginalMessage());
            return null;
        }
    }

    public Optional<ModuleDescriptor> module(String id) {
        return modules().stream().filter(m -> m.id().equals(id)).findFirst();
    }

    /** Übernimmt die gemeldeten Module (ersetzt deren bisherige Beschreibung). */
    public void report(Catalog catalog) {
        Timestamp now = Timestamp.from(clock.instant());
        tx.executeWithoutResult(s -> catalog.modules().forEach(m -> {
            if (m.id() == null || m.id().isBlank() || m.id().length() > 32) {
                throw new IllegalArgumentException("Ungültige Modul-ID: " + m.id());
            }
            String descriptor = json.writeValueAsString(m);
            if (jdbc.sql("UPDATE module_catalog SET descriptor = ?, updated_at = ? WHERE module_id = ?")
                    .params(descriptor, now, m.id()).update() == 0) {
                jdbc.sql("INSERT INTO module_catalog (module_id, descriptor, updated_at) VALUES (?, ?, ?)")
                        .params(m.id(), descriptor, now).update();
            }
        }));
        cache.set(null);
    }
}
