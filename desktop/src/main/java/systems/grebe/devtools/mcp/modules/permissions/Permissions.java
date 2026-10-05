package systems.grebe.devtools.mcp.modules.permissions;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.springframework.ai.tool.definition.ToolDefinition;
import systems.grebe.devtools.mcp.config.ModuleSettings;
import systems.grebe.devtools.mcp.core.AccessModule;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolModule;
import systems.grebe.devtools.mcp.core.ToolRegistry;

/**
 * Was das LLM darf und was ihm fehlt: Module, Schalter, in der App abgeschaltete Tools und freigegebene Verzeichnisse –
 * gelesen aus der {@link ToolRegistry}, ohne etwas zu ändern. Welche Tools ein Schalter freischaltet, ermittelt
 * {@link ToolRegistry#probeTools} (das Modul baut seine Tools probeweise mit umgelegtem Schalter).
 *
 * <p>{@link #apply} setzt eine vom Nutzer bestätigte {@link Change} um.
 */
final class Permissions {

    /** Gesperrte Schlüssel für Modul an/aus bzw. einzelne Tools (siehe {@code SettingsResolver#locked}). */
    static final String LOCK_ENABLED = "@enabled";
    static final String LOCK_TOOLS = "@tools";

    private static final int MAX_VALUE = 300;

    /** Was sich für eine Berechtigung ändern muss. */
    enum Kind { SWITCH_ON, ENABLE_TOOL, ENABLE_MODULE, ADD_DIRECTORY }

    /**
     * Eine Änderung an den Einstellungen.
     *
     * @param key    Feld (Schalter, Verzeichnisliste) bzw. Tool-Name bei {@link Kind#ENABLE_TOOL}
     * @param value  neues Verzeichnis bei {@link Kind#ADD_DIRECTORY}
     */
    record Change(Kind kind, ToolModule module, String key, String value, String text) {

        /** Schlüssel, dessen Sperre (Vorgabe des Administrators) die Änderung verhindert. */
        String lockKey() {
            return switch (kind) {
                case ENABLE_MODULE -> LOCK_ENABLED;
                case ENABLE_TOOL -> LOCK_TOOLS;
                case SWITCH_ON, ADD_DIRECTORY -> key;
            };
        }
    }

    /**
     * Stand eines Tools.
     *
     * @param module  Modul, das das Tool anbietet bzw. mit {@code changes} anbieten würde; leer, wenn keines
     * @param changes was sich ändern muss, damit es angeboten wird (leer, wenn aktiv)
     */
    record ToolStatus(String tool, Optional<ToolModule> module, boolean active, List<Change> changes) {
    }

    private final ToolRegistry registry;
    private final boolean showValues;

    Permissions(ToolRegistry registry, boolean showValues) {
        this.registry = registry;
        this.showValues = showValues;
    }

    // ------------------------------------------------------------------ Übersicht

    String overview() {
        StringBuilder sb = new StringBuilder("Änderungen an Einstellungen gehen in: ").append(registry.settingsTarget())
                .append("\n\n");
        for (ToolModule m : registry.modules()) {
            ModuleSettings s = registry.settings(m.id());
            Set<String> locked = registry.lockedKeys(m.id());
            sb.append(m.id()).append(" – ").append(m.displayName());
            if (m.hasTools()) {
                sb.append(s.enabled() ? " [an" : " [aus").append(locked.contains(LOCK_ENABLED) ? ", gesperrt]" : "]");
                sb.append(": ").append(activeTools(m).size()).append(" Tools aktiv");
                if (!s.disabledTools().isEmpty()) {
                    sb.append(", in der App abgeschaltet: ").append(String.join(", ", sorted(s.disabledTools())));
                }
            } else {
                sb.append(" [nur Einstellungen]");
            }
            registry.moduleError(m.id()).ifPresent(e -> sb.append(" – Fehler: ").append(e));
            sb.append('\n');
            ModuleConfig cfg = registry.config(m.id());
            List<String> switches = switches(m).stream()
                    .map(f -> f.label() + " = " + onOff(cfg.getBoolean(f.key())) + (locked.contains(f.key()) ? " (gesperrt)" : ""))
                    .toList();
            if (!switches.isEmpty()) {
                sb.append("  Schalter: ").append(String.join("; ", switches)).append('\n');
            }
        }
        sb.append("\nDetails eines Moduls (Werte, welcher Schalter welche Tools freischaltet): permissions_overview "
                + "mit module=<id>. Ob ein Tool oder Verzeichnis erlaubt ist: permissions_check.");
        return sb.toString();
    }

