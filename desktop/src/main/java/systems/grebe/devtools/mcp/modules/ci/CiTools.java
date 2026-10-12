package systems.grebe.devtools.mcp.modules.ci;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.Text;
import systems.grebe.devtools.mcp.modules.ci.spi.CiSystem;
import systems.grebe.devtools.mcp.modules.ci.spi.CiSystem.Build;
import systems.grebe.devtools.mcp.modules.ci.spi.CiSystem.BuildDetails;
import systems.grebe.devtools.mcp.modules.ci.spi.CiSystem.BuildQuery;
import systems.grebe.devtools.mcp.modules.ci.spi.CiSystem.Job;
import systems.grebe.devtools.mcp.modules.ci.spi.CiSystem.Log;
import systems.grebe.devtools.mcp.modules.ci.spi.CiSystem.Workflow;

/** Lesende CI/CD-Tools. Ausgaben kompakt, eine Zeile je Build bzw. Job. */
public class CiTools {

    static final String BUILD = "Build bzw. Pipeline/Lauf: Nummer bzw. ID, voller Schlüssel (owner/repo#123, "
            + "gruppe/projekt#456, Ordner/Job#42) oder URL";
    static final String REPOSITORY = "Lokales Repository (Name aus git_list_repositories); bestimmt über sein Remote "
            + "CI-System und Projekt. Leer = Standard-Repository.";
    static final String PROJECT = "Projekt im CI-System (owner/repo, gruppe/projekt, Jenkins: Ordner/Job), falls nicht aus "
            + "'repository' bzw. der URL ableitbar";
    static final String PROVIDER = "CI-System (jenkins, gitlab, github). Leer = aus Remote bzw. URL erkannt.";

    private final CiEnvironment env;

    CiTools(CiEnvironment env) {
        this.env = env;
    }

    @Tool(name = "providers", description = "Listet die aktiven CI/CD-Systeme mit Erreichbarkeit, angemeldetem Benutzer und "
            + "Formaten sowie die lokalen Repositories mit erkanntem System, Projekt und aktuellem Branch." + ShellHints.CI)
    public String providers() {
        if (env.entries().isEmpty()) {
            return "Kein CI-System aktiviert – in der DevTools-App unter Module → CI/CD einschalten.";
        }
        StringBuilder sb = new StringBuilder();
        for (CiEnvironment.Entry e : env.entries()) {
            CiSystem.Availability a = e.system().probe();
            sb.append(e.provider().id()).append(" (").append(e.provider().displayName()).append(", ")
                    .append(e.system().instance()).append("): ");
            if (a.available()) {
                sb.append("verfügbar");
                if (a.version() != null) {
                    sb.append(", ").append(a.version());
                }
                if (a.user() != null) {
                    sb.append(", angemeldet als ").append(a.user());
                }
                if (!"verfügbar".equals(a.message())) {
                    sb.append(" – ").append(a.message());
                }
            } else {
                sb.append("nicht erreichbar – ").append(a.message());
            }
            sb.append("\n  Projekt: ").append(e.provider().projectHelp())
                    .append("\n  Build: ").append(e.provider().buildHelp()).append('\n');
        }
        if (!env.repositories().isEmpty()) {
            sb.append("\nLokale Repositories:\n");
            for (String name : env.repositories().all().keySet()) {
                sb.append("- ").append(name).append(": ");
                try {
                    CiEnvironment.Target t = env.target(null, name, null, null);
                    sb.append(t.providerId()).append(' ').append(t.project()).append(", Branch ")
                            .append(Text.orDash(t.localBranch()));
                } catch (RuntimeException ex) {
                    sb.append(ex.getMessage());
                }
                sb.append('\n');
            }
        }
        return Text.limitLines(sb.toString().strip(), env.maxLines());
    }

