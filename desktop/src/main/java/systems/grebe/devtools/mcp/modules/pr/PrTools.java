package systems.grebe.devtools.mcp.modules.pr;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.Text;
import systems.grebe.devtools.mcp.modules.pr.spi.GitServer;
import systems.grebe.devtools.mcp.modules.pr.spi.GitServer.Annotation;
import systems.grebe.devtools.mcp.modules.pr.spi.GitServer.Check;
import systems.grebe.devtools.mcp.modules.pr.spi.GitServer.Comment;
import systems.grebe.devtools.mcp.modules.pr.spi.GitServer.FileChange;
import systems.grebe.devtools.mcp.modules.pr.spi.GitServer.Insight;
import systems.grebe.devtools.mcp.modules.pr.spi.GitServer.PrDetails;
import systems.grebe.devtools.mcp.modules.pr.spi.GitServer.PrQuery;
import systems.grebe.devtools.mcp.modules.pr.spi.GitServer.PullRequest;
import systems.grebe.devtools.mcp.modules.pr.spi.GitServer.Thread;

/** Lesende Pull-Request-Tools. Ausgaben kompakt, eine Zeile je Pull Request. */
public class PrTools {

    static final String PR = "Pull Request: Nummer (12, #12, !12), voller Schlüssel (owner/repo#12, gruppe/projekt!12) oder URL";
    static final String REPOSITORY = "Lokales Repository (Name aus git_list_repositories); bestimmt über sein Remote "
            + "Server und Repository. Leer = Standard-Repository.";
    static final String PROJECT = "Repository auf dem Server (owner/repo, gruppe/projekt, workspace/repo, PROJ/repo), "
            + "falls nicht aus 'repository' bzw. der URL ableitbar";
    static final String PROVIDER = "Git-Server (github, gitlab, bitbucket). Leer = aus Remote bzw. URL erkannt.";

    private final PrEnvironment env;

    PrTools(PrEnvironment env) {
        this.env = env;
    }

    @Tool(name = "providers", description = "Listet die aktiven Git-Server mit Erreichbarkeit, angemeldetem Benutzer und "
            + "Formaten sowie die lokalen Repositories mit erkanntem Server, Repository und aktuellem Branch." + ShellHints.PR)
    public String providers() {
        if (env.entries().isEmpty()) {
            return "Kein Git-Server aktiviert – in der DevTools-App unter Module → Pull Requests einschalten.";
        }
        StringBuilder sb = new StringBuilder();
        for (PrEnvironment.Entry e : env.entries()) {
            GitServer.Availability a = e.server().probe();
            sb.append(e.provider().id()).append(" (").append(e.provider().displayName()).append(", ")
                    .append(e.server().instance()).append("): ");
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
            sb.append("\n  Repository: ").append(e.provider().projectHelp())
                    .append("\n  Pull Request: ").append(e.provider().keyHelp()).append('\n');
        }
        if (!env.repositories().isEmpty()) {
            sb.append("\nLokale Repositories:\n");
            for (String name : env.repositories().all().keySet()) {
                sb.append("- ").append(name).append(": ");
                try {
                    PrEnvironment.Target t = env.target(null, name, null, null);
                    sb.append(t.providerId()).append(' ').append(t.project()).append(", Branch ")
                            .append(Text.orDash(t.local().branch()));
                } catch (RuntimeException ex) {
                    sb.append(ex.getMessage());
                }
                sb.append('\n');
            }
        }
        return Text.limitLines(sb.toString().strip(), env.maxLines());
    }