    String module(String id) {
        ToolModule m = find(id);
        ModuleSettings s = registry.settings(m.id());
        Set<String> locked = registry.lockedKeys(m.id());
        ModuleConfig cfg = registry.config(m.id());
        StringBuilder sb = new StringBuilder();
        sb.append(m.displayName()).append(" (").append(m.id()).append(")\n").append(m.description()).append("\n\n");
        if (m.hasTools()) {
            sb.append("Modul: ").append(onOff(s.enabled())).append(lockNote(locked, LOCK_ENABLED)).append('\n');
        }
        registry.moduleError(m.id()).ifPresent(e -> sb.append("Fehler beim Erzeugen der Tools: ").append(e).append('\n'));
        sb.append("Änderungen gehen in: ").append(registry.settingsTarget()).append('\n');
        if (m.hasTools()) {
            List<String> active = activeTools(m);
            sb.append("\nAktive Tools (").append(active.size()).append("): ")
                    .append(active.isEmpty() ? "keine" : String.join(", ", active)).append('\n');
            if (!s.disabledTools().isEmpty()) {
                sb.append("In der App abgeschaltet").append(lockNote(locked, LOCK_TOOLS)).append(": ")
                        .append(String.join(", ", sorted(s.disabledTools()))).append('\n');
            }
        }
        List<ConfigField> switches = switches(m);
        if (!switches.isEmpty()) {
            sb.append("\nSchalter:\n");
            for (ConfigField f : switches) {
                boolean on = cfg.getBoolean(f.key());
                sb.append("- ").append(f.label()).append(" (").append(f.key()).append("): ").append(onOff(on))
                        .append(lockNote(locked, f.key()));
                Set<String> tools = switchTools(m, f.key(), on);
                if (!tools.isEmpty()) {
                    sb.append(on ? " – bietet: " : " – würde freischalten: ").append(String.join(", ", tools));
                }
                sb.append('\n');
                if (f.help() != null && !f.help().isBlank()) {
                    sb.append("  ").append(shorten(f.help().strip())).append('\n');
                }
            }
        }
        List<ConfigField> fields = m.configSchema().stream().filter(f -> f.type() != FieldType.BOOLEAN).toList();
        if (!fields.isEmpty()) {
            if (!showValues) {
                sb.append("\nWerte der übrigen Einstellungen ausgeblendet (Modul „Berechtigungen“, Schalter "
                        + "„Einstellungswerte zeigen“).\n");
            } else {
                sb.append("\nEinstellungen (Geheimnisse nur als gesetzt/leer):\n");
                for (ConfigField f : fields) {
                    sb.append("- ").append(f.label()).append(" (").append(f.key()).append(')')
                            .append(lockNote(locked, f.key())).append(": ").append(value(f, cfg)).append('\n');
                }
            }
        }
        return sb.toString().strip();
    }

    // ------------------------------------------------------------------ Prüfen

    /** Ob das Tool angeboten wird und, falls nicht, was dafür fehlt. */
    ToolStatus tool(String name) {
        String tool = name.strip();
        for (ToolModule m : registry.modules()) {
            if (!m.hasTools() || !names(registry.availableTools(m.id())).contains(tool)) {
                continue;
            }
            if (registry.isToolActive(m.id(), tool)) {
                return new ToolStatus(tool, Optional.of(m), true, List.of());
            }
            return new ToolStatus(tool, Optional.of(m), false, enable(m, tool, new ArrayList<>()));
        }
        // nicht gebaut: ein ausgeschalteter Schalter des Moduls mit passendem Präfix?
        for (ToolModule m : registry.modules()) {
            if (!m.hasTools() || !tool.startsWith(m.id() + "_")) {
                continue;
            }
            ModuleConfig cfg = registry.config(m.id());
            for (ConfigField f : switches(m)) {
                if (!cfg.getBoolean(f.key()) && switchTools(m, f.key(), false).contains(tool)) {
                    List<Change> changes = new ArrayList<>();
                    changes.add(switchOn(m, f, switchTools(m, f.key(), false)));
                    return new ToolStatus(tool, Optional.of(m), false, enable(m, tool, changes));
                }
            }
            return new ToolStatus(tool, Optional.of(m), false, List.of());
        }
        return new ToolStatus(tool, Optional.empty(), false, List.of());
    }

