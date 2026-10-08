package systems.grebe.devtools.mcp.core;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContextClassLoaderTest {

    private static ClassLoader other() {
        return new URLClassLoader(new URL[0], null);
    }

    @Test
    void usesTheGivenLoaderInsideAndRestoresTheOldOne() {
        ClassLoader before = Thread.currentThread().getContextClassLoader();
        ClassLoader loader = other();

        ClassLoader inside = ContextClassLoader.call(loader, () -> Thread.currentThread().getContextClassLoader());

        assertThat(inside).isSameAs(loader);
        assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(before);
    }

    @Test
    void restoresTheOldLoaderAfterAnException() {
        ClassLoader before = Thread.currentThread().getContextClassLoader();

        assertThatThrownBy(() -> ContextClassLoader.run(other(), () -> {
            throw new IllegalStateException("kaputt");
        })).hasMessage("kaputt");
        assertThatThrownBy(() -> ContextClassLoader.callChecked(other(), () -> {
            throw new IOException("io");
        })).isInstanceOf(IOException.class);

        assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(before);
    }
}
