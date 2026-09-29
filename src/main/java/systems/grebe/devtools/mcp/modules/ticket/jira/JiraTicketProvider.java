package systems.grebe.devtools.mcp.modules.ticket.jira;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
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

import static systems.grebe.devtools.mcp.modules.ticket.spi.HttpJson.query;
import static systems.grebe.devtools.mcp.modules.ticket.spi.HttpJson.text;
import static systems.grebe.devtools.mcp.modules.ticket.spi.HttpJson.texts;

/**
 * Jira Cloud und Jira Data Center/Server über REST API v2 (Beschreibungen als Wiki-Text, kein ADF) und die
 * Agile-API für Boards. Cloud sucht über {@code /search/jql} (Token-Paginierung), Data Center über {@code /search}.
 */
public class JiraTicketProvider implements TicketProvider {

    static final String BASE_URL = "baseUrl";
    static final String USER = "user";
    static final String TOKEN = "token";
    static final String DEPLOYMENT = "deployment";

    @Override
    public String id() {
        return "jira";
    }

    @Override
    public String displayName() {
        return "Jira";
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public List<ConfigField> configFields() {
        return List.of(
                ConfigField.of(BASE_URL, "Server-URL", FieldType.URL)
                        .withHelp("z.B. https://firma.atlassian.net oder https://jira.firma.de"),
                ConfigField.of(USER, "Benutzer / E-Mail", FieldType.STRING)
                        .withHelp("Cloud: E-Mail-Adresse zum API-Token. Data Center: leer lassen für ein Personal Access "
                                + "Token, oder Benutzername zum Passwort."),
                ConfigField.of(TOKEN, "API-Token", FieldType.SECRET)
                        .withHelp("Cloud: API-Token (id.atlassian.com → Sicherheit). Data Center: Personal Access Token. "
                                + "Wird verschlüsselt gespeichert."),
                ConfigField.of(DEPLOYMENT, "Variante", FieldType.ENUM).withDefault("auto")
                        .withOptions("auto", "cloud", "datacenter")
                        .withHelp("auto = Cloud bei *.atlassian.net, sonst Data Center/Server."));
    }

    @Override
    public String projectHelp() {
        return "Jira-Projektschlüssel, z.B. ABC";
    }

    @Override
    public String keyHelp() {
        return "Jira-Schlüssel wie ABC-123 oder Browse-URL";
    }

    @Override
    public String queryHelp() {
        return "JQL, z.B. sprint in openSprints() AND priority = High (ohne ORDER BY wird nach updated sortiert)";
    }

    @Override
    public TicketSystem create(ProviderSettings s) {
        String base = HttpJson.stripSlash(s.getString(BASE_URL, ""));
        Map<String, String> headers = new LinkedHashMap<>();
        s.get(TOKEN).ifPresent(token -> headers.put("Authorization", s.get(USER)
                .map(u -> "Basic " + Base64.getEncoder().encodeToString((u + ":" + token).getBytes(StandardCharsets.UTF_8)))
                .orElse("Bearer " + token)));
        String deployment = s.getString(DEPLOYMENT, "auto");
        boolean cloud = "cloud".equals(deployment) || "auto".equals(deployment) && base.toLowerCase(Locale.ROOT).contains(".atlassian.net");
        return new Jira(base.isEmpty() ? null : new HttpJson("Jira", base, headers, s.timeout()), cloud);
    }

    /** Jira-Anbindung. Paketsichtbar für Tests. */
    static final class Jira implements TicketSystem {

        private static final Pattern KEY = Pattern.compile("(?i)\\b([A-Z][A-Z0-9_]*-\\d+)\\b");
        private static final String LIST_FIELDS = "summary,status,assignee,issuetype,priority,labels,updated";
        private static final int BOARD_PAGE = 50;
        private static final int BOARD_MAX = 300;

        private final HttpJson http;
        private final boolean cloud;

        Jira(HttpJson http, boolean cloud) {
            this.http = http;
            this.cloud = cloud;
        }

        @Override
        public String id() {
            return "jira";
        }

        private HttpJson http() {
            if (http == null) {
                throw new IllegalStateException("Jira: keine Server-URL konfiguriert – in der DevTools-App unter "
                        + "Module → Tickets eintragen.");
            }
            return http;
        }

        @Override
        public Availability probe() {
            if (http == null) {
                return Availability.unavailable("keine Server-URL konfiguriert");
            }
            try {
                JsonNode info = http.getJson("/rest/api/2/serverInfo");
                String version = text(info.path("version")) + " (" + (cloud ? "Cloud" : text(info.path("deploymentType"))) + ")";
                JsonNode me = http.getJson("/rest/api/2/myself");
                return Availability.ok(version, HttpJson.first(text(me.path("displayName")), text(me.path("name"))));
            } catch (RuntimeException e) {
                return Availability.unavailable(e.getMessage());
            }
        }

        @Override
        public boolean ownsKey(String key) {
            if (key == null) {
                return false;
            }
            String k = key.trim();
            if (http != null && k.startsWith(http.baseUrl() + "/")) {
                return true;
            }
            return k.matches("[A-Z][A-Z0-9_]*-\\d+");
        }

        /** Schlüssel aus {@code ABC-123}, {@code abc-123} oder einer Browse-/selectedIssue-URL. */
        static String issueKey(String key) {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("Jira: kein Ticket-Schlüssel angegeben (z.B. ABC-123).");
            }
            Matcher m = KEY.matcher(key.trim());
            String found = null;
            while (m.find()) {
                found = m.group(1); // letzter Treffer: in URLs steht der Schlüssel am Ende
            }
            if (found == null) {
                throw new IllegalArgumentException("Jira: '" + key + "' ist kein Ticket-Schlüssel (erwartet z.B. ABC-123).");
            }
            return found.toUpperCase(Locale.ROOT);
        }

        // ------------------------------------------------------------------ Boards

        @Override
        public List<Board> boards(String project) {
            List<Board> out = new ArrayList<>();
            int startAt = 0;
            while (out.size() < 200) {
                JsonNode res = http().getJson("/rest/agile/1.0/board"
                        + query("projectKeyOrId", project, "startAt", startAt, "maxResults", 50));
                for (JsonNode b : res.path("values")) {
                    String proj = HttpJson.first(text(b.path("location").path("projectKey")), project);
                    out.add(new Board(text(b.path("id")), text(b.path("name")), text(b.path("type")), proj,
                            http.baseUrl() + "/secure/RapidBoard.jspa?rapidView=" + text(b.path("id"))));
                }
                if (res.path("isLast").asBoolean(true) || res.path("values").isEmpty()) {
                    break;
                }
                startAt += res.path("values").size();
            }
            return out;
        }

        @Override
        public BoardView board(String boardRef, String project, BoardOptions options) {
            // Board-IDs sind in Jira global – direkt laden, auch wenn das Board nicht im (Standard-)Projekt liegt
            Board board = boardRef != null && boardRef.trim().matches("\\d+")
                    ? boardById(boardRef.trim())
                    : TicketSystem.pickBoard(boards(project), boardRef, "Jira");

            // Spalten und die Status, die auf sie abgebildet sind
            JsonNode config = http().getJson("/rest/agile/1.0/board/" + board.id() + "/configuration");
            Map<String, String> columnByStatus = new LinkedHashMap<>();
            List<String> columnNames = new ArrayList<>();
            for (JsonNode c : config.path("columnConfig").path("columns")) {
                String name = text(c.path("name"));
                columnNames.add(name);
                c.path("statuses").forEach(st -> columnByStatus.put(text(st.path("id")), name));
            }

            String scope;
            String path;
            List<String> jql = new ArrayList<>();
            if ("scrum".equalsIgnoreCase(board.type())) {
                JsonNode sprints = http.getJson("/rest/agile/1.0/board/" + board.id() + "/sprint" + query("state", "active"));
                JsonNode sprint = sprints.path("values").path(0);
                if (sprint.isMissingNode()) {
                    scope = "kein aktiver Sprint – offene Tickets des Boards";
                    path = "/rest/agile/1.0/board/" + board.id() + "/issue";
                    jql.add("statusCategory != Done");
                } else {
                    scope = "Sprint " + text(sprint.path("name")) + dateRange(sprint);
                    path = "/rest/agile/1.0/board/" + board.id() + "/sprint/" + text(sprint.path("id")) + "/issue";
                }
            } else {
                scope = "offen sowie in den letzten 14 Tagen erledigt";
                path = "/rest/agile/1.0/board/" + board.id() + "/issue";
                jql.add("(statusCategory != Done OR resolutiondate >= -14d)");
            }
            String assignee = assigneeJql(options.assignee());
            if (assignee != null) {
                jql.add(assignee);
            }

            Map<String, List<Ticket>> byColumn = new LinkedHashMap<>();
            columnNames.forEach(n -> byColumn.put(n, new ArrayList<>()));
            int startAt = 0;
            int loaded = 0;
            boolean truncated = false;
            while (true) {
                JsonNode res = http.getJson(path + query("jql", jql.isEmpty() ? null : String.join(" AND ", jql),
                        "fields", LIST_FIELDS, "startAt", startAt, "maxResults", BOARD_PAGE));
                JsonNode issues = res.path("issues");
                for (JsonNode i : issues) {
                    String col = columnByStatus.getOrDefault(text(i.path("fields").path("status").path("id")), "(keiner Spalte zugeordnet)");
                    byColumn.computeIfAbsent(col, k -> new ArrayList<>()).add(ticket(i));
                }
                loaded += issues.size();
                int total = res.path("total").asInt(loaded);
                if (issues.isEmpty() || loaded >= total) {
                    break;
                }
                if (loaded >= BOARD_MAX) {
                    truncated = true;
                    break;
                }
                startAt = loaded;
            }
            List<Column> columns = new ArrayList<>();
            byColumn.forEach((name, list) -> columns.add(new Column(name,
                    list.subList(0, Math.min(list.size(), options.maxPerColumn())), list.size())));
            return new BoardView(board, scope, columns,
                    truncated ? "nur die ersten " + BOARD_MAX + " Tickets geladen – mit ticket_search (JQL) eingrenzen" : null);
        }

        private Board boardById(String id) {
            JsonNode b = http().getJson("/rest/agile/1.0/board/" + id);
            return new Board(text(b.path("id")), text(b.path("name")), text(b.path("type")),
                    text(b.path("location").path("projectKey")),
                    http.baseUrl() + "/secure/RapidBoard.jspa?rapidView=" + text(b.path("id")));
        }

        private static String dateRange(JsonNode sprint) {
            String start = text(sprint.path("startDate"));
            String end = text(sprint.path("endDate"));
            return start == null || end == null ? "" : " (" + day(start) + " – " + day(end) + ")";
        }

        private static String day(String iso) {
            return iso.length() >= 10 ? iso.substring(0, 10) : iso;
        }

        // ------------------------------------------------------------------ Suche

        static String assigneeJql(String assignee) {
            if (assignee == null || assignee.isBlank()) {
                return null;
            }
            if (TicketSystem.isMe(assignee)) {
                return "assignee = currentUser()";
            }
            if (TicketSystem.isNone(assignee)) {
                return "assignee is EMPTY";
            }
            return "assignee = " + quote(assignee.trim());
        }

        static String quote(String s) {
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }

        /** Baut die JQL aus den Filtern; eine eigene JQL wird UND-verknüpft, ihr ORDER BY gewinnt. */
        static String jql(TicketQuery q) {
            List<String> parts = new ArrayList<>();
            if (q.project() != null && !q.project().isBlank()) {
                parts.add("project = " + quote(q.project().trim()));
            }
            switch (q.state()) {
                case OPEN -> parts.add("statusCategory != Done");
                case CLOSED -> parts.add("statusCategory = Done");
                default -> { }
            }
            String a = assigneeJql(q.assignee());
            if (a != null) {
                parts.add(a);
            }
            q.labels().forEach(l -> parts.add("labels = " + quote(l)));
            if (q.text() != null && !q.text().isBlank()) {
                parts.add("text ~ " + quote(q.text().trim()));
            }
            String order = "ORDER BY updated DESC";
            if (q.rawQuery() != null && !q.rawQuery().isBlank()) {
                String raw = q.rawQuery().trim();
                Matcher m = Pattern.compile("(?i)\\border\\s+by\\b").matcher(raw);
                if (m.find()) {
                    order = raw.substring(m.start()).trim();
                    raw = raw.substring(0, m.start()).trim();
                }
                if (!raw.isEmpty()) {
                    parts.add("(" + raw + ")");
                }
            }
            return (String.join(" AND ", parts) + " " + order).trim();
        }

        @Override
        public TicketPage search(TicketQuery q) {
            String jql = jql(q);
            int limit = Math.max(1, Math.min(q.limit(), 100));
            List<Ticket> tickets = new ArrayList<>();
            if (cloud) {
                // /search ist in Jira Cloud abgeschaltet – /search/jql paginiert per Token und liefert keine Gesamtzahl
                JsonNode res = http().getJson("/rest/api/2/search/jql" + query("jql", jql, "fields", LIST_FIELDS,
                        "maxResults", limit, "nextPageToken", q.cursor()));
                res.path("issues").forEach(i -> tickets.add(ticket(i)));
                String next = res.path("isLast").asBoolean(true) ? null : text(res.path("nextPageToken"));
                return new TicketPage(tickets, null, next, jql);
            }
            int startAt = parseCursor(q.cursor());
            JsonNode res = http().getJson("/rest/api/2/search" + query("jql", jql, "fields", LIST_FIELDS,
                    "maxResults", limit, "startAt", startAt));
            res.path("issues").forEach(i -> tickets.add(ticket(i)));
            int total = res.path("total").asInt(tickets.size());
            int next = startAt + tickets.size();
            return new TicketPage(tickets, total, !tickets.isEmpty() && next < total ? String.valueOf(next) : null, jql);
        }

        private static int parseCursor(String cursor) {
            if (cursor == null || cursor.isBlank()) {
                return 0;
            }
            try {
                return Math.max(0, Integer.parseInt(cursor.trim()));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Jira: ungültiger cursor '" + cursor + "' – den Wert aus der vorigen "
                        + "ticket_search-Ausgabe verwenden.");
            }
        }

