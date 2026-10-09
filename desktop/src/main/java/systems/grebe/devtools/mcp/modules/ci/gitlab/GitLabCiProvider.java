package systems.grebe.devtools.mcp.modules.ci.gitlab;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import static systems.grebe.devtools.mcp.modules.ticket.spi.HttpJson.enc;
import static systems.grebe.devtools.mcp.modules.ticket.spi.HttpJson.query;
import static systems.grebe.devtools.mcp.modules.ticket.spi.HttpJson.text;

/** GitLab.com und selbst betriebenes GitLab: Pipelines und Jobs über die REST API v4. */
public class GitLabCiProvider implements CiProvider {

    static final String BASE_URL = "baseUrl";
    static final String TOKEN = "token";

    @Override
    public String id() {
        return "gitlab";
    }

    @Override
    public String displayName() {
        return "GitLab CI/CD";
    }

    @Override
    public int priority() {
        return 20;
    }

    @Override
    public List<ConfigField> configFields() {
        return List.of(
                ConfigField.of(BASE_URL, "Server-URL", FieldType.URL).withDefault("https://gitlab.com")
                        .withHelp("Ohne /api/v4, z.B. https://gitlab.firma.de"),
                ConfigField.of(TOKEN, "Token", FieldType.SECRET)
                        .withHelp("Personal/Project Access Token mit api (Starten, Abbrechen, Wiederholen) bzw. "
                                + "read_api (nur Lesen)."));
    }

    @Override
    public String projectHelp() {
        return "Projektpfad, z.B. gruppe/projekt";
    }

    @Override
    public String buildHelp() {
        return "gruppe/projekt#4711 (Pipeline-ID), #4711 bzw. 4711 (mit Projekt) oder Pipeline-/Job-URL";
    }

    @Override
    public CiSystem create(ProviderSettings s) {
        String base = HttpJson.stripSlash(s.getString(BASE_URL, "https://gitlab.com"));
        if (base.endsWith("/api/v4")) {
            base = base.substring(0, base.length() - 7);
        }
        Map<String, String> headers = new LinkedHashMap<>();
        s.get(TOKEN).ifPresent(t -> headers.put("PRIVATE-TOKEN", t));
        return new GitLab(new HttpJson("GitLab", base + "/api/v4", headers, s.timeout(), "CI/CD"), base,
                s.get(TOKEN).isPresent());
    }

    /** GitLab-Anbindung. Paketsichtbar für Tests. */
    static final class GitLab implements CiSystem {

        private static final Pattern FULL_KEY = Pattern.compile("([\\w.-]+(?:/[\\w.-]+)+)#(\\d+)");
        private static final Pattern SHORT_KEY = Pattern.compile("#?(\\d+)");
        private static final Pattern URL_KEY = Pattern.compile("https?://[^/]+/(.+?)/-/(pipelines|jobs)/(\\d+).*");

        private final HttpJson http;
        private final String webBase;
        private final String webHost;
        /** Kontextpfad eines unter Unterpfad betriebenen GitLab, z.B. {@code gitlab} bei https://firma.de/gitlab. */
        private final String contextPath;
        private final boolean authenticated;

        GitLab(HttpJson http, String webBase, boolean authenticated) {
            this.http = http;
            this.webBase = webBase;
            this.webHost = GitServer.hostOf(webBase);
            this.contextPath = GitServer.pathOf(webBase);
            this.authenticated = authenticated;
        }

        @Override
        public String id() {
            return "gitlab";
        }

        @Override
        public String instance() {
            return webBase;
        }

        @Override
        public Availability probe() {
            try {
                if (!authenticated) {
                    http.getJson("/projects" + query("per_page", 1));
                    return new Availability(true, "ohne Token", null, "anonym – nur öffentliche Projekte, nur lesen");
                }
                String version = text(http.getJson("/version").path("version"));
                return Availability.ok(version, text(http.getJson("/user").path("username")));
            } catch (RuntimeException e) {
                return Availability.unavailable(e.getMessage());
            }
        }