    /** Modul einschalten und Tool wieder zulassen, soweit nötig – hinter die schon gesammelten Änderungen. */
    private List<Change> enable(ToolModule m, String tool, List<Change> changes) {
        ModuleSettings s = registry.settings(m.id());
        if (s.disabledTools().contains(tool)) {
            changes.add(new Change(Kind.ENABLE_TOOL, m, tool, null,
                    "Tool " + tool + " im Modul „" + m.displayName() + "“ einschalten (in der App abgeschaltet)"));
        }
        if (!s.enabled()) {
            changes.add(enableModule(m));
        }
        return changes;
    }

    String describe(ToolStatus st) {
        if (st.active()) {
            return st.tool() + ": verfügbar (Modul „" + st.module().orElseThrow().displayName() + "“).";
        }
        if (st.module().isEmpty()) {
            return st.tool() + ": kein Modul bietet dieses Tool an. Tool-Namen beginnen mit der Modul-ID "
                    + "(permissions_overview listet die Module).";
        }
        ToolModule m = st.module().get();
        if (st.changes().isEmpty()) {
            return st.tool() + ": vom Modul „" + m.displayName() + "“ mit den aktuellen Einstellungen nicht angeboten, "
                    + "kein einzelner Schalter schaltet es frei – Details mit permissions_overview module=" + m.id() + ".";
        }
        StringBuilder sb = new StringBuilder(st.tool()).append(": nicht verfügbar. Dafür nötig:\n");
        appendChanges(sb, st.changes());
        return sb.toString().strip();
    }

    void appendChanges(StringBuilder sb, List<Change> changes) {
        for (Change c : changes) {
            sb.append("- ").append(c.text());
            if (registry.lockedKeys(c.module().id()).contains(c.lockKey())) {
                sb.append(" – vom Administrator gesperrt");
            }
            sb.append('\n');
        }
    }

    /** Wo das Verzeichnis freigegeben ist. */
    String path(String raw) {
        Path p = absolute(raw);
        StringBuilder sb = new StringBuilder("Pfad ").append(p).append('\n');
        List<String> hits = coverage(p);
        boolean unrestricted = registry.config(AccessModule.ID).getBoolean(AccessModule.UNRESTRICTED);
        if (hits.isEmpty()) {
            sb.append(unrestricted ? "In keinem Modul eingetragen.\n" : "Nicht freigegeben – die Tools lehnen ihn ab.\n");
        } else {
            sb.append("Freigegeben in:\n");
            hits.forEach(h -> sb.append("- ").append(h).append('\n'));
        }
        if (unrestricted) {
            sb.append("Beschränkung aufgehoben (Modul „Freigaben“): Tools dürfen jedes Verzeichnis verwenden.\n");
        }
        if (!registry.localRuntime().scope().canWrite(p)) {
            sb.append("Schreiben: nein – das Projekt ist vom Team-Server nur lesend freigegeben.\n");
        }
        return sb.toString().strip();
    }

    /** Ob das Verzeichnis für Tools mit Projektlisten nutzbar ist (eingetragen oder Beschränkung aufgehoben). */
    boolean pathAllowed(Path p) {
        return !coverage(p).isEmpty() || registry.config(AccessModule.ID).getBoolean(AccessModule.UNRESTRICTED);
    }

