package systems.grebe.devtools.mcp.modules.shares;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Freigaben eigener Skills und Memories auf dem Team-Server: für einen anderen Benutzer, für alle Benutzer einer Rolle
 * oder für alle. Geteiltes bleibt beim Eigentümer – die Empfänger sehen es live und schreibgeschützt (wie globale
 * Vorlagen; wer einen geteilten Skill ändert, bekommt eine persönliche Kopie).
 */
public final class ShareViews {

    /** Ziel {@link Target#ALL}: steht als Name in der Freigabe. */
    public static final String EVERYONE = "*";

    private ShareViews() {
    }

    /** Für wen freigegeben ist. */
    public enum Target {
        /** Ein Benutzer, Name = seine E-Mail (klein geschrieben). */
        USER,
        /** Alle Benutzer mit der Rolle, Name = Rollenname. */
        ROLE,
        /** Alle Benutzer, Name = {@value #EVERYONE}. */
        ALL;

        /** Bezeichnung für Ausgaben, z.B. „Rolle Entwickler“. */
        public String label(String name) {
            return switch (this) {
                case USER -> name;
                case ROLE -> "Rolle " + name;
                case ALL -> "alle";
            };
        }
    }

    /**
     * Eine Freigabe.
     *
     * @param name      E-Mail, Rollenname bzw. {@value #EVERYONE}
     * @param createdAt seit wann
     */
    public record Share(Target target, String name, Instant createdAt) {

        public String label() {
            return target.label(name);
        }
    }

    /**
     * Mögliches Ziel einer Freigabe für die Auswahl in der Oberfläche.
     *
     * @param name  E-Mail bzw. Rollenname
     * @param label Anzeigename des Benutzers bzw. Rollenname
     */
    public record Candidate(Target target, String name, String label) {
    }

    /**
     * Was freigegeben bzw. zurückgenommen werden soll; Benutzer per Anmeldename oder E-Mail, Rollen per Name.
     *
     * @param everyone für alle
     */
    public record Request(List<String> users, List<String> roles, boolean everyone) {

        public Request {
            users = clean(users);
            roles = clean(roles);
        }

        public boolean empty() {
            return users.isEmpty() && roles.isEmpty() && !everyone;
        }

        private static List<String> clean(List<String> names) {
            return names == null ? List.of()
                    : names.stream().filter(n -> n != null && !n.isBlank()).map(String::strip).distinct().toList();
        }
    }

    /** E-Mail als Name einer Benutzer-Freigabe. */
    public static String email(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }
}
