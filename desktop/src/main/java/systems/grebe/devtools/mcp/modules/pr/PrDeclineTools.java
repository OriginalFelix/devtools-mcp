package systems.grebe.devtools.mcp.modules.pr;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.Signature;
import systems.grebe.devtools.mcp.core.SignatureModule;
import systems.grebe.devtools.mcp.modules.pr.spi.GitServer;
import systems.grebe.devtools.mcp.modules.pr.spi.GitServer.NewComment;
import systems.grebe.devtools.mcp.modules.pr.spi.GitServer.PrQuery;
import systems.grebe.devtools.mcp.modules.pr.spi.GitServer.PullRequest;
import systems.grebe.devtools.mcp.modules.pr.spi.GitServer.WriteResult;

import static systems.grebe.devtools.mcp.modules.pr.PrTools.PROJECT;
import static systems.grebe.devtools.mcp.modules.pr.PrTools.PROVIDER;
import static systems.grebe.devtools.mcp.modules.pr.PrTools.REPOSITORY;
import static systems.grebe.devtools.mcp.modules.pr.PrTools.blankToNull;

/**
 * Pull Requests ablehnen und Branches auf dem Server löschen (Schalter {@code allowDecline}). Der Ablauf samt aller
 * Prüfungen steht hier, unabhängig vom Server; die Provider liefern über {@link GitServer} nur die Einzelschritte
 * ({@code get}, {@code comment}, {@code decline}, {@code list}, {@code defaultBranch}, {@code branchHead},
 * {@code deleteBranch}). Nie den Standard-Branch, nie einen Branch, aus dem noch ein Pull Request offen ist, nie
 * einen Branch in einem Fork.
 */
public class PrDeclineTools {

    private final PrEnvironment env;

    PrDeclineTools(PrEnvironment env) {
        this.env = env;
    }

    @Tool(name = "decline", description = "Lehnt einen offenen Pull/Merge Request ab bzw. schließt ihn ohne Merge "
            + "(Bitbucket: Decline, GitHub/GitLab: Close), optional mit Begründung als Kommentar und danach mit "
            + "Löschen des Quell-Branches auf dem Server (nur im selben Repository, nie in einem Fork). Nur auf "
            + "ausdrücklichen Auftrag des Nutzers, z.B. für überholte Pull Requests." + ShellHints.PR)
    public String decline(
            @ToolParam(description = "Pull Request: Nummer, voller Schlüssel oder URL (ausdrücklich, kein Standard)") String pr,
            @ToolParam(required = false, description = "Begründung (Markdown), wird als Kommentar angelegt; leer = ohne") String comment,
            @ToolParam(required = false, description = "true = Quell-Branch danach auf dem Server löschen (abgelehnt, "
                    + "wenn aus ihm noch ein anderer Pull Request offen ist)") Boolean deleteSourceBranch,
            @ToolParam(required = false, description = REPOSITORY) String repository,
            @ToolParam(required = false, description = PROJECT) String project,
            @ToolParam(required = false, description = PROVIDER) String provider,
            @ToolParam(required = false, description = Signature.MODEL_PARAM) String aiModel,
            ToolContext toolContext) {
        if (pr == null || pr.isBlank()) {
            throw new IllegalArgumentException("Pull Request fehlt ('pr') – beim Ablehnen ausdrücklich angeben.");
        }
        String ref = pr.trim();
        PrEnvironment.Target t = env.target(provider, repository, project, ref);
        String repo = env.checkWrite(t, ref, "Ablehnen");
        GitServer server = t.server();
        PullRequest p = server.get(ref, t.project()).pr();
        if (!"open".equals(p.state())) {
            return p.key() + ": ist nicht offen (" + p.state() + ") – unverändert" + (p.url() == null ? "" : "\n" + p.url());
        }
        boolean delete = Boolean.TRUE.equals(deleteSourceBranch);
        if (delete) {
            List<PullRequest> others = openFrom(server, repo, p.source()).stream()
                    .filter(o -> !o.key().equals(p.key())).toList();
            if (!others.isEmpty()) {
                throw new IllegalStateException("Aus Branch '" + p.source() + "' sind noch weitere Pull Requests offen: "
                        + keys(others) + " – deleteSourceBranch weglassen oder diese zuerst abschließen.");
            }
        }
        StringBuilder msg = new StringBuilder();
        if (blankToNull(comment) != null) {
            server.comment(ref, t.project(), new NewComment(
                    env.commentBody(comment, SignatureModule.modelName(aiModel, toolContext)), null, null));
            msg.append(", Kommentar hinzugefügt");
        }
        WriteResult r = server.decline(ref, t.project());
        if (delete) {
            msg.append(", ").append(deleteSource(server, repo, p));
        }
        return PrTools.written(new WriteResult(r.key(), r.message() + msg, r.url() != null ? r.url() : p.url()));
    }

