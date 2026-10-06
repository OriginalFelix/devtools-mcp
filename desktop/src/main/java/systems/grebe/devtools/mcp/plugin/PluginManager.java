package systems.grebe.devtools.mcp.plugin;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ConfigurableApplicationContext;
import systems.grebe.devtools.mcp.config.PluginSettings;
import systems.grebe.devtools.mcp.config.SettingsStore;
import systems.grebe.devtools.mcp.core.ContextLoaderProxy;
import systems.grebe.devtools.mcp.core.ManagedToolCallback;
import systems.grebe.devtools.mcp.core.ServiceProvider;
import systems.grebe.devtools.mcp.core.ToolModule;
import systems.grebe.devtools.mcp.core.ToolRegistry;
import systems.grebe.devtools.mcp.modules.chat.spi.ChatProvider;
import systems.grebe.devtools.mcp.modules.container.spi.ContainerRuntimeProvider;
import systems.grebe.devtools.mcp.modules.pr.spi.GitServerProvider;
import systems.grebe.devtools.mcp.modules.ticket.spi.TicketProvider;
import systems.grebe.devtools.mcp.plugin.store.MavenPluginResolver;

/**
 * Lädt Plugins aus dem Plugin-Ordner ({@code ~/.devtools-mcp/plugins/*.jar}) – angelehnt an Bukkit: jedes Jar trägt
 * eine {@link PluginDescriptor plugin.yml}, die Hauptklasse erweitert {@link DevToolsPlugin}, jedes Plugin bekommt
 * einen eigenen {@link PluginClassLoader}. Reihenfolge nach {@code depend}/{@code softdepend}; fehlt eine
 * Pflicht-Abhängigkeit oder scheitert ein Plugin, bleiben die übrigen davon unberührt.
 *
 * <p>Änderungen (installieren, aktualisieren, entfernen, an/aus) wirken sofort: das betroffene Plugin und alle, die
 * davon abhängen, werden deaktiviert, die Dateien geändert und danach wieder geladen. Die Module der Plugins
 * erscheinen in der {@link ToolRegistry} wie eingebaute; verbundene Clients erhalten {@code tools/list_changed}.
 *
 * <p>Alle ändernden Operationen sind über diese Instanz synchronisiert.
 */
