package systems.grebe.devtools.mcp.modules.ticket.github;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.modules.ticket.spi.HttpJson;
import systems.grebe.devtools.mcp.modules.ticket.spi.ProviderSettings;
import systems.grebe.devtools.mcp.modules.ticket.spi.TicketProvider;
import systems.grebe.devtools.mcp.modules.ticket.spi.TicketSystem;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import static systems.grebe.devtools.mcp.modules.ticket.spi.HttpJson.query;
import static systems.grebe.devtools.mcp.modules.ticket.spi.HttpJson.text;
import static systems.grebe.devtools.mcp.modules.ticket.spi.HttpJson.texts;

/**
 * GitHub.com und GitHub Enterprise Server: Issues über die REST-API, Boards sind GitHub Projects (v2, GraphQL) mit
 * ihrem Single-Select-Feld „Status“ als Spalten.
 */
public class GitHubTicketProvider implements TicketProvider {

    static final String BASE_URL = "baseUrl";
    static final String TOKEN = "token";
    static final String STATUS_FIELD = "statusField";

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
        return 20;
    }

    @Override
    public List<ConfigField> configFields() {
        return List.of(
                ConfigField.of(BASE_URL, "API-URL", FieldType.URL).withDefault("https://api.github.com")
                        .withHelp("GitHub Enterprise Server: https://github.firma.de/api/v3"),
                ConfigField.of(TOKEN, "Token", FieldType.SECRET)
                        .withHelp("Fine-grained Token (Issues: read, Projects: read) oder klassisch mit repo, read:project. "
                                + "Ohne Token nur öffentliche Repositories, 60 Anfragen/Stunde und keine Projects."),
                ConfigField.of(STATUS_FIELD, "Spaltenfeld der Projects", FieldType.STRING).withDefault("Status")
                        .withHelp("Single-Select-Feld, nach dem ticket_board die Spalten bildet."));
    }

    @Override
    public String projectHelp() {
        return "owner/repo für Issues; owner (Organisation/Benutzer) oder owner/repo für Projects";
    }

    @Override
    public String keyHelp() {
        return "owner/repo#12, #12 bzw. 12 (mit Projekt owner/repo) oder Issue-URL";
    }

    @Override
    public String queryHelp() {
        return "GitHub-Suchsyntax, z.B. milestone:v2 type:Bug -label:wontfix (is:issue und repo: ergänzt das Tool)";
    }

    @Override
    public TicketSystem create(ProviderSettings s) {
        String base = HttpJson.stripSlash(s.getString(BASE_URL, "https://api.github.com"));
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-GitHub-Api-Version", "2022-11-28");
        s.get(TOKEN).ifPresent(t -> headers.put("Authorization", "Bearer " + t));
        return new GitHub(new HttpJson("GitHub", base, headers, s.timeout()), s.getString(STATUS_FIELD, "Status"),
                s.get(TOKEN).isPresent());
    }

    /** GitHub-Anbindung. Paketsichtbar für Tests. */
    static final class GitHub implements TicketSystem {

        private static final Pattern FULL_KEY = Pattern.compile("([\\w.-]+)/([\\w.-]+)#(\\d+)");
        private static final Pattern SHORT_KEY = Pattern.compile("#?(\\d+)");
        private static final Pattern URL_KEY = Pattern.compile("https?://[^/]+/([\\w.-]+)/([\\w.-]+)/(?:issues|pull)/(\\d+).*");
        private static final int BOARD_MAX = 500;

        private final HttpJson http;
        private final String statusField;
        private final boolean authenticated;
        private final String webHost;
        private final String graphql;

        GitHub(HttpJson http, String statusField, boolean authenticated) {
            this.http = http;
            this.statusField = statusField;
            this.authenticated = authenticated;
            String host = hostOf(http.baseUrl());
            this.webHost = "api.github.com".equals(host) ? "github.com" : host;
            String base = http.baseUrl();
            this.graphql = base.endsWith("/api/v3") ? base.substring(0, base.length() - 3) + "graphql" : base + "/graphql";
        }

        private static String hostOf(String url) {
            try {
                String h = URI.create(url).getHost();
                return h == null ? "" : h.toLowerCase(Locale.ROOT);
            } catch (IllegalArgumentException e) {
                return "";
            }
        }

        @Override
        public String id() {
            return "github";
        }

        @Override
        public Availability probe() {
            try {
                if (!authenticated) {
                    JsonNode rate = http.getJson("/rate_limit").path("resources").path("core");
                    return new Availability(true, "ohne Token", null, "anonym, " + text(rate.path("remaining"))
                            + " Anfragen übrig – nur öffentliche Repositories, keine Projects");
                }
                JsonNode me = http.getJson("/user");
                return Availability.ok(webHost, text(me.path("login")));
            } catch (RuntimeException e) {
                return Availability.unavailable(e.getMessage());
            }
        }

        @Override
        public boolean ownsKey(String key) {
            return key != null && URL_KEY.matcher(key.trim()).matches() && webHost.equals(hostOf(key.trim()));
        }

        /** {@code owner/repo} und Nummer aus den unterstützten Schlüsselformen. */
        record Ref(String repo, int number) {
            String key() {
                return repo + "#" + number;
            }
        }

        static Ref ref(String key, String project) {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("GitHub: kein Ticket angegeben (z.B. owner/repo#12).");
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
                if (project == null || !project.contains("/")) {
                    throw new IllegalArgumentException("GitHub: '" + k + "' braucht ein Repository – als owner/repo#"
                            + m.group(1) + " angeben oder 'project' = owner/repo setzen.");
                }
                return new Ref(project.trim(), Integer.parseInt(m.group(1)));
            }
            throw new IllegalArgumentException("GitHub: '" + k + "' ist kein Ticket (erwartet owner/repo#12, #12 oder Issue-URL).");
        }

        // ------------------------------------------------------------------ Boards (Projects v2)

        private JsonNode graphql(String query, ObjectNode variables) {
            if (!authenticated) {
                throw new IllegalStateException("GitHub Projects brauchen ein Token (GraphQL-API) – in der DevTools-App "
                        + "unter Module → Tickets eintragen. Issues ohne Board liefert ticket_search.");
            }
            ObjectNode body = HttpJson.object();
            body.put("query", query);
            body.set("variables", variables);
            JsonNode res = http.post(graphql, body).body();
            JsonNode errors = res.path("errors");
            if (errors.isArray() && !errors.isEmpty()) {
                List<String> msgs = texts(errors, "message");
                String joined = String.join("; ", msgs);
                if (joined.contains("read:project") || joined.toLowerCase(Locale.ROOT).contains("scope")) {
                    joined += " – das Token braucht Lesezugriff auf Projects (read:project).";
                }
                // bei Organisation/Benutzer liefert eine der beiden Wurzeln immer NOT_FOUND – nur melden, wenn nichts kam
                if (res.path("data").isMissingNode() || res.path("data").isNull()) {
                    throw new IllegalStateException("GitHub GraphQL: " + joined);
                }
            }
            return res.path("data");
        }

        private static final String PROJECT_FIELDS = "nodes { id number title url closed shortDescription }";

        @Override
        public List<Board> boards(String project) {
            if (project == null || project.isBlank()) {
                throw new IllegalArgumentException("GitHub: für Projects 'project' angeben – Organisation/Benutzer "
                        + "(z.B. octo-org) oder owner/repo.");
            }
            String p = project.trim();
            ObjectNode vars = HttpJson.object();
            JsonNode data;
            List<JsonNode> connections = new ArrayList<>();
            if (p.contains("/")) {
                String[] parts = p.split("/", 2);
                vars.put("owner", parts[0]);
                vars.put("name", parts[1]);
                data = graphql("query($owner:String!,$name:String!){repository(owner:$owner,name:$name){projectsV2(first:50,"
                        + "orderBy:{field:UPDATED_AT,direction:DESC}){" + PROJECT_FIELDS + "}}}", vars);
                connections.add(data.path("repository").path("projectsV2"));
            } else {
                vars.put("login", p);
                data = graphql("query($login:String!){organization(login:$login){projectsV2(first:50,orderBy:{field:UPDATED_AT,"
                        + "direction:DESC}){" + PROJECT_FIELDS + "}} user(login:$login){projectsV2(first:50,orderBy:{field:"
                        + "UPDATED_AT,direction:DESC}){" + PROJECT_FIELDS + "}}}", vars);
                connections.add(data.path("organization").path("projectsV2"));
                connections.add(data.path("user").path("projectsV2"));
            }
            List<Board> out = new ArrayList<>();
            for (JsonNode c : connections) {
                for (JsonNode n : c.path("nodes")) {
                    String type = n.path("closed").asBoolean(false) ? "project (geschlossen)" : "project";
                    out.add(new Board(text(n.path("id")), text(n.path("title")) + " (#" + text(n.path("number")) + ")",
                            type, p, text(n.path("url"))));
                }
            }
            return out;
        }

        private static final String ITEMS_QUERY = """
                query($id:ID!,$after:String,$field:String!){ viewer { login }
                  node(id:$id){ ... on ProjectV2 { id number title url
                    field(name:$field){ ... on ProjectV2SingleSelectField { options { name } } }
                    items(first:100, after:$after){ totalCount pageInfo { hasNextPage endCursor }
                      nodes { isArchived
                        fieldValueByName(name:$field){ ... on ProjectV2ItemFieldSingleSelectValue { name } }
                        content { __typename
                          ... on Issue { number title url state stateReason updatedAt repository { nameWithOwner }
                            assignees(first:10){ nodes { login } } labels(first:10){ nodes { name } } }
                          ... on PullRequest { number title url state updatedAt repository { nameWithOwner }
                            assignees(first:10){ nodes { login } } labels(first:10){ nodes { name } } }
                          ... on DraftIssue { title updatedAt assignees(first:10){ nodes { login } } } } } } } } }""";

        @Override
        public BoardView board(String boardRef, String project, BoardOptions options) {
            Board board = boardRef != null && boardRef.trim().startsWith("PVT_")
                    ? new Board(boardRef.trim(), boardRef.trim(), "project", project, null)
                    : TicketSystem.pickBoard(boards(project), boardRef, "GitHub");

            Map<String, List<Ticket>> byColumn = new LinkedHashMap<>();
            String after = null;
            String viewer = null;
            int loaded = 0;
            boolean truncated = false;
            String title = board.name();
            String url = board.url();
            while (true) {
                ObjectNode vars = HttpJson.object();
                vars.put("id", board.id());
                vars.put("field", statusField);
                if (after != null) {
                    vars.put("after", after);
                }
                JsonNode data = graphql(ITEMS_QUERY, vars);
                viewer = text(data.path("viewer").path("login"));
                JsonNode proj = data.path("node");
                if (proj.isMissingNode() || proj.isNull() || proj.path("id").isMissingNode()) {
                    throw new IllegalArgumentException("GitHub: Project '" + board.id() + "' nicht gefunden – "
                            + "ticket_boards listet die verfügbaren.");
                }
                title = text(proj.path("title")) + " (#" + text(proj.path("number")) + ")";
                url = text(proj.path("url"));
                if (byColumn.isEmpty()) {
                    texts(proj.path("field").path("options"), "name").forEach(o -> byColumn.put(o, new ArrayList<>()));
                }
                JsonNode items = proj.path("items");
                for (JsonNode item : items.path("nodes")) {
                    if (item.path("isArchived").asBoolean(false)) {
                        continue;
                    }
                    Ticket t = projectItem(item.path("content"));
                    if (t == null || !assigneeMatches(t, options.assignee(), viewer)) {
                        continue;
                    }
                    String col = HttpJson.first(text(item.path("fieldValueByName").path("name")), "(ohne " + statusField + ")");
                    byColumn.computeIfAbsent(col, k -> new ArrayList<>()).add(t);
                }
                loaded += items.path("nodes").size();
                if (!items.path("pageInfo").path("hasNextPage").asBoolean(false)) {
                    break;
                }
                if (loaded >= BOARD_MAX) {
                    truncated = true;
                    break;
                }
                after = text(items.path("pageInfo").path("endCursor"));
            }
            List<Column> columns = new ArrayList<>();
            byColumn.forEach((name, list) -> {
                list.sort((a, b) -> String.valueOf(b.updated()).compareTo(String.valueOf(a.updated())));
                columns.add(new Column(name, list.subList(0, Math.min(list.size(), options.maxPerColumn())), list.size()));
            });
            Board resolved = new Board(board.id(), title, board.type(), board.project(), url);
            return new BoardView(resolved, "alle nicht archivierten Einträge, Spalten = Feld '" + statusField + "'", columns,
                    truncated ? "nur die ersten " + BOARD_MAX + " Einträge geladen" : null);
        }

        private static boolean assigneeMatches(Ticket t, String assignee, String viewer) {
            if (assignee == null || assignee.isBlank()) {
                return true;
            }
            if (TicketSystem.isNone(assignee)) {
                return t.assignees().isEmpty();
            }
            String who = TicketSystem.isMe(assignee) ? viewer : assignee.trim();
            return who != null && t.assignees().stream().anyMatch(a -> a.equalsIgnoreCase(who));
        }

        private Ticket projectItem(JsonNode c) {
            String type = text(c.path("__typename"));
            if (type == null) {
                return null; // kein Zugriff auf das verknüpfte Issue
            }
            List<String> assignees = texts(c.path("assignees").path("nodes"), "login");
            if ("DraftIssue".equals(type)) {
                return new Ticket("(Entwurf)", text(c.path("title")), "Entwurf", StatusCategory.TODO, "Draft", null,
                        assignees, List.of(), text(c.path("updatedAt")), null);
            }
            String state = text(c.path("state"));
            String key = text(c.path("repository").path("nameWithOwner")) + "#" + text(c.path("number"));
            return new Ticket(key, text(c.path("title")), stateText(state, text(c.path("stateReason"))),
                    category(state), "PullRequest".equals(type) ? "Pull Request" : "Issue", null, assignees,
                    texts(c.path("labels").path("nodes"), "name"), text(c.path("updatedAt")), text(c.path("url")));
        }

        // ------------------------------------------------------------------ Suche

        /** Suchausdruck für {@code /search/issues}. */
        static String searchQuery(TicketQuery q) {
            List<String> parts = new ArrayList<>(List.of("is:issue"));
            if (q.project() != null && !q.project().isBlank()) {
                String p = q.project().trim();
                parts.add(p.contains("/") ? "repo:" + p : "user:" + p);
            }
            switch (q.state()) {
                case OPEN -> parts.add("is:open");
                case CLOSED -> parts.add("is:closed");
                default -> { }
            }
            if (TicketSystem.isMe(q.assignee())) {
                parts.add("assignee:@me");
            } else if (TicketSystem.isNone(q.assignee())) {
                parts.add("no:assignee");
            } else if (q.assignee() != null && !q.assignee().isBlank()) {
                parts.add("assignee:" + q.assignee().trim());
            }
            q.labels().forEach(l -> parts.add("label:" + (l.contains(" ") ? "\"" + l + "\"" : l)));
            if (q.rawQuery() != null && !q.rawQuery().isBlank()) {
                parts.add(q.rawQuery().trim());
            }
            if (q.text() != null && !q.text().isBlank()) {
                parts.add(q.text().trim());
            }
            return String.join(" ", parts);
        }

        @Override
        public TicketPage search(TicketQuery q) {
            String expr = searchQuery(q);
            if (TicketSystem.isMe(q.assignee()) && !authenticated) {
                throw new IllegalArgumentException("GitHub: assignee=me braucht ein Token (wer ist 'ich'?).");
            }
            int limit = Math.max(1, Math.min(q.limit(), 100));
            int page = q.cursor() == null || q.cursor().isBlank() ? 1 : Integer.parseInt(q.cursor().trim());
            // advanced_search ist auf github.com Standard und ab dort Pflicht; GHES kennt den Parameter (noch) nicht
            JsonNode res = http.getJson("/search/issues" + query("q", expr, "sort", "updated", "order", "desc",
                    "per_page", limit, "page", page, "advanced_search", "github.com".equals(webHost) ? "true" : null));
            List<Ticket> tickets = new ArrayList<>();
            res.path("items").forEach(i -> tickets.add(restIssue(i)));
            int total = res.path("total_count").asInt(tickets.size());
            // die Such-API liefert höchstens 1000 Treffer
            boolean more = !tickets.isEmpty() && page * limit < Math.min(total, 1000);
            return new TicketPage(tickets, total, more ? String.valueOf(page + 1) : null, expr);
        }

        // ------------------------------------------------------------------ Einzelnes Ticket

        @Override
        public TicketDetails ticket(String key, String project, int maxComments) {
            Ref ref = ref(key, project);
            String base = "/repos/" + ref.repo() + "/issues/" + ref.number();
            JsonNode i = http.getJson(base);
            Map<String, String> extra = new LinkedHashMap<>();
            if (i.has("pull_request")) {
                extra.put("Art", "Pull Request (" + HttpJson.first(text(i.path("pull_request").path("merged_at")) != null
                        ? "gemergt" : null, "nicht gemergt") + ")");
            }
            put(extra, "Milestone", text(i.path("milestone").path("title")));
            put(extra, "Geschlossen", text(i.path("closed_at")));
            put(extra, "Geschlossen von", text(i.path("closed_by").path("login")));

            int total = i.path("comments").asInt(0);
            List<Comment> comments = new ArrayList<>();
            if (maxComments > 0 && total > 0) {
                int per = Math.min(100, maxComments);
                int last = (total + per - 1) / per;
                List<JsonNode> raw = new ArrayList<>();
                // letzte Seite, bei Bedarf die vorletzte davor – so kommen die neuesten Kommentare
                if (last > 1 && total % per != 0) {
                    http.getJson(base + "/comments" + query("per_page", per, "page", last - 1)).forEach(raw::add);
                }
                http.getJson(base + "/comments" + query("per_page", per, "page", last)).forEach(raw::add);
                for (JsonNode c : raw.subList(Math.max(0, raw.size() - maxComments), raw.size())) {
                    comments.add(new Comment(text(c.path("user").path("login")), text(c.path("created_at")),
                            text(c.path("body"))));
                }
            }
            return new TicketDetails(restIssue(i), text(i.path("user").path("login")), text(i.path("created_at")),
                    text(i.path("body")), extra, comments, total);
        }

        private static void put(Map<String, String> m, String k, String v) {
            if (v != null && !v.isBlank()) {
                m.put(k, v);
            }
        }

        private Ticket restIssue(JsonNode i) {
            String repo = repoFromUrl(text(i.path("repository_url")));
            String state = text(i.path("state"));
            String type = i.has("pull_request") ? "Pull Request" : HttpJson.first(text(i.path("type").path("name")), "Issue");
            return new Ticket(repo + "#" + text(i.path("number")), text(i.path("title")),
                    stateText(state, text(i.path("state_reason"))), category(state), type, null,
                    texts(i.path("assignees"), "login"), texts(i.path("labels"), "name"), text(i.path("updated_at")),
                    text(i.path("html_url")));
        }

        private static String repoFromUrl(String repositoryUrl) {
            if (repositoryUrl == null) {
                return "?";
            }
            int repos = repositoryUrl.lastIndexOf("/repos/");
            return repos < 0 ? repositoryUrl : repositoryUrl.substring(repos + 7);
        }

        private static String stateText(String state, String reason) {
            if (state == null) {
                return null;
            }
            String s = state.toLowerCase(Locale.ROOT);
            return reason == null || reason.isBlank() || "reopened".equalsIgnoreCase(reason) ? s
                    : s + " (" + reason.toLowerCase(Locale.ROOT) + ")";
        }

        private static StatusCategory category(String state) {
            if (state == null) {
                return StatusCategory.UNKNOWN;
            }
            return "open".equalsIgnoreCase(state) ? StatusCategory.TODO : StatusCategory.DONE;
        }
    }
}
