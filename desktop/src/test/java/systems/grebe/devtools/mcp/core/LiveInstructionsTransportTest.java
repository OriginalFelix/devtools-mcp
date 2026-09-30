package systems.grebe.devtools.mcp.core;

import java.time.Duration;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LiveInstructionsTransportTest {

    private static final String TOOLS = McpSchema.METHOD_NOTIFICATION_TOOLS_LIST_CHANGED;
    private static final String PROMPTS = McpSchema.METHOD_NOTIFICATION_PROMPTS_LIST_CHANGED;

    private final McpStreamableServerTransportProvider delegate = mock(McpStreamableServerTransportProvider.class);
    private final LiveInstructionsTransport transport =
            new LiveInstructionsTransport(delegate, () -> "text", Duration.ofMillis(50));

    LiveInstructionsTransportTest() {
        when(delegate.notifyClients(anyString(), any())).thenReturn(Mono.empty());
    }

    @Test
    void coalescesABurstOfListChangedIntoOneNotification() {
        for (int i = 0; i < 150; i++) {
            transport.notifyClients(TOOLS, null).block();
        }
        transport.notifyClients(PROMPTS, null).block();

        verify(delegate, timeout(1000).times(1)).notifyClients(eq(TOOLS), any());
        verify(delegate, timeout(1000).times(1)).notifyClients(eq(PROMPTS), any());
        verify(delegate, after(200).times(1)).notifyClients(eq(TOOLS), any());
    }

    @Test
    void sendsAgainForChangesAfterTheQuietPeriod() {
        transport.notifyClients(TOOLS, null).block();
        verify(delegate, timeout(1000).times(1)).notifyClients(eq(TOOLS), any());

        transport.notifyClients(TOOLS, null).block();
        verify(delegate, timeout(1000).times(2)).notifyClients(eq(TOOLS), any());
    }

    @Test
    void passesOtherNotificationsThroughImmediately() {
        transport.notifyClients(McpSchema.METHOD_NOTIFICATION_MESSAGE, "hallo").block();

        verify(delegate, times(1)).notifyClients(McpSchema.METHOD_NOTIFICATION_MESSAGE, "hallo");
    }

    @Test
    void dropsPendingNotificationsOnClose() {
        transport.notifyClients(TOOLS, null).block();
        transport.close();

        verify(delegate, after(200).never()).notifyClients(eq(TOOLS), any());
    }
}