    @Tool(name = "list", description = "Listet Pull/Merge Requests eines Repositories: Schlüssel, Status, Autor, "
            + "Quell- → Ziel-Branch und Titel. Filter nach Status, Autor (me) und Branches – z.B. source=<aktueller Branch>, "
            + "um den Pull Request zum Branch zu finden." + ShellHints.PR)
    public String list(
            @ToolParam(required = false, description = "open (Standard), merged, closed oder all") String state,
            @ToolParam(required = false, description = "Autor: me oder Benutzername") String author,
            @ToolParam(required = false, description = "Quell-Branch; 'current' = aktueller Branch des lokalen Repositories") String source,
            @ToolParam(required = false, description = "Ziel-Branch") String target,
            @ToolParam(required = false, description = "Anzahl (Standard 30, max. 100)") Integer limit,
            @ToolParam(required = false, description = REPOSITORY) String repository,
            @ToolParam(required = false, description = PROJECT) String project,
            @ToolParam(required = false, description = PROVIDER) String provider) {
        PrEnvironment.Target t = env.target(provider, repository, project, null);
        String src = blankToNull(source);
        if ("current".equalsIgnoreCase(src)) {
            src = t.local() == null ? null : t.local().branch();
            if (src == null) {
                throw new IllegalArgumentException("source=current braucht ein lokales Repository mit ausgechecktem Branch.");
            }
        }
        List<PullRequest> prs = t.server().list(new PrQuery(t.project(), GitServer.State.parse(state),
                blankToNull(author), src, blankToNull(target), limit == null ? 30 : limit));
        StringBuilder sb = new StringBuilder(prs.size() + " Pull Request(s) in " + t.project() + " (" + t.providerId() + ")");
        if (src != null) {
            sb.append(" – Quell-Branch ").append(src);
        }
        sb.append(":\n");
        if (prs.isEmpty()) {
            sb.append("(keine gefunden)");
        }
        prs.forEach(p -> sb.append("- ").append(line(p)).append('\n'));
        return Text.limitLines(sb.toString().strip(), env.maxLines());
    }

    @Tool(name = "get", description = "Liest einen Pull/Merge Request: Titel, Status, Autor, Branches, Reviewer, Freigaben, "
            + "Merge-Status (Konflikte, fehlende Freigaben), CI-Checks des letzten Commits, Kurzfassung der Berichte von "
            + "Integrationen und Beschreibung. Ohne 'pr': "
            + "der offene Pull Request zum aktuellen Branch des lokalen Repositories." + ShellHints.PR)
    public String get(
            @ToolParam(required = false, description = PR + ". Leer = zum aktuellen Branch.") String pr,
            @ToolParam(required = false, description = REPOSITORY) String repository,
            @ToolParam(required = false, description = PROJECT) String project,
            @ToolParam(required = false, description = PROVIDER) String provider) {
        PrEnvironment.Target t = env.target(provider, repository, project, pr);
        String ref = refOrCurrent(t, pr);
        PrDetails d = t.server().get(ref, t.project());
        PullRequest p = d.pr();
        StringBuilder sb = new StringBuilder();
        sb.append(p.key()).append(": ").append(p.title()).append('\n');
        row(sb, "Status", p.state() + (p.draft() ? " (Entwurf)" : ""));
        row(sb, "Branches", p.source() + " → " + p.target());
        row(sb, "Autor", p.author());
        row(sb, "Reviewer", d.reviewers().isEmpty() ? null : String.join(", ", d.reviewers()));
        row(sb, "Freigaben", d.approvedBy().isEmpty() ? "keine" : String.join(", ", d.approvedBy()));
        row(sb, "Merge", d.mergeStatus());
        row(sb, "Integrationen", integrations(t.server(), ref, t.project()));
        for (Map.Entry<String, String> f : d.fields().entrySet()) {
            row(sb, f.getKey(), f.getValue());
        }
        row(sb, "Erstellt", d.created());
        row(sb, "Geändert", p.updated());
        row(sb, "Commit", d.headCommit());
        row(sb, "URL", p.url());
        if (!d.checks().isEmpty()) {
            sb.append("\n## Checks\n");
            for (Check c : d.checks()) {
                sb.append("- ").append(Text.orDash(c.name())).append(": ").append(Text.orDash(c.status()));
                if (c.url() != null) {
                    sb.append("  ").append(c.url());
                }
                sb.append('\n');
            }
        }
        sb.append("\n## Beschreibung\n").append(d.description() == null || d.description().isBlank() ? "(keine)"
                : d.description().strip());
        return Text.limitLines(sb.toString().strip(), env.maxLines());
    }

