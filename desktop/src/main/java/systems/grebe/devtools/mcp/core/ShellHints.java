package systems.grebe.devtools.mcp.core;

/**
 * Hinweise, die an jede Tool-Beschreibung eines Moduls angehängt werden: welches Werkzeug der Shell das Tool ersetzt.
 *
 * <p>Die Server-{@code instructions} (siehe {@link ServerInstructions}) übernimmt nicht jeder Client (Hermes z.B.
 * nicht), die Tool-Beschreibungen sieht dagegen jeder. Deshalb steht die Grundregel „Tool statt Shell“ zusätzlich hier
 * und wird in jeder Beschreibung wiederholt. Die Texte müssen Konstanten sein, weil sie in
 * {@code @Tool(description = …)} verwendet werden. Jeder Hinweis beginnt mit einem Leerzeichen.
 */
public final class ShellHints {

    public static final String GIT = " Für freigegebene Repositories gilt: Git immer über die git_*-Tools, "
            + "`git` in der Shell nur für Befehle ohne passendes Tool (submodule, bisect, interaktiver Rebase …). Es gibt "
            + "auch git_fetch/git_pull/git_push, git_merge/git_rebase/git_cherry_pick/git_revert und git_restore/"
            + "git_delete_branch – fehlen sie in der Tool-Liste, ist ihr Schalter in der DevTools-App aus: mit "
            + "permissions_request (z.B. tool=git_push) beim Nutzer anfragen statt per Shell auszuweichen.";

    public static final String BUILD = " Für freigegebene Projekte gilt: Builds und Tests immer über die build_*-Tools, "
            + "nicht mit `./gradlew`, `gradle` oder `mvn` in der Shell.";

    public static final String CONTAINER = " Für freigegebene Container gilt: immer über die container_*-Tools, nicht mit "
            + "`podman`/`docker` in der Shell. Fehlt ein Tool, ist es in der DevTools-App abgeschaltet – nachfragen.";

    public static final String SONAR = " SonarQube immer über die sonar_*-Tools abfragen, nicht per `curl` gegen die "
            + "Web-API oder im Browser.";

    public static final String MAVEN = " Maven-Artefakte (Versionen, POM-Metadaten, Breaking Changes) immer über die "
            + "maven_*-Tools abfragen, nicht per `curl` gegen Maven Central, mit `mvn` in der Shell oder im Browser.";

    public static final String JVM = " Laufende JVMs immer über die jvm_*-Tools untersuchen, nicht mit `jps`, `jcmd`, "
            + "`jstack`, `jmap` oder `jinfo` in der Shell.";

    public static final String JFR = " Flight-Recorder-Aufzeichnungen immer über die jfr_*-Tools, nicht mit "
            + "`jcmd JFR.*` oder `jfr print` in der Shell.";

    public static final String ASPROF = " async-profiler immer über die asprof_*-Tools steuern, nicht mit `asprof` "
            + "in der Shell.";

    public static final String VISUALVM = " Heap-Dumps und CPU-Sampling über die visualvm_*-Tools, statt Dateien selbst "
            + "zu parsen oder Analysewerkzeuge in der Shell zu starten.";

    public static final String DECOMPILE = " Klassen ohne Quelltext (JDK, Bibliotheks-JARs, kompilierte Klassen) über die "
            + "decompile_*-Tools dekompilieren, nicht mit `javap`, `unzip` oder einem Decompiler in der Shell.";

    public static final String DEBUG = " Breakpoint-Debugging immer über die debug_*-Tools, nicht mit `jdb` in der Shell.";

    public static final String SKILLS = " Skills nur über skills_*-Tools, nicht als SKILL.md-Dateien.";

    public static final String MEMORIES = " Memories nur über memories_*-Tools, nicht als Notizdateien.";

    public static final String SCRIPTS = " Eigene Tools als Groovy-Skripte immer über die scripts_*-Tools anlegen und "
            + "pflegen, nicht als Dateien im Dateisystem und nicht per `groovy` in der Shell ausführen.";

    public static final String GRAPH = " Für freigegebene Java-Projekte gilt: Dateien, Struktur, Aufrufer und Abhängigkeiten "
            + "immer zuerst über die graph_*-Tools suchen, nicht mit `grep`/`find`/Glob in der Shell.";

    public static final String TICKET = " Tickets (Jira, GitHub, GitLab, YouTrack, OpenProject) immer über die ticket_*-Tools lesen, nicht per "
            + "`curl` gegen die REST-API, mit `gh`/`glab` in der Shell oder im Browser.";

    public static final String PR = " Pull/Merge Requests (GitHub, GitLab, Bitbucket) immer über die pr_*-Tools, nicht "
            + "mit `gh pr`/`glab mr` in der Shell, per `curl` gegen die REST-API oder im Browser.";

    public static final String SSH =" Konfigurierte SSH-Server immer über die ssh_*-Tools ansprechen, nicht mit "
            + "`ssh`, `scp` oder `sftp` in der Shell – die Zugangsdaten liegen nur in der DevTools-App.";