    @Tool(name = "delete_branch", description = "Löscht einen Branch auf dem Git-Server (Remote), z.B. nach einem "
            + "abgelehnten oder überholten Pull Request; der lokale Branch bleibt (git_delete_branch). Nie den "
            + "Standard-Branch (main/master) und nie, solange aus dem Branch ein Pull Request offen ist. Meldet den "
            + "letzten Commit, damit sich der Branch wiederherstellen lässt. Nur auf ausdrücklichen Auftrag des Nutzers."
            + ShellHints.PR)
    public String deleteBranch(
            @ToolParam(description = "Branch auf dem Server (ohne refs/heads/ und ohne Remote-Präfix)") String branch,
            @ToolParam(required = false, description = REPOSITORY) String repository,
            @ToolParam(required = false, description = PROJECT) String project,
            @ToolParam(required = false, description = PROVIDER) String provider) {
        String b = blankToNull(branch);
        if (b == null) {
            throw new IllegalArgumentException("Branch fehlt ('branch').");
        }
        if (b.startsWith("refs/heads/")) {
            b = b.substring("refs/heads/".length());
        }
        if (!b.matches("[\\w./-]+") || b.startsWith("-") || b.contains("..")) {
            throw new IllegalArgumentException("Ungültiger Branch-Name '" + b + "'.");
        }
        PrEnvironment.Target t = env.target(provider, repository, project, null);
        if (t.local() != null && t.local().remote() != null && b.startsWith(t.local().remote() + "/")) {
            throw new IllegalArgumentException("'" + b + "' sieht nach einem Remote-Tracking-Branch aus – den Namen auf "
                    + "dem Server ohne '" + t.local().remote() + "/' angeben.");
        }
        if (List.of("main", "master").contains(b)
                || t.local() != null && b.equals(PrEnvironment.remoteDefaultBranch(t.local()))) {
            throw notDefault(b);
        }
        String repo = env.checkWrite(t, null, "Branch löschen");
        List<PullRequest> open = openFrom(t.server(), repo, b);
        if (!open.isEmpty()) {
            throw new IllegalStateException("Aus Branch '" + b + "' sind noch Pull Requests offen: " + keys(open)
                    + " – zuerst mergen oder mit pr_decline ablehnen (deleteSourceBranch=true löscht den Branch dabei).");
        }
        String result = delete(t.server(), repo, b);
        String local = t.local() != null && PrEnvironment.hasBranch(t.local(), b)
                ? "\nLokaler Branch " + b + " in '" + t.local().name() + "' bleibt bestehen (bei Bedarf git_delete_branch)."
                : "";
        return repo + ": " + result + local;
    }

    /** Quell-Branch nach dem Ablehnen löschen; Fehler landen in der Meldung, das Ablehnen bleibt gültig. */
    private static String deleteSource(GitServer server, String repo, PullRequest p) {
        if (p.source() == null) {
            return "Quell-Branch unbekannt – nicht gelöscht";
        }
        if (!Boolean.FALSE.equals(p.fromFork())) {
            return "Branch " + p.source() + (p.fromFork() == null ? ": Quell-Repository unbekannt"
                    : " liegt in einem Fork") + " – nicht gelöscht";
        }
        try {
            return delete(server, repo, p.source());
        } catch (RuntimeException e) {
            return "Branch " + p.source() + " NICHT gelöscht: " + e.getMessage();
        }
    }

    /** Prüft Standard-Branch und Existenz, löscht und meldet den letzten Commit zum Wiederherstellen. */
    private static String delete(GitServer server, String repo, String branch) {
        String defaultBranch;
        try {
            defaultBranch = server.defaultBranch(repo);
        } catch (UnsupportedOperationException e) {
            defaultBranch = null; // main/master hat das Tool schon ausgeschlossen
        }
        if (branch.equals(defaultBranch)) {
            throw notDefault(branch);
        }
        String head = server.branchHead(repo, branch);
        if (head == null) {
            throw new IllegalArgumentException("Branch '" + branch + "' gibt es in " + repo + " nicht.");
        }
        server.deleteBranch(repo, branch);
        return "Branch " + branch + " gelöscht" + (head.isEmpty() ? "" : " (letzter Commit " + head + ")");
    }

    private static IllegalStateException notDefault(String branch) {
        return new IllegalStateException("'" + branch + "' ist der Standard-Branch – es werden nur Feature-Branches "
                + "gelöscht.");
    }

    private static List<PullRequest> openFrom(GitServer server, String repo, String branch) {
        return server.list(new PrQuery(repo, GitServer.State.OPEN, null, branch, null, 20)).stream()
                .filter(p -> branch.equals(p.source())).toList();
    }

    private static String keys(List<PullRequest> prs) {
        return prs.stream().map(PullRequest::key).collect(Collectors.joining(", "));
    }
}
