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
            + "`git` in der Shell nur für Befehle ohne passendes Tool (push, pull, fetch, merge, rebase, stash …).";

    public static final String BUILD = " Für freigegebene Projekte gilt: Builds und Tests immer über die build_*-Tools, "
            + "nicht mit `./gradlew`, `gradle` oder `mvn` in der Shell.";

    public static final String CONTAINER = " Für freigegebene Container gilt: immer über die container_*-Tools, nicht mit "
            + "`podman`/`docker` in der Shell. Fehlt ein Tool, ist es in der DevTools-App abgeschaltet – nachfragen.";

    public static final String SONAR = " SonarQube immer über die sonar_*-Tools abfragen, nicht per `curl` gegen die "
            + "Web-API oder im Browser.";

    public static final String JVM = " Laufende JVMs immer über die jvm_*-Tools untersuchen, nicht mit `jps`, `jcmd`, "
            + "`jstack`, `jmap` oder `jinfo` in der Shell.";

    public static final String JFR = " Flight-Recorder-Aufzeichnungen immer über die jfr_*-Tools, nicht mit "
            + "`jcmd JFR.*` oder `jfr print` in der Shell.";

    public static final String ASPROF = " async-profiler immer über die asprof_*-Tools steuern, nicht mit `asprof` "
            + "in der Shell.";

    public static final String VISUALVM = " Heap-Dumps und CPU-Sampling über die visualvm_*-Tools, statt Dateien selbst "
            + "zu parsen oder Analysewerkzeuge in der Shell zu starten.";

    public static final String DEBUG = " Breakpoint-Debugging immer über die debug_*-Tools, nicht mit `jdb` in der Shell.";

    public static final String SKILLS = " Skills (wiederverwendbare Abläufe) immer über die skills_*-Tools lesen und "
            + "pflegen, nicht als SKILL.md-Dateien in der Shell oder im Dateisystem.";

    public static final String GRAPH = " Für freigegebene Java-Projekte gilt: Struktur, Aufrufer und Abhängigkeiten über "
            + "die graph_*-Tools ermitteln, nicht mit `grep`/`find` in der Shell.";

    private ShellHints() {
    }
}
