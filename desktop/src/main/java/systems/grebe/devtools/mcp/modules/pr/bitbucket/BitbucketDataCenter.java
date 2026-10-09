package systems.grebe.devtools.mcp.modules.pr.bitbucket;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import systems.grebe.devtools.mcp.modules.pr.spi.GitServer;
import systems.grebe.devtools.mcp.modules.ticket.spi.HttpJson;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import static systems.grebe.devtools.mcp.modules.ticket.spi.HttpJson.query;
import static systems.grebe.devtools.mcp.modules.ticket.spi.HttpJson.text;

/** Bitbucket Data Center/Server über die REST API 1.0. Paketsichtbar für Tests. */
final class BitbucketDataCenter implements GitServer {

    private static final Pattern FULL_KEY = Pattern.compile("(~?[\\w.-]+)/([\\w.-]+)#(\\d+)");
    private static final Pattern SHORT_KEY = Pattern.compile("[#!]?(\\d+)");
    private static final Pattern URL_PATH = Pattern.compile("(projects|users)/([\\w.~-]+)/repos/([\\w.-]+)/pull-requests/(\\d+).*");
    private static final int MAX_PAGES = 10;

    private final HttpJson http;
    private final String webBase;
    private final String webHost;
    private final String contextPath;
    private final boolean authenticated;
    private volatile String me;

    BitbucketDataCenter(HttpJson http, String webBase, boolean authenticated) {
        this.http = http;
        this.webBase = webBase;
        this.webHost = GitServer.hostOf(webBase);
        this.contextPath = GitServer.pathOf(webBase);
        this.authenticated = authenticated;
    }

    @Override
    public String id() {
        return "bitbucket";
    }

    @Override
    public String instance() {
        return webBase;
    }

    @Override
    public Availability probe() {
        try {
            HttpJson.Response res = http.get("/rest/api/1.0/application-properties");
            String user = res.header("X-AUSERNAME");
            if (user != null) {
                me = user;
            }
            String version = "Data Center " + text(res.body().path("version"));
            return authenticated ? Availability.ok(version, user)
                    : new Availability(true, version, null, "anonym – nur öffentliche Repositories, nur lesen");
        } catch (RuntimeException e) {
            return Availability.unavailable(e.getMessage());
        }
    }

    private String me() {
        if (me == null) {
            probe();
        }
        if (me == null) {
            throw new IllegalStateException("Bitbucket: angemeldeten Benutzer nicht ermittelbar – Token prüfen.");
        }
        return me;
    }

    @Override
    public String projectOfRemote(String remoteUrl) {
        Remote r = GitServer.parseRemote(remoteUrl);
        if (r == null || !r.host().equals(webHost)) {
            return null;
        }
        String p = r.path();
        if (!r.scheme().equals("ssh")) {
            if (!contextPath.isEmpty() && p.startsWith(contextPath + "/")) {
                p = p.substring(contextPath.length() + 1);
            }
            if (!p.startsWith("scm/")) {
                return null;
            }
            p = p.substring(4);
        }
        String[] parts = p.split("/");
        return parts.length == 2 ? normalize(parts[0]) + "/" + parts[1] : null;
    }

    /** Projektschlüssel in Großbuchstaben, persönliche Projekte ({@code ~benutzer}) unverändert. */
    private static String normalize(String projectKey) {
        return projectKey.startsWith("~") ? projectKey : projectKey.toUpperCase(Locale.ROOT);
    }

    @Override
    public boolean ownsKey(String ref) {
        return ref != null && ref.trim().startsWith(webBase + "/")
                && URL_PATH.matcher(ref.trim().substring(webBase.length() + 1)).matches();
    }

    @Override
    public String projectOf(String ref, String project) {
        return ref(ref, project).repo();
    }

    record Ref(String projectKey, String slug, int id) {
        String repo() {
            return projectKey + "/" + slug;
        }

        String key() {
            return repo() + "#" + id;
        }

        String repoPath() {
            return "/rest/api/1.0/projects/" + projectKey + "/repos/" + slug;
        }

        String path() {
            return repoPath() + "/pull-requests/" + id;
        }
    }

