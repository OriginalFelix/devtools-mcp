package systems.grebe.devtools.mcp.core;

import org.springframework.ai.tool.ToolCallback;

/**
 * Callback, der einen anderen einhüllt (Zeitlimit, Protokoll, ClassLoader …). {@link ToolBeans#hints} schaut durch
 * solche Hüllen hindurch – wer einen Callback mit {@link ToolHints} einhüllt, sollte dieses Interface implementieren,
 * damit die Hinweise erhalten bleiben.
 */
public interface DelegatingToolCallback extends ToolCallback {

    /** Der eingehüllte Callback. */
    ToolCallback delegate();
}
