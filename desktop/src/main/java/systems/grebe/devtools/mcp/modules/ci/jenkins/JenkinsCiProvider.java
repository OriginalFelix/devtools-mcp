package systems.grebe.devtools.mcp.modules.ci.jenkins;

import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.modules.ci.spi.CiProvider;
import systems.grebe.devtools.mcp.modules.ci.spi.CiSystem;
import systems.grebe.devtools.mcp.modules.pr.spi.GitServer;
import systems.grebe.devtools.mcp.modules.ticket.spi.HttpJson;
import systems.grebe.devtools.mcp.modules.ticket.spi.ProviderSettings;
import tools.jackson.databind.JsonNode;

import static systems.grebe.devtools.mcp.modules.ticket.spi.HttpJson.enc;
import static systems.grebe.devtools.mcp.modules.ticket.spi.HttpJson.query;
import static systems.grebe.devtools.mcp.modules.ticket.spi.HttpJson.text;

/**
 * Jenkins über die Remote-API ({@code …/api/json}): Builds von Freestyle-, Pipeline- und Multibranch-Jobs, Stages über
 * Pipeline Stage View ({@code wfapi}), Konsolen-Log, Starten mit Parametern, Abbrechen und Neustarten. Ein Projekt ist
 * der Job-Pfad über Ordner ({@code team/app}); in Multibranch-Projekten ist der Branch der letzte Teil
 * ({@code team/app/main}). Anmeldung mit Benutzer und API-Token (Basic), damit braucht es kein CSRF-Crumb.
 */
public class JenkinsCiProvider implements CiProvider {

    static final String BASE_URL = "baseUrl";
    static final String USER = "user";
    static final String TOKEN = "token";
    static final String JOBS = "jobs";

    @Override
    public String id() {
        return "jenkins";
    }

    @Override
    public String displayName() {
        return "Jenkins";
    }

    @Override
    public int priority() {
        return 30;
    }

    @Override
    public List<ConfigField> configFields() {
        return List.of(
                ConfigField.of(BASE_URL, "Server-URL", FieldType.URL)
                        .withHelp("z.B. https://jenkins.firma.de oder https://firma.de/jenkins"),
                ConfigField.of(USER, "Benutzer", FieldType.STRING)
                        .withHelp("Jenkins-Benutzer, dem das API-Token gehört."),
                ConfigField.of(TOKEN, "API-Token", FieldType.SECRET)
                        .withHelp("Benutzer → Konfigurieren → API-Token. Ohne Token nur anonym Sichtbares lesen."),
                ConfigField.of(JOBS, "Job-Zuordnung", FieldType.STRING_LIST)
                        .withHelp("Ein Eintrag je Zeile: Repository-Pfad aus dem Git-Remote = Job-Pfad, z.B. "
                                + "octo/app = team/app (Multibranch-Projekt oder Job). Damit ergibt sich der Job aus dem "
                                + "lokalen Repository."));
    }

    @Override
    public String projectHelp() {
        return "Job-Pfad über Ordner, z.B. team/app; Multibranch: team/app/main";
    }

    @Override
    public String buildHelp() {
        return "team/app#42, #42 bzw. 42 (mit Projekt), lastBuild/lastFailedBuild oder Build-URL";
    }

