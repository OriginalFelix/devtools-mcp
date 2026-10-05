package systems.grebe.devtools.mcp.modules.ticket.jira;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
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
import systems.grebe.devtools.mcp.core.Text;
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
        private static final Pattern STORY_POINTS = Pattern.compile("(?i)story[ _-]?points?|story point estimate");
        /** Format von {@code started} – Jira lehnt ISO-Offsets mit Doppelpunkt ab. */
        private static final DateTimeFormatter STARTED = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ");

        private final HttpJson http;
        private final boolean cloud;
        private volatile Map<String, String> storyPointFields;

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

        // ------------------------------------------------------------------ Verknüpfungen, Statuswechsel, Schreiben

        @Override
        public String projectOf(String key, String project) {
            String k = issueKey(key);
            return k.substring(0, k.lastIndexOf('-'));
        }

        @Override
        public String markup() {
            return "Jira-Wiki-Markup (h2., *fett*, {code}…{code})";
        }

        @Override
        public List<Link> links(String key, String project) {
            String k = issueKey(key);
            JsonNode f = http().getJson("/rest/api/2/issue/" + HttpJson.enc(k)
                    + query("fields", "parent,subtasks,issuelinks")).path("fields");
            List<Link> out = new ArrayList<>();
            if (f.path("parent").isObject()) {
                out.add(link("Parent", f.path("parent")));
            }
            f.path("subtasks").forEach(s -> out.add(link("Unteraufgabe", s)));
            for (JsonNode l : f.path("issuelinks")) {
                if (l.path("outwardIssue").isObject()) {
                    out.add(link(text(l.path("type").path("outward")), l.path("outwardIssue")));
                } else if (l.path("inwardIssue").isObject()) {
                    out.add(link(text(l.path("type").path("inward")), l.path("inwardIssue")));
                }
            }
            // Unteraufgaben eines Epics (Cloud: parent = EPIC, Data Center: "Epic Link")
            String children = cloud ? "parent = " + k : "\"Epic Link\" = " + k;
            try {
                JsonNode res = http.getJson((cloud ? "/rest/api/2/search/jql" : "/rest/api/2/search")
                        + query("jql", children + " ORDER BY rank", "fields", "summary,status", "maxResults", 50));
                res.path("issues").forEach(i -> {
                    if (out.stream().noneMatch(o -> o.key().equals(text(i.path("key"))))) {
                        out.add(link("Kind (Epic)", i));
                    }
                });
            } catch (RuntimeException ignored) {
                // kein Epic bzw. Feld "Epic Link" unbekannt – dann gibt es keine Kinder
            }
            return out;
        }

        private Link link(String relation, JsonNode issue) {
            String key = text(issue.path("key"));
            return new Link(relation, key, text(issue.path("fields").path("summary")),
                    text(issue.path("fields").path("status").path("name")), http.baseUrl() + "/browse/" + key);
        }

        @Override
        public List<Transition> transitions(String key, String project) {
            String k = issueKey(key);
            List<Transition> out = new ArrayList<>();
            for (JsonNode t : http().getJson("/rest/api/2/issue/" + HttpJson.enc(k) + "/transitions").path("transitions")) {
                out.add(new Transition(text(t.path("id")), text(t.path("name")), text(t.path("to").path("name")),
                        category(text(t.path("to").path("statusCategory").path("key"))), "Workflow"));
            }
            return out;
        }

        @Override
        public WriteResult transition(String key, String project, Transition transition) {
            String k = issueKey(key);
            var body = HttpJson.object();
            body.putObject("transition").put("id", transition.id());
            http().post("/rest/api/2/issue/" + HttpJson.enc(k) + "/transitions", body);
            return new WriteResult(k, "Status → " + transition.to() + " ('" + transition.name() + "')",
                    http.baseUrl() + "/browse/" + k);
        }

        /**
         * Wechsel und Kommentar in einem Aufruf ({@code update.comment}) – Validatoren, die beim Übergang einen Kommentar
         * verlangen, sehen ihn nur so. Nimmt die Instanz am Übergang keinen Kommentar an, nacheinander wie im Default.
         */
        @Override
        public WriteResult transition(String key, String project, Transition transition, String comment) {
            if (comment == null || comment.isBlank()) {
                return transition(key, project, transition);
            }
            String k = issueKey(key);
            var body = HttpJson.object();
            body.putObject("transition").put("id", transition.id());
            body.putObject("update").putArray("comment").addObject().putObject("add").put("body", comment);
            try {
                http().post("/rest/api/2/issue/" + HttpJson.enc(k) + "/transitions", body);
            } catch (HttpJson.StatusException e) {
                if (e.status() == 400 && e.getMessage().toLowerCase(Locale.ROOT).contains("comment")) {
                    return TicketSystem.super.transition(key, project, transition, comment);
                }
                throw e;
            }
            String id = newestCommentId(k, comment);
            return new WriteResult(k, "Status → " + transition.to() + " ('" + transition.name() + "') mit Kommentar"
                    + (id == null ? "" : " " + id), http.baseUrl() + "/browse/" + k
                    + (id == null ? "" : "?focusedCommentId=" + id), id);
        }

        /** ID des neuesten Kommentars, wenn er den gesendeten Text trägt – der Übergang selbst liefert sie nicht. */
        private String newestCommentId(String k, String body) {
            try {
                JsonNode newest = http.getJson("/rest/api/2/issue/" + HttpJson.enc(k) + "/comment"
                        + query("orderBy", "-created", "maxResults", 1)).path("comments").path(0);
                String text = text(newest.path("body"));
                return text != null && text.strip().equals(body.strip()) ? text(newest.path("id")) : null;
            } catch (RuntimeException e) {
                return null; // nur für die Liste selbst geschriebener Kommentare
            }
        }

        @Override
        public WriteResult comment(String key, String project, String body) {
            String k = issueKey(key);
            var req = HttpJson.object();
            req.put("body", body);
            JsonNode res = http().post("/rest/api/2/issue/" + HttpJson.enc(k) + "/comment", req).body();
            String id = text(res.path("id"));
            return new WriteResult(k, "Kommentar " + Text.orDash(id) + " hinzugefügt",
                    http.baseUrl() + "/browse/" + k + (id == null ? "" : "?focusedCommentId=" + id), id);
        }

        @Override
        public WriteResult assign(String key, String project, List<String> assignees) {
            String k = issueKey(key);
            if (assignees.size() > 1) {
                throw new IllegalArgumentException("Jira: ein Ticket hat genau einen Zuständigen – nur einen Benutzer angeben.");
            }
            var body = HttpJson.object();
            String who;
            if (assignees.isEmpty() || TicketSystem.isNone(assignees.getFirst())) {
                body.putNull(cloud ? "accountId" : "name");
                who = "niemand";
            } else {
                JsonNode user = TicketSystem.isMe(assignees.getFirst()) ? http().getJson("/rest/api/2/myself")
                        : assignableUser(k, assignees.getFirst());
                if (cloud) {
                    body.put("accountId", text(user.path("accountId")));
                } else {
                    body.put("name", text(user.path("name")));
                }
                who = user(user);
            }
            http().put("/rest/api/2/issue/" + HttpJson.enc(k) + "/assignee", body);
            return new WriteResult(k, "zugewiesen an " + who, http.baseUrl() + "/browse/" + k);
        }

        /** Sucht unter den für das Ticket zuweisbaren Benutzern – braucht nur Projektrechte, nicht „Browse Users“. */
        private JsonNode assignableUser(String issueKey, String who) {
            String q = who.trim().replaceFirst("^@", "");
            JsonNode users = http.getJson("/rest/api/2/user/assignable/search"
                    + query("issueKey", issueKey, cloud ? "query" : "username", q, "maxResults", 20));
            List<JsonNode> list = new ArrayList<>();
            users.forEach(list::add);
            // Stream.of statt List.of: je nach Variante fehlen Felder (DC: keine accountId, Cloud: kein name)
            List<JsonNode> exact = list.stream().filter(u -> java.util.stream.Stream.of(text(u.path("name")),
                    text(u.path("emailAddress")), text(u.path("displayName")), text(u.path("accountId")))
                    .anyMatch(v -> v != null && v.equalsIgnoreCase(q))).toList();
            if (exact.size() == 1) {
                return exact.getFirst();
            }
            if (list.size() == 1) {
                return list.getFirst();
            }
            if (list.isEmpty()) {
                throw new IllegalArgumentException("Jira: kein zuweisbarer Benutzer '" + q + "' für " + issueKey
                        + " (Benutzername, E-Mail oder Anzeigename).");
            }
            throw new IllegalArgumentException("Jira: '" + q + "' ist mehrdeutig: " + String.join(", ",
                    list.stream().limit(10).map(u -> user(u) + " (" + HttpJson.first(text(u.path("name")),
                            text(u.path("accountId"))) + ")").toList()) + " – genauer angeben.");
        }

        @Override
        public WriteResult update(String key, String project, TicketUpdate update) {
            String k = issueKey(key);
            var fields = HttpJson.object();
            if (update.title() != null) {
                fields.put("summary", update.title());
            }
            if (update.description() != null) {
                fields.put("description", update.description());
            }
            if (update.labels() != null) {
                var arr = fields.putArray("labels");
                update.labels().forEach(l -> arr.add(l.replace(' ', '_'))); // Jira-Labels ohne Leerzeichen
            }
            var body = HttpJson.object();
            body.set("fields", fields);
            http().put("/rest/api/2/issue/" + HttpJson.enc(k), body);
            return new WriteResult(k, "geändert: " + update.summary(), http.baseUrl() + "/browse/" + k);
        }

        /** Felder per ID oder Anzeigename aus der Editmeta des Tickets; der Text wird passend zum Feldtyp umgewandelt. */
        @Override
        public WriteResult updateFields(String key, String project, Map<String, String> values) {
            String k = issueKey(key);
            if (values == null || values.isEmpty()) {
                throw new IllegalArgumentException("Jira: keine Felder angegeben.");
            }
            JsonNode editable = http().getJson("/rest/api/2/issue/" + HttpJson.enc(k) + "/editmeta").path("fields");
            var fields = HttpJson.object();
            List<String> done = new ArrayList<>();
            values.forEach((ref, raw) -> {
                Map.Entry<String, JsonNode> f = editableField(editable, ref, k);
                String name = HttpJson.first(text(f.getValue().path("name")), f.getKey());
                String value = raw == null ? "" : raw.strip();
                JsonNode v = fieldValue(k, f.getValue(), name, value);
                if (v == null) {
                    fields.putNull(f.getKey());
                } else {
                    fields.set(f.getKey(), v);
                }
                done.add(value.isEmpty() ? name + " geleert" : name + " = " + value);
            });
            var body = HttpJson.object();
            body.set("fields", fields);
            http().put("/rest/api/2/issue/" + HttpJson.enc(k), body);
            return new WriteResult(k, "Felder geändert: " + String.join(", ", done), http.baseUrl() + "/browse/" + k);
        }

        /** Bearbeitbares Feld per ID oder Anzeigename, beides ohne Groß-/Kleinschreibung. */
        private static Map.Entry<String, JsonNode> editableField(JsonNode editable, String ref, String k) {
            String r = ref == null ? "" : ref.strip();
            List<Map.Entry<String, JsonNode>> all = new ArrayList<>(editable.properties());
            for (Map.Entry<String, JsonNode> e : all) {
                if (e.getKey().equalsIgnoreCase(r)) {
                    return e;
                }
            }
            List<Map.Entry<String, JsonNode>> byName = all.stream()
                    .filter(e -> r.equalsIgnoreCase(text(e.getValue().path("name")))).toList();
            if (byName.size() == 1) {
                return byName.getFirst();
            }
            if (byName.size() > 1) {
                throw new IllegalArgumentException("Jira: Feldname '" + r + "' ist mehrdeutig: " + fieldList(byName)
                        + " – die Feld-ID angeben.");
            }
            throw new IllegalArgumentException("Jira: Feld '" + r + "' ist für " + k + " unbekannt oder nicht bearbeitbar. "
                    + "Bearbeitbar: " + fieldList(all.stream().limit(40).toList()) + ".");
        }

        private static String fieldList(List<Map.Entry<String, JsonNode>> fields) {
            return String.join(", ", fields.stream()
                    .map(e -> HttpJson.first(text(e.getValue().path("name")), e.getKey()) + " (" + e.getKey() + ")").toList());
        }

        /**
         * JSON-Wert für ein Feld; {@code null} = leeren. Text, der mit {@code {} oder {@code [} beginnt, geht unverändert
         * als JSON hinaus – für Feldtypen ohne eigene Umwandlung.
         */
        private JsonNode fieldValue(String k, JsonNode field, String name, String value) {
            JsonNode schema = field.path("schema");
            boolean list = "array".equals(text(schema.path("type")));
            if (value.isEmpty()) {
                return list ? HttpJson.JSON.createArrayNode() : null;
            }
            if (value.startsWith("{") || value.startsWith("[")) {
                try {
                    return HttpJson.JSON.readTree(value);
                } catch (RuntimeException e) {
                    throw new IllegalArgumentException("Jira: Wert für " + name + " ist kein gültiges JSON: " + e.getMessage());
                }
            }
            if (!list) {
                return scalar(k, field, name, text(schema.path("type")), value);
            }
            var arr = HttpJson.JSON.createArrayNode();
            for (String part : value.split(",")) {
                if (!part.isBlank()) {
                    arr.add(scalar(k, field, name, text(schema.path("items")), part.strip()));
                }
            }
            return arr;
        }

        private JsonNode scalar(String k, JsonNode field, String name, String type, String v) {
            Object value = switch (type == null ? "string" : type) {
                case "string", "date", "datetime" -> v;
                case "number" -> {
                    try {
                        yield new BigDecimal(v.replace(',', '.'));
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("Jira: " + name + " erwartet eine Zahl, erhalten: " + v);
                    }
                }
                case "user" -> {
                    JsonNode u = TicketSystem.isMe(v) ? http().getJson("/rest/api/2/myself") : assignableUser(k, v);
                    yield cloud ? Map.of("accountId", text(u.path("accountId"))) : Map.of("name", text(u.path("name")));
                }
                case "option" -> Map.of("value", allowed(field, "value", name, v));
                case "version", "component", "priority", "resolution", "group" -> Map.of("name", allowed(field, "name", name, v));
                default -> throw new IllegalArgumentException("Jira: " + name + " hat den Feldtyp '" + type + "' – den Wert "
                        + "als JSON angeben, z.B. {\"id\":\"10001\"}.");
            };
            return HttpJson.JSON.valueToTree(value);
        }

        /** Erlaubter Wert in der Schreibweise von Jira; ohne Werteliste in der Editmeta unverändert. */
        private static String allowed(JsonNode field, String property, String name, String v) {
            JsonNode values = field.path("allowedValues");
            if (!values.isArray() || values.isEmpty()) {
                return v;
            }
            List<String> names = new ArrayList<>();
            for (JsonNode a : values) {
                String s = text(a.path(property));
                if (s != null && s.equalsIgnoreCase(v)) {
                    return s;
                }
                if (s != null) {
                    names.add(s);
                }
            }
            throw new IllegalArgumentException("Jira: '" + v + "' ist für " + name + " nicht erlaubt – erlaubt: "
                    + String.join(", ", names.stream().limit(30).toList()) + ".");
        }

        @Override
        public WriteResult create(String project, NewTicket t) {
            if (project == null || project.isBlank()) {
                throw new IllegalArgumentException("Jira: 'project' (Projektschlüssel) angeben oder Standardprojekt setzen.");
            }
            var fields = HttpJson.object();
            fields.putObject("project").put("key", project.trim().toUpperCase(Locale.ROOT));
            fields.put("summary", t.title());
            if (t.description() != null) {
                fields.put("description", t.description());
            }
            fields.putObject("issuetype").put("name", HttpJson.first(t.type(), "Task"));
            if (!t.labels().isEmpty()) {
                var arr = fields.putArray("labels");
                t.labels().forEach(l -> arr.add(l.replace(' ', '_')));
            }
            var body = HttpJson.object();
            body.set("fields", fields);
            String newKey;
            try {
                newKey = text(http().post("/rest/api/2/issue", body).body().path("key"));
            } catch (HttpJson.StatusException e) {
                if (e.status() == 400 && e.getMessage().toLowerCase(Locale.ROOT).contains("issuetype")) {
                    throw new IllegalArgumentException(e.getMessage() + " – gültige Typen: " + issueTypes(project), e);
                }
                throw e;
            }
            String msg = "angelegt (" + HttpJson.first(t.type(), "Task") + ")";
            if (!t.assignees().isEmpty()) {
                // Zuweisung getrennt: das Feld 'assignee' steht nicht auf jedem Create-Screen
                try {
                    msg += ", " + assign(newKey, project, t.assignees()).message();
                } catch (RuntimeException e) {
                    msg += ", Zuweisung fehlgeschlagen: " + e.getMessage();
                }
            }
            return new WriteResult(newKey, msg, http.baseUrl() + "/browse/" + newKey);
        }

        @Override
        public List<WorkLogEntry> worklogs(String key, String project) {
            String k = issueKey(key);
            List<WorkLogEntry> out = new ArrayList<>();
            int startAt = 0;
            while (out.size() < 1000) {
                JsonNode res = http().getJson("/rest/api/2/issue/" + HttpJson.enc(k) + "/worklog"
                        + query("startAt", startAt, "maxResults", 500));
                JsonNode logs = res.path("worklogs");
                for (JsonNode w : logs) {
                    String started = text(w.path("started"));
                    out.add(new WorkLogEntry(text(w.path("id")), user(w.path("author")), started == null ? null : day(started),
                            Duration.ofSeconds(w.path("timeSpentSeconds").asLong()), text(w.path("comment")), null));
                }
                startAt += logs.size();
                if (logs.isEmpty() || startAt >= res.path("total").asInt(startAt)) {
                    break;
                }
            }
            return out;
        }

        /** Zieht die Dauer automatisch von der Restschätzung ab (Jira-Standard {@code adjustEstimate=auto}). */
        @Override
        public WriteResult logTime(String key, String project, WorkLog work) {
            if (work.activity() != null) {
                throw new IllegalArgumentException("Jira kennt keine Tätigkeitsart beim Buchen – ohne 'activity' aufrufen.");
            }
            String k = issueKey(key);
            var body = HttpJson.object();
            body.put("timeSpentSeconds", work.duration().toSeconds());
            body.put("started", work.start().format(STARTED));
            if (work.comment() != null) {
                body.put("comment", work.comment());
            }
            String id = text(http().post("/rest/api/2/issue/" + HttpJson.enc(k) + "/worklog", body).body().path("id"));
            return new WriteResult(k, TicketSystem.formatDuration(work.duration()) + " gebucht am " + work.date()
                    + (id == null ? "" : " (Buchung " + id + ")"),
                    http.baseUrl() + "/browse/" + k + (id == null ? "" : "?focusedWorklogId=" + id), id);
        }

        @Override
        public String canonicalKey(String key, String project) {
            return issueKey(key);
        }

        @Override
        public String instance() {
            return http == null ? id() : http.baseUrl();
        }

        @Override
        public WriteResult deleteComment(String key, String project, String commentId) {
            String k = issueKey(key);
            http().delete("/rest/api/2/issue/" + HttpJson.enc(k) + "/comment/" + HttpJson.enc(commentId.trim()));
            return new WriteResult(k, "Kommentar " + commentId.trim() + " gelöscht", http.baseUrl() + "/browse/" + k);
        }

        @Override
        public WriteResult delete(String key, String project) {
            String k = issueKey(key);
            try {
                // deleteSubtasks=false: Jira lehnt dann Tickets mit Unteraufgaben ab, statt sie mitzulöschen
                http().delete("/rest/api/2/issue/" + HttpJson.enc(k) + query("deleteSubtasks", "false"));
            } catch (HttpJson.StatusException e) {
                if (e.status() == 400) {
                    throw new IllegalArgumentException(e.getMessage() + " – Tickets mit Unteraufgaben werden nicht gelöscht; "
                            + "Unteraufgaben zuerst einzeln löschen oder das Ticket schließen (ticket_transition).", e);
                }
                throw e;
            }
            return new WriteResult(k, "Ticket gelöscht", null);
        }

        private String issueTypes(String project) {
            try {
                return String.join(", ", texts(http.getJson("/rest/api/2/project/" + HttpJson.enc(project.trim()))
                        .path("issueTypes"), "name"));
            } catch (RuntimeException e) {
                return "(nicht abrufbar)";
            }
        }

        // ------------------------------------------------------------------ Einzelnes Ticket

        @Override
        public TicketDetails ticket(String key, String project, int maxComments) {
            String k = issueKey(key);
            Map<String, String> storyPoints = storyPointFields();
            String fields = LIST_FIELDS + ",description,reporter,created,parent,fixVersions,components,duedate,resolution"
                    + (maxComments > 0 ? ",comment" : "")
                    + (storyPoints.isEmpty() ? "" : "," + String.join(",", storyPoints.keySet()));
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
            storyPoints.forEach((id, name) -> put(extra, name, number(text(f.path(id)))));

            List<Comment> comments = new ArrayList<>();
            int totalComments = 0;
            if (maxComments > 0) {
                JsonNode all = f.path("comment").path("comments");
                totalComments = f.path("comment").path("total").asInt(all.size());
                int from = Math.max(0, all.size() - maxComments);
                for (int n = from; n < all.size(); n++) {
                    JsonNode c = all.get(n);
                    comments.add(new Comment(text(c.path("id")), user(c.path("author")), text(c.path("created")), text(c.path("body"))));
                }
            }
            return new TicketDetails(ticket(i), user(f.path("reporter")), text(f.path("created")),
                    text(f.path("description")), extra, comments, totalComments);
        }

        /**
         * Story-Point-Felder (Custom Fields, je Instanz andere ID: „Story Points“, „Story point estimate“), einmal je
         * Verbindung über die Feldliste ermittelt. Ohne Feldliste (Rechte, alte Version) bleibt die Map leer.
         */
        private Map<String, String> storyPointFields() {
            Map<String, String> found = storyPointFields;
            if (found == null) {
                found = new LinkedHashMap<>();
                try {
                    for (JsonNode n : http().getJson("/rest/api/2/field")) {
                        String id = text(n.path("id"));
                        String name = text(n.path("name"));
                        if (id != null && name != null && STORY_POINTS.matcher(name).find()) {
                            found.put(id, name);
                        }
                    }
                } catch (RuntimeException e) {
                    // nur Zusatzinformation – das Ticket soll trotzdem lesbar sein
                }
                storyPointFields = found;
            }
            return found;
        }

        /** {@code 5.0} → {@code 5}: Jira liefert Story Points als Gleitkommazahl. */
        private static String number(String v) {
            return v != null && v.endsWith(".0") ? v.substring(0, v.length() - 2) : v;
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