    @Tool(name = "diff", description = "Geänderte Dateien eines Pull/Merge Requests mit Zeilenstatistik und Unified Diff, "
            + "optional nur für eine Datei oder nur die Dateiliste." + ShellHints.PR)
    public String diff(
            @ToolParam(required = false, description = PR + ". Leer = zum aktuellen Branch.") String pr,
            @ToolParam(required = false, description = "Nur Dateien, deren Pfad dies enthält") String path,
            @ToolParam(required = false, description = "true = nur Dateiliste ohne Diff") Boolean filesOnly,
            @ToolParam(required = false, description = REPOSITORY) String repository,
            @ToolParam(required = false, description = PROJECT) String project,
            @ToolParam(required = false, description = PROVIDER) String provider) {
        PrEnvironment.Target t = env.target(provider, repository, project, pr);
        String ref = refOrCurrent(t, pr);
        List<FileChange> files = t.server().diff(ref, t.project());
        if (path != null && !path.isBlank()) {
            String p = path.trim().replace('\\', '/');
            files = files.stream().filter(f -> f.path() != null && f.path().contains(p)
                    || f.oldPath() != null && f.oldPath().contains(p)).toList();
        }
        StringBuilder sb = new StringBuilder(files.size() + " Datei(en) geändert:\n");
        for (FileChange f : files) {
            sb.append("  ").append(Text.orDash(f.status())).append(' ')
                    .append(f.oldPath() == null ? f.path() : f.oldPath() + " -> " + f.path());
            if (f.additions() >= 0) {
                sb.append("  (+").append(f.additions()).append(" −").append(f.deletions()).append(')');
            }
            sb.append('\n');
        }
        if (!Boolean.TRUE.equals(filesOnly)) {
            for (FileChange f : files) {
                sb.append("\n--- ").append(f.oldPath() == null ? f.path() : f.oldPath()).append("\n+++ ").append(f.path())
                        .append('\n').append(f.patch() == null ? "(kein Diff – binär oder zu groß)" : f.patch()).append('\n');
            }
        }
        return Text.limitLines(sb.toString().strip(), env.maxLines());
    }

    @Tool(name = "comments", description = "Alle Kommentare eines Pull/Merge Requests als Threads: allgemeine Kommentare, "
            + "Code-Kommentare mit Datei und Zeile, Datei-Kommentare (ganze Datei) samt Antworten, Reviews. Je Thread ID "
            + "(für pr_reply/pr_resolve) und Status offen/erledigt; Kommentare von Integrationen (Apps, Bots) sind "
            + "gekennzeichnet. Mit unresolved=true nur offene Threads – Ausgangspunkt zum Abarbeiten von "
            + "Review-Anmerkungen." + ShellHints.PR)
    public String comments(
            @ToolParam(required = false, description = PR + ". Leer = zum aktuellen Branch.") String pr,
            @ToolParam(required = false, description = "true = nur offene (nicht erledigte) Threads") Boolean unresolved,
            @ToolParam(required = false, description = "Nur Code- und Datei-Kommentare zu Dateien, deren Pfad dies enthält") String path,
            @ToolParam(required = false, description = REPOSITORY) String repository,
            @ToolParam(required = false, description = PROJECT) String project,
            @ToolParam(required = false, description = PROVIDER) String provider) {
        PrEnvironment.Target t = env.target(provider, repository, project, pr);
        String ref = refOrCurrent(t, pr);
        List<Thread> all = t.server().threads(ref, t.project());
        List<Thread> threads = all.stream()
                .filter(th -> !Boolean.TRUE.equals(unresolved) || !Boolean.TRUE.equals(th.resolved()))
                .filter(th -> path == null || path.isBlank() || th.path() != null && th.path().contains(path.trim()))
                .toList();
        long open = all.stream().filter(th -> Boolean.FALSE.equals(th.resolved())).count();
        StringBuilder sb = new StringBuilder(ref + " (" + t.providerId() + "): " + all.size() + " Thread(s), davon "
                + open + " offen");
        if (threads.size() != all.size()) {
            sb.append(" – angezeigt: ").append(threads.size());
        }
        sb.append('\n');
        for (Thread th : threads) {
            sb.append("\n### [").append(th.id()).append("] ").append(th.kind());
            if (th.path() != null) {
                sb.append("  ").append(th.path()).append(th.line() == null ? "" : ":" + th.line());
            }
            if (th.resolved() != null) {
                sb.append("  ").append(th.resolved() ? "ERLEDIGT" : "OFFEN");
            }
            if (th.outdated()) {
                sb.append("  (veraltet – Code seither geändert)");
            }
            sb.append('\n');
            for (Comment c : th.comments()) {
                sb.append("- ").append(Text.orDash(c.author())).append(c.integration() ? " [Integration]" : "")
                        .append(", ").append(Text.orDash(c.created())).append(" (").append(c.id()).append("):\n");
                String body = c.body() == null ? "" : c.body().strip();
                body.lines().forEach(l -> sb.append("  ").append(l).append('\n'));
            }
        }
        if (threads.isEmpty()) {
            sb.append(Boolean.TRUE.equals(unresolved) ? "(keine offenen Threads)" : "(keine Kommentare)");
        }
        return Text.limitLines(sb.toString().strip(), env.maxLines());
    }