        @Override
        public String projectOfRemote(String remoteUrl) {
            GitServer.Remote r = GitServer.parseRemote(remoteUrl);
            if (r == null || !r.host().equals(webHost)) {
                return null;
            }
            String p = r.path();
            // HTTPS-Remotes tragen den Kontextpfad (https://firma.de/gitlab/gruppe/projekt.git), SSH nicht
            if (!contextPath.isEmpty() && !r.scheme().equals("ssh") && p.startsWith(contextPath + "/")) {
                p = p.substring(contextPath.length() + 1);
            }
            return p.contains("/") ? p : null;
        }

        @Override
        public boolean ownsKey(String ref) {
            return ref != null && URL_KEY.matcher(ref.trim()).matches() && webHost.equals(GitServer.hostOf(ref.trim()));
        }

        @Override
        public String projectOf(String ref, String project) {
            return ref(ref, project).project();
        }

        /**
         * Pipeline bzw. Job (aus einer Job-URL) eines Projekts.
         *
         * @param job {@code true}, wenn {@code id} eine Job-ID ist
         */
        record Ref(String project, long id, boolean job) {
            String api() {
                return "/projects/" + enc(project);
            }
        }

        Ref ref(String key, String project) {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("GitLab: keine Pipeline angegeben (z.B. gruppe/projekt#4711 oder 4711).");
            }
            String k = key.trim();
            Matcher m = URL_KEY.matcher(k);
            if (m.matches()) {
                String p = m.group(1);
                if (!contextPath.isEmpty() && p.startsWith(contextPath + "/")) {
                    p = p.substring(contextPath.length() + 1);
                }
                return new Ref(p, Long.parseLong(m.group(3)), m.group(2).equals("jobs"));
            }
            m = FULL_KEY.matcher(k);
            if (m.matches()) {
                return new Ref(m.group(1), Long.parseLong(m.group(2)), false);
            }
            m = SHORT_KEY.matcher(k);
            if (m.matches()) {
                return new Ref(project(project), Long.parseLong(m.group(1)), false);
            }
            throw new IllegalArgumentException("GitLab: '" + k + "' ist keine Pipeline (erwartet gruppe/projekt#4711, "
                    + "#4711 oder Pipeline-URL).");
        }

        static String project(String project) {
            if (project == null || !project.trim().matches("[\\w.-]+(?:/[\\w.-]+)+")) {
                throw new IllegalArgumentException("GitLab: Projekt fehlt oder ist ungültig ('" + project
                        + "') – als gruppe/projekt angeben oder ein lokales Repository mit GitLab-Remote wählen.");
            }
            return project.trim();
        }

        /** Pipeline-ID; bei einer Job-URL die Pipeline des Jobs. */
        private long pipelineId(Ref r) {
            if (!r.job()) {
                return r.id();
            }
            return http.getJson(r.api() + "/jobs/" + r.id()).path("pipeline").path("id").asLong();
        }

        private void requireToken(String what) {
            if (!authenticated) {
                throw new IllegalStateException("GitLab: " + what + " braucht ein Token – in der DevTools-App unter "
                        + "Module → CI/CD eintragen.");
            }
        }

        // ------------------------------------------------------------------ Lesen

