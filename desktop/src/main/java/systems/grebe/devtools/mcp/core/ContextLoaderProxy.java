package systems.grebe.devtools.mcp.core;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Hülle um ein Objekt aus fremdem Code (z.B. einen Provider aus einem Plugin): jeder Aufruf läuft mit
 * {@code loader} als Thread-Context-ClassLoader – Bibliotheken wie Jackson oder {@code ServiceLoader} finden so die
 * Klassen des Plugins.
 *
 * <p>Rückgabewerte, deren deklarierter Typ ein Interface ist und deren Klasse {@code loader} selbst definiert hat
 * (also aus dem Plugin stammt, nicht aus API, App oder JDK), werden ebenso eingehüllt – etwa das
 * {@code TicketSystem} aus {@code TicketProvider.create}. Die Hülle implementiert alle öffentlichen Interfaces des
 * Objekts, {@code instanceof AutoCloseable} bleibt also erhalten. {@code equals}, {@code hashCode} und
 * {@code toString} gehen an das Objekt; Exceptions kommen unverändert an.
 */
public final class ContextLoaderProxy implements InvocationHandler {

    private final Object target;
    private final ClassLoader loader;

    private ContextLoaderProxy(Object target, ClassLoader loader) {
        this.target = target;
        this.loader = loader;
    }

    /** Hüllt {@code target} ein; {@code null} und bereits eingehüllte Objekte bleiben, wie sie sind. */
    @SuppressWarnings("unchecked")
    public static <T> T wrap(Class<T> type, T target, ClassLoader loader) {
        if (target == null || Proxy.isProxyClass(target.getClass())
                && Proxy.getInvocationHandler(target) instanceof ContextLoaderProxy) {
            return target;
        }
        Set<Class<?>> interfaces = new LinkedHashSet<>();
        interfaces.add(type);
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            for (Class<?> i : c.getInterfaces()) {
                if (Modifier.isPublic(i.getModifiers())) {
                    interfaces.add(i);
                }
            }
        }
        ContextLoaderProxy handler = new ContextLoaderProxy(target, loader);
        try {
            return (T) Proxy.newProxyInstance(loader, interfaces.toArray(Class<?>[]::new), handler);
        } catch (IllegalArgumentException notVisible) {
            // ein Interface ist aus dem ClassLoader nicht sichtbar – dann nur der verlangte Typ
            return (T) Proxy.newProxyInstance(loader, new Class<?>[] {type}, handler);
        }
    }

    /** Das eingehüllte Objekt (für Meldungen mit dem echten Klassennamen); sonst {@code o} selbst. */
    public static Object unwrap(Object o) {
        if (o != null && Proxy.isProxyClass(o.getClass())
                && Proxy.getInvocationHandler(o) instanceof ContextLoaderProxy h) {
            return h.target;
        }
        return o;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "equals" -> target.equals(unwrap(args[0]));
                case "hashCode" -> target.hashCode();
                default -> call(method, args); // toString
            };
        }
        return call(method, args);
    }

    private Object call(Method method, Object[] args) throws Throwable {
        return ContextClassLoader.callChecked(loader, () -> {
            try {
                Object result = method.invoke(target, args);
                Class<?> declared = method.getReturnType();
                if (result != null && declared.isInterface() && result.getClass().getClassLoader() == loader) {
                    return wrapResult(declared, result);
                }
                return result;
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        });
    }

    @SuppressWarnings("unchecked")
    private <T> T wrapResult(Class<T> type, Object result) {
        return wrap(type, (T) result, loader);
    }
}