    public static final String JDBC = " Konfigurierte Datenbanken immer über die jdbc_*-Tools ansprechen, nicht mit "
            + "`psql`, `mysql`, `sqlplus`, `sqlcmd` o.ä. in der Shell – die Zugangsdaten liegen nur in der DevTools-App.";

    public static final String DOLT = " Branches verknüpfter Dolt-, Doltgres- und Doltlite-Datenbanken nicht mit `dolt`, "
            + "`doltlite`, `mysql` oder `psql` in der Shell umschalten – DevTools stellt sie beim Wechsel des Git-Branches "
            + "selbst um (dolt_status, dolt_sync).";

    public static final String CHAT =" Chat-Nachrichten (Matrix, Teams) immer über die chat_*-Tools senden und lesen, "
            + "nicht per `curl` gegen die APIs – die Zugangsdaten liegen nur in der DevTools-App.";

    public static final String INVOCATIONS = " Auf lang laufende Aktionen per Rückruf warten (Memory vom Typ "
            + "INVOCATION, Parameter invocation des Tools), nicht mit Schleifen, `sleep` oder Abfragen in der Shell.";

    public static final String SHARE = " Inhalte mit den Claude-Instanzen anderer Nutzer oder Geräte immer über die "
            + "share_*-Tools austauschen, nicht per Mail, Chat, Dateiablage oder Shell.";

    public static final String MAIL = " E-Mails konfigurierter Konten immer über die mail_*-Tools lesen und verwalten, "
            + "nicht per `curl`, Skript oder Mail-Programm – die Zugangsdaten liegen nur in der DevTools-App.";

    public static final String PROJECTS = " Welche Projekte (Repositories, Build-, Graph-Projekte) zur Verfügung stehen, "
            + "immer über projects_list ermitteln, nicht durch Durchsuchen des Dateisystems in der Shell.";

    public static final String WINDOW = " Fenster anderer Anwendungen immer über die window_*-Tools sehen und bedienen, "
            + "nicht per PowerShell, AppleScript, xdotool oder Skripten in der Shell.";

    public static final String CONTEXT = " Gekürzte Ergebnisse über context_slice nachlesen, statt das Tool mit höherem "
            + "Limit erneut aufzurufen oder Ausgaben per Shell in Dateien umzuleiten.";

    public static final String PERMISSIONS =" Was in DevTools erlaubt ist, immer über die permissions_*-Tools klären, "
            + "nicht durch Lesen von Konfigurationsdateien und nicht durch Ausweichen auf die Shell.";

    /**
     * Hinweis je Modul-ID. Mit {@link ContextSettings#shellHintsOnce} steht jeder Hinweis nur einmal in den
     * Instructions statt in jeder Tool-Beschreibung (der Git-Hinweis allein steckt sonst in über 30 Beschreibungen).
     */
    static final java.util.Map<String, String> BY_MODULE = java.util.Map.ofEntries(
            java.util.Map.entry("git", GIT), java.util.Map.entry("build", BUILD),
            java.util.Map.entry("container", CONTAINER), java.util.Map.entry("sonar", SONAR),
            java.util.Map.entry("maven", MAVEN), java.util.Map.entry("jvm", JVM), java.util.Map.entry("jfr", JFR),
            java.util.Map.entry("asprof", ASPROF), java.util.Map.entry("visualvm", VISUALVM),
            java.util.Map.entry("decompile", DECOMPILE), java.util.Map.entry("debug", DEBUG),
            java.util.Map.entry("skills", SKILLS), java.util.Map.entry("memories", MEMORIES),
            java.util.Map.entry("scripts", SCRIPTS), java.util.Map.entry("graph", GRAPH),
            java.util.Map.entry("ticket", TICKET), java.util.Map.entry("pr", PR), java.util.Map.entry("ssh", SSH),
            java.util.Map.entry("jdbc", JDBC), java.util.Map.entry("dolt", DOLT), java.util.Map.entry("chat", CHAT),
            java.util.Map.entry("invocations", INVOCATIONS), java.util.Map.entry("share", SHARE),
            java.util.Map.entry("mail", MAIL), java.util.Map.entry("projects", PROJECTS),
            java.util.Map.entry("permissions", PERMISSIONS), java.util.Map.entry("context", CONTEXT),
            java.util.Map.entry("window", WINDOW));

    private ShellHints() {
    }

    /** Hinweis des Moduls ohne führendes Leerzeichen, {@code null} wenn es keinen gibt. */
    public static String forModule(String moduleId) {
        String h = BY_MODULE.get(moduleId);
        return h == null ? null : h.strip();
    }

    /** Beschreibung ohne die Shell-Hinweise, die darin stehen. */
    public static String strip(String description) {
        if (description == null) {
            return null;
        }
        String out = description;
        for (String h : BY_MODULE.values()) {
            if (out.contains(h)) {
                out = out.replace(h, "");
            }
        }
        return out;
    }
}
