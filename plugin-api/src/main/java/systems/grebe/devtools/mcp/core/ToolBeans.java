package systems.grebe.devtools.mcp.core;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolCallResultConverter;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.tool.method.MethodToolCallback;
import org.springframework.ai.tool.support.ToolDefinitions;
import org.springframework.ai.tool.support.ToolUtils;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

/**
 * Erzeugt Tool-Callbacks aus Objekten mit {@code @Tool}-Methoden wie {@code ToolCallbacks.from} von Spring AI, übernimmt
 * aber zusätzlich die {@link ToolHints} – die App meldet sie dem Client als MCP-Tool-Annotations. Die Tool-Definitionen
 * (JSON-Schemas der Parameter, per Reflection erzeugt und teuer) werden je Klasse nur einmal gebaut; jeder Aufruf
 * erzeugt nur noch die Callbacks für das jeweilige Objekt.
 *
 * <pre>{@code
 * public List<ToolCallback> createTools(ModuleConfig config) {
 *     return ToolBeans.callbacks(new JiraTools(config));
 * }
 * }</pre>
 */
public final class ToolBeans {

    private ToolBeans() {
    }

    /**
     * Hinweise eines Tools (Werte wie bei MCP-Tool-Annotations, {@code null} = keine Angabe).
     *
     * @param title optionaler Anzeigename
     */
    public record Hints(String title, Boolean readOnly, Boolean destructive, Boolean idempotent, Boolean openWorld) {

        /** Aus der Annotation; destructive/idempotent sind laut Spezifikation nur bei nicht lesenden Tools bedeutsam. */
        public static Hints of(ToolHints h) {
            return new Hints(null, h.readOnly(), h.readOnly() ? null : h.destructive(),
                    h.readOnly() ? null : h.idempotent(), h.openWorld());
        }
    }

    /** Eine {@code @Tool}-Methode einer Klasse mit allem, was nicht vom Objekt abhängt. */
    private record Template(Method method, ToolDefinition definition, ToolMetadata metadata,
                            ToolCallResultConverter converter, Hints hints) {
    }

    private static final ClassValue<List<Template>> TEMPLATES = new ClassValue<>() {
        @Override
        protected List<Template> computeValue(Class<?> type) {
            return templates(type);
        }
    };

    /** Callbacks aller {@code @Tool}-Methoden der Objekte, mit Hinweisen aus {@link ToolHints}. */
    public static List<ToolCallback> callbacks(Object... beans) {
        List<ToolCallback> out = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (Object bean : beans) {
            for (Template t : TEMPLATES.get(bean.getClass())) {
                if (beans.length > 1 && !names.add(t.definition.name())) {
                    throw new IllegalArgumentException("Multiple tools with the same name (" + t.definition.name()
                            + ") found in sources: " + Arrays.stream(beans).map(b -> b.getClass().getName())
                            .collect(Collectors.joining(", ")));
                }
                ToolCallback cb = new MethodToolCallback(t.definition, t.metadata, t.method, bean, t.converter);
                out.add(t.hints == null ? cb : new Hinted(cb, t.hints));
            }
        }
        return out;
    }

    /** Wie Spring AIs {@code MethodToolCallbackProvider}: {@code @Tool}-Methoden, die keinen Funktionstyp liefern. */
    private static List<Template> templates(Class<?> type) {
        ToolHints classHints = type.getAnnotation(ToolHints.class);
        List<Template> out = new ArrayList<>();
        for (Method m : ReflectionUtils.getDeclaredMethods(type)) {
            if (AnnotationUtils.findAnnotation(m, Tool.class) == null || isFunctional(m)
                    || !ReflectionUtils.USER_DECLARED_METHODS.matches(m)) {
                continue;
            }
            ToolHints h = m.getAnnotation(ToolHints.class);
            if (h == null) {
                h = classHints;
            }
            out.add(new Template(m, ToolDefinitions.from(m), ToolMetadata.from(m),
                    ToolUtils.getToolCallResultConverter(m), h == null ? null : Hints.of(h)));
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("No @Tool annotated methods found in " + type.getName()
                    + ". Did you mean to pass a ToolCallback or ToolCallbackProvider?");
        }
        Set<String> names = new HashSet<>();
        for (Template t : out) {
            if (!names.add(t.definition.name())) {
                throw new IllegalArgumentException("Multiple tools with the same name (" + t.definition.name()
                        + ") found in sources: " + type.getName());
            }
        }
        return List.copyOf(out);
    }

    private static boolean isFunctional(Method m) {
        Class<?> r = m.getReturnType();
        return ClassUtils.isAssignable(Function.class, r) || ClassUtils.isAssignable(Supplier.class, r)
                || ClassUtils.isAssignable(Consumer.class, r);
    }

    /**
     * Versieht einen Callback mit Hinweisen – für Tools ohne {@code @Tool}-Methode. {@code null} lässt den Callback
     * unverändert.
     */
    public static ToolCallback withHints(ToolCallback cb, Hints hints) {
        return hints == null ? cb : new Hinted(cb, hints);
    }

    /** Hinweise eines Callbacks (auch durch {@link DelegatingToolCallback Hüllen} hindurch) oder {@code null}. */
    public static Hints hints(ToolCallback cb) {
        if (cb instanceof Hinted h) {
            return h.hints;
        }
        if (cb instanceof DelegatingToolCallback d) {
            return hints(d.delegate());
        }
        return null;
    }

    /** Callback mit Hinweisen; ansonsten unverändert. */
    private record Hinted(ToolCallback delegate, Hints hints) implements ForwardingToolCallback {
    }
}
