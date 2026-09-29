package systems.grebe.devtools.mcp.modules.ticket.gitlab;

import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.modules.ticket.spi.HttpJson;
import systems.grebe.devtools.mcp.modules.ticket.spi.ProviderSettings;
import systems.grebe.devtools.mcp.modules.ticket.spi.TicketProvider;
import systems.grebe.devtools.mcp.modules.ticket.spi.TicketSystem;
import tools.jackson.databind.JsonNode;

import static systems.grebe.devtools.mcp.modules.ticket.spi.HttpJson.enc;
import static systems.grebe.devtools.mcp.modules.ticket.spi.HttpJson.query;
import static systems.grebe.devtools.mcp.modules.ticket.spi.HttpJson.text;
import static systems.grebe.devtools.mcp.modules.ticket.spi.HttpJson.texts;

/**
 * GitLab.com und selbst betriebenes GitLab über REST API v4: Issues und Issue-Boards (Projekt- und Gruppen-Boards,
 * Spalten = Open, Label-/Assignee-/Milestone-Listen, Closed).
 */
public class GitLabTicketProvider implements TicketProvider {

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
        return 30;
    }

    @Override
    public List<ConfigField> configFields() {
        return List.of(
                ConfigField.of(BASE_URL, "Server-URL", FieldType.URL).withDefault("https://gitlab.com")
                        .withHelp("Ohne /api/v4, z.B. https://gitlab.firma.de"),
                ConfigField.of(TOKEN, "Token", FieldType.SECRET)
                        .withHelp("Personal/Project Access Token mit read_api. Ohne Token nur öffentliche Projekte "
                                + "und keine Boards."));
    }

    @Override
    public String projectHelp() {
        return "Projekt- oder Gruppenpfad, z.B. gruppe/projekt oder gruppe";
    }

    @Override
    public String keyHelp() {
        return "gruppe/projekt#12, #12 bzw. 12 (mit Projekt) oder Issue-URL";
    }

    @Override
    public String queryHelp() {
        return "zusätzliche Parameter der Issues-API als a=b&c=d, z.B. milestone=16.0&weight=3&iteration_id=7";
    }

    @Override
    public TicketSystem create(ProviderSettings s) {
        String base = HttpJson.stripSlash(s.getString(BASE_URL, "https://gitlab.com"));
        if (base.endsWith("/api/v4")) {
            base = base.substring(0, base.length() - 7);
        }
        Map<String, String> headers = new LinkedHashMap<>();
        s.get(TOKEN).ifPresent(t -> headers.put("PRIVATE-TOKEN", t));
        return new GitLab(new HttpJson("GitLab", base + "/api/v4", headers, s.timeout()), base, s.get(TOKEN).isPresent());
    }

    /** GitLab-Anbindung. Paketsichtbar für Tests. */
    static final class GitLab implements TicketSystem {

        private static final Pattern FULL_KEY = Pattern.compile("([\\w.-]+(?:/[\\w.-]+)+)#(\\d+)");
        private static final Pattern SHORT_KEY = Pattern.compile("#?(\\d+)");
        private static final Pattern URL_KEY = Pattern.compile("https?://[^/]+/(.+?)/-/(?:issues|work_items)/(\\d+).*");
        private static final Set<String> RAW_KEYS_BLOCKED = Set.of("private_token", "access_token", "sudo");

        private final HttpJson http;
        private final String webBase;
        private final boolean authenticated;
        /** Pfad → "projects" oder "groups". */
        private final Map<String, String> kinds = new ConcurrentHashMap<>();

        GitLab(HttpJson http, String webBase, boolean authenticated) {
            this.http = http;
            this.webBase = webBase;
            this.authenticated = authenticated;
        }

        @Override
        public String id() {
            return "gitlab";
        }

        @Override
        public Availability probe() {
            try {
                if (!authenticated) {
                    http.getJson("/projects" + query("per_page", 1));
                    return new Availability(true, "ohne Token", null, "anonym – nur öffentliche Projekte, keine Boards");
                }
                String version = text(http.getJson("/version").path("version"));
                JsonNode me = http.getJson("/user");
                return Availability.ok(version, text(me.path("username")));
            } catch (RuntimeException e) {
                return Availability.unavailable(e.getMessage());
            }
        }

        @Override
        public boolean ownsKey(String key) {
            return key != null && key.trim().startsWith(webBase + "/") && URL_KEY.matcher(key.trim()).matches();
        }

        record Ref(String project, int iid) {
            String key() {
                return project + "#" + iid;
            }
        }

        static Ref ref(String key, String project) {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("GitLab: kein Ticket angegeben (z.B. gruppe/projekt#12).");
            }
            String k = key.trim();
            Matcher m = URL_KEY.matcher(k);
            if (m.matches()) {
                return new Ref(m.group(1), Integer.parseInt(m.group(2)));
            }
            m = FULL_KEY.matcher(k);
            if (m.matches()) {
                return new Ref(m.group(1), Integer.parseInt(m.group(2)));
            }
            m = SHORT_KEY.matcher(k);
            if (m.matches()) {
                if (project == null || project.isBlank()) {
                    throw new IllegalArgumentException("GitLab: '" + k + "' braucht ein Projekt – als gruppe/projekt#"
                            + m.group(1) + " angeben oder 'project' setzen.");
                }
                return new Ref(project.trim(), Integer.parseInt(m.group(1)));
            }
            throw new IllegalArgumentException("GitLab: '" + k + "' ist kein Ticket (erwartet gruppe/projekt#12, #12 oder Issue-URL).");
        }

        /** {@code /projects/<id>} oder {@code /groups/<id>} – ein Pfad kann beides sein, Projekt wird zuerst geprüft. */
        String scope(String project) {
            if (project == null || project.isBlank()) {
                return "";
            }
            String p = project.trim();
            String kind = kinds.computeIfAbsent(p, k -> {
                if (exists("/projects/" + enc(k))) {
                    return "projects";
                }
                if (exists("/groups/" + enc(k) + query("with_projects", "false"))) {
                    return "groups";
                }
                throw new IllegalArgumentException("GitLab: '" + k + "' ist weder Projekt noch Gruppe (oder das "
                        + "Token sieht es nicht). Pfad wie in der URL angeben, z.B. gruppe/projekt.");
            });
            return "/" + kind + "/" + enc(p);
        }

        /** 404 = gibt es nicht; andere Fehler (401, Netz) werden durchgereicht statt als „nicht vorhanden“ gedeutet. */
        private boolean exists(String path) {
            try {
                http.getJson(path);
                return true;
            } catch (HttpJson.StatusException e) {
                if (e.status() == 404) {
                    return false;
                }
                throw e;
            }
        }

        // ------------------------------------------------------------------ Boards

        @Override
        public List<Board> boards(String project) {
            if (project == null || project.isBlank()) {
                throw new IllegalArgumentException("GitLab: für Boards 'project' angeben (Projekt- oder Gruppenpfad).");
            }
            String scope = scope(project);
            List<Board> out = new ArrayList<>();
            for (JsonNode b : http.getJson(scope + "/boards" + query("per_page", 100))) {
                out.add(board(b, project.trim(), scope));
            }
            return out;
        }

        private Board board(JsonNode b, String project, String scope) {
            String web = webBase + "/" + (scope.startsWith("/groups/") ? "groups/" : "") + project + "/-/boards/" + text(b.path("id"));
            return new Board(text(b.path("id")), HttpJson.first(text(b.path("name")), "Development"),
                    scope.startsWith("/groups/") ? "group board" : "board", project, web);
        }

        @Override
        public BoardView board(String boardRef, String project, BoardOptions options) {
            if (project == null || project.isBlank()) {
                throw new IllegalArgumentException("GitLab: für ticket_board 'project' angeben (Projekt- oder Gruppenpfad).");
            }
            String scope = scope(project);
            List<Board> all = new ArrayList<>();
            Map<String, JsonNode> raw = new LinkedHashMap<>();
            for (JsonNode b : http.getJson(scope + "/boards" + query("per_page", 100))) {
                Board board = board(b, project.trim(), scope);
                all.add(board);
                raw.put(board.id(), b);
            }
            Board board = TicketSystem.pickBoard(all, boardRef, "GitLab");
            JsonNode b = raw.get(board.id());

            // Grundfilter des Boards (Board-Scope, Premium) plus Zuständige aus dem Aufruf
            Map<String, Object> base = new LinkedHashMap<>();
            List<String> scopeLabels = texts(b.path("labels"), "name");
            if (!scopeLabels.isEmpty()) {
                base.put("labels", String.join(",", scopeLabels));
            }
            putIfText(base, "milestone", text(b.path("milestone").path("title")));
            putIfText(base, "assignee_username", text(b.path("assignee").path("username")));
            applyAssignee(base, options.assignee());

            int max = options.maxPerColumn();
            List<Column> columns = new ArrayList<>();
            Set<String> listLabels = new LinkedHashSet<>();
            List<JsonNode> lists = new ArrayList<>();
            b.path("lists").forEach(lists::add);
            lists.sort((x, y) -> Integer.compare(x.path("position").asInt(0), y.path("position").asInt(0)));
            lists.forEach(l -> {
                String label = text(l.path("label").path("name"));
                if (label != null) {
                    listLabels.add(label);
                }
            });

            if (!b.path("hide_backlog_list").asBoolean(false)) {
                // Open = offene Issues ohne Label einer Liste; die API kann das nicht filtern, daher clientseitig
                Map<String, Object> p = new LinkedHashMap<>(base);
                p.put("state", "opened");
                List<Ticket> open = new ArrayList<>();
                boolean complete = false;
                for (int page = 1; page <= 3; page++) {
                    JsonNode res = issues(scope, p, 100, page).body();
                    for (JsonNode i : res) {
                        if (texts(i.path("labels"), null).stream().noneMatch(listLabels::contains)) {
                            open.add(issue(i));
                        }
                    }
                    if (res.size() < 100) {
                        complete = true;
                        break;
                    }
                }
                // nach 300 geprüften offenen Issues abbrechen: Gesamtzahl dann unbekannt (-1)
                columns.add(new Column("Open", open.subList(0, Math.min(open.size(), max)), complete ? open.size() : -1));
            }
            for (JsonNode l : lists) {
                Map<String, Object> p = new LinkedHashMap<>(base);
                p.put("state", "opened");
                String name;
                String label = text(l.path("label").path("name"));
                if (label != null) {
                    name = label;
                    p.merge("labels", label, (a, c) -> a + "," + c);
                } else if (text(l.path("assignee").path("username")) != null) {
                    name = "@" + text(l.path("assignee").path("username"));
                    p.remove("assignee_id");
                    p.put("assignee_username", text(l.path("assignee").path("username")));
                } else if (text(l.path("milestone").path("title")) != null) {
                    name = "Milestone " + text(l.path("milestone").path("title"));
                    p.put("milestone", text(l.path("milestone").path("title")));
                } else if (text(l.path("iteration").path("id")) != null) {
                    name = "Iteration " + HttpJson.first(text(l.path("iteration").path("title")), text(l.path("iteration").path("id")));
                    p.put("iteration_id", text(l.path("iteration").path("id")));
                } else {
                    continue;
                }
                HttpJson.Response res = issues(scope, p, max, 1);
                List<Ticket> tickets = new ArrayList<>();
                res.body().forEach(i -> tickets.add(issue(i)));
                columns.add(new Column(name, tickets, total(res, tickets.size())));
            }
            if (!b.path("hide_closed_list").asBoolean(false)) {
                Map<String, Object> p = new LinkedHashMap<>(base);
                p.put("state", "closed");
                p.put("updated_after", LocalDate.now().minusDays(14).toString());
                HttpJson.Response res = issues(scope, p, max, 1);
                List<Ticket> tickets = new ArrayList<>();
                res.body().forEach(i -> tickets.add(issue(i)));
                columns.add(new Column("Closed (14 Tage)", tickets, total(res, tickets.size())));
            }
            return new BoardView(board, "offene Issues nach Board-Listen, geschlossene der letzten 14 Tage", columns, null);
        }

        private static void putIfText(Map<String, Object> m, String k, String v) {
            if (v != null && !v.isBlank()) {
                m.put(k, v);
            }
        }

        private void applyAssignee(Map<String, Object> p, String assignee) {
            if (assignee == null || assignee.isBlank()) {
                return;
            }
            if (TicketSystem.isMe(assignee)) {
                if (!authenticated) {
                    throw new IllegalArgumentException("GitLab: assignee=me braucht ein Token (wer ist 'ich'?).");
                }
                p.put("assignee_username", text(http.getJson("/user").path("username")));
            } else if (TicketSystem.isNone(assignee)) {
                p.put("assignee_id", "None");
            } else {
                p.put("assignee_username", assignee.trim().replaceFirst("^@", ""));
            }
        }

        private HttpJson.Response issues(String scope, Map<String, Object> params, int perPage, int page) {
            List<Object> kv = new ArrayList<>();
            params.forEach((k, v) -> {
                kv.add(k);
                kv.add(v);
            });
            kv.addAll(List.of("order_by", "updated_at", "sort", "desc", "per_page", perPage, "page", page));
            // ohne Projekt/Gruppe: /issues liefert sonst nur eigene – scope=all zeigt alles Sichtbare
            if (scope.isEmpty() && !params.containsKey("scope")) {
                kv.addAll(List.of("scope", "all"));
            }
            return http.get(scope + "/issues" + query(kv.toArray()));
        }

        private static int total(HttpJson.Response res, int fallback) {
            String t = res.header("X-Total");
            try {
                return t == null ? fallback : Integer.parseInt(t.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }

        // ------------------------------------------------------------------ Suche

        @Override
        public TicketPage search(TicketQuery q) {
            if (q.project() == null && !authenticated) {
                throw new IllegalArgumentException("GitLab: ohne Token nur mit 'project' suchen.");
            }
            String scope = scope(q.project());
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("state", switch (q.state()) {
                case OPEN -> "opened";
                case CLOSED -> "closed";
                case ALL -> "all";
            });
            if (TicketSystem.isMe(q.assignee())) {
                p.put("scope", "assigned_to_me");
            } else {
                applyAssignee(p, q.assignee());
            }
            if (!q.labels().isEmpty()) {
                p.put("labels", String.join(",", q.labels()));
            }
            putIfText(p, "search", q.text());
            p.putAll(rawParams(q.rawQuery()));
            int limit = Math.max(1, Math.min(q.limit(), 100));
            int page = q.cursor() == null || q.cursor().isBlank() ? 1 : Integer.parseInt(q.cursor().trim());
            HttpJson.Response res = issues(scope, p, limit, page);
            List<Ticket> tickets = new ArrayList<>();
            res.body().forEach(i -> tickets.add(issue(i)));
            String next = res.header("X-Next-Page");
            Integer total = res.header("X-Total") == null ? null : total(res, tickets.size());
            StringBuilder effective = new StringBuilder("GET ").append(scope.isEmpty() ? "" : scope).append("/issues");
            p.forEach((k, v) -> effective.append(effective.indexOf("?") < 0 ? '?' : '&').append(k).append('=').append(v));
            return new TicketPage(tickets, total, next == null || next.isBlank() ? null : next.trim(), effective.toString());
        }

        /** {@code a=b&c=d} bzw. zeilen-/leerzeichengetrennt; Anmeldeparameter werden abgewiesen. */
        static Map<String, String> rawParams(String raw) {
            Map<String, String> out = new LinkedHashMap<>();
            if (raw == null || raw.isBlank()) {
                return out;
            }
            for (String part : raw.trim().split("[&\\n]+")) {
                String s = part.trim();
                if (s.isEmpty()) {
                    continue;
                }
                int eq = s.indexOf('=');
                if (eq <= 0) {
                    throw new IllegalArgumentException("GitLab: 'query' erwartet API-Parameter als name=wert&name=wert "
                            + "(z.B. milestone=16.0), nicht '" + s + "'. Freitext gehört in 'text'.");
                }
                String k = s.substring(0, eq).trim();
                if (RAW_KEYS_BLOCKED.contains(k.toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("GitLab: Parameter '" + k + "' ist nicht erlaubt.");
                }
                out.put(k, s.substring(eq + 1).trim());
            }
            return out;
        }

        // ------------------------------------------------------------------ Einzelnes Ticket

        @Override
        public TicketDetails ticket(String key, String project, int maxComments) {
            Ref ref = ref(key, project);
            String base = "/projects/" + enc(ref.project()) + "/issues/" + ref.iid();
            JsonNode i = http.getJson(base);
            Map<String, String> extra = new LinkedHashMap<>();
            put(extra, "Milestone", text(i.path("milestone").path("title")));
            put(extra, "Iteration", text(i.path("iteration").path("title")));
            put(extra, "Epic", text(i.path("epic").path("title")));
            put(extra, "Gewicht", text(i.path("weight")));
            put(extra, "Fällig", text(i.path("due_date")));
            put(extra, "Aufgaben", i.path("task_completion_status").isObject()
                    ? i.path("task_completion_status").path("completed_count").asInt() + "/"
                    + i.path("task_completion_status").path("count").asInt() + " erledigt" : null);
            if (i.path("merge_requests_count").asInt(0) > 0) {
                extra.put("Merge Requests", String.valueOf(i.path("merge_requests_count").asInt()));
            }
            put(extra, "Geschlossen", text(i.path("closed_at")));

            int total = i.path("user_notes_count").asInt(0);
            List<Comment> comments = new ArrayList<>();
            if (maxComments > 0 && total > 0) {
                // neueste zuerst holen, Systemnotizen (Label geändert …) auslassen, dann chronologisch drehen
                JsonNode notes = http.getJson(base + "/notes" + query("sort", "desc", "order_by", "created_at",
                        "per_page", Math.min(100, maxComments * 3)));
                for (JsonNode n : notes) {
                    if (n.path("system").asBoolean(false)) {
                        continue;
                    }
                    comments.addFirst(new Comment(text(n.path("author").path("username")), text(n.path("created_at")),
                            text(n.path("body"))));
                    if (comments.size() >= maxComments) {
                        break;
                    }
                }
            }
            return new TicketDetails(issue(i), text(i.path("author").path("username")), text(i.path("created_at")),
                    text(i.path("description")), extra, comments, total);
        }

        private static void put(Map<String, String> m, String k, String v) {
            if (v != null && !v.isBlank()) {
                m.put(k, v);
            }
        }

        private Ticket issue(JsonNode i) {
            String state = text(i.path("state"));
            String key = HttpJson.first(text(i.path("references").path("full")), projectFromUrl(text(i.path("web_url")))
                    + "#" + text(i.path("iid")));
            return new Ticket(key, text(i.path("title")), state, "opened".equals(state) ? StatusCategory.TODO
                    : "closed".equals(state) ? StatusCategory.DONE : StatusCategory.UNKNOWN,
                    HttpJson.first(text(i.path("issue_type")), "issue"), text(i.path("severity")) == null
                    || "UNKNOWN".equals(text(i.path("severity"))) ? null : text(i.path("severity")),
                    texts(i.path("assignees"), "username"), texts(i.path("labels"), null), text(i.path("updated_at")),
                    text(i.path("web_url")));
        }

        private static String projectFromUrl(String webUrl) {
            if (webUrl == null) {
                return "?";
            }
            Matcher m = URL_KEY.matcher(webUrl);
            if (m.matches()) {
                return m.group(1);
            }
            try {
                return URI.create(webUrl).getPath();
            } catch (IllegalArgumentException e) {
                return webUrl;
            }
        }
    }
}
