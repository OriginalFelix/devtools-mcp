package systems.grebe.devtools.mcp.core;

import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.Test;
import systems.grebe.devtools.mcp.api.Errors;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorsTest {

    @Test
    void followsTheCauseChainToTheRoot() {
        Exception e = new ExecutionException(new IllegalStateException("Wrapper", new java.io.IOException("Platte voll")));
        assertThat(Errors.rootMessage(e)).isEqualTo("Platte voll");
        assertThat(ManagedToolCallback.describe(e)).isEqualTo("Platte voll");
    }

    @Test
    void usesTheClassNameWhenThereIsNoMessage() {
        assertThat(Errors.rootMessage(new IllegalStateException())).isEqualTo("IllegalStateException");
        assertThat(Errors.rootMessage(new RuntimeException("außen", new NullPointerException(" "))))
                .isEqualTo("NullPointerException");
    }

    @Test
    void toleratesSelfReferencingCauses() {
        Throwable t = new RuntimeException("selbst") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
        assertThat(Errors.rootMessage(t)).isEqualTo("selbst");
    }
}