    @Tool(name = "list", description = "Listet Builds bzw. Pipelines/Workflow-Läufe eines Projekts, neueste zuerst: "
            + "Schlüssel, Status, Branch, Commit, Auslöser, Start und Dauer. Filter nach Status, Branch (current = "
            + "aktueller Branch des lokalen Repositories) und Workflow." + ShellHints.CI)
    public String list(
            @ToolParam(required = false, description = "queued, running, success, failed, unstable, canceled oder all "
                    + "(Standard)") String status,
            @ToolParam(required = false, description = "Branch oder Tag; 'current' = aktueller Branch des lokalen "
                    + "Repositories") String branch,
            @ToolParam(required = false, description = "Nur Läufe dieses Workflows (GitHub: ID, Dateiname oder Name)") String workflow,
            @ToolParam(required = false, description = "Anzahl (Standard 20, max. 100)") Integer limit,
            @ToolParam(required = false, description = REPOSITORY) String repository,
            @ToolParam(required = false, description = PROJECT) String project,
            @ToolParam(required = false, description = PROVIDER) String provider) {
        CiEnvironment.Target t = env.target(provider, repository, project, null);
        String ref = branch(t, branch);
        List<Build> builds = t.system().builds(new BuildQuery(t.project(), ref, CiSystem.Status.parse(status),
                blankToNull(workflow), limit == null ? 20 : limit));
        StringBuilder sb = new StringBuilder(builds.size() + " Build(s) in " + t.project() + " (" + t.providerId() + ")");
        if (ref != null) {
            sb.append(" – Branch ").append(ref);
        }
        sb.append(":\n");
        if (builds.isEmpty()) {
            sb.append("(keine gefunden)");
        }
        builds.forEach(b -> sb.append("- ").append(line(b)).append('\n'));
        return Text.limitLines(sb.toString().strip(), env.maxLines());
    }

    @Tool(name = "get", description = "Status eines Builds bzw. einer Pipeline/eines Workflow-Laufs: Status, Branch, Commit, "
            + "Auslöser, Start, Dauer, Parameter und alle Jobs/Stages mit Status (fehlgeschlagene Schritte). Ohne 'build': "
            + "der neueste Build des aktuellen Branches des lokalen Repositories." + ShellHints.CI)
    public String get(
            @ToolParam(required = false, description = BUILD + ". Leer = neuester des aktuellen Branches.") String build,
            @ToolParam(required = false, description = REPOSITORY) String repository,
            @ToolParam(required = false, description = PROJECT) String project,
            @ToolParam(required = false, description = PROVIDER) String provider) {
        CiEnvironment.Target t = env.target(provider, repository, project, build);
        BuildDetails d = t.system().get(refOrLatest(t, build), t.project());
        Build b = d.build();
        StringBuilder sb = new StringBuilder();
        sb.append(b.key()).append(": ").append(status(b)).append(b.name() == null ? "" : "  " + b.name()).append('\n');
        row(sb, "Branch", b.ref());
        row(sb, "Commit", b.commit());
        row(sb, "Auslöser", b.trigger());
        row(sb, "Start", b.started());
        row(sb, "Dauer", duration(b.durationSeconds()));
        for (Map.Entry<String, String> f : d.fields().entrySet()) {
            row(sb, f.getKey(), f.getValue());
        }
        row(sb, "URL", b.url());
        if (!d.jobs().isEmpty()) {
            long failed = d.jobs().stream().filter(j -> j.status() == CiSystem.Status.FAILED).count();
            sb.append("\n## Jobs (").append(d.jobs().size()).append(failed == 0 ? "" : ", " + failed + " fehlgeschlagen")
                    .append(")\n");
            String stage = null;
            for (Job j : d.jobs()) {
                if (j.stage() != null && !j.stage().equals(stage)) {
                    stage = j.stage();
                    sb.append("[").append(stage).append("]\n");
                }
                sb.append("- ").append(j.name()).append(": ").append(j.status().label());
                if (j.durationSeconds() != null) {
                    sb.append(", ").append(duration(j.durationSeconds()));
                }
                if (j.id() != null) {
                    sb.append("  (Job ").append(j.id()).append(')');
                }
                if (j.detail() != null && !j.detail().isBlank()) {
                    sb.append(" – ").append(j.detail());
                }
                sb.append('\n');
            }
        }
        return Text.limitLines(sb.toString().strip(), env.maxLines());
    }

