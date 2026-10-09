package systems.grebe.devtools.mcp.modules.ci.github;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.modules.ci.spi.CiProvider;
import systems.grebe.devtools.mcp.modules.ci.spi.CiSystem;
import systems.grebe.devtools.mcp.modules.pr.spi.GitServer;
import systems.grebe.devtools.mcp.modules.ticket.spi.HttpJson;
import systems.grebe.devtools.mcp.modules.ticket.spi.ProviderSettings;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import static systems.grebe.devtools.mcp.modules.ticket.spi.HttpJson.enc;
import static systems.grebe.devtools.mcp.modules.ticket.spi.HttpJson.query;
import static systems.grebe.devtools.mcp.modules.ticket.spi.HttpJson.text;

/** GitHub Actions auf GitHub.com und GitHub Enterprise Server: Workflow-Läufe und Jobs über die REST-API. */
public class GitHubCiProvider implements CiProvider {

    static final String BASE_URL = "baseUrl";
    static final String TOKEN = "token";

    @Override
    public String id() {
        return "github";
    }

    @Override
    public String displayName() {
        return "GitHub Actions";
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public List<ConfigField> configFields() {
        return List.of(
                ConfigField.of(BASE_URL, "API-URL", FieldType.URL).withDefault("https://api.github.com")
                        .withHelp("GitHub Enterprise Server: https://github.firma.de/api/v3"),
                ConfigField.of(TOKEN, "Token", FieldType.SECRET)
                        .withHelp("Fine-grained Token (Actions: read, zum Starten/Abbrechen/Wiederholen read/write) oder "
                                + "klassisch mit repo und workflow. Ohne Token nur öffentliche Repositories lesen."));
    }

    @Override
    public String projectHelp() {
        return "owner/repo";
    }

    @Override
    public String buildHelp() {
        return "owner/repo#123456789 (Lauf-ID), #123456789 bzw. 123456789 (mit Repository) oder Lauf-/Job-URL";
    }

    @Override
    public CiSystem create(ProviderSettings s) {
        String base = HttpJson.stripSlash(s.getString(BASE_URL, "https://api.github.com"));
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-GitHub-Api-Version", "2022-11-28");
        s.get(TOKEN).ifPresent(t -> headers.put("Authorization", "Bearer " + t));
        return new GitHub(new HttpJson("GitHub", base, headers, s.timeout(), "CI/CD"), headers, s.timeout(),
                s.get(TOKEN).isPresent());
    }

    /** GitHub-Actions-Anbindung. Paketsichtbar für Tests. */
    static final class GitHub implements CiSystem {

        private static final Pattern FULL_KEY = Pattern.compile("([\\w.-]+)/([\\w.-]+)#(\\d+)");
        private static final Pattern SHORT_KEY = Pattern.compile("#?(\\d+)");
        private static final Pattern URL_KEY = Pattern.compile(
                "https?://[^/]+/([\\w.-]+)/([\\w.-]+)/actions/runs/(\\d+)(?:/attempts/\\d+)?(?:/job/(\\d+))?.*");
        /** Zeitstempel am Zeilenanfang der Job-Logs. */
        private static final Pattern TIMESTAMP = Pattern.compile("(?m)^\\d{4}-\\d\\d-\\d\\dT[\\d:.]+Z ");

        private final HttpJson http;
        private final Map<String, String> headers;
        private final Duration timeout;
        private final boolean authenticated;
        private final String webHost;
        /** Ohne automatische Weiterleitung: Job-Logs leiten auf signierte Blob-URLs um, die kein Token sehen sollen. */
        private final HttpClient raw = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build();

        GitHub(HttpJson http, Map<String, String> headers, Duration timeout, boolean authenticated) {
            this.http = http;
            this.headers = Map.copyOf(headers);
            this.timeout = timeout;
            this.authenticated = authenticated;
            String host = GitServer.hostOf(http.baseUrl());
            this.webHost = "api.github.com".equals(host) ? "github.com" : host;
        }

        @Override
        public String id() {
            return "github";
        }

        @Override
        public String instance() {
            return "https://" + webHost;
        }

        @Override
        public Availability probe() {
            try {
                if (!authenticated) {
                    JsonNode rate = http.getJson("/rate_limit").path("resources").path("core");
                    return new Availability(true, "ohne Token", null, "anonym, " + text(rate.path("remaining"))
                            + " Anfragen übrig – nur öffentliche Repositories, nur lesen");
                }
                return Availability.ok(webHost, text(http.getJson("/user").path("login")));
            } catch (RuntimeException e) {
                return Availability.unavailable(e.getMessage());
            }
        }

        @Override
        public String projectOfRemote(String remoteUrl) {
            GitServer.Remote r = GitServer.parseRemote(remoteUrl);
            if (r == null || !(r.host().equals(webHost) || r.host().equals("ssh." + webHost))) {
                return null;
            }
            return r.path().split("/").length == 2 ? r.path() : null;
        }

        @Override
        public boolean ownsKey(String ref) {
            return ref != null && URL_KEY.matcher(ref.trim()).matches() && webHost.equals(GitServer.hostOf(ref.trim()));
        }

        @Override
        public String projectOf(String ref, String project) {
            return ref(ref, project).repo();
        }

        /**
         * Lauf eines Repositories.
         *
         * @param job Job-ID aus einer Job-URL oder {@code null}
         */
        record Ref(String repo, long run, String job) {
            String path() {
                return "/repos/" + repo + "/actions/runs/" + run;
            }
        }

        static Ref ref(String key, String project) {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("GitHub: kein Workflow-Lauf angegeben (z.B. owner/repo#123456789).");
            }
            String k = key.trim();
            Matcher m = URL_KEY.matcher(k);
            if (m.matches()) {
                return new Ref(m.group(1) + "/" + m.group(2), Long.parseLong(m.group(3)), m.group(4));
            }
            m = FULL_KEY.matcher(k);
            if (m.matches()) {
                return new Ref(m.group(1) + "/" + m.group(2), Long.parseLong(m.group(3)), null);
            }
            m = SHORT_KEY.matcher(k);
            if (m.matches()) {
                return new Ref(repo(project), Long.parseLong(m.group(1)), null);
            }
            throw new IllegalArgumentException("GitHub: '" + k + "' ist kein Workflow-Lauf (erwartet owner/repo#123456789, "
                    + "Lauf-ID oder Lauf-URL).");
        }

        static String repo(String project) {
            if (project == null || !project.trim().matches("[\\w.-]+/[\\w.-]+")) {
                throw new IllegalArgumentException("GitHub: Repository fehlt oder ist ungültig ('" + project
                        + "') – als owner/repo angeben oder ein lokales Repository mit GitHub-Remote wählen.");
            }
            return project.trim();
        }

        private void requireToken(String what) {
            if (!authenticated) {
                throw new IllegalStateException("GitHub: " + what + " braucht ein Token – in der DevTools-App unter "
                        + "Module → CI/CD eintragen.");
            }
        }

        // ------------------------------------------------------------------ Lesen

        @Override
        public List<Build> builds(BuildQuery q) {
            String repo = repo(q.project());
            String serverStatus = q.status() == null ? null : switch (q.status()) {
                case QUEUED -> "queued";
                case RUNNING -> "in_progress";
                case SUCCESS -> "success";
                case FAILED -> "failure";
                case CANCELED -> "cancelled";
                case SKIPPED -> "skipped";
                case MANUAL -> "action_required";
                default -> null;
            };
            boolean clientFilter = q.status() != null && serverStatus == null;
            String base = q.workflow() == null ? "/repos/" + repo + "/actions/runs"
                    : "/repos/" + repo + "/actions/workflows/" + enc(workflowId(repo, q.workflow())) + "/runs";
            List<Build> out = new ArrayList<>();
            for (JsonNode n : http.getJson(base + query("branch", q.ref(), "status", serverStatus,
                    "per_page", clientFilter ? 100 : q.limit())).path("workflow_runs")) {
                Build b = build(n, repo);
                if (clientFilter && b.status() != q.status()) {
                    continue;
                }
                out.add(b);
                if (out.size() >= q.limit()) {
                    break;
                }
            }
            return out;
        }

        private static Build build(JsonNode n, String repo) {
            String status = text(n.path("status"));
            String conclusion = text(n.path("conclusion"));
            String name = text(n.path("name"));
            String title = text(n.path("display_title"));
            if (title != null && !title.equals(name)) {
                name = name == null ? title : name + ": " + title;
            }
            if (n.path("run_attempt").asInt(1) > 1) {
                name = (name == null ? "" : name + " ") + "(Versuch " + n.path("run_attempt").asInt() + ")";
            }
            String trigger = text(n.path("event"));
            String actor = HttpJson.first(text(n.path("triggering_actor").path("login")), text(n.path("actor").path("login")));
            if (actor != null) {
                trigger = (trigger == null ? "" : trigger + " von ") + actor;
            }
            String started = HttpJson.first(text(n.path("run_started_at")), text(n.path("created_at")));
            Status s = status(status, conclusion);
            return new Build(repo + "#" + text(n.path("id")), s, conclusion != null ? conclusion : status, name,
                    text(n.path("head_branch")), text(n.path("head_sha")), trigger, started,
                    seconds(started, s.finished() ? text(n.path("updated_at")) : null), text(n.path("html_url")));
        }

        static Status status(String status, String conclusion) {
            if (status == null) {
                return Status.UNKNOWN;
            }
            return switch (status) {
                case "queued", "requested", "waiting", "pending" -> Status.QUEUED;
                case "in_progress" -> Status.RUNNING;
                case "completed" -> conclusion == null ? Status.UNKNOWN : switch (conclusion) {
                    case "success" -> Status.SUCCESS;
                    case "failure", "timed_out", "startup_failure" -> Status.FAILED;
                    case "cancelled" -> Status.CANCELED;
                    case "skipped", "neutral", "stale" -> Status.SKIPPED;
                    case "action_required" -> Status.MANUAL;
                    default -> Status.UNKNOWN;
                };
                default -> Status.UNKNOWN;
            };
        }

        /** Sekunden zwischen zwei ISO-Zeitpunkten; ohne Ende bis jetzt. */
        static Long seconds(String from, String to) {
            if (from == null) {
                return null;
            }
            try {
                Instant a = Instant.parse(from);
                Instant b = to == null ? Instant.now() : Instant.parse(to);
                return Math.max(0, Duration.between(a, b).toSeconds());
            } catch (DateTimeParseException e) {
                return null;
            }
        }

        @Override
        public BuildDetails get(String ref, String project) {
            Ref r = ref(ref, project);
            JsonNode n = http.getJson(r.path());
            Map<String, String> fields = new LinkedHashMap<>();
            if (text(n.path("path")) != null) {
                fields.put("Workflow", text(n.path("path")));
            }
            String msg = text(n.path("head_commit").path("message"));
            if (msg != null) {
                fields.put("Nachricht", msg.lines().findFirst().orElse(msg));
            }
            List<String> prs = new ArrayList<>();
            n.path("pull_requests").forEach(p -> prs.add("#" + text(p.path("number"))));
            if (!prs.isEmpty()) {
                fields.put("Pull Requests", String.join(", ", prs));
            }
            return new BuildDetails(build(n, r.repo()), jobs(r), fields);
        }

        private List<Job> jobs(Ref r) {
            List<Job> out = new ArrayList<>();
            for (JsonNode j : http.getJson(r.path() + "/jobs" + query("per_page", 100)).path("jobs")) {
                Status s = status(text(j.path("status")), text(j.path("conclusion")));
                List<String> failed = new ArrayList<>();
                for (JsonNode step : j.path("steps")) {
                    if ("failure".equals(text(step.path("conclusion")))) {
                        failed.add(text(step.path("name")));
                    }
                }
                String started = text(j.path("started_at"));
                out.add(new Job(text(j.path("id")), text(j.path("name")), null, s,
                        s == Status.QUEUED ? null : seconds(started, text(j.path("completed_at"))), text(j.path("html_url")),
                        failed.isEmpty() ? null : "fehlgeschlagen: " + String.join(", ", failed)));
            }
            return out;
        }

        @Override
        public Log log(String ref, String project, String job) {
            Ref r = ref(ref, project);
            Job target;
            String wanted = job != null ? job : r.job();
            List<Job> jobs = jobs(r);
            target = pick(jobs, wanted, r.repo() + "#" + r.run());
            String text = jobLog(r.repo(), target.id());
            return new Log("Job " + target.name() + " (#" + target.id() + "), " + target.status().label()
                    + (target.detail() == null ? "" : " – " + target.detail()), TIMESTAMP.matcher(text).replaceAll(""),
                    target.url());
        }

        /** Job nach ID oder Name; ohne Angabe der erste fehlgeschlagene, sonst der einzige. */
        static Job pick(List<Job> jobs, String job, String run) {
            if (jobs.isEmpty()) {
                throw new IllegalArgumentException("GitHub: Lauf " + run + " hat keine Jobs.");
            }
            if (job != null) {
                String j = job.trim().replaceFirst("^#", "");
                return jobs.stream().filter(x -> j.equals(x.id())).findFirst()
                        .or(() -> jobs.stream().filter(x -> j.equalsIgnoreCase(x.name())).findFirst())
                        .orElseThrow(() -> new IllegalArgumentException("GitHub: Job '" + job + "' gibt es in Lauf "
                                + run + " nicht. Jobs: " + names(jobs)));
            }
            return jobs.stream().filter(x -> x.status() == Status.FAILED).findFirst().orElseGet(() -> {
                if (jobs.size() == 1) {
                    return jobs.getFirst();
                }
                throw new IllegalArgumentException("GitHub: kein Job fehlgeschlagen – 'job' angeben. Jobs: " + names(jobs));
            });
        }

        private static String names(List<Job> jobs) {
            List<String> out = new ArrayList<>();
            jobs.forEach(j -> out.add(j.name() + " (" + j.id() + ", " + j.status().label() + ")"));
            return String.join(", ", out);
        }

        /**
         * Log-Text eines Jobs. Die API leitet auf eine signierte Blob-URL um; der Weiterleitung wird ohne
         * {@code Authorization} gefolgt, damit das Token nicht an den Speicherdienst geht.
         */
        String jobLog(String repo, String jobId) {
            String url = http.baseUrl() + "/repos/" + repo + "/actions/jobs/" + jobId + "/logs";
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET();
            headers.forEach(req::header);
            HttpResponse<String> res = send(req.build());
            int code = res.statusCode();
            if (code >= 300 && code < 400) {
                String location = res.headers().firstValue("Location").orElseThrow(() -> new IllegalStateException(
                        "GitHub: Weiterleitung ohne Ziel beim Log von Job " + jobId + "."));
                res = send(HttpRequest.newBuilder(URI.create(location)).timeout(timeout).GET().build());
                code = res.statusCode();
            }
            if (code == 404 || code == 410) {
                throw new IllegalStateException("GitHub: Log von Job " + jobId + " nicht verfügbar (" + code + ") – der Job "
                        + "läuft noch, wurde übersprungen oder das Log ist abgelaufen.");
            }
            if (code == 401 || code == 403) {
                throw new IllegalStateException("GitHub: keine Berechtigung für das Log von Job " + jobId + " (" + code
                        + ") – Token braucht Actions: read.");
            }
            if (code >= 400) {
                throw new IllegalStateException("GitHub-Fehler " + code + " beim Log von Job " + jobId + ".");
            }
            return res.body();
        }

        private HttpResponse<String> send(HttpRequest req) {
            try {
                return raw.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new IllegalStateException("GitHub nicht erreichbar (" + http.baseUrl() + "): " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Abgebrochen", e);
            }
        }

        @Override
        public List<Workflow> workflows(String project) {
            String repo = repo(project);
            List<Workflow> out = new ArrayList<>();
            for (JsonNode w : http.getJson("/repos/" + repo + "/actions/workflows" + query("per_page", 100))
                    .path("workflows")) {
                String path = text(w.path("path"));
                out.add(new Workflow(path == null ? text(w.path("id")) : fileName(path), text(w.path("name")), path,
                        text(w.path("state")), text(w.path("html_url"))));
            }
            return out;
        }

        private static String fileName(String path) {
            int slash = path.lastIndexOf('/');
            return slash < 0 ? path : path.substring(slash + 1);
        }

        /** ID oder Dateiname eines Workflows; ein Name wird über die Workflow-Liste aufgelöst. */
        String workflowId(String repo, String workflow) {
            String w = workflow.trim();
            if (w.matches("\\d+") || w.endsWith(".yml") || w.endsWith(".yaml")) {
                return fileName(w);
            }
            List<Workflow> all = workflows(repo);
            return all.stream().filter(x -> w.equalsIgnoreCase(x.name())).map(Workflow::id).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("GitHub: Workflow '" + workflow + "' gibt es in "
                            + repo + " nicht. Workflows: " + all.stream().map(x -> x.id() + " (" + x.name() + ")").toList()));
        }

        // ------------------------------------------------------------------ Steuern

        @Override
        public WriteResult start(String project, StartRequest req) {
            requireToken("Starten");
            String repo = repo(project);
            if (req.workflow() == null) {
                throw new IllegalArgumentException("GitHub: 'workflow' angeben (Dateiname wie build.yml, ID oder Name) – "
                        + "verfügbar: " + workflows(repo).stream().map(Workflow::id).toList());
            }
            String wf = workflowId(repo, req.workflow());
            String ref = req.ref() != null ? req.ref() : text(http.getJson("/repos/" + repo).path("default_branch"));
            ObjectNode body = HttpJson.object().put("ref", ref);
            if (!req.parameters().isEmpty()) {
                ObjectNode inputs = body.putObject("inputs");
                req.parameters().forEach(inputs::put);
            }
            http.post("/repos/" + repo + "/actions/workflows/" + enc(wf) + "/dispatches", body);
            return new WriteResult(repo, "Workflow " + wf + " auf " + ref + " gestartet" + (req.parameters().isEmpty()
                    ? "" : " mit Inputs " + req.parameters().keySet()) + " – der Lauf erscheint in Kürze in ci_list "
                    + "workflow=" + wf + ".", "https://" + webHost + "/" + repo + "/actions/workflows/" + wf);
        }

        @Override
        public WriteResult cancel(String ref, String project) {
            requireToken("Abbrechen");
            Ref r = ref(ref, project);
            JsonNode n = http.getJson(r.path());
            Status s = status(text(n.path("status")), text(n.path("conclusion")));
            String key = r.repo() + "#" + r.run();
            if (s.finished()) {
                return new WriteResult(key, "läuft nicht mehr (" + s.label() + ") – nichts abzubrechen.",
                        text(n.path("html_url")));
            }
            http.post(r.path() + "/cancel", HttpJson.object());
            return new WriteResult(key, "Abbruch angefordert.", text(n.path("html_url")));
        }

        @Override
        public WriteResult retry(String ref, String project, boolean failedOnly) {
            requireToken("Wiederholen");
            Ref r = ref(ref, project);
            http.post(r.path() + (failedOnly ? "/rerun-failed-jobs" : "/rerun"), HttpJson.object());
            return new WriteResult(r.repo() + "#" + r.run(), failedOnly ? "fehlgeschlagene Jobs neu gestartet."
                    : "ganzer Lauf neu gestartet.", "https://" + webHost + "/" + r.repo() + "/actions/runs/" + r.run());
        }
    }
}
