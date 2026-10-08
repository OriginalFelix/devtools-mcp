package systems.grebe.devtools.mcp.core;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContextLoaderProxyTest {

    private final URLClassLoader loader = new URLClassLoader("fremd", new URL[0], getClass().getClassLoader());

    @AfterEach
    void close() throws IOException {
        loader.close();
    }

    public interface Greeter {
        String greet(String who);

        default String loaderName() {
            return Thread.currentThread().getContextClassLoader().getName();
        }

        Callable<String> later();
    }

    static final class Impl implements Greeter, AutoCloseable {
        boolean closed;

        @Override
        public String greet(String who) {
            if (who == null) {
                throw new IllegalArgumentException("wer?");
            }
            return "Hallo " + who + " aus " + loaderName();
        }

        @Override
        public Callable<String> later() {
            return () -> "später";
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                throw new IOException("schon zu");
            }
            closed = true;
        }

        @Override
        public String toString() {
            return "Impl";
        }
    }

    @Test
    void callsRunWithTheGivenContextClassLoader() {
        ClassLoader before = Thread.currentThread().getContextClassLoader();
        Greeter g = ContextLoaderProxy.wrap(Greeter.class, new Impl(), loader);

        assertThat(g.greet("Welt")).isEqualTo("Hallo Welt aus fremd");
        assertThat(g.loaderName()).isEqualTo("fremd"); // Default-Methode
        assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(before);
    }

    @Test
    void exceptionsArriveUnchangedAndObjectMethodsGoToTheTarget() throws Exception {
        Impl impl = new Impl();
        Greeter g = ContextLoaderProxy.wrap(Greeter.class, impl, loader);

        assertThatThrownBy(() -> g.greet(null)).isInstanceOf(IllegalArgumentException.class).hasMessage("wer?");
        assertThat(g).isInstanceOf(AutoCloseable.class).hasToString("Impl");
        ((AutoCloseable) g).close();
        assertThat(impl.closed).isTrue();
        assertThatThrownBy(((AutoCloseable) g)::close).isInstanceOf(IOException.class).hasMessage("schon zu");
        assertThat(g).isEqualTo(ContextLoaderProxy.wrap(Greeter.class, impl, loader));
        assertThat(g.hashCode()).isEqualTo(impl.hashCode());
    }

    @Test
    void wrapsOnceAndUnwraps() {
        Impl impl = new Impl();
        Greeter g = ContextLoaderProxy.wrap(Greeter.class, impl, loader);

        assertThat(ContextLoaderProxy.wrap(Greeter.class, g, loader)).isSameAs(g);
        assertThat(ContextLoaderProxy.unwrap(g)).isSameAs(impl);
        assertThat(ContextLoaderProxy.unwrap(impl)).isSameAs(impl);
        assertThat(ContextLoaderProxy.wrap(Greeter.class, null, loader)).isNull();
    }

    @Test
    void resultsNotDefinedByTheLoaderStayUnwrapped() {
        // das Lambda stammt aus der App, nicht aus „fremd“ – bleibt, wie es ist; Plugin-Objekte prüft PluginApiOnlyTest
        Callable<String> later = ContextLoaderProxy.wrap(Greeter.class, new Impl(), loader).later();

        assertThat(later.getClass().getName()).doesNotContain("$Proxy");
    }
}
