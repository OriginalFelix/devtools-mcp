package systems.grebe.devtools.mcp.modules.pr.github;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.modules.pr.spi.GitServer;
import systems.grebe.devtools.mcp.modules.pr.spi.GitServerProvider;
import systems.grebe.devtools.mcp.modules.ticket.spi.HttpJson;
import systems.grebe.devtools.mcp.modules.ticket.spi.ProviderSettings;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import static systems.grebe.devtools.mcp.modules.ticket.spi.HttpJson.enc;
import static systems.grebe.devtools.mcp.modules.ticket.spi.HttpJson.query;
import static systems.grebe.devtools.mcp.modules.ticket.spi.HttpJson.text;
import static systems.grebe.devtools.mcp.modules.ticket.spi.HttpJson.texts;

/**
 * GitHub.com und GitHub Enterprise Server: Pull Requests über die REST-API; Review-Threads (Auflösen, Antworten) über
 * GraphQL, weil REST deren Erledigt-Status nicht kennt.
 */
public class GitHubServerProvider implements GitServerProvider {

    static final String BASE_URL = "baseUrl";
    static final String TOKEN = "token";

    @Override
    public String id() {
        return "github";
    }

    @Override
    public String displayName() {
        return "GitHub";
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
                        .withHelp("Fine-grained Token (Pull requests: read/write, Contents: read, Commit statuses/"
                                + "Checks: read) oder klassisch mit repo. Ohne Token nur öffentliche Repositories lesen."));
    }

    @Override
    public String projectHelp() {
        return "owner/repo";
    }

    @Override
    public String keyHelp() {
        return "owner/repo#12, #12 bzw. 12 (mit Repository) oder Pull-Request-URL";
    }

    @Override
    public GitServer create(ProviderSettings s) {
        String base = HttpJson.stripSlash(s.getString(BASE_URL, "https://api.github.com"));
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-GitHub-Api-Version", "2022-11-28");
        s.get(TOKEN).ifPresent(t -> headers.put("Authorization", "Bearer " + t));
        return new GitHub(new HttpJson("GitHub", base, headers, s.timeout(), "Pull Requests"), s.get(TOKEN).isPresent());
    }

    /** GitHub-Anbindung. Paketsichtbar für Tests. */
    static final class GitHub implements GitServer {

        private static final Pattern FULL_KEY = Pattern.compile("([\\w.-]+)/([\\w.-]+)#(\\d+)");
        private static final Pattern SHORT_KEY = Pattern.compile("[#!]?(\\d+)");
        private static final Pattern URL_KEY = Pattern.compile("https?://[^/]+/([\\w.-]+)/([\\w.-]+)/pull/(\\d+).*");
        private static final int MAX_PAGES = 10;

        private final HttpJson http;
        private final boolean authenticated;
        private final String webHost;
        private final String graphql;

        GitHub(HttpJson http, boolean authenticated) {
            this.http = http;
            this.authenticated = authenticated;
            String host = GitServer.hostOf(http.baseUrl());
            this.webHost = "api.github.com".equals(host) ? "github.com" : host;
            String base = http.baseUrl();
            this.graphql = base.endsWith("/api/v3") ? base.substring(0, base.length() - 3) + "graphql" : base + "/graphql";
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
            Remote r = GitServer.parseRemote(remoteUrl);
            if (r == null || !(r.host().equals(webHost) || r.host().equals("ssh." + webHost))) {
                return null;
            }
            String[] parts = r.path().split("/");
            return parts.length == 2 ? r.path() : null;
        }

        @Override
        public boolean ownsKey(String ref) {
            return ref != null && URL_KEY.matcher(ref.trim()).matches() && webHost.equals(GitServer.hostOf(ref.trim()));
        }

        @Override
        public String projectOf(String ref, String project) {
            return ref(ref, project).repo();
        }

        /** {@code owner/repo} und Nummer. */
        record Ref(String repo, int number) {
            String key() {
                return repo + "#" + number;
            }

            String path() {
                return "/repos/" + repo + "/pulls/" + number;
            }
        }

        static Ref ref(String key, String project) {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("GitHub: kein Pull Request angegeben (z.B. owner/repo#12 oder 12).");
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
            throw new IllegalArgumentException("GitHub: '" + k + "' ist kein Pull Request (erwartet owner/repo#12, "
                    + "#12 oder Pull-Request-URL).");
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
                        + "Module → Pull Requests eintragen.");
            }
        }

        // ------------------------------------------------------------------ Lesen

        /** Alle Seiten einer Listen-Ressource (Link-Header), höchstens {@code max} Einträge. */
        private List<JsonNode> pages(String path, int max) {
            List<JsonNode> out = new ArrayList<>();
            String next = path;
            for (int page = 0; next != null && page < MAX_PAGES && out.size() < max; page++) {
                HttpJson.Response res = http.get(next);
                res.body().forEach(out::add);
                next = nextLink(res.header("Link"));
            }
            return out.size() > max ? out.subList(0, max) : out;
        }

        static String nextLink(String link) {
            if (link == null) {
                return null;
            }
            for (String part : link.split(",")) {
                if (part.contains("rel=\"next\"")) {
                    int a = part.indexOf('<');
                    int b = part.indexOf('>');
                    return a >= 0 && b > a ? part.substring(a + 1, b) : null;
                }
            }
            return null;
        }

        @Override
        public List<PullRequest> list(PrQuery q) {
            String repo = repo(q.project());
            String state = switch (q.state()) {
                case OPEN -> "open";
                case MERGED, CLOSED -> "closed";
                case ALL -> "all";
            };
            String owner = repo.substring(0, repo.indexOf('/'));
            String author = q.author() != null && GitServer.isMe(q.author()) && authenticated
                    ? text(http.getJson("/user").path("login")) : q.author();
            int limit = Math.max(1, Math.min(q.limit(), 100));
            boolean filtered = author != null || q.state() == State.MERGED || q.state() == State.CLOSED;
            List<PullRequest> out = new ArrayList<>();
            for (JsonNode n : pages("/repos/" + repo + "/pulls" + query("state", state,
                    "head", q.source() == null ? null : owner + ":" + q.source(), "base", q.target(),
                    "sort", "updated", "direction", "desc", "per_page", filtered ? 100 : limit), filtered ? 300 : limit)) {
                boolean merged = text(n.path("merged_at")) != null;
                if (q.state() == State.MERGED && !merged || q.state() == State.CLOSED && merged) {
                    continue;
                }
                if (author != null && !author.equalsIgnoreCase(text(n.path("user").path("login")))) {
                    continue;
                }
                out.add(pr(n, repo));
                if (out.size() >= limit) {
                    break;
                }
            }
            return out;
        }

        private static PullRequest pr(JsonNode n, String repo) {
            String state = text(n.path("merged_at")) != null || n.path("merged").asBoolean(false) ? "merged"
                    : text(n.path("state"));
            return new PullRequest(repo + "#" + text(n.path("number")), text(n.path("title")), state,
                    n.path("draft").asBoolean(false), text(n.path("user").path("login")),
                    text(n.path("head").path("ref")), text(n.path("base").path("ref")), text(n.path("updated_at")),
                    text(n.path("html_url")));
        }

        @Override
        public PrDetails get(String key, String project) {
            Ref r = ref(key, project);
            JsonNode n = http.getJson(r.path());
            List<String> reviewers = new ArrayList<>(texts(n.path("requested_reviewers"), "login"));
            n.path("requested_teams").forEach(t -> reviewers.add("team:" + text(t.path("slug"))));
            // letzte Bewertung je Benutzer zählt
            Map<String, String> verdicts = new LinkedHashMap<>();
            for (JsonNode rv : pages(r.path() + "/reviews" + query("per_page", 100), 300)) {
                String state = text(rv.path("state"));
                if (state != null && !"COMMENTED".equals(state) && !"PENDING".equals(state)) {
                    verdicts.put(text(rv.path("user").path("login")), state);
                }
            }
            List<String> approved = verdicts.entrySet().stream().filter(e -> "APPROVED".equals(e.getValue()))
                    .map(Map.Entry::getKey).toList();
            Map<String, String> fields = new LinkedHashMap<>();
            List<String> changes = verdicts.entrySet().stream().filter(e -> "CHANGES_REQUESTED".equals(e.getValue()))
                    .map(Map.Entry::getKey).toList();
            if (!changes.isEmpty()) {
                fields.put("Änderungen angefordert", String.join(", ", changes));
            }
            fields.put("Commits", text(n.path("commits")));
            fields.put("Änderungen", "+" + text(n.path("additions")) + " −" + text(n.path("deletions")) + " in "
                    + text(n.path("changed_files")) + " Datei(en)");
            fields.put("Kommentare", n.path("comments").asInt(0) + " allgemein, " + n.path("review_comments").asInt(0)
                    + " im Code");
            List<String> labels = texts(n.path("labels"), "name");
            if (!labels.isEmpty()) {
                fields.put("Labels", String.join(", ", labels));
            }
            fields.put("Milestone", text(n.path("milestone").path("title")));
            String head = text(n.path("head").path("sha"));
            return new PrDetails(pr(n, r.repo()), text(n.path("body")), text(n.path("created_at")), reviewers, approved,
                    mergeStatus(n), head, head == null ? List.of() : checks(r.repo(), head), fields);
        }

        private static String mergeStatus(JsonNode n) {
            if (n.path("merged").asBoolean(false)) {
                return "gemergt" + (text(n.path("merged_by").path("login")) == null ? ""
                        : " von " + text(n.path("merged_by").path("login")));
            }
            String s = text(n.path("mergeable_state"));
            if (s == null) {
                return null;
            }
            return switch (s) {
                case "clean" -> "mergeable";
                case "dirty" -> "Konflikte mit dem Ziel-Branch";
                case "blocked" -> "blockiert (Freigaben oder Pflicht-Checks fehlen)";
                case "behind" -> "Quell-Branch ist nicht aktuell";
                case "unstable" -> "mergeable, aber Checks fehlgeschlagen";
                case "draft" -> "Entwurf";
                case "unknown" -> "wird berechnet";
                default -> s;
            };
        }

        private List<Check> checks(String repo, String sha) {
            List<Check> out = new ArrayList<>();
            try {
                http.getJson("/repos/" + repo + "/commits/" + sha + "/check-runs" + query("per_page", 100))
                        .path("check_runs").forEach(c -> out.add(new Check(text(c.path("name")),
                                HttpJson.first(text(c.path("conclusion")), text(c.path("status"))),
                                text(c.path("html_url")))));
                http.getJson("/repos/" + repo + "/commits/" + sha + "/status").path("statuses")
                        .forEach(c -> out.add(new Check(text(c.path("context")), text(c.path("state")),
                                text(c.path("target_url")))));
            } catch (RuntimeException e) {
                // fehlende Checks-Berechtigung soll den Pull Request nicht verhindern
                out.add(new Check("Checks", "nicht lesbar: " + e.getMessage(), null));
            }
            return out;
        }

        @Override
        public List<FileChange> diff(String key, String project) {
            Ref r = ref(key, project);
            List<FileChange> out = new ArrayList<>();
            for (JsonNode f : pages(r.path() + "/files" + query("per_page", 100), 1000)) {
                out.add(new FileChange(text(f.path("filename")), text(f.path("previous_filename")),
                        text(f.path("status")), f.path("additions").asInt(-1), f.path("deletions").asInt(-1),
                        text(f.path("patch"))));
            }
            return out;
        }

        @Override
        public List<Thread> threads(String key, String project) {
            Ref r = ref(key, project);
            List<Thread> out = new ArrayList<>();
            for (JsonNode c : pages("/repos/" + r.repo() + "/issues/" + r.number() + "/comments" + query("per_page", 100), 500)) {
                out.add(new Thread("c" + text(c.path("id")), "Kommentar", null, null, null, false, List.of(restComment(c))));
            }
            for (JsonNode rv : pages(r.path() + "/reviews" + query("per_page", 100), 300)) {
                String body = text(rv.path("body"));
                if (body != null) {
                    out.add(new Thread("r" + text(rv.path("id")), "Review " + text(rv.path("state")), null, null, null,
                            false, List.of(new Comment(text(rv.path("id")), text(rv.path("user").path("login")),
                                    text(rv.path("submitted_at")), body, integration(rv)))));
                }
            }
            out.addAll(authenticated ? reviewThreads(r) : restReviewThreads(r));
            out.sort(Comparator.comparing(t -> t.comments().isEmpty() ? "" : String.valueOf(t.comments().getFirst().created())));
            return out;
        }

        /** Issue- oder Review-Kommentar aus der REST-API. */
        private static Comment restComment(JsonNode c) {
            return new Comment(text(c.path("id")), text(c.path("user").path("login")), text(c.path("created_at")),
                    text(c.path("body")), integration(c));
        }

        /** Von einer GitHub App oder einem Bot-Konto (z.B. {@code dependabot[bot]}) geschrieben. */
        static boolean integration(JsonNode item) {
            return "Bot".equals(text(item.path("user").path("type"))) || item.path("performed_via_github_app").isObject();
        }

        private static final String THREADS_QUERY = """
                query($owner:String!,$name:String!,$number:Int!,$after:String){repository(owner:$owner,name:$name){
                  pullRequest(number:$number){reviewThreads(first:100,after:$after){pageInfo{hasNextPage endCursor}
                    nodes{id isResolved isOutdated path line originalLine subjectType
                      comments(first:100){nodes{databaseId author{__typename login} createdAt body}}}}}}}""";

        /** Code-Threads über GraphQL – nur dort gibt es Thread-ID und Erledigt-Status. */
        private List<Thread> reviewThreads(Ref r) {
            List<Thread> out = new ArrayList<>();
            String after = null;
            for (int page = 0; page < MAX_PAGES; page++) {
                ObjectNode vars = HttpJson.object();
                vars.put("owner", r.repo().substring(0, r.repo().indexOf('/')));
                vars.put("name", r.repo().substring(r.repo().indexOf('/') + 1));
                vars.put("number", r.number());
                if (after != null) {
                    vars.put("after", after);
                }
                JsonNode threads = graphql(THREADS_QUERY, vars).path("repository").path("pullRequest").path("reviewThreads");
                for (JsonNode t : threads.path("nodes")) {
                    List<Comment> comments = new ArrayList<>();
                    t.path("comments").path("nodes").forEach(c -> comments.add(new Comment(text(c.path("databaseId")),
                            text(c.path("author").path("login")), text(c.path("createdAt")), text(c.path("body")),
                            "Bot".equals(text(c.path("author").path("__typename"))))));
                    boolean file = "FILE".equals(text(t.path("subjectType")));
                    JsonNode line = t.path("line").isNull() || t.path("line").isMissingNode() ? t.path("originalLine") : t.path("line");
                    out.add(new Thread(text(t.path("id")), file ? "Datei" : "Code", t.path("isResolved").asBoolean(false),
                            text(t.path("path")), !file && line.isNumber() ? line.asInt() : null,
                            t.path("isOutdated").asBoolean(false), comments));
                }
                if (!threads.path("pageInfo").path("hasNextPage").asBoolean(false)) {
                    break;
                }
                after = text(threads.path("pageInfo").path("endCursor"));
            }
            return out;
        }

        /** Ohne Token: Code-Kommentare per REST, nach {@code in_reply_to_id} gruppiert, ohne Erledigt-Status. */
        private List<Thread> restReviewThreads(Ref r) {
            Map<String, List<JsonNode>> byRoot = new LinkedHashMap<>();
            for (JsonNode c : pages(r.path() + "/comments" + query("per_page", 100), 1000)) {
                String root = HttpJson.first(text(c.path("in_reply_to_id")), text(c.path("id")));
                byRoot.computeIfAbsent(root, k -> new ArrayList<>()).add(c);
            }
            List<Thread> out = new ArrayList<>();
            byRoot.forEach((id, list) -> {
                JsonNode first = list.getFirst();
                boolean file = "file".equals(text(first.path("subject_type")));
                JsonNode line = first.path("line").isNumber() ? first.path("line") : first.path("original_line");
                out.add(new Thread("c" + id, file ? "Datei" : "Code", null, text(first.path("path")),
                        !file && line.isNumber() ? line.asInt() : null, !file && !first.path("line").isNumber(),
                        list.stream().map(GitHub::restComment).toList()));
            });
            return out;
        }

        /** Check-Runs des letzten Commits mit Ausgabe (Titel, Zusammenfassung, Annotations) – Berichte von GitHub Apps. */
        @Override
        public List<Insight> insights(String key, String project) {
            Ref r = ref(key, project);
            String head = text(http.getJson(r.path()).path("head").path("sha"));
            List<Insight> out = new ArrayList<>();
            if (head == null) {
                return out;
            }
            for (JsonNode c : http.getJson("/repos/" + r.repo() + "/commits/" + head + "/check-runs"
                    + query("per_page", 100)).path("check_runs")) {
                JsonNode output = c.path("output");
                int count = output.path("annotations_count").asInt(0);
                String title = text(output.path("title"));
                String summary = text(output.path("summary"));
                if (count == 0 && title == null && summary == null) {
                    continue;
                }
                List<Annotation> annotations = new ArrayList<>();
                if (count > 0) {
                    for (JsonNode a : pages("/repos/" + r.repo() + "/check-runs/" + text(c.path("id")) + "/annotations"
                            + query("per_page", 100), 300)) {
                        String msg = text(a.path("message"));
                        String heading = text(a.path("title"));
                        JsonNode line = a.path("start_line");
                        annotations.add(new Annotation(text(a.path("path")), line.isNumber() && line.asInt() > 0
                                ? line.asInt() : null, text(a.path("annotation_level")), null,
                                heading == null || heading.equals(msg) ? msg : heading + ": " + msg, text(a.path("blob_href"))));
                    }
                }
                String description = title == null ? summary : summary == null ? title : title + "\n" + summary;
                out.add(new Insight(text(c.path("id")), text(c.path("name")), text(c.path("app").path("name")),
                        HttpJson.first(text(c.path("conclusion")), text(c.path("status"))), description,
                        text(c.path("html_url")), null, annotations, count));
            }
            return out;
        }

        private JsonNode graphql(String query, ObjectNode variables) {
            requireToken("Die GraphQL-API (Review-Threads)");
            ObjectNode body = HttpJson.object();
            body.put("query", query);
            body.set("variables", variables);
            JsonNode res = http.post(graphql, body).body();
            JsonNode errors = res.path("errors");
            if (errors.isArray() && !errors.isEmpty()) {
                throw new IllegalStateException("GitHub GraphQL: " + String.join("; ", texts(errors, "message")));
            }
            return res.path("data");
        }

        // ------------------------------------------------------------------ Schreiben

        @Override
        public WriteResult create(String project, NewPullRequest p) {
            requireToken("Anlegen");
            String repo = repo(project);
            String target = p.target() != null ? p.target()
                    : text(http.getJson("/repos/" + repo).path("default_branch"));
            ObjectNode body = HttpJson.object();
            body.put("title", p.title());
            body.put("head", p.source());
            body.put("base", target);
            if (p.description() != null) {
                body.put("body", p.description());
            }
            body.put("draft", p.draft());
            JsonNode n;
            try {
                n = http.post("/repos/" + repo + "/pulls", body).body();
            } catch (HttpJson.StatusException e) {
                if (e.status() == 422 && e.getMessage().contains("already exists")) {
                    throw new IllegalStateException("GitHub: für " + p.source() + " → " + target + " gibt es schon einen "
                            + "offenen Pull Request – mit pr_list (source=" + p.source() + ") finden.", e);
                }
                if (e.status() == 422 && e.getMessage().contains("head")) {
                    throw new IllegalStateException("GitHub: Branch '" + p.source() + "' ist auf dem Server nicht "
                            + "vorhanden – zuerst pushen (pr_push). " + e.getMessage(), e);
                }
                throw e;
            }
            String key = repo + "#" + text(n.path("number"));
            String note = "";
            if (!p.reviewers().isEmpty()) {
                ObjectNode rv = HttpJson.object();
                var users = rv.putArray("reviewers");
                var teams = rv.putArray("team_reviewers");
                p.reviewers().forEach(u -> {
                    if (u.startsWith("team:")) {
                        teams.add(u.substring(5));
                    } else {
                        users.add(u.startsWith("@") ? u.substring(1) : u);
                    }
                });
                try {
                    http.post("/repos/" + repo + "/pulls/" + text(n.path("number")) + "/requested_reviewers", rv);
                    note = ", Reviewer angefragt: " + String.join(", ", p.reviewers());
                } catch (RuntimeException e) {
                    note = " – Reviewer NICHT angefragt: " + e.getMessage();
                }
            }
            return new WriteResult(key, (p.draft() ? "Entwurf" : "Pull Request") + " angelegt: " + p.source() + " → "
                    + target + note, text(n.path("html_url")));
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
                body.put("body", u.description());
            }
            if (u.target() != null) {
                body.put("base", u.target());
            }
            JsonNode n = http.patch(r.path(), body).body();
            return new WriteResult(r.key(), "aktualisiert", text(n.path("html_url")));
        }

        @Override
        public WriteResult comment(String key, String project, NewComment c) {
            requireToken("Kommentieren");
            Ref r = ref(key, project);
            ObjectNode body = HttpJson.object();
            body.put("body", c.body());
            if (!c.inline()) {
                JsonNode n = http.post("/repos/" + r.repo() + "/issues/" + r.number() + "/comments", body).body();
                return new WriteResult(r.key(), "Kommentar hinzugefügt", text(n.path("html_url")), "c" + text(n.path("id")));
            }
            if (c.line() != null && c.line() < 1) {
                throw new IllegalArgumentException("GitHub: 'line' muss ≥ 1 sein (Zeile der neuen Fassung).");
            }
            body.put("commit_id", text(http.getJson(r.path()).path("head").path("sha")));
            body.put("path", c.path());
            if (c.fileLevel()) {
                body.put("subject_type", "file");
            } else {
                body.put("line", c.line());
                body.put("side", "RIGHT");
            }
            JsonNode n = http.post(r.path() + "/comments", body).body();
            return new WriteResult(r.key(), (c.fileLevel() ? "Datei-Kommentar an " + c.path()
                    : "Code-Kommentar an " + c.path() + ":" + c.line()) + " hinzugefügt",
                    text(n.path("html_url")), "c" + text(n.path("id")));
        }

        @Override
        public WriteResult reply(String key, String project, String threadId, String message) {
            requireToken("Antworten");
            Ref r = ref(key, project);
            String id = threadId.trim();
            if (id.startsWith("PRRT_")) {
                ObjectNode vars = HttpJson.object();
                vars.put("thread", id);
                vars.put("body", message);
                JsonNode c = graphql("mutation($thread:ID!,$body:String!){addPullRequestReviewThreadReply("
                        + "input:{pullRequestReviewThreadId:$thread,body:$body}){comment{databaseId url}}}", vars)
                        .path("addPullRequestReviewThreadReply").path("comment");
                return new WriteResult(r.key(), "Antwort in Thread " + id + " hinzugefügt", text(c.path("url")),
                        text(c.path("databaseId")));
            }
            if (id.startsWith("c") && id.substring(1).matches("\\d+")) {
                // allgemeiner Kommentar (Issue-Kommentar) oder Code-Kommentar ohne GraphQL-ID
                try {
                    ObjectNode body = HttpJson.object();
                    body.put("body", message);
                    JsonNode n = http.post(r.path() + "/comments/" + id.substring(1) + "/replies", body).body();
                    return new WriteResult(r.key(), "Antwort hinzugefügt", text(n.path("html_url")), text(n.path("id")));
                } catch (HttpJson.StatusException e) {
                    if (e.status() != 404) {
                        throw e;
                    }
                }
                JsonNode orig = http.getJson("/repos/" + r.repo() + "/issues/comments/" + id.substring(1));
                return comment(key, project, new NewComment("@" + text(orig.path("user").path("login")) + " "
                        + quote(text(orig.path("body"))) + message, null, null));
            }
            if (id.startsWith("r")) {
                return comment(key, project, new NewComment(message, null, null));
            }
            throw new IllegalArgumentException("GitHub: unbekannte Thread-ID '" + id + "' – IDs aus pr_comments verwenden.");
        }

        private static String quote(String body) {
            if (body == null || body.isBlank()) {
                return "\n\n";
            }
            String first = body.strip().lines().findFirst().orElse("");
            return "\n> " + (first.length() > 200 ? first.substring(0, 200) + "…" : first) + "\n\n";
        }

        @Override
        public WriteResult resolve(String key, String project, String threadId, boolean resolved) {
            requireToken("Threads auflösen");
            Ref r = ref(key, project);
            String id = threadId.trim();
            if (!id.startsWith("PRRT_")) {
                throw new IllegalArgumentException("GitHub: nur Code-Threads (ID PRRT_…) lassen sich auflösen; '" + id
                        + "' ist ein allgemeiner Kommentar oder ein Review.");
            }
            ObjectNode vars = HttpJson.object();
            vars.put("thread", id);
            String mutation = resolved ? "resolveReviewThread" : "unresolveReviewThread";
            graphql("mutation($thread:ID!){" + mutation + "(input:{threadId:$thread}){thread{isResolved}}}", vars);
            return new WriteResult(r.key(), "Thread " + id + (resolved ? " als erledigt markiert" : " wieder geöffnet"),
                    null);
        }

        @Override
        public WriteResult merge(String key, String project, MergeOptions o) {
            requireToken("Mergen");
            Ref r = ref(key, project);
            JsonNode pr = http.getJson(r.path());
            ObjectNode body = HttpJson.object();
            if (o.method() != null) {
                body.put("merge_method", o.method());
            }
            if (o.message() != null) {
                body.put("commit_message", o.message());
            }
            body.put("sha", text(pr.path("head").path("sha")));
            JsonNode n = http.put(r.path() + "/merge", body).body();
            String msg = "gemergt (" + text(n.path("sha")) + ")";
            String head = text(pr.path("head").path("ref"));
            boolean sameRepo = r.repo().equalsIgnoreCase(text(pr.path("head").path("repo").path("full_name")));
            if (o.deleteSourceBranch() && head != null && sameRepo) {
                try {
                    http.delete("/repos/" + r.repo() + "/git/refs/heads/" + enc(head).replace("%2F", "/"));
                    msg += ", Branch " + head + " gelöscht";
                } catch (RuntimeException e) {
                    msg += ", Branch " + head + " NICHT gelöscht: " + e.getMessage();
                }
            }
            return new WriteResult(r.key(), msg, text(pr.path("html_url")));
        }
    }
}