        // ------------------------------------------------------------------ Einzelnes Ticket

        @Override
        public TicketDetails ticket(String key, String project, int maxComments) {
            String k = issueKey(key);
            String fields = LIST_FIELDS + ",description,reporter,created,parent,fixVersions,components,duedate,resolution"
                    + (maxComments > 0 ? ",comment" : "");
            JsonNode i = http().getJson("/rest/api/2/issue/" + HttpJson.enc(k) + query("fields", fields));
            JsonNode f = i.path("fields");
            Map<String, String> extra = new LinkedHashMap<>();
            JsonNode parent = f.path("parent");
            if (parent.isObject()) {
                extra.put("Parent", text(parent.path("key")) + " " + HttpJson.first(text(parent.path("fields").path("summary")), ""));
            }
            put(extra, "Resolution", text(f.path("resolution").path("name")));
            put(extra, "Fix-Versionen", String.join(", ", texts(f.path("fixVersions"), "name")));
            put(extra, "Komponenten", String.join(", ", texts(f.path("components"), "name")));
            put(extra, "Fällig", text(f.path("duedate")));

            List<Comment> comments = new ArrayList<>();
            int totalComments = 0;
            if (maxComments > 0) {
                JsonNode all = f.path("comment").path("comments");
                totalComments = f.path("comment").path("total").asInt(all.size());
                int from = Math.max(0, all.size() - maxComments);
                for (int n = from; n < all.size(); n++) {
                    JsonNode c = all.get(n);
                    comments.add(new Comment(user(c.path("author")), text(c.path("created")), text(c.path("body"))));
                }
            }
            return new TicketDetails(ticket(i), user(f.path("reporter")), text(f.path("created")),
                    text(f.path("description")), extra, comments, totalComments);
        }

