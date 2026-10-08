package systems.grebe.devtools.mcp.modules.context;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.core.ContextSettings;
import systems.grebe.devtools.mcp.core.ManagedToolCallback;
import systems.grebe.devtools.mcp.core.OutputCleaner;
import systems.grebe.devtools.mcp.core.ToolCallListener;
import systems.grebe.devtools.mcp.core.ToolRegistry;

/**
 * Hält Tool-Ergebnisse klein, bevor sie an das LLM gehen – für alle Module an einer Stelle:
 * <ol>
 *   <li><b>Aufräumen:</b> Steuerzeichen und Fortschrittsanzeigen raus; bei Logs und Befehlsausgaben außerdem
 *   Framework-Frames, Wiederholungen und Leerzeilen-Serien ({@link OutputCleaner}).</li>
 *   <li><b>Schon gesehen:</b> Liefert ein lesendes Tool in derselben Session mit denselben Argumenten dasselbe wie
 *   zuletzt, geht nur ein Verweis raus.</li>
 *   <li><b>Budget:</b> Längere Ergebnisse werden auf Anfang und Ende gekürzt und vollständig im {@link ResultStore}
 *   abgelegt; {@code context_slice} liest gezielt nach.</li>
 * </ol>
 * Läuft vor allen anderen Listenern ({@link Order}), damit deren Hinweise nicht weggekürzt werden. Aufrufe aus einem
 * anderen Tool heraus (Skripte, {@code context_call}) bleiben unangetastet – das äußere Tool wird gekürzt.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ResultBudget implements ToolCallListener {

    /** Darunter lohnt weder Ablage noch Verweis. */
    static final int DEDUPE_MIN_CHARS = 600;
    private static final int MAX_SEEN = 2_000;
    /** Eigene Tools, deren Ergebnis schon begrenzt ist oder nie gekürzt werden darf. */
    static final Set<String> OWN_EXEMPT = Set.of("context_slice", "context_stats", "context_guide", "context_find");

    private record Seen(String handle, int hash, int length, Instant at) {
    }

    private final ObjectProvider<ToolRegistry> registry;
    private final ResultStore store;
    private final Map<String, Seen> seen = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Seen> eldest) {
            return size() > MAX_SEEN;
        }
    };

    public ResultBudget(ObjectProvider<ToolRegistry> registry, ResultStore store) {
        this.registry = registry;
        this.store = store;
    }

    @Override
    public String afterSuccess(ToolCall call, String result) {
        if (result == null || result.isEmpty() || OWN_EXEMPT.contains(call.toolName())
                || ManagedToolCallback.nested()) {
            return result;
        }
        ToolRegistry r = registry.getIfAvailable();
        ContextSettings ctx = r == null ? ContextSettings.OFF : r.contextSettings();
        return apply(ctx, call, result);
    }

    String apply(ContextSettings ctx, ToolCall call, String result) {
        if (!ctx.enabled()) {
            return result;
        }
        String tool = call.toolName();
        String text = ctx.compactOutput() && !ctx.verbatim(tool) ? OutputCleaner.compact(result)
                : OutputCleaner.clean(result);
        ResultStore.Stored stored = null;
        if (ctx.dedupe() && call.readOnly() && call.sessionId() != null && text.length() >= DEDUPE_MIN_CHARS) {
            String key = call.sessionId() + '\u0000' + tool + '\u0000' + (call.input() == null ? "" : call.input());
            Instant now = Instant.now();
            Seen before;
            synchronized (seen) {
                before = seen.get(key);
            }
            if (before != null && before.hash() == text.hashCode() && before.length() == text.length()
                    && Duration.between(before.at(), now).toMinutes() < ctx.dedupeMinutes()
                    && store.get(before.handle()).map(s -> s.text().equals(text)).orElse(false)) {
                return unchanged(before, now);
            }
            stored = store.put(tool, call.input(), call.sessionId(), text);
            synchronized (seen) {
                seen.put(key, new Seen(stored.handle(), text.hashCode(), text.length(), now));
            }
        }
        if (ctx.maxChars() <= 0 || text.length() <= ctx.maxChars() || ctx.exempt(tool)) {
            return text;
        }
        if (stored == null) {
            stored = store.put(tool, call.input(), call.sessionId(), text);
        }
        return ContextText.truncate(text, ctx.maxChars(), stored.handle());
    }

    private static String unchanged(Seen before, Instant now) {
        long s = Duration.between(before.at(), now).toSeconds();
        String ago = s < 90 ? s + " s" : (s / 60) + " min";
        return "[DevTools] Ergebnis unverändert seit dem gleichen Aufruf vor " + ago + " (" + before.length()
                + " Zeichen, Handle " + before.handle() + ") – es steht schon weiter oben. Falls nicht mehr im "
                + "Kontext: context_slice(handle=\"" + before.handle() + "\").";
    }
}
