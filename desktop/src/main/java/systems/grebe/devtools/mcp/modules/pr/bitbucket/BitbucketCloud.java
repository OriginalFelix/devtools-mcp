package systems.grebe.devtools.mcp.modules.pr.bitbucket;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import systems.grebe.devtools.mcp.modules.pr.spi.GitServer;
import systems.grebe.devtools.mcp.modules.ticket.spi.HttpJson;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import static systems.grebe.devtools.mcp.modules.ticket.spi.HttpJson.query;
import static systems.grebe.devtools.mcp.modules.ticket.spi.HttpJson.text;

/** Bitbucket Cloud über die REST API 2.0. Paketsichtbar für Tests. */
final class BitbucketCloud implements GitServer {

    private static final Pattern FULL_KEY = Pattern.compile("([\\w.-]+)/([\\w.-]+)#(\\d+)");
    private static final Pattern SHORT_KEY = Pattern.compile("[#!]?(\\d+)");
    private static final Pattern URL_KEY = Pattern.compile("https?://(?:www\\.)?bitbucket\\.org/([\\w.-]+)/([\\w.-]+)/pull-requests/(\\d+).*");
    private static final int MAX_PAGES = 10;
    private static final String WEB = "https://bitbucket.org";

    private final HttpJson http;
    private final boolean authenticated;

    BitbucketCloud(HttpJson http, boolean authenticated) {
        this.http = http;
        this.authenticated = authenticated;
    }

    @Override
    public String id() {
        return "bitbucket";
    }

    @Override
    public String instance() {
        return WEB;
    }

    @Override
    public Availability probe() {
        try {
            if (!authenticated) {
                return new Availability(true, "Cloud, ohne Token", null, "anonym – nur öffentliche Repositories, nur lesen");
            }
            JsonNode me = http.getJson("/user");
            return Availability.ok("Cloud", HttpJson.first(text(me.path("username")), text(me.path("display_name"))));
        } catch (RuntimeException e) {
            return Availability.unavailable(e.getMessage());
        }
    }

    @Override
    public String projectOfRemote(String remoteUrl) {
        Remote r = GitServer.parseRemote(remoteUrl);
        if (r == null || !r.host().endsWith("bitbucket.org")) {
            return null;
        }
        return r.path().split("/").length == 2 ? r.path() : null;
    }

    @Override
    public boolean ownsKey(String ref) {
        return ref != null && URL_KEY.matcher(ref.trim()).matches();
    }

    @Override
    public String projectOf(String ref, String project) {
        return ref(ref, project).repo();
    }

    record Ref(String repo, int id) {
        String key() {
            return repo + "#" + id;
        }

        String path() {
            return "/repositories/" + repo + "/pullrequests/" + id;
        }

        String web() {
            return WEB + "/" + repo + "/pull-requests/" + id;
        }
    }