        @Override
        public List<Build> builds(BuildQuery q) {
            String p = project(q.project());
            String serverStatus = q.status() == null ? null : switch (q.status()) {
                case RUNNING -> "running";
                case SUCCESS -> "success";
                case FAILED -> "failed";
                case CANCELED -> "canceled";
                case SKIPPED -> "skipped";
                case MANUAL -> "manual";
                default -> null;
            };
            boolean clientFilter = q.status() != null && serverStatus == null;
            List<Build> out = new ArrayList<>();
            for (JsonNode n : http.getJson("/projects/" + enc(p) + "/pipelines" + query("ref", q.ref(),
                    "status", serverStatus, "order_by", "id", "sort", "desc",
                    "per_page", clientFilter ? 100 : q.limit()))) {
                Build b = build(n, p);
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

        private static Build build(JsonNode n, String project) {
            String raw = text(n.path("status"));
            String trigger = text(n.path("source"));
            String user = text(n.path("user").path("username"));
            if (user != null) {
                trigger = (trigger == null ? "" : trigger + " von ") + user;
            }
            return new Build(project + "#" + text(n.path("id")), status(raw), raw, text(n.path("name")),
                    text(n.path("ref")), text(n.path("sha")), trigger,
                    HttpJson.first(text(n.path("started_at")), text(n.path("created_at"))),
                    n.path("duration").isNumber() ? n.path("duration").asLong() : null, text(n.path("web_url")));
        }

        static Status status(String s) {
            if (s == null) {
                return Status.UNKNOWN;
            }
            return switch (s) {
                case "created", "waiting_for_resource", "preparing", "pending", "scheduled",
                     "waiting_for_callback" -> Status.QUEUED;
                case "running" -> Status.RUNNING;
                case "success" -> Status.SUCCESS;
                case "failed" -> Status.FAILED;
                case "canceled", "canceling" -> Status.CANCELED;
                case "skipped" -> Status.SKIPPED;
                case "manual" -> Status.MANUAL;
                default -> Status.UNKNOWN;
            };
        }

        @Override
        public BuildDetails get(String ref, String project) {
            Ref r = ref(ref, project);
            long id = pipelineId(r);
            JsonNode n = http.getJson(r.api() + "/pipelines/" + id);
            Map<String, String> fields = new LinkedHashMap<>();
            String detailed = text(n.path("detailed_status").path("text"));
            if (detailed != null && !detailed.equalsIgnoreCase(text(n.path("status")))) {
                fields.put("Anzeige", detailed);
            }
            if (n.path("queued_duration").isNumber()) {
                fields.put("Wartezeit", Math.round(n.path("queued_duration").asDouble()) + "s");
            }
            if (text(n.path("coverage")) != null) {
                fields.put("Coverage", text(n.path("coverage")) + " %");
            }
            if (text(n.path("yaml_errors")) != null) {
                fields.put("YAML-Fehler", text(n.path("yaml_errors")));
            }
            return new BuildDetails(build(n, r.project()), jobs(r, id), fields);
        }

        /** Jobs in Ausführungsreihenfolge (nach ID), gruppiert nach Stage in der Reihenfolge ihres ersten Jobs. */
        private List<Job> jobs(Ref r, long pipelineId) {
            List<JsonNode> raw = new ArrayList<>();
            http.getJson(r.api() + "/pipelines/" + pipelineId + "/jobs" + query("per_page", 100)).forEach(raw::add);
            raw.sort(Comparator.comparingLong(j -> j.path("id").asLong()));
            Map<String, List<Job>> byStage = new LinkedHashMap<>();
            for (JsonNode j : raw) {
                List<String> detail = new ArrayList<>();
                if (text(j.path("failure_reason")) != null) {
                    detail.add(text(j.path("failure_reason")));
                }
                if (j.path("allow_failure").asBoolean(false)) {
                    detail.add("darf fehlschlagen");
                }
                String stage = text(j.path("stage"));
                byStage.computeIfAbsent(stage == null ? "" : stage, s -> new ArrayList<>()).add(new Job(
                        text(j.path("id")), text(j.path("name")), stage, status(text(j.path("status"))),
                        j.path("duration").isNumber() ? Math.round(j.path("duration").asDouble()) : null,
                        text(j.path("web_url")), detail.isEmpty() ? null : String.join(", ", detail)));
            }
            List<Job> out = new ArrayList<>();
            byStage.values().forEach(out::addAll);
            return out;
        }

        @Override
        public Log log(String ref, String project, String job) {
            Ref r = ref(ref, project);
            Job target;
            if (job == null && r.job()) {
                JsonNode j = http.getJson(r.api() + "/jobs/" + r.id());
                target = new Job(text(j.path("id")), text(j.path("name")), text(j.path("stage")),
                        status(text(j.path("status"))), null, text(j.path("web_url")), null);
            } else {
                target = pick(jobs(r, pipelineId(r)), job, r.project() + "#" + pipelineId(r));
            }
            String text = http.getText(r.api() + "/jobs/" + target.id() + "/trace");
            return new Log("Job " + target.name() + " (#" + target.id() + ")" + (target.stage() == null ? ""
                    : ", Stage " + target.stage()) + ", " + target.status().label(), text, target.url());
        }

        /** Job nach ID oder Name; ohne Angabe der erste fehlgeschlagene (ohne „darf fehlschlagen“ zuerst), sonst der einzige. */
        static Job pick(List<Job> jobs, String job, String pipeline) {
            if (jobs.isEmpty()) {
                throw new IllegalArgumentException("GitLab: Pipeline " + pipeline + " hat keine Jobs.");
            }
            if (job != null) {
                String j = job.trim().replaceFirst("^#", "");
                return jobs.stream().filter(x -> j.equals(x.id())).findFirst()
                        .or(() -> jobs.stream().filter(x -> j.equalsIgnoreCase(x.name())).reduce((a, b) -> b))
                        .orElseThrow(() -> new IllegalArgumentException("GitLab: Job '" + job + "' gibt es in Pipeline "
                                + pipeline + " nicht. Jobs: " + names(jobs)));
            }
            List<Job> failed = jobs.stream().filter(x -> x.status() == Status.FAILED).toList();
            if (!failed.isEmpty()) {
                return failed.stream().filter(x -> x.detail() == null || !x.detail().contains("darf fehlschlagen"))
                        .findFirst().orElse(failed.getFirst());
            }
            if (jobs.size() == 1) {
                return jobs.getFirst();
            }
            throw new IllegalArgumentException("GitLab: kein Job fehlgeschlagen – 'job' angeben. Jobs: " + names(jobs));
        }

        private static String names(List<Job> jobs) {
            List<String> out = new ArrayList<>();
            jobs.forEach(j -> out.add(j.name() + " (" + j.id() + ", " + j.status().label() + ")"));
            return String.join(", ", out);
        }

        @Override
        public List<Workflow> workflows(String project) {
            throw new UnsupportedOperationException("GitLab hat eine Pipeline je Projekt (.gitlab-ci.yml) – mit ci_start "
                    + "und 'branch' starten, Variablen über 'parameters'.");
        }

        // ------------------------------------------------------------------ Steuern

        @Override
        public WriteResult start(String project, StartRequest req) {
            requireToken("Starten");
            String p = project(project);
            String ref = req.ref() != null ? req.ref()
                    : text(http.getJson("/projects/" + enc(p)).path("default_branch"));
            if (ref == null) {
                throw new IllegalArgumentException("GitLab: Projekt " + p + " hat keinen Standard-Branch – 'branch' angeben.");
            }
            ObjectNode body = HttpJson.object().put("ref", ref);
            if (!req.parameters().isEmpty()) {
                ArrayNode vars = body.putArray("variables");
                req.parameters().forEach((k, v) -> vars.addObject().put("key", k).put("value", v));
            }
            JsonNode n = http.post("/projects/" + enc(p) + "/pipeline", body).body();
            return new WriteResult(p + "#" + text(n.path("id")), "Pipeline auf " + ref + " gestartet ("
                    + text(n.path("status")) + ")" + (req.parameters().isEmpty() ? "" : " mit Variablen "
                    + req.parameters().keySet()), text(n.path("web_url")));
        }

        @Override
        public WriteResult cancel(String ref, String project) {
            requireToken("Abbrechen");
            Ref r = ref(ref, project);
            long id = pipelineId(r);
            JsonNode before = http.getJson(r.api() + "/pipelines/" + id);
            if (status(text(before.path("status"))).finished()) {
                return new WriteResult(r.project() + "#" + id, "läuft nicht mehr (" + text(before.path("status"))
                        + ") – nichts abzubrechen.", text(before.path("web_url")));
            }
            JsonNode n = http.post(r.api() + "/pipelines/" + id + "/cancel", HttpJson.object()).body();
            return new WriteResult(r.project() + "#" + id, "Abbruch angefordert (" + text(n.path("status")) + ").",
                    text(n.path("web_url")));
        }

        @Override
        public WriteResult retry(String ref, String project, boolean failedOnly) {
            requireToken("Wiederholen");
            Ref r = ref(ref, project);
            long id = pipelineId(r);
            if (failedOnly) {
                JsonNode n = http.post(r.api() + "/pipelines/" + id + "/retry", HttpJson.object()).body();
                return new WriteResult(r.project() + "#" + id, "fehlgeschlagene und abgebrochene Jobs neu gestartet ("
                        + text(n.path("status")) + ").", text(n.path("web_url")));
            }
            String pipelineRef = text(http.getJson(r.api() + "/pipelines/" + id).path("ref"));
            WriteResult started = start(r.project(), new StartRequest(pipelineRef, null, null));
            return new WriteResult(started.key(), started.message() + " – Wiederholung von #" + id
                    + " (neuester Stand des Branches)", started.url());
        }
    }
}
