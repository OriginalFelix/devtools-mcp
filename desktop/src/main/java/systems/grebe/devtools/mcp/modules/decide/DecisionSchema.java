package systems.grebe.devtools.mcp.modules.decide;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.ai.tool.annotation.ToolParam;

/** Schema einer Entscheidung: die Felder, die ein Aufruf ausfüllt, mit Typ und erlaubten Werten. */
final class DecisionSchema {

    static final int MAX_FIELDS = 32;
    static final int MAX_OPTIONS = 50;
    private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N}_.\\-]{1,64}");

    private DecisionSchema() {
    }

    /** Feldtypen. */
    enum Kind {
        /** Genau eine Option. */
        CHOICE,
        /** Stufe auf einer geordneten Skala (Optionen = Stufen von niedrig nach hoch). */
        SCORE,
        /** Ja/Nein mit Wahrscheinlichkeit für „ja“. */
        BOOLEAN,
        /** Beliebig viele der Optionen (je Option ja/nein). */
        MULTI;

        String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Kind parse(String s, boolean hasOptions) {
            if (s == null || s.isBlank()) {
                return hasOptions ? CHOICE : BOOLEAN;
            }
            return switch (s.toLowerCase(Locale.ROOT).strip()) {
                case "choice", "enum", "category", "auswahl" -> CHOICE;
                case "score", "scale", "rating", "ordinal", "skala" -> SCORE;
                case "boolean", "bool", "noul", "yesno", "flag", "ja/nein" -> BOOLEAN;
                case "multi", "multilabel", "multi-label", "labels", "tags", "set" -> MULTI;
                default -> throw new IllegalArgumentException("Unbekannter Feldtyp '" + s + "' – erlaubt: choice, score, "
                        + "boolean, multi.");
            };
        }
    }

    /** Tool-Eingabe für ein Feld. */
    record FieldSpec(
            @ToolParam(description = "Name des Feldes im Ergebnis, z.B. category, urgent, severity") String name,
            @ToolParam(required = false, description = "choice (genau eine Option), score (Stufe einer geordneten Skala, "
                    + "options von niedrig nach hoch), boolean (ja/nein mit Wahrscheinlichkeit), multi (beliebig viele "
                    + "Optionen). Leer = choice mit options, sonst boolean.") String type,
            @ToolParam(required = false, description = "Die Frage bzw. Aussage, um die es geht, z.B. 'Um welche Art von "
                    + "Anliegen geht es?' oder bei boolean 'Der Kunde wurde doppelt belastet.'") String question,
            @ToolParam(required = false, description = "Erlaubte Werte (choice, multi) bzw. Stufen von niedrig nach hoch "
                    + "(score); bei boolean leer") List<String> options,
            @ToolParam(required = false, description = "Beschreibung je Option (Schlüssel = Option), bei boolean "
                    + "optional 'true'/'false' – macht die Entscheidung deutlich genauer") Map<String, String> descriptions) {
    }

    /** Geprüftes Feld. */
    record Field(String name, Kind kind, String question, List<String> options, Map<String, String> descriptions) {

        /** Optionen der einzelnen Fragen: bei boolean [false, true]. */
        List<String> values() {
            return kind == Kind.BOOLEAN ? List.of("false", "true") : options;
        }

        String description(String option) {
            String d = descriptions == null ? null : descriptions.get(option);
            return d == null || d.isBlank() ? null : d.strip();
        }
    }

    static List<Field> validate(List<FieldSpec> specs) {
        if (specs == null || specs.isEmpty()) {
            throw new IllegalArgumentException("Keine Felder angegeben – 'fields' mit mindestens einem Feld übergeben "
                    + "oder eine gespeicherte Aufgabe ('task') verwenden.");
        }
        if (specs.size() > MAX_FIELDS) {
            throw new IllegalArgumentException("Höchstens " + MAX_FIELDS + " Felder je Entscheidung.");
        }
        List<Field> out = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (FieldSpec s : specs) {
            String name = s.name() == null ? "" : s.name().strip();
            if (!NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("Ungültiger Feldname '" + name + "' – Buchstaben, Ziffern, _ . - (max. 64).");
            }
            if (!names.add(name)) {
                throw new IllegalArgumentException("Feld '" + name + "' ist doppelt.");
            }
            List<String> options = new ArrayList<>();
            if (s.options() != null) {
                for (String o : s.options()) {
                    if (o != null && !o.isBlank() && !options.contains(o.strip())) {
                        options.add(o.strip());
                    }
                }
            }
            Kind kind = Kind.parse(s.type(), !options.isEmpty());
            Map<String, String> desc = new LinkedHashMap<>();
            if (s.descriptions() != null) {
                s.descriptions().forEach((k, v) -> {
                    if (k != null && v != null && !v.isBlank()) {
                        desc.put(k.strip(), v.strip());
                    }
                });
            }
            String question = s.question() == null ? "" : s.question().strip();
            switch (kind) {
                case BOOLEAN -> {
                    options = List.of();
                    if (question.isEmpty()) {
                        question = name;
                    }
                }
                case MULTI -> {
                    requireOptions(name, options, 1);
                    if (question.isEmpty()) {
                        question = name;
                    }
                }
                case CHOICE -> {
                    requireOptions(name, options, 2);
                    if (question.isEmpty()) {
                        question = "Welche Option passt für „" + name + "“?";
                    }
                }
                case SCORE -> {
                    requireOptions(name, options, 2);
                    if (question.isEmpty()) {
                        question = "Wie ist „" + name + "“ einzustufen?";
                    }
                }
            }
            out.add(new Field(name, kind, question, List.copyOf(options), Map.copyOf(desc)));
        }
        return out;
    }

    private static void requireOptions(String name, List<String> options, int min) {
        if (options.size() < min) {
            throw new IllegalArgumentException("Feld '" + name + "' braucht mindestens " + min + " Option(en) in 'options'.");
        }
        if (options.size() > MAX_OPTIONS) {
            throw new IllegalArgumentException("Feld '" + name + "' hat mehr als " + MAX_OPTIONS + " Optionen – in mehrere "
                    + "Felder aufteilen (z.B. erst Bereich, dann Detail).");
        }
    }

    /** Feld zurück in die Tool-Form (für die Anzeige gespeicherter Aufgaben). */
    static FieldSpec spec(Field f) {
        return new FieldSpec(f.name(), f.kind().id(), f.question(), f.options().isEmpty() ? null : f.options(),
                f.descriptions().isEmpty() ? null : f.descriptions());
    }
}