    static Ref ref(String key, String project) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Bitbucket: kein Pull Request angegeben (z.B. workspace/repo#12 oder 12).");
        }
        String k = key.trim();
        Matcher m = URL_KEY.matcher(k);
        if (m.matches()) {
            return new Ref(m.group(1) + "/" + m.group(2), Integer.parseInt(m.group(3)));
        }
        m = FULL_KEY.matcher(k);
        if (m.matches()) {
            return new Ref(m.group(1) + "/" + m.group(2), Integer.parseInt(m.group(3)));
        }
        m = SHORT_KEY.matcher(k);
        if (m.matches()) {
            return new Ref(repo(project), Integer.parseInt(m.group(1)));
        }
        throw new IllegalArgumentException("Bitbucket: '" + k + "' ist kein Pull Request (erwartet workspace/repo#12, "
                + "#12 oder Pull-Request-URL).");
    }

    static String repo(String project) {
        if (project == null || !project.trim().matches("[\\w.-]+/[\\w.-]+")) {
            throw new IllegalArgumentException("Bitbucket: Repository fehlt oder ist ungültig ('" + project
                    + "') – als workspace/repo angeben oder ein lokales Repository mit Bitbucket-Remote wählen.");
        }
        return project.trim();
    }

    private void requireToken(String what) {
        if (!authenticated) {
            throw new IllegalStateException("Bitbucket: " + what + " braucht ein Token – in der DevTools-App unter "
                    + "Module → Pull Requests eintragen.");
        }
    }

    /** Alle Seiten ({@code next}), höchstens {@code max} Einträge. */
    private List<JsonNode> pages(String path, int max) {
        List<JsonNode> out = new ArrayList<>();
        String next = path;
        for (int i = 0; next != null && i < MAX_PAGES && out.size() < max; i++) {
            JsonNode page = http.getJson(next);
            page.path("values").forEach(out::add);
            next = text(page.path("next"));
        }
        return out.size() > max ? out.subList(0, max) : out;
    }

    // ------------------------------------------------------------------ Lesen

    @Override
    public List<PullRequest> list(PrQuery q) {
        String repo = repo(q.project());
        List<String> states = switch (q.state()) {
            case OPEN -> List.of("OPEN");
            case MERGED -> List.of("MERGED");
            case CLOSED -> List.of("DECLINED", "SUPERSEDED");
            case ALL -> List.of("OPEN", "MERGED", "DECLINED", "SUPERSEDED");
        };
        List<String> filter = new ArrayList<>();
        if (q.source() != null) {
            filter.add("source.branch.name=\"" + q.source().replace("\"", "") + "\"");
        }
        if (q.target() != null) {
            filter.add("destination.branch.name=\"" + q.target().replace("\"", "") + "\"");
        }
        if (q.author() != null) {
            filter.add(GitServer.isMe(q.author()) && authenticated
                    ? "author.uuid=\"" + text(http.getJson("/user").path("uuid")) + "\""
                    : "author.nickname=\"" + q.author().replace("\"", "") + "\"");
        }
        int limit = Math.max(1, Math.min(q.limit(), 50));
        List<PullRequest> out = new ArrayList<>();
        for (JsonNode n : pages("/repositories/" + repo + "/pullrequests" + query("state", states,
                "q", filter.isEmpty() ? null : String.join(" AND ", filter), "sort", "-updated_on", "pagelen", limit),
                limit)) {
            out.add(pr(n, repo));
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
                n.path("draft").asBoolean(false), user(n.path("author")),
                text(n.path("source").path("branch").path("name")), text(n.path("destination").path("branch").path("name")),
                text(n.path("updated_on")), text(n.path("links").path("html").path("href")));
    }

    private static String user(JsonNode u) {
        return HttpJson.first(text(u.path("nickname")), text(u.path("display_name")));
    }

    @Override
    public PrDetails get(String key, String project) {
        Ref r = ref(key, project);
        JsonNode n = http.getJson(r.path());
        List<String> reviewers = new ArrayList<>();
        List<String> approved = new ArrayList<>();
        List<String> changes = new ArrayList<>();
        for (JsonNode p : n.path("participants")) {
            String name = user(p.path("user"));
            if ("REVIEWER".equals(text(p.path("role")))) {
                reviewers.add(name);
            }
            if (p.path("approved").asBoolean(false)) {
                approved.add(name);
            } else if ("changes_requested".equals(text(p.path("state")))) {
                changes.add(name);
            }
        }
        Map<String, String> fields = new LinkedHashMap<>();
        if (!changes.isEmpty()) {
            fields.put("Änderungen angefordert", String.join(", ", changes));
        }
        fields.put("Kommentare", text(n.path("comment_count")));
        fields.put("Offene Aufgaben", text(n.path("task_count")));
        if (n.path("close_source_branch").asBoolean(false)) {
            fields.put("Quell-Branch schließen", "ja");
        }
        String state = text(n.path("state"));
        String mergeStatus = "MERGED".equals(state) ? "gemergt"
                : "OPEN".equals(state) ? null : "geschlossen (" + state + ")";
        List<Check> checks = new ArrayList<>();
        try {
            for (JsonNode s : pages(r.path() + "/statuses" + query("pagelen", 100), 100)) {
                checks.add(new Check(HttpJson.first(text(s.path("name")), text(s.path("key"))), text(s.path("state")),
                        text(s.path("url"))));
            }
        } catch (RuntimeException e) {
            checks.add(new Check("Build-Status", "nicht lesbar: " + e.getMessage(), null));
        }
        return new PrDetails(pr(n, r.repo()), text(n.path("description")), text(n.path("created_on")), reviewers,
                approved, mergeStatus, text(n.path("source").path("commit").path("hash")), checks, fields);
    }

    @Override
    public List<FileChange> diff(String key, String project) {
        Ref r = ref(key, project);
        Map<String, String> patches = GitServer.splitUnifiedDiff(http.getText(r.path() + "/diff"));
        List<FileChange> out = new ArrayList<>();
        for (JsonNode f : pages(r.path() + "/diffstat" + query("pagelen", 500), 2000)) {
            String newPath = text(f.path("new").path("path"));
            String oldPath = text(f.path("old").path("path"));
            String path = newPath != null ? newPath : oldPath;
            String status = text(f.path("status"));
            out.add(new FileChange(path, "renamed".equals(status) ? oldPath : null, status,
                    f.path("lines_added").asInt(-1), f.path("lines_removed").asInt(-1), patches.get(path)));
        }
        return out;
    }

    @Override
    public List<Thread> threads(String key, String project) {
        Ref r = ref(key, project);
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        for (JsonNode c : pages(r.path() + "/comments" + query("pagelen", 100), 2000)) {
            byId.put(text(c.path("id")), c);
        }
        Map<String, List<JsonNode>> byRoot = new LinkedHashMap<>();
        for (JsonNode c : byId.values()) {
            byRoot.computeIfAbsent(root(c, byId), k -> new ArrayList<>()).add(c);
        }
        List<Thread> out = new ArrayList<>();
        byRoot.forEach((rootId, list) -> {
            JsonNode root = byId.getOrDefault(rootId, list.getFirst());
            JsonNode inline = root.path("inline");
            List<Comment> comments = list.stream().filter(c -> !c.path("deleted").asBoolean(false))
                    .sorted(Comparator.comparing(c -> String.valueOf(text(c.path("created_on")))))
                    .map(c -> new Comment(text(c.path("id")), user(c.path("user")), text(c.path("created_on")),
                            text(c.path("content").path("raw")))).toList();
            if (comments.isEmpty()) {
                return;
            }
            boolean code = inline.isObject();
            JsonNode line = inline.path("to").isNumber() ? inline.path("to") : inline.path("from");
            // nur Code-Kommentare lassen sich auflösen
            out.add(new Thread(rootId, code ? "Code" : "Kommentar", code ? root.path("resolution").isObject() : null,
                    code ? text(inline.path("path")) : null, code && line.isNumber() ? line.asInt() : null,
                    code && inline.path("outdated").asBoolean(false), comments));
        });
        out.sort(Comparator.comparing(t -> String.valueOf(t.comments().getFirst().created())));
        return out;
    }

    private static String root(JsonNode c, Map<String, JsonNode> byId) {
        JsonNode cur = c;
        for (int depth = 0; depth < 100; depth++) {
            String parent = text(cur.path("parent").path("id"));
            if (parent == null || !byId.containsKey(parent)) {
                return parent != null ? parent : text(cur.path("id"));
            }
            cur = byId.get(parent);
        }
        return text(cur.path("id"));
    }

    // ------------------------------------------------------------------ Schreiben

    @Override
    public WriteResult create(String project, NewPullRequest p) {
        requireToken("Anlegen");
        String repo = repo(project);
        ObjectNode body = HttpJson.object();
        body.put("title", p.title());
        if (p.description() != null) {
            body.put("description", p.description());
        }
        body.putObject("source").putObject("branch").put("name", p.source());
        if (p.target() != null) {
            body.putObject("destination").putObject("branch").put("name", p.target());
        }
        if (p.draft()) {
            body.put("draft", true);
        }
        body.put("close_source_branch", p.deleteSourceBranch());
        if (!p.reviewers().isEmpty()) {
            var reviewers = body.putArray("reviewers");
            p.reviewers().forEach(u -> {
                ObjectNode o = reviewers.addObject();
                if (u.startsWith("{")) {
                    o.put("uuid", u);
                } else {
                    o.put("account_id", u);
                }
            });
        }
        JsonNode n;
        try {
            n = http.post("/repositories/" + repo + "/pullrequests", body).body();
        } catch (HttpJson.StatusException e) {
            if (e.status() == 400 && e.getMessage().toLowerCase(java.util.Locale.ROOT).contains("branch")) {
                throw new IllegalStateException("Bitbucket: Branch '" + p.source() + "' ist auf dem Server nicht "
                        + "vorhanden oder der Pull Request existiert schon – pushen (pr_push) bzw. pr_list prüfen. "
                        + e.getMessage(), e);
            }
            throw e;
        }
        return new WriteResult(repo + "#" + text(n.path("id")), (p.draft() ? "Entwurf" : "Pull Request")
                + " angelegt: " + p.source() + " → " + text(n.path("destination").path("branch").path("name")),
                text(n.path("links").path("html").path("href")));
    }

    @Override
    public WriteResult update(String key, String project, PrUpdate u) {
        requireToken("Bearbeiten");
        Ref r = ref(key, project);
        ObjectNode body = HttpJson.object();
        if (u.title() != null) {
            body.put("title", u.title());
        }
        if (u.description() != null) {
            body.put("description", u.description());
        }
        if (u.target() != null) {
            body.putObject("destination").putObject("branch").put("name", u.target());
        }
        http.put(r.path(), body);
        return new WriteResult(r.key(), "aktualisiert", r.web());
    }

    @Override
    public WriteResult comment(String key, String project, NewComment c) {
        requireToken("Kommentieren");
        Ref r = ref(key, project);
        ObjectNode body = HttpJson.object();
        body.putObject("content").put("raw", c.body());
        if (c.inline()) {
            if (c.line() == null || c.line() < 1) {
                throw new IllegalArgumentException("Bitbucket: Code-Kommentar braucht 'line' (Zeile der neuen Fassung).");
            }
            ObjectNode inline = body.putObject("inline");
            inline.put("path", c.path());
            inline.put("to", c.line());
        }
        JsonNode n = http.post(r.path() + "/comments", body).body();
        return new WriteResult(r.key(), c.inline() ? "Code-Kommentar an " + c.path() + ":" + c.line() + " hinzugefügt"
                : "Kommentar hinzugefügt", text(n.path("links").path("html").path("href")), text(n.path("id")));
    }

    @Override
    public WriteResult reply(String key, String project, String threadId, String message) {
        requireToken("Antworten");
        Ref r = ref(key, project);
        ObjectNode body = HttpJson.object();
        body.putObject("content").put("raw", message);
        body.putObject("parent").put("id", Long.parseLong(numeric(threadId)));
        JsonNode n = http.post(r.path() + "/comments", body).body();
        return new WriteResult(r.key(), "Antwort in Thread " + threadId.trim() + " hinzugefügt",
                text(n.path("links").path("html").path("href")), text(n.path("id")));
    }

    @Override
    public WriteResult resolve(String key, String project, String threadId, boolean resolved) {
        requireToken("Threads auflösen");
        Ref r = ref(key, project);
        String path = r.path() + "/comments/" + numeric(threadId) + "/resolve";
        if (resolved) {
            http.post(path, HttpJson.object());
        } else {
            http.delete(path);
        }
        return new WriteResult(r.key(), "Thread " + threadId.trim() + (resolved ? " als erledigt markiert"
                : " wieder geöffnet"), r.web());
    }

    static String numeric(String id) {
        String s = id == null ? "" : id.trim();
        if (!s.matches("\\d+")) {
            throw new IllegalArgumentException("Bitbucket: Thread-ID '" + id + "' ist keine Kommentar-ID – IDs aus "
                    + "pr_comments verwenden.");
        }
        return s;
    }

    @Override
    public WriteResult merge(String key, String project, MergeOptions o) {
        requireToken("Mergen");
        Ref r = ref(key, project);
        ObjectNode body = HttpJson.object();
        if (o.method() != null) {
            body.put("merge_strategy", switch (o.method()) {
                case "squash" -> "squash";
                case "rebase" -> "rebase_fast_forward";
                default -> "merge_commit";
            });
        }
        if (o.message() != null) {
            body.put("message", o.message());
        }
        body.put("close_source_branch", o.deleteSourceBranch());
        HttpJson.Response res = http.post(r.path() + "/merge", body);
        String msg = res.status() == 202 ? "Merge gestartet (läuft im Hintergrund)"
                : "gemergt (" + text(res.body().path("merge_commit").path("hash")) + ")";
        return new WriteResult(r.key(), msg + (o.deleteSourceBranch() ? ", Quell-Branch wird geschlossen" : ""), r.web());
    }
}