    Ref ref(String key, String project) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Bitbucket: kein Pull Request angegeben (z.B. PROJ/repo#12 oder 12).");
        }
        String k = key.trim();
        if (k.startsWith(webBase + "/")) {
            Matcher m = URL_PATH.matcher(k.substring(webBase.length() + 1));
            if (m.matches()) {
                String owner = "users".equals(m.group(1)) ? "~" + m.group(2) : normalize(m.group(2));
                return new Ref(owner, m.group(3), Integer.parseInt(m.group(4)));
            }
        }
        Matcher m = FULL_KEY.matcher(k);
        if (m.matches()) {
            return new Ref(normalize(m.group(1)), m.group(2), Integer.parseInt(m.group(3)));
        }
        m = SHORT_KEY.matcher(k);
        if (m.matches()) {
            String[] repo = repo(project);
            return new Ref(repo[0], repo[1], Integer.parseInt(m.group(1)));
        }
        throw new IllegalArgumentException("Bitbucket: '" + k + "' ist kein Pull Request (erwartet PROJ/repo#12, #12 "
                + "oder Pull-Request-URL).");
    }

    static String[] repo(String project) {
        if (project == null || !project.trim().matches("~?[\\w.-]+/[\\w.-]+")) {
            throw new IllegalArgumentException("Bitbucket: Repository fehlt oder ist ungültig ('" + project
                    + "') – als PROJEKT/repo angeben oder ein lokales Repository mit Bitbucket-Remote wählen.");
        }
        String[] parts = project.trim().split("/");
        return new String[] {normalize(parts[0]), parts[1]};
    }

    private static String repoPath(String project) {
        String[] r = repo(project);
        return "/rest/api/1.0/projects/" + r[0] + "/repos/" + r[1];
    }

    private void requireToken(String what) {
        if (!authenticated) {
            throw new IllegalStateException("Bitbucket: " + what + " braucht ein Token – in der DevTools-App unter "
                    + "Module → Pull Requests eintragen.");
        }
    }

    /** Alle Seiten ({@code nextPageStart}), höchstens {@code max} Einträge. */
    private List<JsonNode> pages(String path, int max) {
        List<JsonNode> out = new ArrayList<>();
        String sep = path.contains("?") ? "&" : "?";
        String start = "0";
        for (int i = 0; start != null && i < MAX_PAGES && out.size() < max; i++) {
            JsonNode page = http.getJson(path + sep + "start=" + start);
            page.path("values").forEach(out::add);
            start = page.path("isLastPage").asBoolean(true) ? null : text(page.path("nextPageStart"));
        }
        return out.size() > max ? out.subList(0, max) : out;
    }

    private static String date(JsonNode millis) {
        return millis.isNumber() ? Instant.ofEpochMilli(millis.asLong()).toString() : null;
    }

    private static String user(JsonNode u) {
        return HttpJson.first(text(u.path("name")), text(u.path("slug")), text(u.path("displayName")));
    }

    // ------------------------------------------------------------------ Lesen

    @Override
    public List<PullRequest> list(PrQuery q) {
        String base = repoPath(q.project());
        String state = switch (q.state()) {
            case OPEN -> "OPEN";
            case MERGED -> "MERGED";
            case CLOSED -> "DECLINED";
            case ALL -> "ALL";
        };
        String author = q.author() == null ? null : GitServer.isMe(q.author()) ? me() : q.author();
        int limit = Math.max(1, Math.min(q.limit(), 100));
        boolean filtered = author != null || q.source() != null && q.target() != null;
        String at = q.source() != null ? "refs/heads/" + q.source() : q.target() != null ? "refs/heads/" + q.target() : null;
        String direction = q.source() != null ? "OUTGOING" : q.target() != null ? "INCOMING" : null;
        String[] r = repo(q.project());
        List<PullRequest> out = new ArrayList<>();
        for (JsonNode n : pages(base + "/pull-requests" + query("state", state, "order", "NEWEST", "at", at,
                "direction", direction, "limit", filtered ? 100 : limit), filtered ? 500 : limit)) {
            if (author != null && !author.equalsIgnoreCase(user(n.path("author").path("user")))) {
                continue;
            }
            if (q.target() != null && !q.target().equals(text(n.path("toRef").path("displayId")))) {
                continue;
            }
            out.add(pr(n, r[0] + "/" + r[1]));
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    private static PullRequest pr(JsonNode n, String repo) {
        String state = text(n.path("state"));
        state = state == null ? null : switch (state) {
            case "OPEN" -> "open";
            case "MERGED" -> "merged";
            default -> "closed";
        };
        return new PullRequest(repo + "#" + text(n.path("id")), text(n.path("title")), state,
                n.path("draft").asBoolean(false), user(n.path("author").path("user")),
                text(n.path("fromRef").path("displayId")), text(n.path("toRef").path("displayId")),
                date(n.path("updatedDate")), text(n.path("links").path("self").path(0).path("href")));
    }

    @Override
    public PrDetails get(String key, String project) {
        Ref r = ref(key, project);
        JsonNode n = http.getJson(r.path());
        List<String> reviewers = new ArrayList<>();
        List<String> approved = new ArrayList<>();
        List<String> needsWork = new ArrayList<>();
        for (JsonNode rv : n.path("reviewers")) {
            String name = user(rv.path("user"));
            reviewers.add(name);
            if ("APPROVED".equals(text(rv.path("status"))) || rv.path("approved").asBoolean(false)) {
                approved.add(name);
            } else if ("NEEDS_WORK".equals(text(rv.path("status")))) {
                needsWork.add(name);
            }
        }
        Map<String, String> fields = new LinkedHashMap<>();
        if (!needsWork.isEmpty()) {
            fields.put("Überarbeitung nötig", String.join(", ", needsWork));
        }
        JsonNode props = n.path("properties");
        fields.put("Kommentare", text(props.path("commentCount")));
        fields.put("Offene Aufgaben", text(props.path("openTaskCount")));
        String head = text(n.path("fromRef").path("latestCommit"));
        return new PrDetails(pr(n, r.repo()), text(n.path("description")), date(n.path("createdDate")), reviewers,
                approved, mergeStatus(r, n), head, head == null ? List.of() : checks(head), fields);
    }

    private String mergeStatus(Ref r, JsonNode pr) {
        String state = text(pr.path("state"));
        if ("MERGED".equals(state)) {
            return "gemergt";
        }
        if (!"OPEN".equals(state)) {
            return "geschlossen (" + state + ")";
        }
        try {
            JsonNode m = http.getJson(r.path() + "/merge");
            if (m.path("canMerge").asBoolean(false)) {
                return "mergeable";
            }
            List<String> reasons = new ArrayList<>();
            if (m.path("conflicted").asBoolean(false)) {
                reasons.add("Konflikte mit dem Ziel-Branch");
            }
            m.path("vetoes").forEach(v -> reasons.add(HttpJson.first(text(v.path("summaryMessage")),
                    text(v.path("detailedMessage")))));
            return "blockiert: " + (reasons.isEmpty() ? text(m.path("outcome")) : String.join("; ", reasons));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private List<Check> checks(String sha) {
        List<Check> out = new ArrayList<>();
        try {
            for (JsonNode s : pages("/rest/build-status/1.0/commits/" + sha + query("limit", 100), 100)) {
                out.add(new Check(HttpJson.first(text(s.path("name")), text(s.path("key"))), text(s.path("state")),
                        text(s.path("url"))));
            }
        } catch (RuntimeException e) {
            out.add(new Check("Build-Status", "nicht lesbar: " + e.getMessage(), null));
        }
        return out;
    }

    @Override
    public List<FileChange> diff(String key, String project) {
        Ref r = ref(key, project);
        Map<String, JsonNode> diffs = new LinkedHashMap<>();
        http.getJson(r.path() + "/diff" + query("contextLines", 3, "withComments", false)).path("diffs")
                .forEach(d -> diffs.put(HttpJson.first(text(d.path("destination").path("toString")),
                        text(d.path("source").path("toString"))), d));
        List<FileChange> out = new ArrayList<>();
        for (JsonNode c : pages(r.path() + "/changes" + query("limit", 1000), 3000)) {
            String path = text(c.path("path").path("toString"));
            String type = text(c.path("type"));
            String status = type == null ? null : switch (type) {
                case "ADD" -> "added";
                case "DELETE" -> "removed";
                case "MOVE" -> "renamed";
                case "COPY" -> "copied";
                default -> "modified";
            };
            JsonNode d = diffs.get(path);
            int[] counts = new int[] {-1, -1};
            String patch = d == null ? null : unified(d, counts);
            out.add(new FileChange(path, "renamed".equals(status) ? text(c.path("srcPath").path("toString")) : null,
                    status, counts[0], counts[1], patch));
        }
        return out;
    }

    /** Wandelt den JSON-Diff einer Datei in Unified-Diff-Hunks; zählt dabei hinzugefügte und entfernte Zeilen. */
    static String unified(JsonNode diff, int[] counts) {
        StringBuilder sb = new StringBuilder();
        int add = 0;
        int del = 0;
        for (JsonNode h : diff.path("hunks")) {
            sb.append("@@ -").append(h.path("sourceLine").asInt()).append(',').append(h.path("sourceSpan").asInt())
                    .append(" +").append(h.path("destinationLine").asInt()).append(',')
                    .append(h.path("destinationSpan").asInt()).append(" @@\n");
            for (JsonNode seg : h.path("segments")) {
                String type = text(seg.path("type"));
                char prefix = "ADDED".equals(type) ? '+' : "REMOVED".equals(type) ? '-' : ' ';
                for (JsonNode line : seg.path("lines")) {
                    sb.append(prefix).append(line.path("line").asString("")).append('\n');
                    if (prefix == '+') {
                        add++;
                    } else if (prefix == '-') {
                        del++;
                    }
                }
            }
        }
        counts[0] = add;
        counts[1] = del;
        return sb.isEmpty() ? null : sb.toString().stripTrailing();
    }

    @Override
    public List<Thread> threads(String key, String project) {
        Ref r = ref(key, project);
        List<Thread> out = new ArrayList<>();
        for (JsonNode a : pages(r.path() + "/activities" + query("limit", 250), 2500)) {
            if (!"COMMENTED".equals(text(a.path("action"))) || !"ADDED".equals(text(a.path("commentAction")))) {
                continue;
            }
            JsonNode c = a.path("comment");
            List<Comment> comments = new ArrayList<>();
            collect(c, comments);
            JsonNode anchor = a.path("commentAnchor").isObject() ? a.path("commentAnchor") : c.path("anchor");
            boolean code = anchor.isObject() && text(anchor.path("path")) != null;
            boolean task = "BLOCKER".equals(text(c.path("severity")));
            Boolean resolved = task ? "RESOLVED".equals(text(c.path("state")))
                    : c.has("threadResolved") ? c.path("threadResolved").asBoolean(false) : null;
            // Anker ohne Zeile = Kommentar zur ganzen Datei
            boolean line = code && anchor.path("line").isNumber();
            out.add(new Thread(text(c.path("id")), task ? "Aufgabe" : line ? "Code" : code ? "Datei" : "Kommentar",
                    resolved, code ? text(anchor.path("path")) : null, line ? anchor.path("line").asInt() : null,
                    code && anchor.path("orphaned").asBoolean(false), comments));
        }
        out.sort(Comparator.comparing(t -> String.valueOf(t.comments().getFirst().created())));
        return out;
    }

    private static void collect(JsonNode c, List<Comment> out) {
        out.add(new Comment(text(c.path("id")), user(c.path("author")), date(c.path("createdDate")), text(c.path("text")),
                CodeInsights.integration(c.path("author"))));
        c.path("comments").forEach(child -> collect(child, out));
    }

    /** Code-Insights-Berichte zum letzten Commit des Quell-Branches samt Annotations. */
    @Override
    public List<Insight> insights(String key, String project) {
        Ref r = ref(key, project);
        String head = text(http.getJson(r.path()).path("fromRef").path("latestCommit"));
        List<Insight> out = new ArrayList<>();
        if (head == null) {
            return out;
        }
        String base = "/rest/insights/1.0/projects/" + r.projectKey() + "/repos/" + r.slug() + "/commits/" + head
                + "/reports";
        for (JsonNode rep : pages(base + query("limit", 100), 100)) {
            String id = text(rep.path("key"));
            JsonNode res = http.getJson(base + "/" + HttpJson.enc(id) + "/annotations");
            JsonNode list = res.isArray() ? res : res.path("annotations");
            List<Annotation> annotations = new ArrayList<>();
            for (JsonNode a : list) {
                JsonNode line = a.path("line");
                annotations.add(new Annotation(text(a.path("path")), line.isNumber() && line.asInt() > 0
                        ? line.asInt() : null, text(a.path("severity")), text(a.path("type")), text(a.path("message")),
                        text(a.path("link"))));
            }
            out.add(new Insight(id, text(rep.path("title")), text(rep.path("reporter")), text(rep.path("result")),
                    text(rep.path("details")), text(rep.path("link")), CodeInsights.data(rep.path("data")), annotations,
                    res.path("totalCount").asInt(annotations.size())));
        }
        return out;
    }

    // ------------------------------------------------------------------ Schreiben

    private static ObjectNode branchRef(String branch, String[] repo) {
        ObjectNode o = HttpJson.object();
        o.put("id", branch.startsWith("refs/") ? branch : "refs/heads/" + branch);
        ObjectNode rep = o.putObject("repository");
        rep.put("slug", repo[1]);
        rep.putObject("project").put("key", repo[0]);
        return o;
    }

    private String defaultBranch(String project) {
        String base = repoPath(project);
        try {
            return text(http.getJson(base + "/default-branch").path("displayId"));
        } catch (HttpJson.StatusException e) {
            if (e.status() != 404) {
                throw e;
            }
            return text(http.getJson(base + "/branches/default").path("displayId"));
        }
    }

    @Override
    public WriteResult create(String project, NewPullRequest p) {
        requireToken("Anlegen");
        String[] repo = repo(project);
        String target = p.target() != null ? p.target() : defaultBranch(project);
        ObjectNode body = HttpJson.object();
        body.put("title", p.title());
        if (p.description() != null) {
            body.put("description", p.description());
        }
        if (p.draft()) {
            body.put("draft", true);
        }
        body.set("fromRef", branchRef(p.source(), repo));
        body.set("toRef", branchRef(target, repo));
        var reviewers = body.putArray("reviewers");
        p.reviewers().forEach(u -> reviewers.addObject().putObject("user").put("name", u.startsWith("@") ? u.substring(1) : u));
        JsonNode n;
        try {
            n = http.post(repoPath(project) + "/pull-requests", body).body();
        } catch (HttpJson.StatusException e) {
            if (e.status() == 409) {
                throw new IllegalStateException("Bitbucket: für " + p.source() + " → " + target + " gibt es schon einen "
                        + "offenen Pull Request – mit pr_list (source=" + p.source() + ") finden. " + e.getMessage(), e);
            }
            if (e.status() == 404 || e.getMessage().contains("fromRef")) {
                throw new IllegalStateException("Bitbucket: Branch '" + p.source() + "' ist auf dem Server nicht "
                        + "vorhanden – zuerst pushen (pr_push). " + e.getMessage(), e);
            }
            throw e;
        }
        return new WriteResult(repo[0] + "/" + repo[1] + "#" + text(n.path("id")), (p.draft() ? "Entwurf" : "Pull Request")
                + " angelegt: " + p.source() + " → " + target, text(n.path("links").path("self").path(0).path("href")));
    }

    @Override
    public WriteResult update(String key, String project, PrUpdate u) {
        requireToken("Bearbeiten");
        Ref r = ref(key, project);
        JsonNode pr = http.getJson(r.path());
        ObjectNode body = HttpJson.object();
        body.put("version", pr.path("version").asInt());
        body.put("title", u.title() != null ? u.title() : text(pr.path("title")));
        String description = u.description() != null ? u.description() : text(pr.path("description"));
        if (description != null) {
            body.put("description", description);
        }
        if (u.target() != null) {
            body.set("toRef", branchRef(u.target(), new String[] {r.projectKey(), r.slug()}));
        }
        // Reviewer mitschicken, sonst entfernt Bitbucket sie
        var reviewers = body.putArray("reviewers");
        pr.path("reviewers").forEach(rv -> reviewers.addObject().putObject("user")
                .put("name", text(rv.path("user").path("name"))));
        JsonNode n = http.put(r.path(), body).body();
        return new WriteResult(r.key(), "aktualisiert", text(n.path("links").path("self").path(0).path("href")));
    }

    @Override
    public WriteResult comment(String key, String project, NewComment c) {
        requireToken("Kommentieren");
        Ref r = ref(key, project);
        ObjectNode body = HttpJson.object();
        body.put("text", c.body());
        if (c.inline()) {
            if (c.line() != null && c.line() < 1) {
                throw new IllegalArgumentException("Bitbucket: 'line' muss ≥ 1 sein (Zeile der neuen Fassung).");
            }
            // ohne Zeile: Kommentar zur ganzen Datei
            ObjectNode anchor = body.putObject("anchor");
            anchor.put("path", c.path());
            if (!c.fileLevel()) {
                anchor.put("line", c.line());
                anchor.put("lineType", lineType(r, c.path(), c.line()));
                anchor.put("fileType", "TO");
            }
            anchor.put("diffType", "EFFECTIVE");
        }
        JsonNode n = http.post(r.path() + "/comments", body).body();
        return new WriteResult(r.key(), (c.fileLevel() ? "Datei-Kommentar an " + c.path()
                : c.inline() ? "Code-Kommentar an " + c.path() + ":" + c.line() : "Kommentar") + " hinzugefügt",
                webUrl(r) + "/overview?commentId=" + text(n.path("id")), text(n.path("id")));
    }

    /** {@code ADDED} oder {@code CONTEXT} – Bitbucket verankert Kommentare nur an Zeilen des Diffs. */
    private String lineType(Ref r, String path, int line) {
        String encoded = String.join("/", java.util.Arrays.stream(path.split("/")).map(HttpJson::enc).toList());
        JsonNode diff = http.getJson(r.path() + "/diff/" + encoded + query("contextLines", 10, "withComments", false));
        for (JsonNode d : diff.path("diffs")) {
            for (JsonNode h : d.path("hunks")) {
                for (JsonNode seg : h.path("segments")) {
                    for (JsonNode l : seg.path("lines")) {
                        if (l.path("destination").asInt(-1) == line && !"REMOVED".equals(text(seg.path("type")))) {
                            return "ADDED".equals(text(seg.path("type"))) ? "ADDED" : "CONTEXT";
                        }
                    }
                }
            }
        }
        throw new IllegalArgumentException("Bitbucket: Zeile " + line + " von " + path + " liegt nicht im Diff des Pull "
                + "Requests – Code-Kommentare nur an geänderten Zeilen oder deren Umfeld (pr_diff).");
    }

    private String webUrl(Ref r) {
        String owner = r.projectKey().startsWith("~") ? "users/" + r.projectKey().substring(1) : "projects/" + r.projectKey();
        return webBase + "/" + owner + "/repos/" + r.slug() + "/pull-requests/" + r.id();
    }

    @Override
    public WriteResult reply(String key, String project, String threadId, String message) {
        requireToken("Antworten");
        Ref r = ref(key, project);
        ObjectNode body = HttpJson.object();
        body.put("text", message);
        body.putObject("parent").put("id", Long.parseLong(BitbucketCloud.numeric(threadId)));
        JsonNode n = http.post(r.path() + "/comments", body).body();
        return new WriteResult(r.key(), "Antwort in Thread " + threadId.trim() + " hinzugefügt",
                webUrl(r) + "/overview?commentId=" + text(n.path("id")), text(n.path("id")));
    }

    @Override
    public WriteResult resolve(String key, String project, String threadId, boolean resolved) {
        requireToken("Threads auflösen");
        Ref r = ref(key, project);
        String path = r.path() + "/comments/" + BitbucketCloud.numeric(threadId);
        JsonNode c = http.getJson(path);
        ObjectNode body = HttpJson.object();
        body.put("version", c.path("version").asInt());
        if ("BLOCKER".equals(text(c.path("severity")))) {
            body.put("state", resolved ? "RESOLVED" : "OPEN");
        } else {
            body.put("threadResolved", resolved);
        }
        http.put(path, body);
        return new WriteResult(r.key(), "Thread " + threadId.trim() + (resolved ? " als erledigt markiert"
                : " wieder geöffnet"), webUrl(r));
    }

    @Override
    public WriteResult merge(String key, String project, MergeOptions o) {
        requireToken("Mergen");
        Ref r = ref(key, project);
        JsonNode pr = http.getJson(r.path());
        ObjectNode body = HttpJson.object();
        if (o.message() != null) {
            body.put("message", o.message());
        }
        if (o.method() != null) {
            body.put("strategyId", switch (o.method()) {
                case "squash" -> "squash";
                case "rebase" -> "rebase-no-ff";
                default -> "no-ff";
            });
        }
        http.post(r.path() + "/merge" + query("version", pr.path("version").asInt()), body);
        String msg = "gemergt";
        String branch = text(pr.path("fromRef").path("id"));
        boolean sameRepo = r.slug().equalsIgnoreCase(text(pr.path("fromRef").path("repository").path("slug")));
        if (o.deleteSourceBranch() && branch != null && sameRepo) {
            ObjectNode del = HttpJson.object();
            del.put("name", branch);
            del.put("dryRun", false);
            try {
                http.request("DELETE", "/rest/branch-utils/1.0/projects/" + r.projectKey() + "/repos/" + r.slug()
                        + "/branches", del);
                msg += ", Branch " + text(pr.path("fromRef").path("displayId")) + " gelöscht";
            } catch (RuntimeException e) {
                msg += ", Branch NICHT gelöscht: " + e.getMessage();
            }
        }
        return new WriteResult(r.key(), msg, webUrl(r));
    }
}
