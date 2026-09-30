package systems.grebe.devtools.mcp.plugin;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;

/**
 * ClassLoader eines Plugins: Plugin-Jar plus aufgelöste {@code libraries}.
 *
 * <p>Suchreihenfolge: zuerst die App (Parent – Plugin-API, Spring AI, Jackson … gibt es damit genau einmal), dann das
 * Plugin selbst, dann die Plugins aus {@code depend}/{@code softdepend} (nur deren eigene Klassen, nicht wieder deren
 * Parent). Bibliotheken, die die App schon mitbringt, kommen deshalb immer in der Version der App.
 */
final class PluginClassLoader extends URLClassLoader {

    static {
        registerAsParallelCapable();
    }

    private final List<PluginClassLoader> dependencies;

    PluginClassLoader(String pluginName, URL[] urls, ClassLoader parent, List<PluginClassLoader> dependencies) {
        super("plugin-" + pluginName, urls, parent);
        this.dependencies = List.copyOf(dependencies);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        try {
            return super.findClass(name);
        } catch (ClassNotFoundException notHere) {
            for (PluginClassLoader dep : dependencies) {
                try {
                    return dep.findOwnClass(name);
                } catch (ClassNotFoundException ignored) {
                    // nächste Abhängigkeit
                }
            }
            throw notHere;
        }
    }

    /** Nur Klassen aus diesem Plugin (ohne Parent und ohne dessen Abhängigkeiten – keine Zyklen). */
    Class<?> findOwnClass(String name) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            return c != null ? c : super.findClass(name);
        }
    }

    @Override
    public URL getResource(String name) {
        URL url = super.getResource(name);
        if (url != null) {
            return url;
        }
        for (PluginClassLoader dep : dependencies) {
            url = dep.findResource(name);
            if (url != null) {
                return url;
            }
        }
        return null;
    }
}