    @Tool(name = "insights", description = "Berichte von Integrationen zum letzten Commit eines Pull/Merge Requests: "
            + "Code-Analyse (z.B. SonarQube), Tests, Sicherheits-Scans – je Bericht Ergebnis, Kennzahlen und Befunde mit "
            + "Datei, Zeile, Schwere und Meldung (Bitbucket Code Insights, GitHub Check-Runs, GitLab Testberichte). "
            + "Ergänzt die CI-Checks aus pr_get um die Details." + ShellHints.PR)
    public String insights(
            @ToolParam(required = false, description = PR + ". Leer = zum aktuellen Branch.") String pr,
            @ToolParam(required = false, description = "Nur Befunde zu Dateien, deren Pfad dies enthält") String path,
            @ToolParam(required = false, description = REPOSITORY) String repository,
            @ToolParam(required = false, description = PROJECT) String project,
            @ToolParam(required = false, description = PROVIDER) String provider) {
        PrEnvironment.Target t = env.target(provider, repository, project, pr);
        String ref = refOrCurrent(t, pr);
        List<Insight> insights = t.server().insights(ref, t.project());
        String filter = path == null || path.isBlank() ? null : path.trim().replace('\\', '/');
        int findings = insights.stream().mapToInt(Insight::annotationCount).sum();
        StringBuilder sb = new StringBuilder(ref + " (" + t.providerId() + "): " + insights.size()
                + " Bericht(e) von Integrationen, " + findings + " Befund(e)\n");
        for (Insight in : insights) {
            sb.append("\n### ").append(Text.orDash(in.title()));
            if (in.result() != null) {
                sb.append("  ").append(in.result());
            }
            sb.append('\n');
            List<String> meta = new ArrayList<>();
            if (in.id() != null) {
                meta.add("Schlüssel " + in.id());
            }
            if (in.source() != null && !in.source().equals(in.title())) {
                meta.add("Quelle " + in.source());
            }
            if (in.created() != null) {
                meta.add("erstellt " + in.created());
            }
            if (!meta.isEmpty()) {
                sb.append(String.join(" · ", meta)).append('\n');
            }
            if (in.url() != null) {
                sb.append(in.url()).append('\n');
            }
            if (in.summary() != null && !in.summary().isBlank()) {
                in.summary().strip().lines().forEach(l -> sb.append(l).append('\n'));
            }
            in.data().forEach((k, v) -> sb.append("- ").append(k).append(": ").append(Text.orDash(v)).append('\n'));
            List<Annotation> shown = in.annotations().stream()
                    .filter(a -> filter == null || a.path() != null && a.path().contains(filter))
                    .sorted(Comparator.comparingInt(a -> Annotation.severityRank(a.severity()))).toList();
            if (!shown.isEmpty() || in.annotationCount() > 0) {
                String bySeverity = severities(in.annotations());
                sb.append("Befunde (").append(filter == null ? "" : shown.size() + " von ").append(in.annotationCount())
                        .append(bySeverity.isEmpty() ? "" : ": " + bySeverity).append("):\n");
            }
            for (Annotation a : shown) {
                sb.append("- ").append(a.path() == null ? "(allgemein)" : a.path() + (a.line() == null ? "" : ":" + a.line()));
                if (a.severity() != null) {
                    sb.append("  ").append(a.severity());
                }
                if (a.type() != null) {
                    sb.append(' ').append(a.type());
                }
                String msg = a.message() == null ? "" : a.message().strip();
                sb.append("  ").append(msg.lines().findFirst().orElse(""));
                msg.lines().skip(1).forEach(l -> sb.append("\n    ").append(l));
                if (a.url() != null) {
                    sb.append("  ").append(a.url());
                }
                sb.append('\n');
            }
            if (filter == null && in.annotationCount() > in.annotations().size()) {
                sb.append("  … ").append(in.annotationCount() - in.annotations().size()).append(" weitere beim Server\n");
            }
        }
        if (insights.isEmpty()) {
            sb.append("(keine Berichte – keine Integration hat zum letzten Commit berichtet)");
        }
        return Text.limitLines(sb.toString().strip(), env.maxLines());
    }

