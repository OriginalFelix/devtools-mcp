package systems.grebe.devtools.mcp.modules.pr.gitlab;

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

/** GitLab.com und selbst betriebenes GitLab: Merge Requests, Diskussionen und Pipelines über die REST API v4. */
public class GitLabServerProvider implements GitServerProvider {

    static final String BASE_URL = "baseUrl";
    static final String TOKEN = "token";

    @Override
    public String id() {
        return "gitlab";
    }

    @Override
    public String displayName() {
        return "GitLab";
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
                        .withHelp("Personal/Project Access Token mit api (Schreiben) bzw. read_api (nur Lesen)."));
    }

    @Override
    public String projectHelp() {
        return "Projektpfad, z.B. gruppe/projekt";
    }

    @Override
    public String keyHelp() {
        return "gruppe/projekt!12, !12 bzw. 12 (mit Projekt) oder Merge-Request-URL";
    }

    @Override
    public GitServer create(ProviderSettings s) {
        String base = HttpJson.stripSlash(s.getString(BASE_URL, "https://gitlab.com"));
        if (base.endsWith("/api/v4")) {
            base = base.substring(0, base.length() - 7);
        }
        Map<String, String> headers = new LinkedHashMap<>();
        s.get(TOKEN).ifPresent(t -> headers.put("PRIVATE-TOKEN", t));
        return new GitLab(new HttpJson("GitLab", base + "/api/v4", headers, s.timeout(), "Pull Requests"), base,
                s.get(TOKEN).isPresent());
    }

    /** GitLab-Anbindung. Paketsichtbar für Tests. */
    static final class GitLab implements GitServer {

        private static final Pattern FULL_KEY = Pattern.compile("([\\w.-]+(?:/[\\w.-]+)+)[!#](\\d+)");
        private static final Pattern SHORT_KEY = Pattern.compile("[!#]?(\\d+)");
        private static final Pattern URL_KEY = Pattern.compile("https?://[^/]+/(.+?)/-/merge_requests/(\\d+).*");
        private static final int MAX_PAGES = 10;

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
            Remote r = GitServer.parseRemote(remoteUrl);
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
            return ref != null && ref.trim().startsWith(webBase + "/") && URL_KEY.matcher(ref.trim()).matches();
        }

        @Override
        public String projectOf(String ref, String project) {
            return ref(ref, project).project();
        }

        record Ref(String project, int iid) {
            String key() {
                return project + "!" + iid;
            }

            String path() {
                return "/projects/" + enc(project) + "/merge_requests/" + iid;
            }
        }

        Ref ref(String key, String project) {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("GitLab: kein Merge Request angegeben (z.B. gruppe/projekt!12 oder 12).");
            }
            String k = key.trim();
            Matcher m = URL_KEY.matcher(k);
            if (m.matches()) {
                String p = m.group(1);
                // URL enthält den Kontextpfad
                String prefix = webBase + "/";
                if (k.startsWith(prefix)) {
                    p = k.substring(prefix.length(), k.indexOf("/-/merge_requests/"));
                }
                return new Ref(p, Integer.parseInt(m.group(2)));
            }
            m = FULL_KEY.matcher(k);
            if (m.matches()) {
                return new Ref(m.group(1), Integer.parseInt(m.group(2)));
            }
            m = SHORT_KEY.matcher(k);
            if (m.matches()) {
                return new Ref(project(project), Integer.parseInt(m.group(1)));
            }
            throw new IllegalArgumentException("GitLab: '" + k + "' ist kein Merge Request (erwartet gruppe/projekt!12, "
                    + "!12 oder Merge-Request-URL).");
        }

        static String project(String project) {
            if (project == null || !project.trim().contains("/")) {
                throw new IllegalArgumentException("GitLab: Projekt fehlt oder ist ungültig ('" + project
                        + "') – als gruppe/projekt angeben oder ein lokales Repository mit GitLab-Remote wählen.");
            }
            return project.trim();
        }

        private void requireToken(String what) {
            if (!authenticated) {
                throw new IllegalStateException("GitLab: " + what + " braucht ein Token – in der DevTools-App unter "
                        + "Module → Pull Requests eintragen.");
            }
        }

        /** Alle Seiten (Header X-Next-Page), höchstens {@code max} Einträge. */
        private List<JsonNode> pages(String path, int max) {
            List<JsonNode> out = new ArrayList<>();
            String sep = path.contains("?") ? "&" : "?";
            String next = "1";
            for (int i = 0; next != null && !next.isBlank() && i < MAX_PAGES && out.size() < max; i++) {
                HttpJson.Response res = http.get(path + sep + "page=" + next);
                res.body().forEach(out::add);
                next = res.header("X-Next-Page");
            }
            return out.size() > max ? out.subList(0, max) : out;
        }

        // ------------------------------------------------------------------ Lesen

        @Override
        public List<PullRequest> list(PrQuery q) {
            String project = project(q.project());
            String state = switch (q.state()) {
                case OPEN -> "opened";
                case MERGED -> "merged";
                case CLOSED -> "closed";
                case ALL -> "all";
            };
            boolean me = q.author() != null && GitServer.isMe(q.author());
            int limit = Math.max(1, Math.min(q.limit(), 100));
            List<PullRequest> out = new ArrayList<>();
            for (JsonNode n : pages("/projects/" + enc(project) + "/merge_requests" + query("state", state,
                    "author_username", me ? null : q.author(), "scope", me ? "created_by_me" : null,
                    "source_branch", q.source(), "target_branch", q.target(), "order_by", "updated_at",
                    "per_page", limit), limit)) {
                out.add(pr(n, project));
            }
            return out;
        }

        private static PullRequest pr(JsonNode n, String project) {
            String state = text(n.path("state"));
            if ("opened".equals(state)) {
                state = "open";
            }
            return new PullRequest(project + "!" + text(n.path("iid")), text(n.path("title")), state,
                    n.path("draft").asBoolean(n.path("work_in_progress").asBoolean(false)),
                    text(n.path("author").path("username")), text(n.path("source_branch")),
                    text(n.path("target_branch")), text(n.path("updated_at")), text(n.path("web_url")));
        }

        @Override
        public PrDetails get(String key, String project) {
            Ref r = ref(key, project);
            JsonNode n = http.getJson(r.path());
            List<String> approved = List.of();
            try {
                approved = new ArrayList<>();
                for (JsonNode a : http.getJson(r.path() + "/approvals").path("approved_by")) {
                    approved.add(text(a.path("user").path("username")));
                }
            } catch (RuntimeException e) {
                // Freigaben sind optional (ältere Versionen, fehlende Rechte)
            }
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("Kommentare", text(n.path("user_notes_count")));
            fields.put("Änderungen", text(n.path("changes_count")));
            List<String> labels = texts(n.path("labels"), null);
            if (!labels.isEmpty()) {
                fields.put("Labels", String.join(", ", labels));
            }
            fields.put("Milestone", text(n.path("milestone").path("title")));
            fields.put("Zuständig", String.join(", ", texts(n.path("assignees"), "username")));
            if (n.path("squash").asBoolean(false)) {
                fields.put("Squash", "ja");
            }
            if (n.path("force_remove_source_branch").asBoolean(false)) {
                fields.put("Quell-Branch löschen", "ja");
            }
            List<Check> checks = new ArrayList<>();
            JsonNode pipeline = n.path("head_pipeline");
            if (pipeline.isObject()) {
                checks.add(new Check("Pipeline #" + text(pipeline.path("id")), text(pipeline.path("status")),
                        text(pipeline.path("web_url"))));
                if ("failed".equals(text(pipeline.path("status")))) {
                    checks.addAll(failedJobs(text(pipeline.path("project_id")), text(pipeline.path("id"))));
                }
            }
            return new PrDetails(pr(n, r.project()), text(n.path("description")), text(n.path("created_at")),
                    texts(n.path("reviewers"), "username"), approved, mergeStatus(n),
                    text(n.path("sha")), checks, fields);
        }

        private List<Check> failedJobs(String projectId, String pipelineId) {
            List<Check> out = new ArrayList<>();
            try {
                http.getJson("/projects/" + projectId + "/pipelines/" + pipelineId + "/jobs" + query("scope", "failed",
                        "per_page", 50)).forEach(j -> out.add(new Check("  Job " + text(j.path("name")) + " ("
                        + text(j.path("stage")) + ")", text(j.path("status")), text(j.path("web_url")))));
            } catch (RuntimeException e) {
                // nur Zusatzinfo
            }
            return out;
        }

        private static String mergeStatus(JsonNode n) {
            String state = text(n.path("state"));
            if ("merged".equals(state)) {
                return "gemergt" + (text(n.path("merged_by").path("username")) == null ? ""
                        : " von " + text(n.path("merged_by").path("username")));
            }
            if ("closed".equals(state)) {
                return "geschlossen";
            }
            String s = HttpJson.first(text(n.path("detailed_merge_status")), text(n.path("merge_status")));
            if (s == null) {
                return null;
            }
            String desc = switch (s) {
                case "mergeable", "can_be_merged" -> "mergeable";
                case "conflict", "cannot_be_merged" -> "Konflikte mit dem Ziel-Branch";
                case "not_approved" -> "blockiert: Freigaben fehlen";
                case "discussions_not_resolved" -> "blockiert: offene Diskussionen";
                case "draft_status" -> "blockiert: Entwurf";
                case "ci_must_pass", "ci_still_running" -> "blockiert: Pipeline muss erfolgreich sein";
                case "need_rebase" -> "Rebase nötig";
                case "checking", "unchecked", "preparing", "approvals_syncing" -> "wird geprüft";
                default -> s;
            };
            return n.path("has_conflicts").asBoolean(false) && !desc.startsWith("Konflikte") ? desc + ", Konflikte" : desc;
        }

        @Override
        public List<FileChange> diff(String key, String project) {
            Ref r = ref(key, project);
            List<JsonNode> files;
            try {
                files = pages(r.path() + "/diffs" + query("per_page", 100), 1000);
            } catch (HttpJson.StatusException e) {
                if (e.status() != 404) {
                    throw e;
                }
                // vor GitLab 15.7
                files = new ArrayList<>();
                http.getJson(r.path() + "/changes").path("changes").forEach(files::add);
            }
            List<FileChange> out = new ArrayList<>();
            for (JsonNode f : files) {
                String status = f.path("new_file").asBoolean(false) ? "added"
                        : f.path("deleted_file").asBoolean(false) ? "removed"
                        : f.path("renamed_file").asBoolean(false) ? "renamed" : "modified";
                String patch = text(f.path("diff"));
                int[] counts = count(patch);
                out.add(new FileChange(text(f.path("new_path")), "renamed".equals(status) ? text(f.path("old_path")) : null,
                        status, counts[0], counts[1], patch));
            }
            return out;
        }

        static int[] count(String patch) {
            if (patch == null) {
                return new int[] {-1, -1};
            }
            int add = 0;
            int del = 0;
            for (String l : patch.split("\n")) {
                if (l.startsWith("+") && !l.startsWith("+++")) {
                    add++;
                } else if (l.startsWith("-") && !l.startsWith("---")) {
                    del++;
                }
            }
            return new int[] {add, del};
        }

        @Override
        public List<Thread> threads(String key, String project) {
            Ref r = ref(key, project);
            List<Thread> out = new ArrayList<>();
            for (JsonNode d : pages(r.path() + "/discussions" + query("per_page", 100), 1000)) {
                List<Comment> comments = new ArrayList<>();
                JsonNode position = null;
                Boolean resolved = null;
                for (JsonNode note : d.path("notes")) {
                    if (note.path("system").asBoolean(false)) {
                        continue;
                    }
                    comments.add(new Comment(text(note.path("id")), text(note.path("author").path("username")),
                            text(note.path("created_at")), text(note.path("body")), integration(note.path("author"))));
                    if (position == null && note.path("position").isObject()) {
                        position = note.path("position");
                    }
                    if (note.path("resolvable").asBoolean(false)) {
                        resolved = note.path("resolved").asBoolean(false) && (resolved == null || resolved);
                    }
                }
                if (comments.isEmpty()) {
                    continue;
                }
                String path = position == null ? null : HttpJson.first(text(position.path("new_path")),
                        text(position.path("old_path")));
                JsonNode line = position == null ? null
                        : position.path("new_line").isNumber() ? position.path("new_line") : position.path("old_line");
                Integer lineNo = line != null && line.isNumber() ? line.asInt() : null;
                out.add(new Thread(text(d.path("id")), path == null ? "Kommentar" : lineNo == null ? "Datei" : "Code",
                        resolved, path, lineNo, false, comments));
            }
            out.sort(Comparator.comparing(t -> String.valueOf(t.comments().getFirst().created())));
            return out;
        }

        private static final Pattern BOT_USER = Pattern.compile("(?:project|group)_\\d+_bot.*|service_account_.*|.*\\[bot]");

        /** Projekt-/Gruppen-Bot (Access Token), Dienstkonto oder ein als Bot markierter Benutzer. */
        static boolean integration(JsonNode author) {
            String user = text(author.path("username"));
            return author.path("bot").asBoolean(false) || user != null && BOT_USER.matcher(user).matches();
        }

        /** Testbericht der Pipeline und externe Status-Checks (Premium) des Merge Requests. */
        @Override
        public List<Insight> insights(String key, String project) {
            Ref r = ref(key, project);
            List<Insight> out = new ArrayList<>();
            JsonNode pipeline = http.getJson(r.path()).path("head_pipeline");
            if (pipeline.isObject()) {
                try {
                    Insight tests = testReport(http.getJson("/projects/" + text(pipeline.path("project_id"))
                            + "/pipelines/" + text(pipeline.path("id")) + "/test_report_summary"), pipeline);
                    if (tests != null) {
                        out.add(tests);
                    }
                } catch (HttpJson.StatusException e) {
                    // kein Testbericht (ältere Versionen, Pipeline ohne JUnit-Artefakte)
                }
            }
            try {
                for (JsonNode c : http.getJson(r.path() + "/status_checks")) {
                    out.add(new Insight(text(c.path("id")), text(c.path("name")), "Externer Status-Check",
                            text(c.path("status")), null, text(c.path("external_url")), null, null, 0));
                }
            } catch (HttpJson.StatusException e) {
                // nur GitLab Ultimate
            }
            return out;
        }

        private static Insight testReport(JsonNode summary, JsonNode pipeline) {
            JsonNode total = summary.path("total");
            if (total.path("count").asInt(0) == 0) {
                return null;
            }
            Map<String, String> data = new LinkedHashMap<>();
            data.put("Tests", text(total.path("count")));
            data.put("Erfolgreich", text(total.path("success")));
            data.put("Fehlgeschlagen", text(total.path("failed")));
            data.put("Fehler", text(total.path("error")));
            data.put("Übersprungen", text(total.path("skipped")));
            data.put("Dauer", text(total.path("time")) + " s");
            List<Annotation> failed = new ArrayList<>();
            for (JsonNode suite : summary.path("test_suites")) {
                int bad = suite.path("failed_count").asInt(0) + suite.path("error_count").asInt(0);
                if (bad > 0) {
                    failed.add(new Annotation(null, null, "failed", null, "Suite " + text(suite.path("name")) + ": "
                            + bad + " von " + text(suite.path("total_count")) + " fehlgeschlagen", null));
                }
            }
            boolean ok = total.path("failed").asInt(0) + total.path("error").asInt(0) == 0;
            return new Insight("tests-" + text(pipeline.path("id")), "Testbericht Pipeline #" + text(pipeline.path("id")),
                    "GitLab CI", ok ? "success" : "failed", null, text(pipeline.path("web_url")) + "/test_report", data,
                    failed, failed.size());
        }

        // ------------------------------------------------------------------ Schreiben

        @Override
        public WriteResult create(String project, NewPullRequest p) {
            requireToken("Anlegen");
            String pp = project(project);
            String target = p.target() != null ? p.target()
                    : text(http.getJson("/projects/" + enc(pp)).path("default_branch"));
            ObjectNode body = HttpJson.object();
            body.put("source_branch", p.source());
            body.put("target_branch", target);
            body.put("title", p.draft() && !p.title().toLowerCase(Locale.ROOT).startsWith("draft:")
                    ? "Draft: " + p.title() : p.title());
            if (p.description() != null) {
                body.put("description", p.description());
            }
            if (p.deleteSourceBranch()) {
                body.put("remove_source_branch", true);
            }
            String note = "";
            if (!p.reviewers().isEmpty()) {
                var ids = body.putArray("reviewer_ids");
                List<String> unknown = new ArrayList<>();
                for (String u : p.reviewers()) {
                    String name = u.startsWith("@") ? u.substring(1) : u;
                    JsonNode users = http.getJson("/users" + query("username", name));
                    if (users.isArray() && !users.isEmpty()) {
                        ids.add(users.get(0).path("id").asLong());
                    } else {
                        unknown.add(name);
                    }
                }
                if (!unknown.isEmpty()) {
                    note = " – unbekannte Reviewer ignoriert: " + String.join(", ", unknown);
                }
            }
            JsonNode n;
            try {
                n = http.post("/projects/" + enc(pp) + "/merge_requests", body).body();
            } catch (HttpJson.StatusException e) {
                if (e.status() == 409) {
                    throw new IllegalStateException("GitLab: für " + p.source() + " → " + target + " gibt es schon einen "
                            + "offenen Merge Request – mit pr_list (source=" + p.source() + ") finden.", e);
                }
                if (e.getMessage().contains("source_branch") || e.getMessage().contains("Source branch")) {
                    throw new IllegalStateException("GitLab: Branch '" + p.source() + "' ist auf dem Server nicht "
                            + "vorhanden – zuerst pushen (pr_push). " + e.getMessage(), e);
                }
                throw e;
            }
            return new WriteResult(pp + "!" + text(n.path("iid")), (p.draft() ? "Entwurf" : "Merge Request")
                    + " angelegt: " + p.source() + " → " + target + note, text(n.path("web_url")));
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
                body.put("target_branch", u.target());
            }
            JsonNode n = http.put(r.path(), body).body();
            return new WriteResult(r.key(), "aktualisiert", text(n.path("web_url")));
        }

        @Override
        public WriteResult comment(String key, String project, NewComment c) {
            requireToken("Kommentieren");
            Ref r = ref(key, project);
            ObjectNode body = HttpJson.object();
            body.put("body", c.body());
            if (!c.inline()) {
                JsonNode n = http.post(r.path() + "/notes", body).body();
                return new WriteResult(r.key(), "Kommentar hinzugefügt", noteUrl(r, n), text(n.path("id")));
            }
            if (c.line() != null && c.line() < 1) {
                throw new IllegalArgumentException("GitLab: 'line' muss ≥ 1 sein (Zeile der neuen Fassung).");
            }
            JsonNode refs = http.getJson(r.path()).path("diff_refs");
            ObjectNode pos = body.putObject("position");
            // position_type=file (ab GitLab 16.4) verankert den Kommentar an der Datei statt an einer Zeile
            pos.put("position_type", c.fileLevel() ? "file" : "text");
            pos.put("base_sha", text(refs.path("base_sha")));
            pos.put("head_sha", text(refs.path("head_sha")));
            pos.put("start_sha", text(refs.path("start_sha")));
            pos.put("new_path", c.path());
            pos.put("old_path", c.path());
            if (!c.fileLevel()) {
                pos.put("new_line", c.line());
            }
            JsonNode d = http.post(r.path() + "/discussions", body).body();
            JsonNode note = d.path("notes").path(0);
            return new WriteResult(r.key(), (c.fileLevel() ? "Datei-Kommentar an " + c.path()
                    : "Code-Kommentar an " + c.path() + ":" + c.line()) + " hinzugefügt (Thread "
                    + text(d.path("id")) + ")", noteUrl(r, note), text(d.path("id")));
        }

        private String noteUrl(Ref r, JsonNode note) {
            String id = text(note.path("id"));
            return webBase + "/" + r.project() + "/-/merge_requests/" + r.iid() + (id == null ? "" : "#note_" + id);
        }

        @Override
        public WriteResult reply(String key, String project, String threadId, String message) {
            requireToken("Antworten");
            Ref r = ref(key, project);
            ObjectNode body = HttpJson.object();
            body.put("body", message);
            JsonNode n = http.post(r.path() + "/discussions/" + enc(threadId.trim()) + "/notes", body).body();
            return new WriteResult(r.key(), "Antwort in Thread " + threadId.trim() + " hinzugefügt", noteUrl(r, n),
                    text(n.path("id")));
        }

        @Override
        public WriteResult resolve(String key, String project, String threadId, boolean resolved) {
            requireToken("Threads auflösen");
            Ref r = ref(key, project);
            http.put(r.path() + "/discussions/" + enc(threadId.trim()) + query("resolved", resolved), HttpJson.object());
            return new WriteResult(r.key(), "Thread " + threadId.trim() + (resolved ? " als erledigt markiert"
                    : " wieder geöffnet"), webBase + "/" + r.project() + "/-/merge_requests/" + r.iid());
        }

        @Override
        public WriteResult merge(String key, String project, MergeOptions o) {
            requireToken("Mergen");
            Ref r = ref(key, project);
            if ("rebase".equals(o.method())) {
                throw new IllegalArgumentException("GitLab: die Merge-Methode (Merge-Commit, Fast-Forward) legt das "
                        + "Projekt fest; möglich sind method=merge oder method=squash.");
            }
            JsonNode mr = http.getJson(r.path());
            ObjectNode body = HttpJson.object();
            body.put("sha", text(mr.path("sha")));
            if ("squash".equals(o.method())) {
                body.put("squash", true);
                if (o.message() != null) {
                    body.put("squash_commit_message", o.message());
                }
            } else if (o.message() != null) {
                body.put("merge_commit_message", o.message());
            }
            if (o.deleteSourceBranch()) {
                body.put("should_remove_source_branch", true);
            }
            JsonNode n = http.put(r.path() + "/merge", body).body();
            return new WriteResult(r.key(), "gemergt (" + HttpJson.first(text(n.path("merge_commit_sha")),
                    text(n.path("squash_commit_sha")), text(n.path("state"))) + ")"
                    + (o.deleteSourceBranch() ? ", Quell-Branch wird gelöscht" : ""), text(n.path("web_url")));
        }
    }
}