    @Tool(name = "log", description = "Log eines Builds bzw. eines seiner Jobs – standardmäßig die letzten Zeilen, mit "
            + "'grep' nur passende Zeilen samt Zeilennummer. Ohne 'job' der erste fehlgeschlagene Job (Jenkins: das ganze "
            + "Konsolen-Log). Ohne 'build' der neueste Build des aktuellen Branches." + ShellHints.CI)
    public String log(
            @ToolParam(required = false, description = BUILD + ". Leer = neuester des aktuellen Branches.") String build,
            @ToolParam(required = false, description = "Job: ID oder Name aus ci_get. Leer = erster fehlgeschlagener.") String job,
            @ToolParam(required = false, description = "Nur die letzten so vielen Zeilen (Standard aus den Einstellungen, "
                    + "0 = alle)") Integer tail,
            @ToolParam(required = false, description = "Nur Zeilen, die diesen Text enthalten (ohne Groß-/Kleinschreibung), "
                    + "z.B. error, FAILED, Exception") String grep,
            @ToolParam(required = false, description = REPOSITORY) String repository,
            @ToolParam(required = false, description = PROJECT) String project,
            @ToolParam(required = false, description = PROVIDER) String provider) {
        CiEnvironment.Target t = env.target(provider, repository, project, build);
        Log log = t.system().log(refOrLatest(t, build), t.project(), blankToNull(job));
        List<String> lines = log.text() == null ? List.of() : log.text().lines().toList();
        StringBuilder sb = new StringBuilder(log.source()).append(" – ").append(lines.size()).append(" Zeile(n)");
        if (log.url() != null) {
            sb.append("\n").append(log.url());
        }
        sb.append("\n\n");
        if (grep != null && !grep.isBlank()) {
            String needle = grep.trim().toLowerCase(Locale.ROOT);
            List<String> hits = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).toLowerCase(Locale.ROOT).contains(needle)) {
                    hits.add((i + 1) + ": " + lines.get(i));
                }
            }
            sb.append(hits.size()).append(" Treffer für '").append(grep.trim()).append("':\n");
            sb.append(Text.limitLines(String.join("\n", hits), env.maxLines()));
            return sb.toString().strip();
        }
        int n = tail == null ? env.logLines() : tail;
        sb.append(n <= 0 ? Text.limitLines(String.join("\n", lines), env.maxLines())
                : Text.tailLines(lines, Math.min(n, env.maxLines())));
        return sb.toString().strip();
    }

    @Tool(name = "workflows", description = "Startbare Definitionen eines Projekts für ci_start: Jenkins-Jobs eines Ordners "
            + "(ohne Projekt die Jobs der obersten Ebene) bzw. Branches eines Multibranch-Projekts, GitHub-Workflows mit "
            + "Dateiname und Zustand. GitLab hat eine Pipeline je Projekt (.gitlab-ci.yml)." + ShellHints.CI)
    public String workflows(
            @ToolParam(required = false, description = REPOSITORY) String repository,
            @ToolParam(required = false, description = PROJECT + "; Jenkins: Ordner") String project,
            @ToolParam(required = false, description = PROVIDER) String provider) {
        CiEnvironment.Target t = workflowTarget(provider, repository, project);
        List<Workflow> list = t.system().workflows(t.project());
        StringBuilder sb = new StringBuilder(list.size() + " Workflow(s)/Job(s)"
                + (t.project().isEmpty() ? "" : " in " + t.project()) + " (" + t.providerId() + "):\n");
        if (list.isEmpty()) {
            sb.append("(keine gefunden)");
        }
        for (Workflow w : list) {
            sb.append("- ").append(w.id());
            if (w.name() != null && !w.name().equals(w.id())) {
                sb.append("  ").append(w.name());
            }
            if (w.path() != null && !w.path().equals(w.id())) {
                sb.append("  ").append(w.path());
            }
            if (w.state() != null) {
                sb.append("  [").append(w.state()).append(']');
            }
            if (w.url() != null) {
                sb.append("  ").append(w.url());
            }
            sb.append('\n');
        }
        return Text.limitLines(sb.toString().strip(), env.maxLines());
    }

    /** Wie {@link CiEnvironment#target}, aber Jenkins darf ohne Projekt die oberste Ebene auflisten. */
    private CiEnvironment.Target workflowTarget(String provider, String repository, String project) {
        try {
            return env.target(provider, repository, project, null);
        } catch (IllegalArgumentException e) {
            if (blank(project) && blank(repository)) {
                CiEnvironment.Target root = env.target(provider, null, "/", null);
                return new CiEnvironment.Target(root.entry(), "", null);
            }
            throw e;
        }
    }

    // ------------------------------------------------------------------ Hilfen

    /** Branch-Filter: {@code current} = aktueller Branch des lokalen Repositories. */
    static String branch(CiEnvironment.Target t, String branch) {
        String b = blankToNull(branch);
        if ("current".equalsIgnoreCase(b)) {
            b = t.localBranch();
            if (b == null) {
                throw new IllegalArgumentException("branch=current braucht ein lokales Repository mit ausgechecktem Branch.");
            }
        }
        return b;
    }

    /** Build-Angabe oder – ohne Angabe – der neueste Build des aktuellen Branches des lokalen Repositories. */
    static String refOrLatest(CiEnvironment.Target t, String build) {
        if (build != null && !build.isBlank()) {
            return build.trim();
        }
        String branch = t.localBranch();
        if (branch == null) {
            throw new IllegalArgumentException("Kein Build angegeben ('build') und kein lokaler Branch, aus dem er sich "
                    + "ergibt – Schlüssel aus ci_list verwenden.");
        }
        List<Build> latest = t.system().builds(new BuildQuery(t.project(), branch, null, null, 1));
        if (latest.isEmpty()) {
            throw new IllegalArgumentException("Kein Build für Branch '" + branch + "' in " + t.project()
                    + " – 'build' angeben oder mit ci_list suchen.");
        }
        return latest.getFirst().key();
    }

    /** {@code KEY  [status]  branch  commit  auslöser  start  dauer  name}. */
    static String line(Build b) {
        StringBuilder sb = new StringBuilder(b.key()).append("  [").append(status(b)).append("]  ")
                .append(Text.orDash(b.ref()));
        if (b.commit() != null) {
            sb.append("  ").append(b.commit(), 0, Math.min(8, b.commit().length()));
        }
        if (b.trigger() != null) {
            sb.append("  ").append(b.trigger());
        }
        if (b.started() != null) {
            sb.append("  ").append(b.started());
        }
        if (b.durationSeconds() != null) {
            sb.append("  ").append(duration(b.durationSeconds()));
        }
        if (b.name() != null) {
            sb.append("  ").append(b.name());
        }
        return sb.toString();
    }

    /** Vereinheitlichter Status, bei Abweichung mit dem Wortlaut des Systems. */
    static String status(Build b) {
        String label = b.status().label();
        return b.rawStatus() == null || b.rawStatus().equalsIgnoreCase(label) ? label : label + " (" + b.rawStatus() + ")";
    }

    /** {@code 45s}, {@code 3m 07s}, {@code 1h 02m}. */
    static String duration(Long seconds) {
        if (seconds == null || seconds < 0) {
            return null;
        }
        long s = seconds;
        if (s < 60) {
            return s + "s";
        }
        if (s < 3600) {
            return String.format("%dm %02ds", s / 60, s % 60);
        }
        return String.format("%dh %02dm", s / 3600, s % 3600 / 60);
    }

    /** Ergebniszeile einer steuernden Aktion. */
    static String written(CiSystem.WriteResult r) {
        return (r.key() == null ? "" : r.key() + ": ") + r.message() + (r.url() == null ? "" : "\n" + r.url());
    }

    private static void row(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            sb.append(String.format("%-11s %s%n", label + ":", value));
        }
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