public class PluginManager implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(PluginManager.class);
    private static final String BROKEN_PREFIX = "file:";

    /** Provider-Schnittstellen, deren Implementierungen Plugins über {@code META-INF/services} beisteuern können. */
    public static final List<Class<? extends ServiceProvider>> PROVIDER_TYPES = List.of(TicketProvider.class,
            ChatProvider.class, GitServerProvider.class, ContainerRuntimeProvider.class);

    public enum State {
        /** Geladen und aktiv, Module registriert. */
        ENABLED,
        /** Vom Benutzer abgeschaltet – das Jar liegt im Ordner, wird aber nicht geladen. */
        DISABLED,
        /** Konnte nicht geladen oder aktiviert werden (siehe {@link PluginInfo#error()}). */
        FAILED
    }

    /**
     * Anzeige-Sicht auf ein Plugin. {@code name} ist bei einem Jar ohne gültige {@code plugin.yml} der Dateiname,
     * {@code source} die Maven-Koordinate bei Installation aus dem Store, {@code signature} das Ergebnis der Prüfung
     * von {@code plugin.jwt} ({@code null} bei ungültigem Jar), {@code providers} die beigesteuerten Provider
     * ({@code TicketProvider redmine}).
     */
    public record PluginInfo(String name, String version, String description, List<String> authors, String website,
                             String file, State state, String error, List<String> modules, String source,
                             List<String> depend, List<String> softDepend, List<String> libraries, boolean valid,
                             PluginSignature signature, List<String> providers) {
    }

    private final Path directory;
    private final SettingsStore store;
    private final Supplier<Set<String>> builtinModuleIds;
    private final Supplier<ToolRegistry> registrySupplier;
    private final MavenPluginResolver resolver;
    private final ConfigurableApplicationContext app;
    private final ClassLoader parentLoader = PluginManager.class.getClassLoader();
    /** Schlüssel: Plugin-Name bzw. {@code file:<Dateiname>} für ungültige Jars. Einfügereihenfolge = Ladereihenfolge. */
    private final Map<String, Loaded> plugins = new LinkedHashMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private volatile List<PluginInfo> snapshot = List.of();
    private volatile List<ToolModule> activeSnapshot = List.of();
    private volatile Map<Class<?>, List<?>> providerSnapshot = Map.of();
    private ToolRegistry registry;
    private boolean loaded;

    /** Ohne App-Kontext (Tests): Plugins bekommen einen eigenen Spring-Kontext, aber keine App-Beans. */
    public PluginManager(Path directory, SettingsStore store, Supplier<Set<String>> builtinModuleIds,
                         Supplier<ToolRegistry> registry, MavenPluginResolver resolver) {
        this(directory, store, builtinModuleIds, registry, resolver, null);
    }

    /**
     * @param builtinModuleIds IDs der eingebauten Module (dürfen von Plugins nicht belegt werden)
     * @param registry         liefert die Registry; wird erst bei {@link #attachRegistry()} abgefragt
     * @param app              Spring-Kontext der App: seine Beans sind in Plugins per {@code @Autowired} verfügbar,
     *                         seine Properties per {@code @Value}; {@code null} = keine
     */
    public PluginManager(Path directory, SettingsStore store, Supplier<Set<String>> builtinModuleIds,
                         Supplier<ToolRegistry> registry, MavenPluginResolver resolver,
                         ConfigurableApplicationContext app) {
        this.directory = directory;
        this.store = store;
        this.builtinModuleIds = builtinModuleIds;
        this.registrySupplier = registry;
        this.resolver = resolver;
        this.app = app;
    }

    /**
     * Provider der aktiven Plugins für eine Schnittstelle aus {@link #PROVIDER_TYPES}, in Ladereihenfolge. Ohne Sperre
     * und ohne zu laden (wie {@link #activeModules()}); bei unverändertem Stand dieselbe Liste.
     */
    @SuppressWarnings("unchecked")
    public <T> List<T> activeProviders(Class<T> type) {
        return (List<T>) providerSnapshot.getOrDefault(type, List.of());
    }

    /** Lieferant der Plugin-Provider für eine {@code ProviderRegistry}; leer, solange es keinen Manager gibt. */
    public static <T> Supplier<List<T>> providers(ObjectProvider<PluginManager> manager, Class<T> type) {
        return () -> {
            PluginManager m = manager.getIfAvailable();
            return m == null ? List.of() : m.activeProviders(type);
        };
    }

    /** Öffentliche Schlüssel (PEM), gegen die Plugin-Signaturen geprüft werden. */
    public List<String> trustedKeys() {
        return store.plugins().trustedKeys();
    }

    /**
     * Ersetzt die vertrauenswürdigen Schlüssel und prüft die Signaturen aller Plugins neu (ohne sie neu zu laden).
     *
     * @throws IllegalArgumentException wenn ein Eintrag keinen lesbaren öffentlichen Schlüssel enthält
     */
    public synchronized void setTrustedKeys(List<String> pems) {
        List<String> keys = pems.stream().map(String::strip).filter(k -> !k.isEmpty()).toList();
        keys.forEach(PluginKeys::publicKeys);
        store.savePlugins(store.plugins().withTrustedKeys(keys));
        if (!loaded) {
            return;
        }
        for (Loaded l : plugins.values()) {
            if (l.descriptor != null) {
                l.signature = PluginSignature.check(l.jar, l.descriptor, keys);
            }
        }
        publish();
    }

    /** {@code <Einstellungsordner>/plugins}. */
    public static Path defaultDirectory(SettingsStore store) {
        return store.file().toAbsolutePath().getParent().resolve("plugins");
    }

    public Path directory() {
        return directory;
    }

    // ------------------------------------------------------------------ Lesen

    /** Stand aller Plugins (auch deaktivierte und fehlerhafte), sortiert nach Name. */
    public List<PluginInfo> plugins() {
        ensureLoaded();
        return snapshot;
    }

    public Optional<PluginInfo> plugin(String name) {
        return plugins().stream().filter(p -> p.name().equals(name)).findFirst();
    }

    /** Module aller aktiven Plugins (lädt die Plugins beim ersten Aufruf). */
    public List<ToolModule> modules() {
        ensureLoaded();
        return activeModules();
    }

    /**
     * Module der gerade aktiven Plugins, ohne etwas zu laden – für die Server-Instructions, die bei jedem
     * {@code initialize} gebaut werden (auch schon beim Aufbau des MCP-Servers, wenn noch kein Plugin läuft).
     */
    public List<ToolModule> activeModules() {
        return activeSnapshot; // ohne Sperre: ein laufendes Installieren darf den Verbindungsaufbau nicht blockieren
    }

    public void addChangeListener(Runnable listener) {
        listeners.add(listener);
    }

    // ------------------------------------------------------------------ Lebenszyklus

    /** Lädt die Plugins beim ersten Bedarf (ohne Registry: Module werden vorgemerkt). */
    public synchronized void ensureLoaded() {
        if (!loaded) {
            loaded = true;
            apply(Set.of(), removed -> { });
        }
    }

    /** Übergibt die Module aller aktiven Plugins an die Registry; ab jetzt wirken Änderungen sofort dort. */
    public synchronized void attachRegistry() {
        if (registry != null) {
            return;
        }
        if (!loaded) {
            // Normalfall beim Start: Registry zuerst, dann laden – Module gehen direkt an den MCP-Server
            registry = registrySupplier.get();
            ensureLoaded();
            return;
        }
        registry = registrySupplier.get();
        for (Loaded l : List.copyOf(plugins.values())) {
            if (l.state != State.ENABLED) {
                continue;
            }
            try {
                l.modules.forEach(registry::register);
            } catch (RuntimeException e) {
                LOG.error("Module von Plugin {} konnten nicht registriert werden", l.key, e);
                shutdown(l, State.FAILED, "Module nicht registrierbar: " + ManagedToolCallback.describe(e));
            }
        }
        publish();
    }

    /** Alle Plugins neu einlesen und laden (wie Bukkits {@code /reload}), z.B. nach Kopieren eines Jars. */
    public synchronized void reload() {
        ensureLoaded();
        apply(Set.copyOf(plugins.keySet()), removed -> { });
    }

    @Override
    public synchronized void close() {
        registry = null; // App wird beendet: Module nicht mehr einzeln am (evtl. schon geschlossenen) Server abmelden
        List<Loaded> all = new ArrayList<>(plugins.values());
        java.util.Collections.reverse(all);
        for (Loaded l : all) {
            if (l.state == State.ENABLED) {
                shutdown(l, State.DISABLED, null);
            }
        }
        plugins.clear();
        loaded = false;
        activeSnapshot = List.of();
    }

    // ------------------------------------------------------------------ Ändern

    /** Schaltet ein Plugin an oder aus (bleibt gespeichert). */
    public synchronized PluginInfo setEnabled(String name, boolean enabled) {
        ensureLoaded();
        requireValid(name);
        apply(Set.of(name), removed -> {
            Set<String> disabled = new LinkedHashSet<>(store.plugins().disabled());
            if (enabled) {
                disabled.remove(name);
            } else {
                disabled.add(name);
            }
            store.savePlugins(store.plugins().withDisabled(disabled));
        });
        return plugin(name).orElseThrow();
    }

    /**
     * Installiert oder ersetzt ein Plugin aus einem Jar (Datei-Auswahl oder Store). Eine vorhandene Version desselben
     * Plugins wird vorher deaktiviert und gelöscht.
     *
     * @param coordinates Maven-Koordinate bei Installation aus dem Store, sonst {@code null}
     */
    public synchronized PluginInfo install(Path jar, String coordinates) {
        ensureLoaded();
        PluginDescriptor d = PluginDescriptorReader.read(jar);
        checkApiVersion(d);
        Path target = directory.resolve(d.name() + "-" + d.version().replaceAll("[^A-Za-z0-9._-]", "_") + ".jar");
        apply(Set.of(d.name()), removed -> {
            try {
                Files.createDirectories(directory);
                Path tmp = directory.resolve("." + d.name() + ".jar.tmp");
                Files.copy(jar, tmp, StandardCopyOption.REPLACE_EXISTING);
                Loaded old = removed.get(d.name());
                if (old != null && !old.jar.equals(target)) {
                    Files.deleteIfExists(old.jar);
                }
                move(tmp, target);
            } catch (IOException e) {
                throw new UncheckedIOException("Plugin konnte nicht in " + directory + " abgelegt werden", e);
            }
            PluginSettings s = store.plugins();
            Set<String> disabled = new LinkedHashSet<>(s.disabled());
            disabled.remove(d.name());
            store.savePlugins(s.withDisabled(disabled).withSource(d.name(), coordinates));
        });
        return plugin(d.name()).orElseThrow();
    }

    /** Deaktiviert und löscht ein Plugin (Jar); sein Datenordner und seine Modul-Einstellungen bleiben erhalten. */
    public synchronized void uninstall(String name) {
        ensureLoaded();
        if (!plugins.containsKey(name) && !plugins.containsKey(BROKEN_PREFIX + name)) {
            throw new IllegalArgumentException("Plugin '" + name + "' ist nicht installiert.");
        }
        String key = plugins.containsKey(name) ? name : BROKEN_PREFIX + name;
        apply(Set.of(key), removed -> {
            try {
                Files.deleteIfExists(removed.get(key).jar);
            } catch (IOException e) {
                throw new UncheckedIOException("Plugin-Datei nicht löschbar: " + removed.get(key).jar, e);
            }
            PluginSettings s = store.plugins();
            Set<String> disabled = new LinkedHashSet<>(s.disabled());
            disabled.remove(name);
            store.savePlugins(s.withDisabled(disabled).withSource(name, null));
        });
    }

    /** Resolver für die {@code libraries} der Plugins (und den Store). */
    public MavenPluginResolver resolver() {
        return resolver;
    }

    // ------------------------------------------------------------------ Kern

    /**
     * Deaktiviert die genannten Plugins samt allen, die (transitiv) von ihnen abhängen, führt die Dateiänderung aus,
     * liest den Ordner neu und aktiviert alles, was noch nicht läuft und nicht abgeschaltet ist.
     */
    private void apply(Set<String> keys, Consumer<Map<String, Loaded>> change) {
        Set<String> affected = withDependents(keys);
        List<Loaded> stopOrder = new ArrayList<>(plugins.values().stream()
                .filter(l -> affected.contains(l.key)).toList());
        java.util.Collections.reverse(stopOrder);
        Map<String, Loaded> removed = new LinkedHashMap<>();
        for (Loaded l : stopOrder) {
            shutdown(l, State.DISABLED, null);
            removed.put(l.key, plugins.remove(l.key));
        }
        try {
            change.accept(removed);
        } finally {
            rescan();
            startPending();
            publish();
        }
    }

    private Set<String> withDependents(Set<String> keys) {
        Set<String> result = new LinkedHashSet<>(keys);
        boolean grown = true;
        while (grown) {
            grown = false;
            for (Loaded l : plugins.values()) {
                if (l.descriptor != null && !result.contains(l.key)
                        && l.descriptor.allDependencies().stream().anyMatch(result::contains)) {
                    result.add(l.key);
                    grown = true;
                }
            }
        }
        return result;
    }

    /** Nimmt Jars auf, die noch nicht erfasst sind; ungültige Jars werden jedes Mal neu bewertet. */
    private void rescan() {
        plugins.keySet().removeIf(k -> k.startsWith(BROKEN_PREFIX));
        Set<Path> known = new HashSet<>();
        plugins.values().forEach(l -> known.add(l.jar));
        for (Path jar : jars()) {
            if (known.contains(jar)) {
                continue;
            }
            Loaded l;
            try {
                PluginDescriptor d = PluginDescriptorReader.read(jar);
                Loaded existing = plugins.get(d.name());
                if (existing != null) {
                    l = Loaded.broken(jar, "Doppelter Plugin-Name '" + d.name() + "' (auch in "
                            + existing.jar.getFileName() + ") – eine der beiden Dateien entfernen.");
                } else {
                    l = new Loaded(d.name(), jar, d);
                    l.signature = PluginSignature.check(jar, d, store.plugins().trustedKeys());
                }
            } catch (RuntimeException e) {
                l = Loaded.broken(jar, ManagedToolCallback.describe(e));
            }
            plugins.put(l.key, l);
        }
    }

    private List<Path> jars() {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<Path> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(directory, "*.jar")) {
            ds.forEach(p -> {
                if (Files.isRegularFile(p) && !p.getFileName().toString().startsWith(".")) {
                    out.add(p);
                }
            });
        } catch (IOException e) {
            LOG.error("Plugin-Ordner {} nicht lesbar", directory, e);
        }
        out.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return out;
    }

    /** Startet alle gültigen, nicht laufenden und nicht abgeschalteten Plugins in Abhängigkeitsreihenfolge. */
    private void startPending() {
        Set<String> disabled = store.plugins().disabled();
        List<Loaded> order = loadOrder();
        // Ladereihenfolge auch für die Map übernehmen (Abschalten später in umgekehrter Reihenfolge)
        Map<String, Loaded> reordered = new LinkedHashMap<>();
        order.forEach(l -> reordered.put(l.key, l));
        plugins.values().forEach(l -> reordered.putIfAbsent(l.key, l));
        plugins.clear();
        plugins.putAll(reordered);

        for (Loaded l : order) {
            if (l.state == State.ENABLED || l.descriptor == null) {
                continue;
            }
            if (disabled.contains(l.key)) {
                l.state = State.DISABLED;
                l.error = null;
                continue;
            }
            start(l);
        }
    }

    /** Topologische Sortierung nach depend/softdepend, bei Gleichstand nach Name; Zyklen werden als Fehler markiert. */
    private List<Loaded> loadOrder() {
        Map<String, Loaded> valid = new TreeMap<>();
        plugins.values().stream().filter(l -> l.descriptor != null).forEach(l -> valid.put(l.key, l));
        Map<String, Integer> indegree = new TreeMap<>();
        Map<String, List<String>> dependents = new TreeMap<>();
        for (Loaded l : valid.values()) {
            indegree.putIfAbsent(l.key, 0);
            for (String dep : l.descriptor.allDependencies()) {
                if (valid.containsKey(dep)) {
                    indegree.merge(l.key, 1, Integer::sum);
                    dependents.computeIfAbsent(dep, k -> new ArrayList<>()).add(l.key);
                }
            }
        }
        Deque<String> ready = new ArrayDeque<>();
        indegree.forEach((k, v) -> {
            if (v == 0) {
                ready.add(k);
            }
        });
        List<Loaded> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            String k = ready.poll();
            order.add(valid.get(k));
            List<String> next = new ArrayList<>(dependents.getOrDefault(k, List.of()));
            next.sort(null);
            for (String d : next) {
                if (indegree.merge(d, -1, Integer::sum) == 0) {
                    ready.add(d);
                }
            }
        }
        for (Loaded l : valid.values()) {
            if (!order.contains(l)) {
                l.state = State.FAILED;
                l.error = "Zyklische Abhängigkeit (depend/softdepend) – Plugin nicht geladen.";
            }
        }
        return order;
    }

    private void start(Loaded l) {
        PluginDescriptor d = l.descriptor;
        try {
            checkApiVersion(d);
            l.signature = PluginSignature.check(l.jar, d, store.plugins().trustedKeys());
            l.signature.warnings().forEach(w -> LOG.warn("Plugin {} {}: {}", d.name(), d.version(), w));
            List<PluginClassLoader> depLoaders = new ArrayList<>();
            for (String dep : d.depend()) {
                Loaded other = plugins.get(dep);
                if (other == null) {
                    throw new InvalidPluginException("Benötigt das Plugin '" + dep
                            + "', das nicht installiert ist.");
                }
                if (other.state != State.ENABLED) {
                    throw new InvalidPluginException("Benötigt das Plugin '" + dep
                            + "', das nicht aktiv ist.");
                }
                depLoaders.add(other.loader);
            }
            for (String dep : d.softDepend()) {
                Loaded other = plugins.get(dep);
                if (other != null && other.state == State.ENABLED && !depLoaders.contains(other.loader)) {
                    depLoaders.add(other.loader);
                }
            }
            List<URL> urls = new ArrayList<>();
            urls.add(url(l.jar));
            for (Path lib : resolver.resolveWithDependencies(d.libraries())) {
                urls.add(url(lib));
            }
            l.loader = new PluginClassLoader(d.name(), urls.toArray(URL[]::new), parentLoader, depLoaders);
            Class<?> main;
            try {
                main = Class.forName(d.main(), true, l.loader);
            } catch (ClassNotFoundException e) {
                throw new InvalidPluginException("Hauptklasse " + d.main()
                        + " (plugin.yml: main) nicht im Plugin-Jar gefunden.", e);
            }
            if (main.getClassLoader() != l.loader) {
                throw new InvalidPluginException("Hauptklasse " + d.main()
                        + " liegt nicht im Plugin-Jar.");
            }
            if (!DevToolsPlugin.class.isAssignableFrom(main)) {
                throw new InvalidPluginException("Hauptklasse " + d.main() + " erweitert nicht "
                        + DevToolsPlugin.class.getName() + ".");
            }
            l.starting = true; // ab hier dürfen Beans (@PostConstruct) und onEnable Module registrieren
            Context context = new Context(l);
            PluginToolModule.withLoader(l.loader, () -> {
                l.spring = PluginSpringContext.create(d, l.jar, main, l.loader, context, app);
                DevToolsPlugin instance = l.spring.getBean(PluginSpringContext.PLUGIN_BEAN, DevToolsPlugin.class);
                l.instance = instance;
                instance.onLoad();
                for (ToolModule module : PluginSpringContext.moduleBeans(l.spring)) {
                    registerModule(l, module);
                }
                instance.onEnable();
                loadProviders(l);
                return null;
            });
            l.starting = false;
            l.state = State.ENABLED;
            l.error = null;
            LOG.info("Plugin {} {} aktiviert ({} Module)", d.name(), d.version(), l.modules.size());
        } catch (RuntimeException | LinkageError e) {
            LOG.error("Plugin {} konnte nicht aktiviert werden", d.name(), e);
            l.starting = false;
            shutdown(l, State.FAILED, describe(e));
        }
    }

    /**
     * Provider aus {@code META-INF/services} des Plugin-Jars (nur Klassen des Plugins selbst – die Dateien der App
     * sieht der ClassLoader über den Parent mit). Ein defekter Eintrag wird gemeldet und lässt das Plugin weiterlaufen.
     * Die Provider sind in eine {@link ContextLoaderProxy} gehüllt: Aufrufe der Module laufen mit dem ClassLoader des
     * Plugins als Thread-Context-ClassLoader, ebenso die erzeugten Systeme ({@code TicketSystem} …).
     */
    private void loadProviders(Loaded l) {
        for (Class<? extends ServiceProvider> type : PROVIDER_TYPES) {
            List<ServiceProvider> found = new ArrayList<>();
            try {
                ServiceLoader.load(type, l.loader).stream()
                        .filter(p -> p.type().getClassLoader() == l.loader)
                        .forEach(p -> found.add(wrapProvider(type, p.get(), l.loader)));
            } catch (ServiceConfigurationError e) {
                LOG.warn("Plugin {}: {} nicht ladbar: {}", l.key, type.getSimpleName(), e.getMessage());
            }
            if (!found.isEmpty()) {
                l.providers.put(type, List.copyOf(found));
                LOG.info("Plugin {} stellt {} bereit: {}", l.key, type.getSimpleName(),
                        found.stream().map(ServiceProvider::id).toList());
            }
        }
    }

    private static <T extends ServiceProvider> T wrapProvider(Class<T> type, ServiceProvider provider,
                                                              ClassLoader loader) {
        return ContextLoaderProxy.wrap(type, type.cast(provider), loader);
    }

    /** Deaktiviert (onDisable nur, wenn aktiv), entfernt Module und schließt den ClassLoader. */
    private void shutdown(Loaded l, State newState, String error) {
        if (l.state == State.ENABLED && l.instance != null) {
            try {
                PluginToolModule.withLoader(l.loader, () -> {
                    l.instance.onDisable();
                    return null;
                });
            } catch (RuntimeException | LinkageError e) {
                LOG.warn("onDisable von Plugin {} fehlgeschlagen", l.key, e);
            }
        }
        if (registry != null) {
            for (PluginToolModule m : l.modules) {
                registry.unregister(m.id());
            }
        }
        l.modules.clear();
        l.providers.clear();
        if (l.spring != null) {
            try {
                PluginToolModule.withLoader(l.loader, () -> {
                    l.spring.close(); // @PreDestroy, DisposableBean, destroyMethod der Plugin-Beans
                    return null;
                });
            } catch (RuntimeException | LinkageError e) {
                LOG.warn("Spring-Kontext von Plugin {} nicht sauber geschlossen", l.key, e);
            }
            l.spring = null;
        }
        if (l.loader != null) {
            PluginSpringContext.clearCaches(l.loader);
            try {
                l.loader.close();
            } catch (IOException e) {
                LOG.warn("ClassLoader von Plugin {} nicht geschlossen", l.key, e);
            }
        }
        l.loader = null;
        l.instance = null;
        l.state = newState;
        l.error = error;
    }

    private void registerModule(Loaded l, ToolModule module) {
        synchronized (this) {
            if (!(l.starting || l.state == State.ENABLED)) {
                throw new IllegalStateException("Plugin " + l.key + " ist nicht aktiv – Module nur in onEnable() "
                        + "oder danach registrieren.");
            }
            if (l.modules.stream().anyMatch(m -> m.delegate() == module)) {
                return; // z.B. @Component-Modul, das zusätzlich per registerModule gemeldet wird
            }
            String id = PluginToolModule.withLoader(l.loader, module::id);
            if (builtinModuleIds.get().contains(id) || plugins.values().stream()
                    .flatMap(p -> p.modules.stream()).anyMatch(m -> m.id().equals(id))) {
                throw new IllegalArgumentException("Modul-ID '" + id + "' ist bereits vergeben.");
            }
            PluginToolModule wrapped = new PluginToolModule(module, l.key, l.loader);
            if (registry != null) {
                registry.register(wrapped); // prüft das ID-Format
            } else if (!id.matches("[a-z][a-z0-9]{1,31}")) {
                throw new IllegalArgumentException("Modul-ID '" + id + "' ungültig: 2–32 Kleinbuchstaben/Ziffern, "
                        + "beginnend mit einem Buchstaben.");
            }
            l.modules.add(wrapped);
        }
    }

    private void publish() {
        Map<String, String> sources = store.plugins().sources();
        List<PluginInfo> list = plugins.values().stream().map(l -> l.info(sources.get(l.key)))
                .sorted(Comparator.comparing(PluginInfo::name)).toList();
        snapshot = list;
        activeSnapshot = plugins.values().stream().filter(l -> l.state == State.ENABLED)
                .flatMap(l -> l.modules.stream()).<ToolModule>map(m -> m).toList();
        Map<Class<?>, List<?>> providers = new LinkedHashMap<>();
        for (Class<?> type : PROVIDER_TYPES) {
            List<ServiceProvider> all = plugins.values().stream().filter(l -> l.state == State.ENABLED)
                    .flatMap(l -> l.providers.getOrDefault(type, List.of()).stream())
                    .<ServiceProvider>map(p -> p).toList();
            if (!all.isEmpty()) {
                providers.put(type, all);
            }
        }
        boolean providersChanged = !providers.equals(providerSnapshot);
        if (providersChanged) {
            providerSnapshot = Map.copyOf(providers);
            if (registry != null) {
                registry.refreshAll(); // Formulare und Tools der Module mit Providern (Tickets, Chat …) neu aufbauen
            }
        }
        listeners.forEach(r -> {
            try {
                r.run();
            } catch (RuntimeException e) {
                LOG.warn("Plugin-Listener fehlgeschlagen", e);
            }
        });
    }

    private void requireValid(String name) {
        Loaded l = plugins.get(name);
        if (l == null || l.descriptor == null) {
            throw new IllegalArgumentException("Plugin '" + name + "' ist nicht installiert.");
        }
    }

    private static void checkApiVersion(PluginDescriptor d) {
        if (d.apiVersion() > PluginApi.VERSION) {
            throw new InvalidPluginException("Plugin " + d.name() + " braucht Plugin-API "
                    + d.apiVersion() + ", diese App bietet " + PluginApi.VERSION + " – App aktualisieren.");
        }
        if (d.apiVersion() < 1) {
            throw new InvalidPluginException("'api-version' muss mindestens 1 sein.");
        }
    }

    private static String describe(Throwable e) {
        if (e instanceof LinkageError) {
            return e.getClass().getSimpleName() + ": " + ManagedToolCallback.describe(e)
                    + " (gegen eine andere App-/Bibliotheksversion gebaut?)";
        }
        // eigene Meldungen (Plugin ungültig, Bibliothek nicht auflösbar) sind aussagekräftiger als die Ursache
        if (e instanceof InvalidPluginException || e instanceof MavenPluginResolver.ResolutionException) {
            return e.getMessage();
        }
        return ManagedToolCallback.describe(e);
    }

    private static URL url(Path p) {
        try {
            return p.toUri().toURL();
        } catch (MalformedURLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void move(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ------------------------------------------------------------------ Zustand je Plugin

    private static final class Loaded {
        final String key;
        final Path jar;
        final PluginDescriptor descriptor;
        final List<PluginToolModule> modules = new ArrayList<>();
        State state = State.DISABLED;
        String error;
        PluginClassLoader loader;
        org.springframework.context.annotation.AnnotationConfigApplicationContext spring;
        DevToolsPlugin instance;
        boolean starting;
        PluginSignature signature = PluginSignature.NONE;
        final Map<Class<?>, List<? extends ServiceProvider>> providers = new LinkedHashMap<>();

        Loaded(String key, Path jar, PluginDescriptor descriptor) {
            this.key = key;
            this.jar = jar;
            this.descriptor = descriptor;
        }

        static Loaded broken(Path jar, String error) {
            Loaded l = new Loaded(BROKEN_PREFIX + jar.getFileName(), jar, null);
            l.state = State.FAILED;
            l.error = error;
            return l;
        }

        PluginInfo info(String source) {
            List<String> moduleIds = modules.stream().map(PluginToolModule::id).toList();
            if (descriptor == null) {
                return new PluginInfo(jar.getFileName().toString(), "", "", List.of(), null,
                        jar.getFileName().toString(), state, error, List.of(), source, List.of(), List.of(), List.of(),
                        false, null, List.of());
            }
            return new PluginInfo(descriptor.name(), descriptor.version(), descriptor.description(),
                    descriptor.authors(), descriptor.website(), jar.getFileName().toString(), state, error, moduleIds,
                    source, descriptor.depend(), descriptor.softDepend(), descriptor.libraries(), true, signature,
                    providers.entrySet().stream().flatMap(e -> e.getValue().stream()
                            .map(p -> e.getKey().getSimpleName() + " " + p.id())).toList());
        }
    }

    private final class Context implements PluginContext {
        private final Loaded plugin;
        private final Logger logger;

        Context(Loaded plugin) {
            this.plugin = plugin;
            this.logger = LoggerFactory.getLogger("plugin." + plugin.key);
        }

        @Override
        public int apiVersion() {
            return PluginApi.VERSION;
        }

        @Override
        public PluginDescriptor descriptor() {
            return plugin.descriptor;
        }

        @Override
        public Logger logger() {
            return logger;
        }

        @Override
        public Path dataFolder() {
            Path dir = directory.resolve(plugin.key);
            try {
                return Files.createDirectories(dir);
            } catch (IOException e) {
                throw new UncheckedIOException("Datenordner nicht anlegbar: " + dir, e);
            }
        }

        @Override
        public void registerModule(ToolModule module) {
            PluginManager.this.registerModule(plugin, module);
        }

        @Override
        public Optional<DevToolsPlugin> plugin(String name) {
            synchronized (PluginManager.this) {
                Loaded other = plugins.get(name);
                return other != null && other.state == State.ENABLED ? Optional.ofNullable(other.instance)
                        : Optional.empty();
            }
        }
    }

    /** Für Tests: aktuelle Schlüssel in Ladereihenfolge. */
    synchronized List<String> loadOrderKeys() {
        return List.copyOf(plugins.keySet());
    }
}