    @Override
    public CiSystem create(ProviderSettings s) {
        String base = HttpJson.stripSlash(s.getString(BASE_URL, ""));
        Map<String, String> headers = new LinkedHashMap<>();
        boolean auth = s.get(TOKEN).isPresent();
        s.get(TOKEN).ifPresent(t -> headers.put("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                (s.getString(USER, "") + ":" + t).getBytes(StandardCharsets.UTF_8))));
        Map<String, String> jobs = new LinkedHashMap<>();
        s.get(JOBS).map(ModuleConfig::splitLines).orElse(List.of()).forEach(line -> {
            int eq = line.indexOf('=');
            if (eq > 0) {
                jobs.put(line.substring(0, eq).trim().toLowerCase(Locale.ROOT), line.substring(eq + 1).trim());
            }
        });
        return new Jenkins(new HttpJson("Jenkins", base, headers, s.timeout(), "CI/CD"), auth, jobs);
    }

    /** Jenkins-Anbindung. Paketsichtbar für Tests. */
    static final class Jenkins implements CiSystem {

        private static final Pattern FULL_KEY = Pattern.compile("(.+?)#(\\d+|last\\w*Build)");
        private static final Pattern SHORT_KEY = Pattern.compile("#?(\\d+|last\\w*Build)");
        private static final String BUILD_TREE = "number,result,building,timestamp,duration,url,displayName,"
                + "actions[causes[shortDescription],lastBuiltRevision[SHA1,branch[name]]]";
        /** Parameternamen, über die ein Job ohne Multibranch einen Branch bekommt. */
        private static final List<String> BRANCH_PARAMS = List.of("BRANCH", "BRANCH_NAME", "GIT_BRANCH", "REF");

        private final HttpJson http;
        private final boolean authenticated;
        private final Map<String, String> jobs;
        private final String host;

        Jenkins(HttpJson http, boolean authenticated, Map<String, String> jobs) {
            this.http = http;
            this.authenticated = authenticated;
            this.jobs = jobs;
            this.host = GitServer.hostOf(http.baseUrl());
        }

        @Override
        public String id() {
            return "jenkins";
        }

        @Override
        public String instance() {
            return http.baseUrl();
        }

        @Override
        public Availability probe() {
            if (http.baseUrl().isBlank()) {
                return Availability.unavailable("Server-URL fehlt – in der DevTools-App unter Module → CI/CD → Jenkins "
                        + "eintragen.");
            }
            try {
                String version = http.get("/api/json" + query("tree", "mode")).header("X-Jenkins");
                if (!authenticated) {
                    return new Availability(true, version, null, "ohne Token – nur anonym Sichtbares, nur lesen");
                }
                JsonNode me = http.getJson("/me/api/json" + query("tree", "id,fullName"));
                return Availability.ok(version == null ? null : "Jenkins " + version,
                        HttpJson.first(text(me.path("id")), text(me.path("fullName"))));
            } catch (RuntimeException e) {
                return Availability.unavailable(e.getMessage());
            }
        }

        @Override
        public String projectOfRemote(String remoteUrl) {
            GitServer.Remote r = GitServer.parseRemote(remoteUrl);
            if (r == null || jobs.isEmpty()) {
                return null;
            }
            String path = r.path().toLowerCase(Locale.ROOT);
            String job = jobs.get(path);
            return job != null ? job : jobs.get(r.host() + "/" + path);
        }

        @Override
        public boolean ownsKey(String ref) {
            String r = ref == null ? "" : ref.trim();
            return (r.startsWith("http://") || r.startsWith("https://")) && r.contains("/job/")
                    && host.equals(GitServer.hostOf(r));
        }

        @Override
        public String projectOf(String ref, String project) {
            return ref(ref, project).job();
        }

        /** Job-Pfad und Nummer bzw. Permalink ({@code lastBuild}, …). */
        record Ref(String job, String number) {
            String path() {
                return jobPath(job) + "/" + number;
            }
        }

        static Ref ref(String key, String project) {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("Jenkins: kein Build angegeben (z.B. team/app#42 oder 42).");
            }
            String k = key.trim();
            if (k.startsWith("http://") || k.startsWith("https://")) {
                return fromUrl(k);
            }
            Matcher m = SHORT_KEY.matcher(k);
            if (m.matches()) {
                return new Ref(job(project), m.group(1));
            }
            m = FULL_KEY.matcher(k);
            if (m.matches()) {
                return new Ref(job(m.group(1)), m.group(2));
            }
            throw new IllegalArgumentException("Jenkins: '" + k + "' ist kein Build (erwartet team/app#42, #42, "
                    + "lastBuild oder Build-URL).");
        }

        /** {@code …/job/team/job/app/job/feature%252Fx/42/…} → {@code team/app/feature%2Fx}, {@code 42}. */
        private static Ref fromUrl(String url) {
            String[] parts;
            try {
                parts = URI.create(url).getRawPath().split("/");
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Jenkins: ungültige URL " + url, e);
            }
            List<String> segments = new ArrayList<>();
            for (int i = 0; i < parts.length; i++) {
                if (parts[i].equals("job") && i + 1 < parts.length) {
                    segments.add(URLDecoder.decode(parts[++i], StandardCharsets.UTF_8));
                } else if (!segments.isEmpty() && SHORT_KEY.matcher(parts[i]).matches()) {
                    return new Ref(String.join("/", segments), parts[i]);
                }
            }
            throw new IllegalArgumentException("Jenkins: URL " + url + " zeigt auf keinen Build (…/job/<Job>/<Nummer>/).");
        }

        static String job(String project) {
            String p = project == null ? "" : project.trim();
            while (p.startsWith("/")) {
                p = p.substring(1);
            }
            while (p.endsWith("/")) {
                p = p.substring(0, p.length() - 1);
            }
            if (p.isEmpty()) {
                throw new IllegalArgumentException("Jenkins: Job fehlt – als Job-Pfad angeben (z.B. team/app) oder in "
                        + "der DevTools-App eine Job-Zuordnung für das Repository eintragen.");
            }
            return p;
        }

        /** {@code team/app} → {@code /job/team/job/app}; leer = Wurzel. */
        static String jobPath(String job) {
            StringBuilder sb = new StringBuilder();
            for (String seg : job.split("/")) {
                if (!seg.isBlank()) {
                    sb.append("/job/").append(enc(seg));
                }
            }
            return sb.toString();
        }

        /** Jobname eines Branches in Multibranch-Projekten ({@code feature/x} → {@code feature%2Fx}). */
        static String branchJob(String branch) {
            return branch.trim().replace("%", "%25").replace("/", "%2F");
        }

        private void requireToken(String what) {
            if (!authenticated) {
                throw new IllegalStateException("Jenkins: " + what + " braucht Benutzer und API-Token – in der "
                        + "DevTools-App unter Module → CI/CD → Jenkins eintragen.");
            }
        }

        // ------------------------------------------------------------------ Job-Arten

        private JsonNode jobInfo(String job) {
            return http.getJson(jobPath(job) + "/api/json" + query("tree", "_class,name,url,buildable,"
                    + "property[parameterDefinitions[name]],jobs[name,displayName,url,color,_class,"
                    + "lastBuild[" + BUILD_TREE + "]]"));
        }

        private static boolean multibranch(JsonNode info) {
            return String.valueOf(text(info.path("_class"))).contains("MultiBranchProject");
        }

        /** Ordner, Organisationsordner – Jobs ohne eigene Builds. */
        private static boolean folder(JsonNode info) {
            return !multibranch(info) && info.path("jobs").isArray() && !info.path("buildable").asBoolean(false);
        }

        // ------------------------------------------------------------------ Lesen

        @Override
        public List<Build> builds(BuildQuery q) {
            String job = job(q.project());
            JsonNode info = jobInfo(job);
            if (multibranch(info)) {
                if (q.ref() != null) {
                    return buildsOf(job + "/" + branchJob(q.ref()), null, q.ref(), q);
                }
                // ohne Branch: der letzte Build je Branch
                List<Build> out = new ArrayList<>();
                for (JsonNode child : info.path("jobs")) {
                    JsonNode last = child.path("lastBuild");
                    if (last.isObject()) {
                        Build b = build(last, job + "/" + text(child.path("name")), decode(text(child.path("name"))));
                        if (q.status() == null || q.status() == b.status()) {
                            out.add(b);
                        }
                    }
                }
                out.sort(Comparator.comparing((Build b) -> b.started() == null ? "" : b.started()).reversed());
                return out.size() > q.limit() ? out.subList(0, q.limit()) : out;
            }
            if (folder(info)) {
                throw new IllegalArgumentException("Jenkins: '" + job + "' ist ein Ordner – einen Job darin angeben "
                        + "(siehe ci_workflows project=" + job + ").");
            }
            return buildsOf(job, q.ref(), null, q);
        }

        /**
         * Builds eines Jobs.
         *
         * @param branch Filter über die gebaute Git-Revision oder {@code null}
         * @param fixedRef Branch des Jobs selbst (Multibranch) oder {@code null}
         */
        private List<Build> buildsOf(String job, String branch, String fixedRef, BuildQuery q) {
            boolean filtered = branch != null || q.status() != null;
            int fetch = filtered ? 100 : q.limit();
            JsonNode res = http.getJson(jobPath(job) + "/api/json" + query("tree", "builds[" + BUILD_TREE + "]{0,"
                    + fetch + "}"));
            List<Build> out = new ArrayList<>();
            for (JsonNode n : res.path("builds")) {
                Build b = build(n, job, fixedRef);
                if (q.status() != null && b.status() != q.status()) {
                    continue;
                }
                if (branch != null && !branch.equals(b.ref())) {
                    continue;
                }
                out.add(b);
                if (out.size() >= q.limit()) {
                    break;
                }
            }
            return out;
        }

        /**
         * @param ref Branch, falls er sich aus dem Job ergibt (Multibranch), sonst aus der gebauten Revision
         */
        private static Build build(JsonNode n, String job, String ref) {
            String number = text(n.path("number"));
            boolean building = n.path("building").asBoolean(false);
            String result = text(n.path("result"));
            Status status = building ? Status.RUNNING : status(result);
            String commit = null;
            String branch = ref;
            String trigger = null;
            for (JsonNode a : n.path("actions")) {
                JsonNode rev = a.path("lastBuiltRevision");
                if (rev.isObject() && commit == null) {
                    commit = text(rev.path("SHA1"));
                    if (branch == null && rev.path("branch").isArray() && !rev.path("branch").isEmpty()) {
                        branch = shortBranch(text(rev.path("branch").get(0).path("name")));
                    }
                }
                if (a.path("causes").isArray() && !a.path("causes").isEmpty() && trigger == null) {
                    trigger = text(a.path("causes").get(0).path("shortDescription"));
                }
            }
            long ts = n.path("timestamp").asLong(0);
            Long duration = building ? (ts > 0 ? (System.currentTimeMillis() - ts) / 1000 : null)
                    : n.path("duration").isNumber() ? n.path("duration").asLong() / 1000 : null;
            String display = text(n.path("displayName"));
            return new Build(job + "#" + number, status, building ? "BUILDING" : result,
                    display == null || display.equals("#" + number) ? null : display, branch, commit, trigger,
                    ts > 0 ? iso(ts) : null, duration, text(n.path("url")));
        }

        static Status status(String result) {
            if (result == null) {
                return Status.UNKNOWN;
            }
            return switch (result.toUpperCase(Locale.ROOT)) {
                case "SUCCESS" -> Status.SUCCESS;
                case "FAILURE", "FAILED" -> Status.FAILED;
                case "UNSTABLE" -> Status.UNSTABLE;
                case "ABORTED" -> Status.CANCELED;
                case "NOT_BUILT", "NOT_EXECUTED", "SKIPPED" -> Status.SKIPPED;
                case "IN_PROGRESS", "BUILDING" -> Status.RUNNING;
                case "QUEUED", "PENDING" -> Status.QUEUED;
                case "PAUSED_PENDING_INPUT" -> Status.MANUAL;
                default -> Status.UNKNOWN;
            };
        }

        /** {@code refs/remotes/origin/main}, {@code origin/main} → {@code main}. */
        static String shortBranch(String name) {
            if (name == null) {
                return null;
            }
            String n = name.startsWith("refs/remotes/") ? name.substring("refs/remotes/".length())
                    : name.startsWith("refs/heads/") ? name.substring("refs/heads/".length()) : name;
            return n.startsWith("origin/") ? n.substring("origin/".length()) : n;
        }

        private static String decode(String jobName) {
            return jobName == null ? null : URLDecoder.decode(jobName, StandardCharsets.UTF_8);
        }

        private static String iso(long millis) {
            return Instant.ofEpochMilli(millis).truncatedTo(ChronoUnit.SECONDS).toString();
        }

        @Override
        public BuildDetails get(String ref, String project) {
            Ref r = ref(ref, project);
            JsonNode n = buildJson(r, "number,result,building,timestamp,duration,url,displayName,description,"
                    + "actions[causes[shortDescription],parameters[name,value],lastBuiltRevision[SHA1,branch[name]]],"
                    + "changeSets[items[msg,author[fullName]]]");
            Build b = build(n, r.job(), multibranchRef(r.job()));
            Map<String, String> fields = new LinkedHashMap<>();
            Map<String, String> params = parameters(n);
            if (!params.isEmpty()) {
                fields.put("Parameter", shown(params));
            }
            List<String> changes = new ArrayList<>();
            for (JsonNode cs : n.path("changeSets")) {
                for (JsonNode item : cs.path("items")) {
                    String msg = text(item.path("msg"));
                    if (msg != null) {
                        changes.add(msg + " (" + HttpJson.first(text(item.path("author").path("fullName")), "?") + ")");
                    }
                }
            }
            if (!changes.isEmpty()) {
                fields.put("Änderungen", String.join("; ", changes.size() > 5 ? changes.subList(0, 5) : changes)
                        + (changes.size() > 5 ? " … (+" + (changes.size() - 5) + ")" : ""));
            }
            if (text(n.path("description")) != null) {
                fields.put("Beschreibung", text(n.path("description")));
            }
            return new BuildDetails(b, stages(r), fields);
        }

        /** Branch aus dem Job-Pfad, wenn der übergeordnete Job ein Multibranch-Projekt ist (am Pfad nicht erkennbar). */
        private String multibranchRef(String job) {
            int slash = job.lastIndexOf('/');
            if (slash < 0) {
                return null;
            }
            try {
                JsonNode parent = http.getJson(jobPath(job.substring(0, slash)) + "/api/json" + query("tree", "_class"));
                return multibranch(parent) ? decode(job.substring(slash + 1)) : null;
            } catch (RuntimeException e) {
                return null;
            }
        }

        private JsonNode buildJson(Ref r, String tree) {
            try {
                return http.getJson(r.path() + "/api/json" + query("tree", tree));
            } catch (HttpJson.StatusException e) {
                if (e.status() == 404) {
                    JsonNode info;
                    try {
                        info = jobInfo(r.job());
                    } catch (RuntimeException ignored) {
                        throw e;
                    }
                    if (multibranch(info)) {
                        throw new IllegalArgumentException("Jenkins: '" + r.job() + "' ist ein Multibranch-Projekt – "
                                + "den Branch-Job angeben, z.B. " + r.job() + "/main#" + r.number()
                                + " (Schlüssel aus ci_list).");
                    }
                }
                throw e;
            }
        }

        private static Map<String, String> parameters(JsonNode build) {
            Map<String, String> out = new LinkedHashMap<>();
            for (JsonNode a : build.path("actions")) {
                for (JsonNode p : a.path("parameters")) {
                    String name = text(p.path("name"));
                    if (name != null) {
                        JsonNode v = p.path("value");
                        out.put(name, v.isMissingNode() || v.isNull() ? "" : v.isValueNode() ? v.asString() : v.toString());
                    }
                }
            }
            return out;
        }

        private static boolean secret(String name) {
            String n = name.toUpperCase(Locale.ROOT);
            return n.contains("PASS") || n.contains("TOKEN") || n.contains("SECRET") || n.contains("KEY");
        }

        /** Stages laut Pipeline Stage View; ohne das Plugin oder bei Freestyle-Jobs keine. */
        private List<Job> stages(Ref r) {
            JsonNode d;
            try {
                d = http.getJson(r.path() + "/wfapi/describe");
            } catch (RuntimeException e) {
                return List.of();
            }
            List<Job> out = new ArrayList<>();
            for (JsonNode s : d.path("stages")) {
                Long dur = s.path("durationMillis").isNumber() ? s.path("durationMillis").asLong() / 1000 : null;
                String err = text(s.path("error").path("message"));
                out.add(new Job(null, text(s.path("name")), null, status(text(s.path("status"))), dur, null, err));
            }
            return out;
        }

        @Override
        public Log log(String ref, String project, String job) {
            Ref r = ref(ref, project);
            String text;
            try {
                text = http.getText(r.path() + "/consoleText");
            } catch (HttpJson.StatusException e) {
                if (e.status() == 404) {
                    buildJson(r, "number");
                }
                throw e;
            }
            String source = r.job() + "#" + r.number() + " Konsolen-Log"
                    + (job == null ? "" : " (Jenkins hat kein Log je Stage – 'grep' grenzt ein)");
            return new Log(source, text, http.baseUrl() + r.path() + "/console");
        }

        @Override
        public List<Workflow> workflows(String project) {
            String job = project == null ? "" : project.trim().replaceAll("^/+|/+$", "");
            JsonNode res = http.getJson(jobPath(job) + "/api/json" + query("tree", "_class,jobs[name,displayName,url,"
                    + "color,_class]"));
            if (!res.path("jobs").isArray()) {
                throw new IllegalArgumentException("Jenkins: '" + job + "' ist ein Job ohne Unterjobs – direkt mit "
                        + "ci_start project=" + job + " starten.");
            }
            boolean branches = multibranch(res);
            List<Workflow> out = new ArrayList<>();
            for (JsonNode j : res.path("jobs")) {
                String name = text(j.path("name"));
                String cls = String.valueOf(text(j.path("_class")));
                String state = cls.contains("MultiBranchProject") ? "Multibranch"
                        : cls.contains("Folder") ? "Ordner" : color(text(j.path("color")));
                out.add(new Workflow(job.isEmpty() ? name : job + "/" + name,
                        branches ? decode(name) : text(j.path("displayName")), null, state, text(j.path("url"))));
            }
            return out;
        }

        /** Ampel-Farbe des letzten Builds → Zustand. */
        static String color(String color) {
            if (color == null) {
                return null;
            }
            boolean running = color.endsWith("_anime");
            String c = running ? color.substring(0, color.length() - 6) : color;
            String s = switch (c) {
                case "blue" -> "success";
                case "red" -> "failed";
                case "yellow" -> "unstable";
                case "aborted" -> "canceled";
                case "disabled" -> "deaktiviert";
                case "notbuilt" -> "nie gebaut";
                default -> c;
            };
            return running ? s + ", läuft" : s;
        }

        // ------------------------------------------------------------------ Steuern

        @Override
        public WriteResult start(String project, StartRequest req) {
            requireToken("Starten");
            String job = job(project);
            JsonNode info = jobInfo(job);
            String ref = req.ref();
            if (multibranch(info)) {
                if (ref == null) {
                    throw new IllegalArgumentException("Jenkins: '" + job + "' ist ein Multibranch-Projekt – 'branch' "
                            + "angeben (z.B. current).");
                }
                job = job + "/" + branchJob(ref);
                info = jobInfo(job);
                ref = null;
            } else if (folder(info)) {
                throw new IllegalArgumentException("Jenkins: '" + job + "' ist ein Ordner – einen Job darin angeben "
                        + "(siehe ci_workflows project=" + job + ").");
            }
            List<String> defs = new ArrayList<>();
            for (JsonNode p : info.path("property")) {
                p.path("parameterDefinitions").forEach(d -> defs.add(text(d.path("name"))));
            }
            Map<String, String> params = new LinkedHashMap<>(req.parameters());
            if (ref != null) {
                String branchParam = defs.stream().filter(d -> d != null
                        && BRANCH_PARAMS.contains(d.toUpperCase(Locale.ROOT))).findFirst().orElse(null);
                if (branchParam == null) {
                    throw new IllegalArgumentException("Jenkins: Job '" + job + "' hat keinen Branch-Parameter "
                            + BRANCH_PARAMS + " – 'branch' weglassen oder den passenden Parameter setzen. Parameter: "
                            + (defs.isEmpty() ? "keine" : defs));
                }
                params.putIfAbsent(branchParam, ref);
            }
            for (String name : params.keySet()) {
                if (!defs.contains(name)) {
                    throw new IllegalArgumentException("Jenkins: Job '" + job + "' kennt den Parameter '" + name
                            + "' nicht. Parameter: " + (defs.isEmpty() ? "keine" : defs));
                }
            }
            return trigger(job, params, !defs.isEmpty(), params.isEmpty() ? "gestartet"
                    : "gestartet mit " + shown(params));
        }

        /** {@code A=1, TOKEN=****}. */
        private static String shown(Map<String, String> params) {
            List<String> out = new ArrayList<>();
            params.forEach((k, v) -> out.add(k + "=" + (secret(k) ? "****" : v)));
            return String.join(", ", out);
        }

        /** @param parameterized ob der Job Parameter hat ({@code buildWithParameters} statt {@code build}) */
        private WriteResult trigger(String job, Map<String, String> params, boolean parameterized, String what) {
            List<Object> kv = new ArrayList<>();
            params.forEach((k, v) -> {
                kv.add(k);
                kv.add(v);
            });
            String path = jobPath(job) + (parameterized ? "/buildWithParameters" + query(kv.toArray()) : "/build");
            HttpResponse<String> res = http.requestText("POST", path);
            String queue = res.headers().firstValue("Location").orElse(null);
            String jobUrl = http.baseUrl() + jobPath(job) + "/";
            if (queue != null) {
                try {
                    JsonNode exe = http.getJson(HttpJson.stripSlash(queue) + "/api/json").path("executable");
                    if (exe.isObject() && text(exe.path("number")) != null) {
                        return new WriteResult(job + "#" + text(exe.path("number")), "Build " + what,
                                text(exe.path("url")));
                    }
                } catch (RuntimeException ignored) {
                    // Queue-Item noch nicht lesbar – dann ohne Nummer melden
                }
                Matcher m = Pattern.compile("/queue/item/(\\d+)").matcher(queue);
                return new WriteResult(job, what + " – in der Warteschlange" + (m.find() ? " (Queue-Item " + m.group(1)
                        + ")" : "") + ", Build-Nummer in Kürze über ci_list.", jobUrl);
            }
            return new WriteResult(job, what + " – Build-Nummer in Kürze über ci_list.", jobUrl);
        }

        @Override
        public WriteResult cancel(String ref, String project) {
            requireToken("Abbrechen");
            Ref r = ref(ref, project);
            JsonNode n = buildJson(r, "number,building,result,url");
            String key = r.job() + "#" + text(n.path("number"));
            if (!n.path("building").asBoolean(false)) {
                return new WriteResult(key, "läuft nicht mehr (" + HttpJson.first(text(n.path("result")), "?")
                        + ") – nichts abzubrechen.", text(n.path("url")));
            }
            http.requestText("POST", jobPath(r.job()) + "/" + text(n.path("number")) + "/stop");
            return new WriteResult(key, "Abbruch angefordert.", text(n.path("url")));
        }

        @Override
        public WriteResult retry(String ref, String project, boolean failedOnly) {
            requireToken("Wiederholen");
            Ref r = ref(ref, project);
            JsonNode n = buildJson(r, "number,actions[parameters[name,value]]");
            Map<String, String> params = parameters(n);
            String what = "neu gestartet" + (params.isEmpty() ? "" : " mit denselben Parametern") + " (Wiederholung von #"
                    + text(n.path("number")) + (failedOnly ? "; Jenkins wiederholt immer den ganzen Build" : "") + ")";
            return trigger(r.job(), params, !params.isEmpty(), what);
        }
    }
}
