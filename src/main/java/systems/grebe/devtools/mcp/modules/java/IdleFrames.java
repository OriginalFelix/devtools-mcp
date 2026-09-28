package systems.grebe.devtools.mcp.modules.java;

import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Native Methoden, in denen Threads nur warten (Socket-Accept, Poll, Referenzverarbeitung …), aber von JVM
 * und JFR als RUNNABLE gemeldet werden. Für CPU-Profile werden sie ausgeblendet, sonst verfälschen sie das Bild.
 */
public final class IdleFrames {

    public static final Set<String> METHODS = Set.of(
            "sun.nio.ch.Net.accept", "sun.nio.ch.Net.poll", "sun.nio.ch.Net.connect0", "sun.nio.ch.WEPoll.wait",
            "sun.nio.ch.EPoll.wait", "sun.nio.ch.KQueue.poll", "sun.nio.ch.WindowsSelectorImpl$SubSelector.poll0",
            "sun.nio.ch.SocketDispatcher.read0", "sun.nio.ch.UnixDispatcher.read0", "sun.nio.ch.Iocp.getQueuedCompletionStatus",
            "sun.nio.ch.NioSocketImpl.accept", "java.net.SocketInputStream.socketRead0", "java.net.PlainSocketImpl.socketAccept",
            "java.lang.ref.Reference.waitForReferencePendingList", "sun.management.ThreadImpl.dumpThreads0",
            "sun.management.ThreadImpl.getThreadInfo1", "java.io.FileInputStream.readBytes",
            "java.lang.ProcessHandleImpl.waitForProcessExit0", "java.lang.ProcessImpl.waitForInterruptibly");

    /** Regulärer Ausdruck für jfr-converter ({@code -X}), Frames im Punkt-Format. */
    public static final String REGEX = METHODS.stream().map(Pattern::quote).collect(Collectors.joining("|", "^(", ")$"));

    private IdleFrames() {
    }

    public static boolean isIdle(String classDotMethod) {
        return METHODS.contains(classDotMethod);
    }
}