    // ------------------------------------------------------------------ Hilfen

    private static final Set<String> FAILED = Set.of("fail", "failed", "failure", "error", "timed_out",
            "action_required", "cancelled", "canceled");

    /**
     * Kurzfassung der Berichte für pr_get, z.B. „4 Bericht(e), 1 fehlgeschlagen: API-Scanner (6 Befunde: 1 HIGH, 5 LOW)
     * – Details mit pr_insights“; {@code null}, wenn keine Integration berichtet hat oder der Server es nicht kann.
     */
    static String integrations(GitServer server, String ref, String project) {
        List<Insight> insights;
        try {
            insights = server.insights(ref, project);
        } catch (RuntimeException e) {
            // Zusatzinfo – fehlende Code-Insights-Rechte oder ältere Server sollen pr_get nicht verhindern
            return null;
        }
        if (insights.isEmpty()) {
            return null;
        }
        List<String> failed = insights.stream()
                .filter(in -> in.result() != null && FAILED.contains(in.result().toLowerCase(Locale.ROOT)))
                .map(in -> {
                    String bySeverity = severities(in.annotations());
                    return Text.orDash(in.title()) + (in.annotationCount() == 0 ? "" : " (" + in.annotationCount()
                            + " Befund(e)" + (bySeverity.isEmpty() ? "" : ": " + bySeverity) + ")");
                })
                .toList();
        return insights.size() + " Bericht(e)" + (failed.isEmpty() ? ", keiner fehlgeschlagen"
                : ", " + failed.size() + " fehlgeschlagen: " + String.join(", ", failed)) + " – Details mit pr_insights";
    }

    /** Befunde je Schwere, schwerste zuerst, z.B. „1 HIGH, 5 LOW“. */
    static String severities(List<Annotation> annotations) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        annotations.stream().sorted(Comparator.comparingInt(a -> Annotation.severityRank(a.severity())))
                .forEach(a -> counts.merge(a.severity() == null ? "ohne Schwere" : a.severity(), 1, Integer::sum));
        return String.join(", ", counts.entrySet().stream().map(e -> e.getValue() + " " + e.getKey()).toList());
    }

    /**
     * Pull-Request-Angabe oder – ohne Angabe – der offene Pull Request zum aktuellen Branch des lokalen Repositories.
     */
    static String refOrCurrent(PrEnvironment.Target t, String pr) {
        if (pr != null && !pr.isBlank()) {
            return pr.trim();
        }
        if (t.local() == null || t.local().branch() == null) {
            throw new IllegalArgumentException("Kein Pull Request angegeben ('pr') und kein lokaler Branch, aus dem er "
                    + "sich ergibt.");
        }
        List<PullRequest> open = t.server().list(new PrQuery(t.project(), GitServer.State.OPEN, null,
                t.local().branch(), null, 5));
        if (open.isEmpty()) {
            throw new IllegalArgumentException("Kein offener Pull Request für Branch '" + t.local().branch() + "' in "
                    + t.project() + " – 'pr' angeben oder mit pr_create anlegen.");
        }
        if (open.size() > 1) {
            throw new IllegalArgumentException("Mehrere offene Pull Requests für Branch '" + t.local().branch() + "': "
                    + String.join(", ", open.stream().map(PullRequest::key).toList()) + " – 'pr' angeben.");
        }
        return open.getFirst().key();
    }

    /** {@code KEY  [state]  @autor  src → ziel  Titel  (Entwurf)}. */
    static String line(PullRequest p) {
        return p.key() + "  [" + Text.orDash(p.state()) + "]  @" + Text.orDash(p.author()) + "  "
                + Text.orDash(p.source()) + " → " + Text.orDash(p.target()) + "  " + Text.orDash(p.title())
                + (p.draft() ? "  (Entwurf)" : "") + (p.updated() == null ? "" : "  " + p.updated());
    }

    /** Ergebniszeile einer schreibenden Aktion. */
    static String written(GitServer.WriteResult r) {
        return r.key() + ": " + r.message() + (r.id() == null ? "" : " [ID " + r.id() + "]")
                + (r.url() == null ? "" : "\n" + r.url());
    }

    private static void row(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            sb.append(String.format("%-11s %s%n", label + ":", value));
        }
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