        private static void put(Map<String, String> m, String k, String v) {
            if (v != null && !v.isBlank()) {
                m.put(k, v.strip());
            }
        }

        private Ticket ticket(JsonNode i) {
            JsonNode f = i.path("fields");
            String key = text(i.path("key"));
            String assignee = user(f.path("assignee"));
            return new Ticket(key, text(f.path("summary")), text(f.path("status").path("name")),
                    category(text(f.path("status").path("statusCategory").path("key"))),
                    text(f.path("issuetype").path("name")), text(f.path("priority").path("name")),
                    assignee == null ? List.of() : List.of(assignee), texts(f.path("labels"), null),
                    text(f.path("updated")), http == null ? null : http.baseUrl() + "/browse/" + key);
        }

        private static String user(JsonNode u) {
            return HttpJson.first(text(u.path("displayName")), text(u.path("name")), text(u.path("emailAddress")));
        }

        static StatusCategory category(String key) {
            if (key == null) {
                return StatusCategory.UNKNOWN;
            }
            return switch (key) {
                case "new" -> StatusCategory.TODO;
                case "indeterminate" -> StatusCategory.IN_PROGRESS;
                case "done" -> StatusCategory.DONE;
                default -> StatusCategory.UNKNOWN;
            };
        }
    }
}
