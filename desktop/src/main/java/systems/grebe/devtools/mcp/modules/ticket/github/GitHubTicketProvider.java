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

        // ------------------------------------------------------------------ Verknüpfungen, Statuswechsel, Schreiben

        @Override
        public String projectOf(String key, String project) {
            return ref(key, project).repo();
        }

        private void requireToken(String what) {
            if (!authenticated) {
                throw new IllegalStateException("GitHub: " + what + " braucht ein Token – in der DevTools-App unter "
                        + "Module → Tickets eintragen.");
            }
        }

        private static final String LINKS_QUERY = """
                query($owner:String!,$name:String!,$number:Int!){ repository(owner:$owner,name:$name){
                  issueOrPullRequest(number:$number){ __typename
                    ... on Issue {
                      parent { number title state url repository { nameWithOwner } }
                      subIssues(first:50){ nodes { number title state url repository { nameWithOwner } } }
                      closedByPullRequestsReferences(first:20, includeClosedPrs:true){
                        nodes { number title state url repository { nameWithOwner } } } }
                    ... on PullRequest {
                      closingIssuesReferences(first:20){ nodes { number title state url repository { nameWithOwner } } } } } } }""";

        @Override
        public List<Link> links(String key, String project) {
            requireToken("Verknüpfungen (GraphQL)");
            Ref ref = ref(key, project);
            ObjectNode vars = HttpJson.object();
            String[] parts = ref.repo().split("/", 2);
            vars.put("owner", parts[0]);
            vars.put("name", parts[1]);
            vars.put("number", ref.number());
            JsonNode n = graphql(LINKS_QUERY, vars).path("repository").path("issueOrPullRequest");
            if (n.isMissingNode() || n.isNull()) {
                throw new IllegalArgumentException("GitHub: " + ref.key() + " nicht gefunden.");
            }
            List<Link> out = new ArrayList<>();
            if (n.path("parent").isObject()) {
                out.add(link("Parent", n.path("parent")));
            }
            n.path("subIssues").path("nodes").forEach(s -> out.add(link("Sub-Issue", s)));
            dependencies(ref, "blocked_by").forEach(i -> out.add(restLink("is blocked by", i)));
            dependencies(ref, "blocking").forEach(i -> out.add(restLink("blocks", i)));
            n.path("closedByPullRequestsReferences").path("nodes").forEach(s -> out.add(link("Pull Request (schließt)", s)));
            n.path("closingIssuesReferences").path("nodes").forEach(s -> out.add(link("schließt Issue", s)));
            return out;
        }

        private static Link link(String relation, JsonNode n) {
            return new Link(relation, text(n.path("repository").path("nameWithOwner")) + "#" + text(n.path("number")),
                    text(n.path("title")), text(n.path("state")) == null ? null : text(n.path("state")).toLowerCase(Locale.ROOT),
                    text(n.path("url")));
        }

        private static Link restLink(String relation, JsonNode i) {
            return new Link(relation, repoFromUrl(text(i.path("repository_url"))) + "#" + text(i.path("number")),
                    text(i.path("title")), text(i.path("state")), text(i.path("html_url")));
        }

        private static String issuePath(Ref ref) {
            return "/repos/" + ref.repo() + "/issues/" + ref.number();
        }

        /**
         * Abhängigkeiten {@code blocked_by}/{@code blocking} (REST, seit 2025). Ältere Enterprise-Server und Tokens ohne
         * Zugriff liefern Fehler – dann ohne, die übrigen Verknüpfungen sollen trotzdem kommen.
         */
        private List<JsonNode> dependencies(Ref ref, String kind) {
            List<JsonNode> out = new ArrayList<>();
            try {
                http.getJson(issuePath(ref) + "/dependencies/" + kind + query("per_page", 100)).forEach(out::add);
            } catch (HttpJson.StatusException e) {
                return List.of();
            }
            return out;
        }

        /** Sub-Issues und Abhängigkeiten; IDs wie die REST-Pfade, Namen wie in {@link #links}. */
        private static final List<LinkType> LINK_TYPES = List.of(
                new LinkType("parent", "Parent", "Sub-Issue"),
                new LinkType("sub_issue", "Sub-Issue", "Parent"),
                new LinkType("blocked_by", "is blocked by", "blocks"),
                new LinkType("blocking", "blocks", "is blocked by"));

        @Override
        public List<LinkType> linkTypes(String project) {
            return LINK_TYPES;
        }

        @Override
        public WriteResult link(String key, String project, LinkType type, String target) {
            requireToken("Verknüpfen");
            Ref a = ref(key, project);
            Ref b = ref(target, project);
            JsonNode issue = http.getJson(issuePath(a));
            long aId = issue.path("id").asLong();
            ObjectNode body = HttpJson.object();
            switch (type.id()) {
                case "sub_issue" -> {
                    body.put("sub_issue_id", databaseId(b));
                    http.post(issuePath(a) + "/sub_issues", body);
                }
                case "parent" -> {
                    body.put("sub_issue_id", aId);
                    http.post(issuePath(b) + "/sub_issues", body);
                }
                case "blocked_by" -> {
                    body.put("issue_id", databaseId(b));
                    http.post(issuePath(a) + "/dependencies/blocked_by", body);
                }
                case "blocking" -> {
                    body.put("issue_id", aId);
                    http.post(issuePath(b) + "/dependencies/blocked_by", body);
                }
                default -> throw new IllegalArgumentException("GitHub: unbekannte Verknüpfungsart '" + type.id() + "'.");
            }
            return new WriteResult(canonicalKey(key, project), "verknüpft: " + a.key() + " " + type.name() + " " + b.key(),
                    text(issue.path("html_url")));
        }

        @Override
        public WriteResult unlink(String key, String project, String target, String relation) {
            requireToken("Verknüpfung entfernen");
            Ref a = ref(key, project);
            Ref b = ref(target, project);
            String other = canonicalKey(target, project);
            List<Link> found = links(key, project).stream()
                    .filter(l -> LINK_TYPES.stream().anyMatch(t -> t.name().equals(l.relation())))
                    .filter(l -> other.equals(l.key().toLowerCase(Locale.ROOT))).toList();
            Link hit = TicketSystem.pickLink(found, Link::relation, relation, "GitHub", a.key(), b.key());
            switch (hit.relation()) {
                case "Sub-Issue" -> removeSubIssue(a, databaseId(b));
                case "Parent" -> removeSubIssue(b, databaseId(a));
                case "is blocked by" -> http.delete(issuePath(a) + "/dependencies/blocked_by/" + databaseId(b));
                default -> http.delete(issuePath(b) + "/dependencies/blocked_by/" + databaseId(a)); // blocks
            }
            return new WriteResult(canonicalKey(key, project), "Verknüpfung entfernt: " + a.key() + " " + hit.relation()
                    + " " + b.key(), null);
        }

        private void removeSubIssue(Ref parent, long child) {
            ObjectNode body = HttpJson.object();
            body.put("sub_issue_id", child);
            http.request("DELETE", issuePath(parent) + "/sub_issue", body);
        }

        /** Sub-Issues und Abhängigkeiten erwarten die numerische Issue-ID, nicht die Nummer. */
        private long databaseId(Ref ref) {
            long id = http.getJson(issuePath(ref)).path("id").asLong();
            if (id <= 0) {
                throw new IllegalArgumentException("GitHub: " + ref.key() + " nicht gefunden.");
            }
            return id;
        }

        private static final String PROJECT_ITEMS_QUERY = """
                query($owner:String!,$name:String!,$number:Int!,$field:String!){ repository(owner:$owner,name:$name){
                  issueOrPullRequest(number:$number){ ... on Issue { projectItems(first:10){ nodes { id
                      project { id title number field(name:$field){ ... on ProjectV2SingleSelectField { id options { id name } } } }
                      fieldValueByName(name:$field){ ... on ProjectV2ItemFieldSingleSelectValue { name } } } } }
                    ... on PullRequest { projectItems(first:10){ nodes { id
                      project { id title number field(name:$field){ ... on ProjectV2SingleSelectField { id options { id name } } } }
                      fieldValueByName(name:$field){ ... on ProjectV2ItemFieldSingleSelectValue { name } } } } } } } }""";

        /**
         * Schließen/Wiedereröffnen und – mit Token – die Spalten aller Projects, in denen das Issue liegt.
         * IDs: {@code close:<reason>}, {@code reopen}, {@code project:<projectId>:<itemId>:<fieldId>:<optionId>}.
         */
        @Override
        public List<Transition> transitions(String key, String project) {
            Ref ref = ref(key, project);
            JsonNode i = http.getJson("/repos/" + ref.repo() + "/issues/" + ref.number());
            boolean pr = i.has("pull_request");
            List<Transition> out = new ArrayList<>();
            if ("open".equals(text(i.path("state")))) {
                if (pr) {
                    out.add(new Transition("close", "Schließen (ohne Merge)", "closed", StatusCategory.DONE, "Status"));
                } else {
                    out.add(new Transition("close:completed", "Schließen (erledigt)", "closed (completed)", StatusCategory.DONE, "Status"));
                    out.add(new Transition("close:not_planned", "Schließen (nicht geplant)", "closed (not_planned)", StatusCategory.DONE, "Status"));
                    out.add(new Transition("close:duplicate", "Schließen (Duplikat)", "closed (duplicate)", StatusCategory.DONE, "Status"));
                }
            } else if (i.path("pull_request").path("merged_at").isNull() || !pr) {
                out.add(new Transition("reopen", "Wieder öffnen", "open", StatusCategory.TODO, "Status"));
            }
            if (authenticated) {
                String[] parts = ref.repo().split("/", 2);
                ObjectNode vars = HttpJson.object();
                vars.put("owner", parts[0]);
                vars.put("name", parts[1]);
                vars.put("number", ref.number());
                vars.put("field", statusField);
                JsonNode items = graphql(PROJECT_ITEMS_QUERY, vars).path("repository").path("issueOrPullRequest")
                        .path("projectItems").path("nodes");
                for (JsonNode item : items) {
                    JsonNode p = item.path("project");
                    JsonNode field = p.path("field");
                    String current = text(item.path("fieldValueByName").path("name"));
                    for (JsonNode o : field.path("options")) {
                        String name = text(o.path("name"));
                        if (name == null || name.equals(current) || text(field.path("id")) == null) {
                            continue;
                        }
                        out.add(new Transition(String.join(":", "project", text(p.path("id")), text(item.path("id")),
                                text(field.path("id")), text(o.path("id"))),
                                "Project " + text(p.path("title")) + ": " + statusField + " → " + name, name,
                                StatusCategory.UNKNOWN, "Project " + text(p.path("title"))));
                    }
                }
            }
            return out;
        }

        @Override
        public WriteResult transition(String key, String project, Transition t) {
            requireToken("Statuswechsel");
            Ref ref = ref(key, project);
            String id = t.id();
            if (id.startsWith("project:")) {
                String[] p = id.split(":", 5);
                ObjectNode vars = HttpJson.object();
                vars.put("project", p[1]);
                vars.put("item", p[2]);
                vars.put("field", p[3]);
                vars.put("option", p[4]);
                graphql("mutation($project:ID!,$item:ID!,$field:ID!,$option:String!){ updateProjectV2ItemFieldValue("
                        + "input:{projectId:$project,itemId:$item,fieldId:$field,value:{singleSelectOptionId:$option}}){"
                        + " projectV2Item { id } } }", vars);
                return new WriteResult(ref.key(), t.name(), null);
            }
            ObjectNode body = HttpJson.object();
            if (id.equals("reopen")) {
                body.put("state", "open");
            } else if (id.startsWith("close")) {
                body.put("state", "closed");
                if (id.contains(":")) {
                    body.put("state_reason", id.substring(id.indexOf(':') + 1));
                }
            } else {
                throw new IllegalArgumentException("GitHub: unbekannter Statuswechsel '" + id + "' – ticket_transitions liefert die möglichen.");
            }
            JsonNode res = http.patch("/repos/" + ref.repo() + "/issues/" + ref.number(), body).body();
            return new WriteResult(ref.key(), "Status → " + stateText(text(res.path("state")), text(res.path("state_reason"))),
                    text(res.path("html_url")));
        }

        @Override
        public WriteResult comment(String key, String project, String body) {
            requireToken("Kommentieren");
            Ref ref = ref(key, project);
            ObjectNode req = HttpJson.object();
            req.put("body", body);
            JsonNode res = http.post("/repos/" + ref.repo() + "/issues/" + ref.number() + "/comments", req).body();
            String id = text(res.path("id"));
            return new WriteResult(canonicalKey(key, project), "Kommentar " + id + " hinzugefügt", text(res.path("html_url")), id);
        }

        @Override
        public WriteResult assign(String key, String project, List<String> assignees) {
            requireToken("Zuweisen");
            Ref ref = ref(key, project);
            ObjectNode body = HttpJson.object();
            var arr = body.putArray("assignees");
            logins(assignees).forEach(arr::add);
            JsonNode res = http.patch("/repos/" + ref.repo() + "/issues/" + ref.number(), body).body();
            List<String> now = texts(res.path("assignees"), "login");
            List<String> wanted = logins(assignees);
            // GitHub ignoriert Benutzer ohne Zugriff auf das Repository stillschweigend – das melden
            List<String> ignored = wanted.stream().filter(w -> now.stream().noneMatch(n -> n.equalsIgnoreCase(w))).toList();
            return new WriteResult(ref.key(), "zugewiesen an " + (now.isEmpty() ? "niemand" : String.join(", ", now))
                    + (ignored.isEmpty() ? "" : " – ignoriert (kein Zugriff aufs Repository?): " + String.join(", ", ignored)),
                    text(res.path("html_url")));
        }

        private List<String> logins(List<String> assignees) {
            List<String> out = new ArrayList<>();
            for (String a : assignees) {
                if (TicketSystem.isNone(a)) {
                    continue;
                }
                out.add(TicketSystem.isMe(a) ? text(http.getJson("/user").path("login")) : a.trim().replaceFirst("^@", ""));
            }
            return out;
        }

        @Override
        public WriteResult update(String key, String project, TicketUpdate u) {
            requireToken("Bearbeiten");
            Ref ref = ref(key, project);
            ObjectNode body = HttpJson.object();
            if (u.title() != null) {
                body.put("title", u.title());
            }
            if (u.description() != null) {
                body.put("body", u.description());
            }
            if (u.labels() != null) {
                var arr = body.putArray("labels");
                u.labels().forEach(arr::add);
            }
            JsonNode res = http.patch("/repos/" + ref.repo() + "/issues/" + ref.number(), body).body();
            return new WriteResult(ref.key(), "geändert: " + u.summary(), text(res.path("html_url")));
        }

        @Override
        public String canonicalKey(String key, String project) {
            Ref ref = ref(key, project);
            return ref.repo().toLowerCase(Locale.ROOT) + "#" + ref.number();
        }

        @Override
        public String instance() {
            return http.baseUrl();
        }

        @Override
        public WriteResult deleteComment(String key, String project, String commentId) {
            requireToken("Kommentare löschen");
            Ref ref = ref(key, project);
            String id = commentId.trim();
            if (!id.matches("\\d+")) {
                throw new IllegalArgumentException("GitHub: Kommentar-ID ist eine Zahl (aus ticket_get), nicht '" + id + "'.");
            }
            // Kommentare hängen am Repository; prüfen, dass er zu genau diesem Issue gehört
            JsonNode c = http.getJson("/repos/" + ref.repo() + "/issues/comments/" + id);
            String issueUrl = text(c.path("issue_url"));
            if (issueUrl == null || !issueUrl.endsWith("/issues/" + ref.number())) {
                throw new IllegalArgumentException("GitHub: Kommentar " + id + " gehört nicht zu " + ref.key() + ".");
            }
            http.delete("/repos/" + ref.repo() + "/issues/comments/" + id);
            return new WriteResult(canonicalKey(key, project), "Kommentar " + id + " gelöscht", null);
        }

        @Override
        public WriteResult delete(String key, String project) {
            requireToken("Tickets löschen");
            Ref ref = ref(key, project);
            JsonNode i = http.getJson("/repos/" + ref.repo() + "/issues/" + ref.number());
            if (i.has("pull_request")) {
                throw new IllegalArgumentException("GitHub: " + ref.key() + " ist ein Pull Request – die lassen sich nicht löschen.");
            }
            ObjectNode vars = HttpJson.object();
            vars.put("id", text(i.path("node_id")));
            try {
                graphql("mutation($id:ID!){ deleteIssue(input:{issueId:$id}){ repository { nameWithOwner } } }", vars);
            } catch (IllegalStateException e) {
                throw new IllegalStateException(e.getMessage() + " – Issues löschen dürfen nur Repository-Admins; "
                        + "alternativ schließen (ticket_transition).", e);
            }
            return new WriteResult(canonicalKey(key, project), "Issue gelöscht", null);
        }

        @Override
        public WriteResult create(String project, NewTicket t) {
            requireToken("Anlegen");
            if (project == null || !project.contains("/")) {
                throw new IllegalArgumentException("GitHub: 'project' als owner/repo angeben (oder Standardprojekt setzen).");
            }
            ObjectNode body = HttpJson.object();
            body.put("title", t.title());
            if (t.description() != null) {
                body.put("body", t.description());
            }
            if (!t.labels().isEmpty()) {
                var arr = body.putArray("labels");
                t.labels().forEach(arr::add);
            }
            List<String> logins = logins(t.assignees());
            if (!logins.isEmpty()) {
                var arr = body.putArray("assignees");
                logins.forEach(arr::add);
            }
            if (t.type() != null && !t.type().isBlank()) {
                body.put("type", t.type().trim()); // Issue-Typen der Organisation (z.B. Bug, Feature)
            }
            JsonNode res = http.post("/repos/" + project.trim() + "/issues", body).body();
            return new WriteResult(project.trim().toLowerCase(Locale.ROOT) + "#" + text(res.path("number")), "angelegt",
                    text(res.path("html_url")));
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
                    comments.add(new Comment(text(c.path("id")), text(c.path("user").path("login")), text(c.path("created_at")),
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
