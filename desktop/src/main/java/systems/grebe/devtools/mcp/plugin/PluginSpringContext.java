package systems.grebe.devtools.mcp.plugin;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.springframework.beans.CachedIntrospectionResults;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ClassPathBeanDefinitionScanner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.ScannedGenericBeanDefinition;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.Resource;
import org.springframework.util.ReflectionUtils;
import systems.grebe.devtools.mcp.core.ToolModule;
import systems.grebe.devtools.mcp.core.ToolRegistry;

/**
 * Eigener Spring-Kontext je Plugin. Damit funktionieren in Plugins {@code @Component}, {@code @Autowired},
 * Konstruktor-Injektion, {@code @Value}, {@code @Bean}/{@code @Configuration}, {@code @PostConstruct} und
 * {@code @PreDestroy} wie in einer Spring-Anwendung.
 *
 * <ul>
 *   <li>Die Hauptklasse ist selbst eine Bean (und Konfigurationsklasse): {@code @Bean}-Methoden, {@code @Import},
 *       {@code @ComponentScan} auf ihr werden ausgewertet.</li>
 *   <li>Ohne eigenes {@code @ComponentScan} wird das Paket der Hauptklasse samt Unterpaketen gescannt (wie bei
 *       {@code @SpringBootApplication}).</li>
 *   <li>Beans der App (z.B. {@code SettingsStore}, {@code ToolRegistry}, {@code JavaEnvironmentProvider}) sind
 *       injizierbar: Eltern ist die <em>BeanFactory</em> der App, nicht ihr Kontext – Ereignisse des Plugin-Kontexts
 *       (refresh/close) erreichen so die App nicht. Properties der App stehen über {@code @Value} bereit.</li>
 *   <li>Zusätzlich registriert: {@link PluginContext} und {@link PluginDescriptor} des Plugins.</li>
 *   <li>Beans vom Typ {@link ToolModule} im Plugin-Kontext werden automatisch als Module aufgenommen.</li>
 * </ul>
 */
final class PluginSpringContext {

    /** Bean-Name der Hauptklasse im Plugin-Kontext. */
    static final String PLUGIN_BEAN = "devToolsPlugin";

    private PluginSpringContext() {
    }

    static AnnotationConfigApplicationContext create(PluginDescriptor descriptor, Path pluginJar, Class<?> main,
                                                     ClassLoader loader, PluginContext context,
                                                     ConfigurableApplicationContext app) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory(app == null ? null : app.getBeanFactory());
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(beans);
        ctx.setId("plugin-" + descriptor.name());
        ctx.setDisplayName("Plugin " + descriptor.name() + " " + descriptor.version());
        ctx.setClassLoader(loader);
        StandardEnvironment env = new StandardEnvironment();
        if (app != null) {
            env.merge(app.getEnvironment());
        }
        ctx.setEnvironment(env);

        beans.registerSingleton("pluginContext", context);
        beans.registerSingleton("pluginDescriptor", descriptor);
        // PluginContext vor @PostConstruct der Hauptklasse setzen, damit context()/dataFolder() dort schon gehen
        beans.addBeanPostProcessor(new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                if (PLUGIN_BEAN.equals(beanName) && bean instanceof DevToolsPlugin plugin) {
                    plugin.attach(context);
                }
                return bean;
            }
        });

        ctx.registerBean(PLUGIN_BEAN, main);
        if (!AnnotatedElementUtils.hasAnnotation(main, ComponentScan.class)) {
            scanPluginJar(ctx, pluginJar, main);
        }
        try {
            ctx.refresh();
        } catch (RuntimeException | LinkageError e) {
            ctx.close();
            throw e;
        }
        return ctx;
    }

    /**
     * Sucht Komponenten im Paket der Hauptklasse (samt Unterpaketen) – aber nur im Plugin-Jar selbst, nicht in dessen
     * Bibliotheken oder der App. Gelesen wird der Jar-Inhalt direkt statt über {@code classpath*:}: Spring findet
     * Pakete sonst nur in Jars mit Verzeichniseinträgen, und die fehlen bei manchen Build-Werkzeugen. Die übliche
     * Nachbearbeitung (Bean-Namen, {@code @Scope}, {@code @Lazy}, {@code @Primary}) macht {@code scan()} wie gewohnt.
     */
    private static void scanPluginJar(AnnotationConfigApplicationContext ctx, Path pluginJar, Class<?> main) {
        String prefix = main.getPackageName().replace('.', '/') + "/";
        List<String> classes = new ArrayList<>();
        try (JarFile jar = new JarFile(pluginJar.toFile())) {
            jar.stream().map(JarEntry::getName)
                    .filter(n -> n.startsWith(prefix) && n.endsWith(".class") && !n.endsWith("module-info.class")
                            && !n.endsWith("package-info.class"))
                    .forEach(classes::add);
        } catch (IOException e) {
            throw new UncheckedIOException("Plugin-Jar nicht lesbar: " + pluginJar, e);
        }
        classes.sort(null);
        String mainName = main.getName();
        new JarScanner(ctx, classes, mainName).scan(main.getPackageName());
    }

    /** Scanner, der als Kandidaten genau die Klassen des Plugin-Jars prüft. */
    private static final class JarScanner extends ClassPathBeanDefinitionScanner {
        private final AnnotationConfigApplicationContext ctx;
        private final List<String> classes;
        private final String mainName;
        private final MetadataReaderFactory readers;

        JarScanner(AnnotationConfigApplicationContext ctx, List<String> classes, String mainName) {
            super(ctx, true, ctx.getEnvironment(), ctx);
            this.ctx = ctx;
            this.classes = classes;
            this.mainName = mainName;
            this.readers = new CachingMetadataReaderFactory(ctx);
        }

        @Override
        public Set<BeanDefinition> findCandidateComponents(String basePackage) {
            Set<BeanDefinition> out = new LinkedHashSet<>();
            for (String entry : classes) {
                try {
                    Resource resource = ctx.getResource("classpath:" + entry);
                    MetadataReader reader = readers.getMetadataReader(resource);
                    if (reader.getClassMetadata().getClassName().equals(mainName) || !isCandidateComponent(reader)) {
                        continue;
                    }
                    ScannedGenericBeanDefinition candidate = new ScannedGenericBeanDefinition(reader);
                    candidate.setSource(resource);
                    if (isCandidateComponent(candidate)) {
                        out.add(candidate);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException("Klasse " + entry + " im Plugin nicht lesbar", e);
                }
            }
            return out;
        }
    }

    /** {@link ToolModule}-Beans, die im Plugin-Kontext selbst definiert sind (nicht die Module der App). */
    static List<ToolModule> moduleBeans(AnnotationConfigApplicationContext ctx) {
        List<ToolModule> modules = new ArrayList<>(ctx.getBeansOfType(ToolModule.class).values()); // nur lokal
        modules.sort(ToolRegistry.MODULE_ORDER);
        return modules;
    }

    /** Spring-Caches leeren, die Klassen des Plugins halten könnten – sonst bleibt der ClassLoader erreichbar. */
    static void clearCaches(ClassLoader loader) {
        CachedIntrospectionResults.clearClassLoader(loader);
        ReflectionUtils.clearCache();
        AnnotationUtils.clearCache();
        ResolvableType.clearCache();
    }
}
