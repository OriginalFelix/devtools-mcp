package systems.grebe.devtools.mcp.modules.ci.spi;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Anbindung an ein CI/CD-System, erzeugt von {@link CiProvider#create}. Ein <em>Build</em> ist ein einzelner Lauf –
 * Jenkins-Build, GitLab-Pipeline, GitHub-Actions-Workflow-Lauf; seine <em>Jobs</em> sind GitLab-Jobs, GitHub-Jobs bzw.
 * Jenkins-Pipeline-Stages. Lesen ist Pflicht; Starten, Abbrechen und Wiederholen sind {@code default}-Methoden, die
 * {@link UnsupportedOperationException} werfen.
 *
 * <p>Build-Angaben ({@code ref}) sind je System eine Nummer/ID (dann zusammen mit {@code project}), ein voller Schlüssel
 * wie {@code owner/repo#123} oder die Web-URL des Builds. {@link Build#key()} ist immer ein voller Schlüssel, der ohne
 * {@code project} wieder verwendet werden kann. Fehlermeldungen sind für das LLM formuliert.
 */
public interface CiSystem {

    /** ID des Providers. */
    String id();

    /** Erreichbarkeit, Version und angemeldeter Benutzer. Darf nicht werfen. */
    Availability probe();

    /** Basis-URL der Instanz für Anzeige, z.B. {@code https://jenkins.firma.de}. */
    String instance();

    /**
     * Projekt zu einer Git-Remote-URL dieses Systems, sonst {@code null} – damit ergibt sich das Projekt aus dem lokalen
     * Repository. Standard: keine Zuordnung (z.B. Jenkins ohne hinterlegte Job-Zuordnung).
     */
    default String projectOfRemote(String remoteUrl) {
        return null;
    }

    /** Ob die Build-Angabe (typisch eine URL) eindeutig zu dieser Instanz gehört. */
    default boolean ownsKey(String ref) {
        return false;
    }

    /** Projekt eines Builds aus seinem Schlüssel bzw. seiner URL, sonst {@code project}; wirft bei ungültiger Angabe. */
    String projectOf(String ref, String project);

    // ------------------------------------------------------------------ Lesen

    /** Builds eines Projekts, neueste zuerst. */
    List<Build> builds(BuildQuery query);

    /** Ein Build mit seinen Jobs bzw. Stages. */
    BuildDetails get(String ref, String project);

    /**
     * Log eines Builds bzw. eines seiner Jobs.
     *
     * @param job Job-ID oder -Name innerhalb des Builds; {@code null} = der ganze Build (Jenkins) bzw. der erste
     *            fehlgeschlagene Job, sonst der einzige
     */
    Log log(String ref, String project, String job);

    /** Startbare Definitionen: Jenkins-Jobs eines Ordners, GitHub-Workflows, … */
    default List<Workflow> workflows(String project) {
        throw unsupported("Workflows auflisten");
    }

    // ------------------------------------------------------------------ Steuern

    /** Startet einen neuen Build. */
    default WriteResult start(String project, StartRequest request) {
        throw unsupported("Builds starten");
    }

    /** Bricht einen laufenden oder wartenden Build ab. */
    default WriteResult cancel(String ref, String project) {
        throw unsupported("Builds abbrechen");
    }

    /**
     * Wiederholt einen Build.
     *
     * @param failedOnly nur fehlgeschlagene/abgebrochene Jobs, wo das System das kann; sonst der ganze Build
     */
    default WriteResult retry(String ref, String project, boolean failedOnly) {
        throw unsupported("Builds wiederholen");
    }

    default UnsupportedOperationException unsupported(String what) {
        return new UnsupportedOperationException(what + " wird vom CI-System '" + id() + "' nicht unterstützt.");
    }

    // ------------------------------------------------------------------ Modell

    /** Vereinheitlichter Status über alle Systeme. */
    enum Status {
        QUEUED, RUNNING, SUCCESS, FAILED, UNSTABLE, CANCELED, SKIPPED, MANUAL, UNKNOWN;

        /** Kleingeschrieben für Ausgaben. */
        public String label() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** Ob der Build fertig ist (kein Abbrechen mehr möglich). */
        public boolean finished() {
            return this != QUEUED && this != RUNNING && this != MANUAL;
        }

        /** Filter aus Tool-Parametern; leer oder {@code all} = {@code null} (kein Filter). */
        public static Status parse(String s) {
            if (s == null || s.isBlank()) {
                return null;
            }
            return switch (s.trim().toLowerCase(Locale.ROOT)) {
                case "all", "alle" -> null;
                case "queued", "pending", "waiting", "wartend" -> QUEUED;
                case "running", "in_progress", "läuft", "laufend" -> RUNNING;
                case "success", "succeeded", "passed", "erfolgreich" -> SUCCESS;
                case "failed", "failure", "fehlgeschlagen" -> FAILED;
                case "unstable", "instabil" -> UNSTABLE;
                case "canceled", "cancelled", "aborted", "abgebrochen" -> CANCELED;
                case "skipped", "übersprungen" -> SKIPPED;
                case "manual", "manuell" -> MANUAL;
                default -> throw new IllegalArgumentException("Unbekannter Status '" + s + "' – erlaubt: queued, "
                        + "running, success, failed, unstable, canceled, skipped, manual, all.");
            };
        }
    }

    record Availability(boolean available, String version, String user, String message) {
        public static Availability ok(String version, String user) {
            return new Availability(true, version, user, "verfügbar");
        }

        public static Availability unavailable(String message) {
            return new Availability(false, null, null, message);
        }
    }

    /**
     * Ein Build bzw. Pipeline-/Workflow-Lauf.
     *
     * @param key voller Schlüssel, z.B. {@code octo/app#123456}
     * @param rawStatus Status im Wortlaut des Systems (z.B. {@code in_progress}, {@code ABORTED})
     * @param name Workflow- bzw. Jobname oder Titel, falls vorhanden
     * @param ref Branch oder Tag
     * @param trigger Auslöser: Benutzer, Ereignis (push, schedule …)
     * @param started Startzeit (ISO-8601)
     * @param durationSeconds Laufzeit oder {@code null}
     */
    record Build(String key, Status status, String rawStatus, String name, String ref, String commit, String trigger,
                 String started, Long durationSeconds, String url) { }

    /**
     * Job bzw. Stage eines Builds.
     *
     * @param id ID für {@link #log} ({@code null}, wenn das System keine Logs je Job liefert)
     * @param stage Stage, zu der der Job gehört (GitLab), sonst {@code null}
     * @param detail z.B. fehlgeschlagene Schritte, „darf fehlschlagen“
     */
    record Job(String id, String name, String stage, Status status, Long durationSeconds, String url, String detail) { }

    /**
     * Build mit Jobs und weiteren Feldern (Parameter, Ursachen, Commit-Nachricht …) in Anzeigereihenfolge.
     */
    record BuildDetails(Build build, List<Job> jobs, Map<String, String> fields) {
        public BuildDetails {
            jobs = jobs == null ? List.of() : List.copyOf(jobs);
            fields = fields == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        }
    }

    /**
     * @param source was geloggt ist, z.B. „Job test (#4711)“
     * @param url Web-Ansicht des Logs oder {@code null}
     */
    record Log(String source, String text, String url) { }

    /**
     * Startbare Definition.
     *
     * @param id was {@link StartRequest#workflow()} bzw. {@code project} erwartet
     * @param state z.B. aktiv/deaktiviert oder letzter Status
     */
    record Workflow(String id, String name, String path, String state, String url) { }

    /**
     * @param ref nur Builds dieses Branches/Tags ({@code null} = alle)
     * @param status Filter ({@code null} = alle)
     * @param workflow nur Läufe dieses Workflows, wo das System es unterscheidet ({@code null} = alle)
     */
    record BuildQuery(String project, String ref, Status status, String workflow, int limit) {
        public BuildQuery {
            limit = Math.max(1, Math.min(limit <= 0 ? 20 : limit, 100));
        }
    }

    /**
     * @param ref Branch oder Tag ({@code null} = Standard des Systems bzw. Projekts)
     * @param workflow zu startender Workflow (GitHub: ID oder Dateiname), wo das Projekt allein nicht reicht
     * @param parameters Build-Parameter bzw. Pipeline-Variablen bzw. Workflow-Inputs
     */
    record StartRequest(String ref, String workflow, Map<String, String> parameters) {
        public StartRequest {
            parameters = parameters == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(parameters));
        }
    }

    /**
     * Ergebnis einer steuernden Aktion.
     *
     * @param key Schlüssel des betroffenen bzw. neuen Builds, falls bekannt
     */
    record WriteResult(String key, String message, String url) { }
}
