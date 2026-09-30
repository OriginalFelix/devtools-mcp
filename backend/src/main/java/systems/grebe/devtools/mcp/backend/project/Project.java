package systems.grebe.devtools.mcp.backend.project;

import java.time.Instant;

/**
 * Ein Projekt mit Eigentümer. Der Server kennt nur die Metadaten – das Verzeichnis ordnet jede Desktop-App selbst zu.
 *
 * @param sonarKey      Projektschlüssel in SonarQube, optional
 * @param ticketProject Projekt im Ticket-System (z.B. {@code ABC}, {@code owner/repo}), optional
 */
public record Project(long id, long ownerId, String ownerName, String name, String description,
                      String sonarKey, String ticketProject, Instant createdAt) {

    /** Zugriff eines Benutzers auf ein Projekt. */
    public enum Access {
        /** Eigentümer: alles, inkl. Freigeben und Löschen. */
        OWNER,
        /** Freigegeben mit Schreibrecht (Commit, Build, Graph-Aufbau). */
        WRITE,
        /** Freigegeben nur zum Lesen. */
        READ;

        public boolean canWrite() {
            return this != READ;
        }

        public String label() {
            return switch (this) {
                case OWNER -> "Eigentümer";
                case WRITE -> "lesen + schreiben";
                case READ -> "nur lesen";
            };
        }
    }

    /** Ein Projekt aus Sicht eines Benutzers. */
    public record Visible(Project project, Access access) {

        /**
         * Name, unter dem Tools das Projekt kennen: eigene unter ihrem Namen, fremde als {@code name@eigentümer}
         * (Namen sind nur je Eigentümer eindeutig).
         */
        public String toolName() {
            return access == Access.OWNER ? project.name() : project.name() + "@" + project.ownerName();
        }
    }

    /** Freigabe an einen Benutzer. */
    public record Share(long projectId, long userId, String userName, Access access) {
    }
}
