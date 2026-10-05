package systems.grebe.devtools.mcp.modules.scripts;

import java.time.Instant;
import java.util.List;

/** Lesemodell der Groovy-Skripte für Tools und Oberfläche – unabhängig von JPA-Entities und Lazy Loading. */
public final class ScriptViews {

    private ScriptViews() {
    }

    /** Herkunft eines sichtbaren Skripts aus Sicht des aktuellen Benutzers. */
    public enum Scope {
        /** Eigenes Skript (verdeckt eine globale Vorlage gleichen Namens). */
        OWN,
        /** Globale Vorlage für alle Benutzer – nur Administratoren veröffentlichen und ziehen zurück. */
        GLOBAL
    }

    /** Sprache des Quelltexts. */
    public enum Language {
        /** Groovy-DSL ({@code module { … }}, {@code tool('…') { … }}). */
        GROOVY("Groovy"),
        /** Java-Quelldatei mit einer {@code public class}, die {@code ToolModule} implementiert (braucht ein JDK). */
        JAVA("Java"),
        /** Gherkin ({@code Funktionalität:}/{@code Szenario:}): jedes Szenario ein Tool aus vorhandenen Tools. */
        GHERKIN("Gherkin");

        private final String label;

        Language(String label) {
            this.label = label;
        }

        /** Anzeigename in den Oberflächen. */
        public String label() {
            return label;
        }
    }

    /** Zeile der Übersicht; {@code revision} steigt mit jeder Änderung (Grundlage für das Neuladen in der App). */
    public record Summary(String name, String description, Scope scope, Language language, int revision,
                          Instant updatedAt, String updatedBy) {

        public Summary {
            language = language == null ? Language.GROOVY : language;
        }

        public boolean global() {
            return scope == Scope.GLOBAL;
        }
    }

    /** Eintrag der Änderungshistorie mit dem damaligen Quelltext. */
    public record Revision(int revision, String action, String note, String changedBy, Instant changedAt,
                           String content) {
    }

    /** Vollständiges Skript samt Historie (neueste Revision zuerst). */
    public record Details(Summary summary, String content, Instant createdAt, List<Revision> revisions) {
    }
}
