package systems.grebe.devtools.mcp.api;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Wirksame Rechte eines Benutzers – die Vereinigung der Rechte seiner Rollen. Ein Recht ist
 *
 * <ul>
 *   <li>{@value #ALL}: alles (Rolle Administrator),</li>
 *   <li>ein Systemrecht ({@link Permission#key()}, z.B. {@code users.manage}),</li>
 *   <li>{@value #ALL_MODULES}: alle Module mit allen Tools, auch später hinzukommende (Plugins, Skripte),</li>
 *   <li>{@code module:<id>}: ein Modul mit allen seinen Tools,</li>
 *   <li>{@code tool:<name>}: ein einzelnes Tool (sein Modul ist damit nutzbar, die anderen Tools nicht).</li>
 * </ul>
 *
 * <p>Module ohne Tools (Grundeinstellungen) brauchen kein Recht. Durchgesetzt werden Modul- und Tool-Rechte in der
 * Desktop-App (die Tools laufen dort), Systemrechte im Backend.
 */
public record Grants(Set<String> permissions) {

    public static final String ALL = "*";
    public static final String MODULE_PREFIX = "module:";
    public static final String TOOL_PREFIX = "tool:";
    public static final String ALL_MODULES = MODULE_PREFIX + "*";

    private static final Pattern MODULE = Pattern.compile("module:(\\*|[a-z][a-z0-9]{1,31})");
    private static final Pattern TOOL = Pattern.compile("tool:[A-Za-z0-9_.-]{1,128}");

    public static final Grants NONE = new Grants(Set.of());

    public Grants {
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }

    public static Grants of(Collection<String> permissions) {
        return new Grants(permissions == null ? Set.of() : Set.copyOf(permissions));
    }

    /** Ob ein gespeichertes Recht gültig geschrieben ist. */
    public static boolean valid(String permission) {
        return permission != null && (ALL.equals(permission) || Permission.byKey(permission).isPresent()
                || MODULE.matcher(permission).matches() || TOOL.matcher(permission).matches());
    }

    public static String module(String moduleId) {
        return MODULE_PREFIX + moduleId;
    }

    public static String tool(String toolName) {
        return TOOL_PREFIX + toolName;
    }

    /** Alle Rechte (Administrator). */
    public boolean all() {
        return permissions.contains(ALL);
    }

    public boolean has(Permission p) {
        return all() || permissions.contains(p.key());
    }

    public boolean allModules() {
        return all() || permissions.contains(ALL_MODULES);
    }

    /** Das ganze Modul mit allen Tools. */
    public boolean moduleFull(String moduleId) {
        return allModules() || permissions.contains(module(moduleId));
    }

    /** Ein Tool des Moduls. */
    public boolean tool(String moduleId, String toolName) {
        return moduleFull(moduleId) || permissions.contains(tool(toolName));
    }

    /** Ob das Modul nutzbar ist: ganz freigegeben, mindestens eines seiner Tools – oder es hat keine Tools. */
    public boolean moduleUsable(String moduleId, Collection<String> toolNames) {
        return toolNames.isEmpty() || moduleFull(moduleId)
                || toolNames.stream().anyMatch(t -> permissions.contains(tool(t)));
    }

    /** Sortiert, für Anzeige und GraphQL. */
    public List<String> list() {
        return List.copyOf(new TreeSet<>(permissions));
    }
}