    /** Module und Felder, deren Verzeichnisse den Pfad enthalten. */
    private List<String> coverage(Path p) {
        List<String> hits = new ArrayList<>();
        for (ToolModule m : registry.modules()) {
            ModuleConfig cfg = registry.config(m.id());
            for (ConfigField f : m.configSchema()) {
                if (f.type() != FieldType.DIRECTORY_LIST && f.type() != FieldType.DIRECTORY) {
                    continue;
                }
                for (String line : cfg.getList(f.key())) {
                    Optional<Path> dir = entryPath(line);
                    if (dir.isPresent() && p.startsWith(dir.get())) {
                        String state = m.hasTools() ? (registry.settings(m.id()).enabled() ? "" : ", Modul aus") : "";
                        hits.add(m.displayName() + " – " + f.label() + ": " + line.strip() + state);
                    }
                }
            }
        }
        return hits;
    }

    // ------------------------------------------------------------------ Anfragen

    /** Schalter einschalten (und das Modul, falls aus). */
    List<Change> forSwitch(String moduleId, String key) {
        ToolModule m = find(moduleId);
        ConfigField f = switches(m).stream().filter(x -> x.key().equals(key.strip())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Modul „" + m.displayName() + "“ hat keinen Schalter '"
                        + key + "'. Schalter: " + switches(m).stream().map(ConfigField::key).collect(Collectors.joining(", "))));
        List<Change> changes = new ArrayList<>();
        if (!registry.config(m.id()).getBoolean(f.key())) {
            changes.add(switchOn(m, f, switchTools(m, f.key(), false)));
        }
        if (m.hasTools() && !registry.settings(m.id()).enabled()) {
            changes.add(enableModule(m));
        }
        return changes;
    }

    /** Modul einschalten. */
    List<Change> forModule(String moduleId) {
        ToolModule m = find(moduleId);
        if (!m.hasTools()) {
            throw new IllegalArgumentException("„" + m.displayName() + "“ ist ein reines Einstellungsmodul ohne Tools – "
                    + "einen Schalter mit setting anfragen.");
        }
        return registry.settings(m.id()).enabled() ? List.of() : List.of(enableModule(m));
    }

    /** Verzeichnis unter „Freigaben“ eintragen (Datei → ihr Verzeichnis). */
    List<Change> forPath(String raw) {
        Path p = absolute(raw);
        Path dir = Files.isRegularFile(p) ? p.getParent() : p;
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("Verzeichnis " + dir + " existiert nicht.");
        }
        if (pathAllowed(dir)) {
            return List.of();
        }
        ToolModule access = find(AccessModule.ID);
        return List.of(new Change(Kind.ADD_DIRECTORY, access, AccessModule.DIRECTORIES, dir.toString(),
                "Verzeichnis " + dir + " unter „Freigaben“ für alle Tools freigeben (Git, Build, Code-Graph, Pull "
                        + "Requests, Compose)"));
    }

    /** Vom Administrator gesperrte Änderungen. */
    List<Change> locked(List<Change> changes) {
        return changes.stream().filter(c -> registry.lockedKeys(c.module().id()).contains(c.lockKey())).toList();
    }

    /** Setzt eine Änderung um; die Registry baut die Tools neu, Clients bekommen {@code tools/list_changed}. */
    void apply(Change c) {
        String id = c.module().id();
        switch (c.kind()) {
            case SWITCH_ON -> registry.updateValues(id, v -> {
                v.put(c.key(), "true");
                return v;
            });
            case ENABLE_TOOL -> registry.setToolEnabled(id, c.key(), true);
            case ENABLE_MODULE -> registry.setModuleEnabled(id, true);
            case ADD_DIRECTORY -> registry.updateValues(id, v -> {
                List<String> lines = new ArrayList<>(ModuleConfig.splitLines(v.getOrDefault(c.key(), "")));
                lines.add(c.value());
                v.put(c.key(), String.join("\n", lines));
                return v;
            });
        }
    }

    // ------------------------------------------------------------------ intern

    private Change switchOn(ToolModule m, ConfigField f, Set<String> tools) {
        return new Change(Kind.SWITCH_ON, m, f.key(), null, "Im Modul „" + m.displayName() + "“ den Schalter „"
                + f.label() + "“ (" + f.key() + ") einschalten"
                + (tools.isEmpty() ? "" : " – schaltet frei: " + String.join(", ", tools)));
    }

    private static Change enableModule(ToolModule m) {
        return new Change(Kind.ENABLE_MODULE, m, null, null, "Modul „" + m.displayName() + "“ (" + m.id() + ") einschalten");
    }

    /**
     * Tools, die am Schalter hängen: bei {@code on} die, die beim Ausschalten wegfielen, sonst die, die beim Einschalten
     * hinzukämen.
     */
    private Set<String> switchTools(ToolModule m, String key, boolean on) {
        Set<String> current = names(registry.availableTools(m.id()));
        Set<String> probed = registry.probeTools(m.id(), Map.of(key, String.valueOf(!on)));
        Set<String> out = new TreeSet<>(on ? current : probed);
        out.removeAll(on ? probed : current);
        return out;
    }

    ToolModule find(String id) {
        String key = id == null ? "" : id.strip().toLowerCase();
        return registry.modules().stream().filter(m -> m.id().equals(key)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unbekanntes Modul '" + id + "'. Module: "
                        + registry.modules().stream().map(ToolModule::id).collect(Collectors.joining(", "))));
    }

    private List<String> activeTools(ToolModule m) {
        return names(registry.availableTools(m.id())).stream().filter(t -> registry.isToolActive(m.id(), t)).sorted()
                .toList();
    }

    private static List<ConfigField> switches(ToolModule m) {
        return m.configSchema().stream().filter(f -> f.type() == FieldType.BOOLEAN).toList();
    }

    private static Set<String> names(List<ToolDefinition> tools) {
        return tools.stream().map(ToolDefinition::name).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static List<String> sorted(Set<String> values) {
        return values.stream().sorted().toList();
    }

    private static String onOff(boolean on) {
        return on ? "an" : "aus";
    }

    private static String lockNote(Set<String> locked, String key) {
        return locked.contains(key) ? " (vom Administrator gesperrt)" : "";
    }

    private static String value(ConfigField f, ModuleConfig cfg) {
        Optional<String> v = cfg.get(f.key());
        return switch (f.type()) {
            case SECRET -> v.isPresent() ? "(gesetzt)" : "(leer)";
            case RECORD_LIST -> records(f, cfg);
            case DIRECTORY_LIST, STRING_LIST -> {
                List<String> lines = cfg.getList(f.key());
                yield lines.isEmpty() ? "(leer)" : lines.stream().map(Permissions::shorten)
                        .collect(Collectors.joining("\n    ", "\n    ", ""));
            }
            default -> v.map(Permissions::shorten).orElse("(leer)");
        };
    }

    private static String records(ConfigField f, ModuleConfig cfg) {
        List<Map<String, String>> records = cfg.getRecords(f.key());
        if (records.isEmpty()) {
            return "(leer)";
        }
        StringBuilder sb = new StringBuilder();
        for (Map<String, String> r : records) {
            List<String> parts = new ArrayList<>();
            for (ConfigField c : f.columns()) {
                String value = r.get(c.key());
                if (value == null || value.isBlank()) {
                    continue;
                }
                parts.add(c.key() + "=" + (c.secret() ? "(gesetzt)" : shorten(value)));
            }
            sb.append("\n    ").append(String.join(", ", parts));
        }
        return sb.toString();
    }

    private static String shorten(String s) {
        String one = s.replace('\n', ' ');
        return one.length() > MAX_VALUE ? one.substring(0, MAX_VALUE) + " …" : one;
    }

    /** Pfad einer Listenzeile ({@code pfad} oder {@code name=pfad}). */
    private static Optional<Path> entryPath(String line) {
        String raw = line.strip();
        int eq = raw.indexOf('=');
        if (eq > 0 && !looksLikePath(raw.substring(0, eq))) {
            raw = raw.substring(eq + 1).strip();
        }
        try {
            return raw.isEmpty() ? Optional.empty() : Optional.of(Path.of(raw).toAbsolutePath().normalize());
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
    }

    private static boolean looksLikePath(String s) {
        return s.contains("/") || s.contains("\\") || s.contains(":");
    }

    private static Path absolute(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Kein Pfad angegeben.");
        }
        try {
            Path p = Path.of(raw.strip());
            if (!p.isAbsolute()) {
                throw new IllegalArgumentException("Pfad '" + raw + "' ist nicht absolut.");
            }
            return p.normalize();
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("Ungültiger Pfad '" + raw + "': " + e.getMessage());
        }
    }
}
