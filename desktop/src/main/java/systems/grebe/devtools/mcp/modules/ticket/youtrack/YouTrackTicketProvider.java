package systems.grebe.devtools.mcp.modules.ticket.youtrack;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.core.Text;
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
 * YouTrack (Cloud und Server) über die REST-API unter {@code /api}: Issues mit der YouTrack-Suchsprache, Custom Fields
 * (State, Assignee, Priority, Type), Tags als Labels und Agile Boards (aktueller Sprint, Spalten aus den
 * Board-Einstellungen). Statuswechsel und Tags laufen über Befehle ({@code /api/commands}), damit Workflows greifen.
 */
public class YouTrackTicketProvider implements TicketProvider {

    static final String BASE_URL = "baseUrl";
    static final String TOKEN = "token";

    @Override
    public String id() {
        return "youtrack";
    }

    @Override
    public String displayName() {
        return "YouTrack";
    }

    @Override
    public int priority() {
        return 40;
    }

    @Override
    public List<ConfigField> configFields() {
        return List.of(
                ConfigField.of(BASE_URL, "Server-URL", FieldType.URL)
                        .withHelp("Ohne /api, z.B. https://firma.youtrack.cloud oder https://youtrack.firma.de"),
                ConfigField.of(TOKEN, "Permanent Token", FieldType.SECRET)
                        .withHelp("Profil → Kontosicherheit → Neues Token (Scope YouTrack). Wird verschlüsselt gespeichert."));
    }

    @Override
    public String projectHelp() {
        return "YouTrack-Projekt-ID (Kurzname), z.B. ABC";
    }

    @Override
    public String keyHelp() {
        return "Issue-ID wie ABC-123 oder Issue-URL";
    }

    @Override
    public String queryHelp() {
        return "YouTrack-Suchsprache, z.B. State: {In Progress} Priority: Critical (ohne sort by: wird nach updated sortiert)";
    }

    @Override
    public TicketSystem create(ProviderSettings s) {
        String base = HttpJson.stripSlash(s.getString(BASE_URL, ""));
        if (base.endsWith("/api")) {
            base = base.substring(0, base.length() - 4);
        }
        Map<String, String> headers = new LinkedHashMap<>();
        s.get(TOKEN).ifPresent(t -> headers.put("Authorization", "Bearer " + t));
        return new YouTrack(base.isEmpty() ? null : new HttpJson("YouTrack", base + "/api", headers, s.timeout()), base);
    }

    /** YouTrack-Anbindung. Paketsichtbar für Tests. */
    static final class YouTrack implements TicketSystem {

        private static final Pattern KEY = Pattern.compile("(?i)\\b([A-Z][A-Z0-9_]*-\\d+)\\b");
        private static final String USER_FIELDS = "id,login,fullName,email";
        private static final String LIST_FIELDS = "idReadable,summary,updated,resolved,tags(name),"
                + "customFields(name,$type,value(name,login,fullName,presentation,text,isResolved))";
        private static final String AGILE_FIELDS = "id,name,projects(shortName,name),sprintsSettings(disableSprints),"
                + "currentSprint(id,name,start,finish)";
        private static final int BOARD_MAX = 300;
        private static final Set<String> ASSIGNEE = Set.of("assignee", "assignees", "zuständig", "zuständige", "bearbeiter");
        private static final Set<String> PRIORITY = Set.of("priority", "priorität");
        private static final Set<String> TYPE = Set.of("type", "typ");
        private static final List<String> IN_PROGRESS = List.of("progress", "arbeit", "bearbeitung", "review", "test",
                "doing", "develop", "entwicklung");

        private final HttpJson http;
        private final String webBase;

        YouTrack(HttpJson http, String webBase) {
            this.http = http;
            this.webBase = webBase;
        }

        @Override
        public String id() {
            return "youtrack";
        }

        private HttpJson http() {
            if (http == null) {
                throw new IllegalStateException("YouTrack: keine Server-URL konfiguriert – in der DevTools-App unter "
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
                JsonNode me = http.getJson("/users/me" + query("fields", "login,fullName"));
                String version = null;
                try {
                    version = text(http.getJson("/config" + query("fields", "version")).path("version"));
                } catch (RuntimeException ignored) {
                    // Version ist Beiwerk – manche Instanzen geben die Konfiguration nicht heraus
                }
                return Availability.ok(version == null ? null : "YouTrack " + version, name(me));
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
            if (http != null && k.startsWith(webBase + "/issue/")) {
                return true;
            }
            return k.matches("[A-Z][A-Z0-9_]*-\\d+");
        }

        /** Issue-ID aus {@code ABC-123}, {@code abc-123} oder einer Issue-URL ({@code …/issue/ABC-123/titel}). */
        static String issueId(String key) {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("YouTrack: keine Issue-ID angegeben (z.B. ABC-123).");
            }
            String k = key.trim();
            int at = k.indexOf("/issue/");
            Matcher m = KEY.matcher(at < 0 ? k : k.substring(at + 7));
            if (!m.find()) {
                throw new IllegalArgumentException("YouTrack: '" + key + "' ist keine Issue-ID (erwartet z.B. ABC-123).");
            }
            return m.group(1).toUpperCase(Locale.ROOT);
        }

        private String issuePath(String id) {
            return "/issues/" + enc(id);
        }

        private String webUrl(String id) {
            return webBase + "/issue/" + id;
        }

        // ------------------------------------------------------------------ Boards

        @Override
        public List<Board> boards(String project) {
            List<Board> out = new ArrayList<>();
            for (JsonNode a : http().getJson("/agiles" + query("fields", AGILE_FIELDS, "$top", 200))) {
                List<String> projects = new ArrayList<>(texts(a.path("projects"), "shortName"));
                if (project != null && !project.isBlank() && Stream.concat(projects.stream(),
                        texts(a.path("projects"), "name").stream()).noneMatch(p -> p.equalsIgnoreCase(project.trim()))) {
                    continue;
                }
                out.add(board(a, projects));
            }
            return out;
        }

        private Board board(JsonNode a, List<String> projects) {
            String id = text(a.path("id"));
            return new Board(id, text(a.path("name")),
                    a.path("sprintsSettings").path("disableSprints").asBoolean(false) ? "kanban" : "scrum",
                    projects.isEmpty() ? null : String.join(", ", projects), webBase + "/agiles/" + id + "/current");
        }

        @Override
        public BoardView board(String boardRef, String project, BoardOptions options) {
            Board board = TicketSystem.pickBoard(boards(project), boardRef, "YouTrack");
            JsonNode a = http.getJson("/agiles/" + enc(board.id()) + query("fields", AGILE_FIELDS
                    + ",columnSettings(field(name),columns(presentation,fieldValues(name)))"));

            // Spalten und die Feldwerte (meist State), die auf sie abgebildet sind
            String columnField = text(a.path("columnSettings").path("field").path("name"));
            Map<String, String> columnByValue = new LinkedHashMap<>();
            Map<String, List<Ticket>> byColumn = new LinkedHashMap<>();
            for (JsonNode c : a.path("columnSettings").path("columns")) {
                List<String> values = texts(c.path("fieldValues"), "name");
                String name = HttpJson.first(text(c.path("presentation")), String.join(", ", values));
                byColumn.put(name, new ArrayList<>());
                values.forEach(v -> columnByValue.put(v.toLowerCase(Locale.ROOT), name));
            }

            List<JsonNode> issues = new ArrayList<>();
            String scope;
            JsonNode sprint = a.path("currentSprint");
            if (text(sprint.path("id")) != null) {
                JsonNode s = http.getJson("/agiles/" + enc(board.id()) + "/sprints/" + enc(text(sprint.path("id")))
                        + query("fields", "id,name,start,finish,issues(" + LIST_FIELDS + ")"));
                s.path("issues").forEach(issues::add);
                scope = "kanban".equals(board.type()) ? "alle Tickets des Boards"
                        : "Sprint " + text(s.path("name")) + dateRange(s);
            } else {
                List<String> projects = texts(a.path("projects"), "shortName");
                if (projects.isEmpty()) {
                    throw new IllegalArgumentException("YouTrack: Board '" + board.name() + "' hat weder Sprint noch Projekte.");
                }
                http.getJson("/issues" + query("query", "project: " + String.join(", ", projects.stream().map(YouTrack::braces)
                        .toList()) + " #Unresolved sort by: updated desc", "fields", LIST_FIELDS, "$top", BOARD_MAX + 1))
                        .forEach(issues::add);
                scope = "kein aktueller Sprint – offene Tickets der Board-Projekte";
            }
            boolean truncated = issues.size() > BOARD_MAX;
            Predicate<JsonNode> filter = assigneeFilter(options.assignee());
            for (JsonNode i : issues.subList(0, Math.min(issues.size(), BOARD_MAX))) {
                if (!filter.test(i)) {
                    continue;
                }
                JsonNode f = columnField == null ? field(i, YouTrack::isState)
                        : field(i, x -> columnField.equalsIgnoreCase(text(x.path("name"))));
                String value = f == null ? null : fieldValue(f);
                String col = value == null ? null : columnByValue.get(value.toLowerCase(Locale.ROOT));
                byColumn.computeIfAbsent(col == null ? "(keiner Spalte zugeordnet)" : col, k -> new ArrayList<>()).add(ticket(i));
            }
            List<Column> columns = new ArrayList<>();
            byColumn.forEach((name, list) -> columns.add(new Column(name,
                    list.subList(0, Math.min(list.size(), options.maxPerColumn())), list.size())));
            return new BoardView(board, scope, columns,
                    truncated ? "nur die ersten " + BOARD_MAX + " Tickets geladen – mit ticket_search eingrenzen" : null);
        }

        /** Zuständigen-Filter auf die rohen Issues (Board-Inhalte lassen sich serverseitig nicht filtern). */
        private Predicate<JsonNode> assigneeFilter(String assignee) {
            if (assignee == null || assignee.isBlank()) {
                return i -> true;
            }
            if (TicketSystem.isNone(assignee)) {
                return i -> assigneeNames(i).isEmpty();
            }
            String who = TicketSystem.isMe(assignee) ? text(http.getJson("/users/me" + query("fields", "login")).path("login"))
                    : assignee.trim().replaceFirst("^@", "");
            return i -> assigneeNames(i).stream().anyMatch(n -> n.equalsIgnoreCase(who));
        }

        /** Login und Anzeigename aller Zuständigen. */
        private static List<String> assigneeNames(JsonNode issue) {
            JsonNode f = field(issue, YouTrack::isAssignee);
            List<String> out = new ArrayList<>();
            if (f != null) {
                items(f.path("value")).forEach(u -> Stream.of(text(u.path("login")), text(u.path("fullName")))
                        .filter(s -> s != null).forEach(out::add));
            }
            return out;
        }

        private static String dateRange(JsonNode sprint) {
            String start = time(sprint.path("start"));
            String end = time(sprint.path("finish"));
            return start == null || end == null ? "" : " (" + day(start) + " – " + day(end) + ")";
        }

        private static String day(String iso) {
            return iso.length() >= 10 ? iso.substring(0, 10) : iso;
        }

        // ------------------------------------------------------------------ Suche

        static String braces(String s) {
            return "{" + s.trim() + "}";
        }

        /** Baut die Suchanfrage aus den Filtern; eine eigene Abfrage wird UND-verknüpft, ihr {@code sort by:} gewinnt. */
        static String youTrackQuery(TicketQuery q) {
            List<String> parts = new ArrayList<>();
            if (q.project() != null && !q.project().isBlank()) {
                parts.add("project: " + braces(q.project()));
            }
            switch (q.state()) {
                case OPEN -> parts.add("#Unresolved");
                case CLOSED -> parts.add("#Resolved");
                default -> { }
            }
            String a = q.assignee();
            if (a != null && !a.isBlank()) {
                parts.add(TicketSystem.isMe(a) ? "for: me" : TicketSystem.isNone(a) ? "has: -Assignee"
                        : "for: " + braces(a.trim().replaceFirst("^@", "")));
            }
            q.labels().forEach(l -> parts.add("tag: " + braces(l)));
            if (q.text() != null && !q.text().isBlank()) {
                parts.add(q.text().trim());
            }
            String order = "sort by: updated desc";
            if (q.rawQuery() != null && !q.rawQuery().isBlank()) {
                String raw = q.rawQuery().trim();
                Matcher m = Pattern.compile("(?i)\\bsort\\s+by\\s*:").matcher(raw);
                if (m.find()) {
                    order = raw.substring(m.start()).trim();
                    raw = raw.substring(0, m.start()).trim();
                }
                if (!raw.isEmpty()) {
                    parts.add("(" + raw + ")");
                }
            }
            parts.add(order);
            return String.join(" ", parts);
        }

        @Override
        public TicketPage search(TicketQuery q) {
            String yq = youTrackQuery(q);
            int limit = Math.max(1, Math.min(q.limit(), 100));
            int skip = parseCursor(q.cursor());
            // einer mehr als nötig: zeigt, ob es eine weitere Seite gibt (YouTrack liefert keine Gesamtzahl)
            JsonNode res = http().getJson("/issues" + query("query", yq, "fields", LIST_FIELDS, "$skip", skip, "$top", limit + 1));
            List<Ticket> tickets = new ArrayList<>();
            for (JsonNode i : res) {
                if (tickets.size() < limit) {
                    tickets.add(ticket(i));
                }
            }
            return new TicketPage(tickets, null, res.size() > limit ? String.valueOf(skip + limit) : null, yq);
        }

        private static int parseCursor(String cursor) {
            if (cursor == null || cursor.isBlank()) {
                return 0;
            }
            try {
                return Math.max(0, Integer.parseInt(cursor.trim()));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("YouTrack: ungültiger cursor '" + cursor + "' – den Wert aus der vorigen "
                        + "ticket_search-Ausgabe verwenden.");
            }
        }

        // ------------------------------------------------------------------ Verknüpfungen, Statuswechsel, Schreiben

        @Override
        public String projectOf(String key, String project) {
            String id = issueId(key);
            return id.substring(0, id.lastIndexOf('-'));
        }

        @Override
        public String markup() {
            return "Markdown (YouTrack)";
        }

        @Override
        public List<Link> links(String key, String project) {
            String id = issueId(key);
            List<Link> out = new ArrayList<>();
            for (JsonNode l : http().getJson(issuePath(id) + "/links" + query("fields", "direction,"
                    + "linkType(name,sourceToTarget,targetToSource),issues(idReadable,summary,customFields(name,$type,value(name)))"))) {
                String relation = relation(l);
                for (JsonNode i : l.path("issues")) {
                    String other = text(i.path("idReadable"));
                    JsonNode state = field(i, YouTrack::isState);
                    out.add(new Link(relation, other, text(i.path("summary")), state == null ? null : fieldValue(state),
                            webUrl(other)));
                }
            }
            return out;
        }

        private static String relation(JsonNode link) {
            JsonNode type = link.path("linkType");
            return "INWARD".equals(text(link.path("direction")))
                    ? HttpJson.first(text(type.path("targetToSource")), text(type.path("name")))
                    : HttpJson.first(text(type.path("sourceToTarget")), text(type.path("name")));
        }

        /** Linktypen der Instanz; gerichtete je Richtung ({@code <Typ>:out}/{@code <Typ>:in}), Name = Befehl wie „subtask of“. */
        @Override
        public List<LinkType> linkTypes(String project) {
            List<LinkType> out = new ArrayList<>();
            for (JsonNode t : http().getJson("/issueLinkTypes" + query("fields", "name,sourceToTarget,targetToSource,directed"))) {
                String name = text(t.path("name"));
                String outward = HttpJson.first(text(t.path("sourceToTarget")), name);
                String inward = HttpJson.first(text(t.path("targetToSource")), outward);
                if (t.path("directed").asBoolean(false) && !inward.equalsIgnoreCase(outward)) {
                    out.add(new LinkType(name + ":out", outward, inward));
                    out.add(new LinkType(name + ":in", inward, outward));
                } else {
                    out.add(new LinkType(name, outward, outward));
                }
            }
            return out;
        }

        /** Als Befehl („subtask of ABC-1“) wie in der Oberfläche, damit Workflows greifen. */
        @Override
        public WriteResult link(String key, String project, LinkType type, String target) {
            String id = issueId(key);
            String other = issueId(target);
            command(id, type.name() + " " + other);
            return new WriteResult(id, "verknüpft: " + id + " " + type.name() + " " + other, webUrl(id));
        }

        @Override
        public WriteResult unlink(String key, String project, String target, String relation) {
            String id = issueId(key);
            String other = issueId(target);
            record Found(String relation, String link, String issue) { }
            List<Found> found = new ArrayList<>();
            for (JsonNode l : http().getJson(issuePath(id) + "/links" + query("fields", "id,direction,"
                    + "linkType(name,sourceToTarget,targetToSource),issues(id,idReadable)"))) {
                for (JsonNode i : l.path("issues")) {
                    if (other.equalsIgnoreCase(text(i.path("idReadable")))) {
                        found.add(new Found(relation(l), text(l.path("id")), text(i.path("id"))));
                    }
                }
            }
            Found hit = TicketSystem.pickLink(found, Found::relation, relation, "YouTrack", id, other);
            http.delete(issuePath(id) + "/links/" + enc(hit.link()) + "/issues/" + enc(hit.issue()));
            return new WriteResult(id, "Verknüpfung entfernt: " + id + " " + hit.relation() + " " + other, webUrl(id));
        }

        /** Werte des Status-Felds (State) außer dem aktuellen; ID {@code <Feld>:<Wert>}. */
        @Override
        public List<Transition> transitions(String key, String project) {
            String id = issueId(key);
            JsonNode i = http().getJson(issuePath(id) + query("fields", "customFields(name,$type,value(name),"
                    + "projectCustomField(field(name),bundle(values(name,isResolved,archived))))"));
            JsonNode state = field(i, YouTrack::isState);
            if (state == null) {
                throw new IllegalArgumentException("YouTrack: " + id + " hat kein Status-Feld (State).");
            }
            String fieldName = text(state.path("name"));
            String current = fieldValue(state);
            List<Transition> out = new ArrayList<>();
            for (JsonNode v : state.path("projectCustomField").path("bundle").path("values")) {
                String name = text(v.path("name"));
                if (name == null || name.equalsIgnoreCase(current) || v.path("archived").asBoolean(false)) {
                    continue;
                }
                out.add(new Transition(fieldName + ":" + name, fieldName + " → " + name, name,
                        v.path("isResolved").asBoolean(false) ? StatusCategory.DONE : category(name, false), "Status"));
            }
            return out;
        }

        @Override
        public WriteResult transition(String key, String project, Transition t) {
            String id = issueId(key);
            int colon = t.id().indexOf(':');
            if (colon <= 0) {
                throw new IllegalArgumentException("YouTrack: unbekannter Statuswechsel '" + t.id()
                        + "' – ticket_transitions liefert die möglichen.");
            }
            // als Befehl, damit Workflows und Zustandsautomaten greifen
            command(id, t.id().substring(0, colon) + " " + braces(t.id().substring(colon + 1)));
            return new WriteResult(id, "Status → " + t.to(), webUrl(id));
        }

        private void command(String id, String command) {
            var body = HttpJson.object();
            body.put("query", command);
            body.putArray("issues").addObject().put("idReadable", id);
            http().post("/commands", body);
        }

        @Override
        public WriteResult comment(String key, String project, String body) {
            String id = issueId(key);
            var req = HttpJson.object();
            req.put("text", body);
            String cid = text(http().post(issuePath(id) + "/comments" + query("fields", "id"), req).body().path("id"));
            return new WriteResult(id, "Kommentar " + Text.orDash(cid) + " hinzugefügt",
                    webUrl(id) + (cid == null ? "" : "#focus=Comments-" + cid + ".0-0"), cid);
        }

        @Override
        public WriteResult assign(String key, String project, List<String> assignees) {
            String id = issueId(key);
            JsonNode f = field(http().getJson(issuePath(id) + query("fields", "customFields(name,$type)")), YouTrack::isAssignee);
            if (f == null) {
                throw new IllegalArgumentException("YouTrack: " + id + " hat kein Zuständigen-Feld (Assignee).");
            }
            String type = text(f.path("$type"));
            boolean multi = type != null && type.startsWith("Multi");
            List<String> who = assignees.stream().filter(a -> !TicketSystem.isNone(a)).toList();
            if (!multi && who.size() > 1) {
                throw new IllegalArgumentException("YouTrack: das Feld '" + text(f.path("name")) + "' hat genau einen "
                        + "Zuständigen – nur einen Benutzer angeben.");
            }
            List<JsonNode> users = who.stream().map(this::findUser).toList();
            var cf = HttpJson.object();
            cf.put("name", text(f.path("name")));
            cf.put("$type", type);
            if (multi) {
                var arr = cf.putArray("value");
                users.forEach(u -> arr.addObject().put("id", text(u.path("id"))));
            } else if (users.isEmpty()) {
                cf.putNull("value");
            } else {
                cf.putObject("value").put("id", text(users.getFirst().path("id")));
            }
            var body = HttpJson.object();
            body.putArray("customFields").add(cf);
            http.post(issuePath(id) + query("fields", "idReadable"), body);
            return new WriteResult(id, "zugewiesen an " + (users.isEmpty() ? "niemand"
                    : String.join(", ", users.stream().map(YouTrack::name).toList())), webUrl(id));
        }

        private JsonNode findUser(String who) {
            if (TicketSystem.isMe(who)) {
                return http().getJson("/users/me" + query("fields", USER_FIELDS));
            }
            String q = who.trim().replaceFirst("^@", "");
            List<JsonNode> list = new ArrayList<>();
            http().getJson("/users" + query("fields", USER_FIELDS, "query", q, "$top", 20)).forEach(list::add);
            List<JsonNode> exact = list.stream().filter(u -> Stream.of(text(u.path("login")), text(u.path("email")),
                    text(u.path("fullName"))).anyMatch(v -> v != null && v.equalsIgnoreCase(q))).toList();
            if (exact.size() == 1) {
                return exact.getFirst();
            }
            if (list.size() == 1) {
                return list.getFirst();
            }
            if (list.isEmpty()) {
                throw new IllegalArgumentException("YouTrack: kein Benutzer '" + q + "' (Login, E-Mail oder voller Name).");
            }
            throw new IllegalArgumentException("YouTrack: '" + q + "' ist mehrdeutig: " + String.join(", ", list.stream()
                    .limit(10).map(u -> name(u) + " (" + text(u.path("login")) + ")").toList()) + " – genauer angeben.");
        }

        @Override
        public WriteResult update(String key, String project, TicketUpdate update) {
            String id = issueId(key);
            if (update.title() != null || update.description() != null) {
                var body = HttpJson.object();
                if (update.title() != null) {
                    body.put("summary", update.title());
                }
                if (update.description() != null) {
                    body.put("description", update.description());
                }
                http().post(issuePath(id) + query("fields", "idReadable"), body);
            }
            if (update.labels() != null) {
                List<String> current = texts(http().getJson(issuePath(id) + query("fields", "tags(name)")).path("tags"), "name");
                for (String tag : current) {
                    if (update.labels().stream().noneMatch(tag::equalsIgnoreCase)) {
                        command(id, "untag " + braces(tag));
                    }
                }
                for (String tag : update.labels()) {
                    if (current.stream().noneMatch(tag::equalsIgnoreCase)) {
                        command(id, "tag " + braces(tag));
                    }
                }
            }
            return new WriteResult(id, "geändert: " + update.summary(), webUrl(id));
        }

        @Override
        public WriteResult create(String project, NewTicket t) {
            if (project == null || project.isBlank()) {
                throw new IllegalArgumentException("YouTrack: 'project' (Projekt-ID wie ABC) angeben oder Standardprojekt setzen.");
            }
            var body = HttpJson.object();
            body.putObject("project").put("id", projectId(project.trim()));
            body.put("summary", t.title());
            if (t.description() != null) {
                body.put("description", t.description());
            }
            if (t.type() != null && !t.type().isBlank()) {
                var cf = body.putArray("customFields").addObject();
                cf.put("name", "Type");
                cf.put("$type", "SingleEnumIssueCustomField");
                cf.putObject("value").put("name", t.type().trim());
            }
            String newId = text(http().post("/issues" + query("fields", "idReadable"), body).body().path("idReadable"));
            String msg = "angelegt" + (t.type() == null || t.type().isBlank() ? "" : " (" + t.type().trim() + ")");
            // Tags und Zuständige getrennt: Tags gehen nur per Befehl, das Zuständigen-Feld heißt je Projekt anders
            if (!t.labels().isEmpty()) {
                try {
                    t.labels().forEach(l -> command(newId, "tag " + braces(l)));
                    msg += ", Tags " + t.labels();
                } catch (RuntimeException e) {
                    msg += ", Tags fehlgeschlagen: " + e.getMessage();
                }
            }
            if (!t.assignees().isEmpty()) {
                try {
                    msg += ", " + assign(newId, project, t.assignees()).message();
                } catch (RuntimeException e) {
                    msg += ", Zuweisung fehlgeschlagen: " + e.getMessage();
                }
            }
            return new WriteResult(newId, msg, webUrl(newId));
        }

        /** Interne ID eines Projekts aus Kurzname oder Name. */
        private String projectId(String project) {
            List<JsonNode> all = new ArrayList<>();
            http().getJson("/admin/projects" + query("fields", "id,shortName,name", "$top", 500)).forEach(all::add);
            return all.stream().filter(p -> project.equalsIgnoreCase(text(p.path("shortName")))
                            || project.equalsIgnoreCase(text(p.path("name"))))
                    .map(p -> text(p.path("id"))).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("YouTrack: Projekt '" + project + "' nicht gefunden. "
                            + "Verfügbar: " + String.join(", ", all.stream().map(p -> text(p.path("shortName"))).limit(30).toList())));
        }

        // ------------------------------------------------------------------ Zeiterfassung

        @Override
        public List<WorkLogEntry> worklogs(String key, String project) {
            String id = issueId(key);
            List<WorkLogEntry> out = new ArrayList<>();
            for (JsonNode w : http().getJson(issuePath(id) + "/timeTracking/workItems" + query("fields",
                    "id,author(login,fullName),date,duration(minutes),text,type(name)", "$top", 1000))) {
                JsonNode date = w.path("date");
                out.add(new WorkLogEntry(text(w.path("id")), name(w.path("author")), date.isNumber()
                        ? Instant.ofEpochMilli(date.asLong()).atZone(ZoneId.systemDefault()).toLocalDate().toString() : null,
                        Duration.ofMinutes(w.path("duration").path("minutes").asLong()), text(w.path("text")),
                        text(w.path("type").path("name"))));
            }
            out.sort(Comparator.comparing(WorkLogEntry::date, Comparator.nullsLast(Comparator.naturalOrder())));
            return out;
        }

        @Override
        public WriteResult logTime(String key, String project, WorkLog work) {
            String id = issueId(key);
            var body = HttpJson.object();
            body.putObject("duration").put("minutes", work.duration().toMinutes());
            body.put("date", work.start().toInstant().toEpochMilli());
            if (work.comment() != null) {
                body.put("text", work.comment());
            }
            if (work.activity() != null) {
                body.putObject("type").put("id", workItemType(work.activity()));
            }
            String wid;
            try {
                wid = text(http().post(issuePath(id) + "/timeTracking/workItems" + query("fields", "id"), body).body().path("id"));
            } catch (HttpJson.StatusException e) {
                if (e.status() == 400) {
                    throw new IllegalArgumentException(e.getMessage() + " – ist die Zeiterfassung im Projekt aktiviert?", e);
                }
                throw e;
            }
            return new WriteResult(id, TicketSystem.formatDuration(work.duration()) + " gebucht am " + work.date()
                    + (work.activity() == null ? "" : " (" + work.activity() + ")"), webUrl(id), wid);
        }

        /** ID eines Work Item Types per Name – exakt vor eindeutigem Teilstring. */
        private String workItemType(String name) {
            List<JsonNode> all = new ArrayList<>();
            http().getJson("/admin/timeTrackingSettings/workItemTypes" + query("fields", "id,name")).forEach(all::add);
            List<JsonNode> exact = all.stream().filter(t -> name.equalsIgnoreCase(text(t.path("name")))).toList();
            String lower = name.toLowerCase(Locale.ROOT);
            List<JsonNode> hits = exact.isEmpty() ? all.stream().filter(t -> HttpJson.first(text(t.path("name")), "")
                    .toLowerCase(Locale.ROOT).contains(lower)).toList() : exact;
            if (hits.size() == 1) {
                return text(hits.getFirst().path("id"));
            }
            throw new IllegalArgumentException("YouTrack: Tätigkeitsart '" + name + "' " + (hits.isEmpty() ? "gibt es nicht"
                    : "ist mehrdeutig") + ". Verfügbar: " + String.join(", ", all.stream()
                    .map(t -> text(t.path("name"))).toList()));
        }

        @Override
        public String canonicalKey(String key, String project) {
            return issueId(key);
        }

        @Override
        public String instance() {
            return http == null ? id() : webBase;
        }

        @Override
        public WriteResult deleteComment(String key, String project, String commentId) {
            String id = issueId(key);
            http().delete(issuePath(id) + "/comments/" + enc(commentId.trim()));
            return new WriteResult(id, "Kommentar " + commentId.trim() + " gelöscht", webUrl(id));
        }

        @Override
        public WriteResult delete(String key, String project) {
            String id = issueId(key);
            http().delete(issuePath(id));
            return new WriteResult(id, "Issue gelöscht", null);
        }

        // ------------------------------------------------------------------ Einzelnes Ticket

        @Override
        public TicketDetails ticket(String key, String project, int maxComments) {
            String id = issueId(key);
            JsonNode i = http().getJson(issuePath(id) + query("fields", LIST_FIELDS + ",description,created,commentsCount,"
                    + "reporter(login,fullName),parent(issues(idReadable,summary))"));
            Map<String, String> extra = new LinkedHashMap<>();
            JsonNode parent = i.path("parent").path("issues").path(0);
            if (parent.isObject()) {
                extra.put("Parent", text(parent.path("idReadable")) + " " + HttpJson.first(text(parent.path("summary")), ""));
            }
            // übrige Custom Fields (Sprint, Fix versions, Estimation …); die bekannten stehen schon in der Kopfzeile
            for (JsonNode f : i.path("customFields")) {
                if (isState(f) || isAssignee(f) || named(f, PRIORITY) || named(f, TYPE)) {
                    continue;
                }
                String v = fieldValue(f);
                if (v != null && text(f.path("name")) != null) {
                    extra.put(text(f.path("name")), v);
                }
            }
            String resolved = time(i.path("resolved"));
            if (resolved != null) {
                extra.put("Erledigt", resolved);
            }

            int total = i.path("commentsCount").asInt(0);
            List<Comment> comments = new ArrayList<>();
            if (maxComments > 0 && total > 0) {
                JsonNode all = http.getJson(issuePath(id) + "/comments" + query("fields",
                        "id,text,created,deleted,author(login,fullName)", "$skip", Math.max(0, total - maxComments),
                        "$top", maxComments));
                for (JsonNode c : all) {
                    if (!c.path("deleted").asBoolean(false)) {
                        comments.add(new Comment(text(c.path("id")), name(c.path("author")), time(c.path("created")),
                                text(c.path("text"))));
                    }
                }
            }
            return new TicketDetails(ticket(i), name(i.path("reporter")), time(i.path("created")),
                    text(i.path("description")), extra, comments, total);
        }

        private Ticket ticket(JsonNode i) {
            String id = text(i.path("idReadable"));
            JsonNode state = field(i, YouTrack::isState);
            String status = state == null ? null : fieldValue(state);
            boolean resolved = i.path("resolved").isNumber()
                    || state != null && state.path("value").path("isResolved").asBoolean(false);
            JsonNode assignee = field(i, YouTrack::isAssignee);
            List<String> assignees = new ArrayList<>();
            if (assignee != null) {
                items(assignee.path("value")).forEach(u -> {
                    String n = name(u);
                    if (n != null) {
                        assignees.add(n);
                    }
                });
            }
            JsonNode type = field(i, f -> named(f, TYPE));
            JsonNode priority = field(i, f -> named(f, PRIORITY));
            return new Ticket(id, text(i.path("summary")), status, category(status, resolved),
                    type == null ? null : fieldValue(type), priority == null ? null : fieldValue(priority),
                    assignees, texts(i.path("tags"), "name"), time(i.path("updated")), webUrl(id));
        }

        /** Elemente eines Arrays bzw. der einzelne Wert. */
        private static List<JsonNode> items(JsonNode v) {
            List<JsonNode> out = new ArrayList<>();
            if (v.isArray()) {
                v.forEach(out::add);
            } else if (v.isObject()) {
                out.add(v);
            }
            return out;
        }

        private static JsonNode field(JsonNode issue, Predicate<JsonNode> p) {
            for (JsonNode f : issue.path("customFields")) {
                if (p.test(f)) {
                    return f;
                }
            }
            return null;
        }

        private static boolean isState(JsonNode f) {
            String t = text(f.path("$type"));
            return t != null && t.startsWith("State");
        }

        private static boolean isAssignee(JsonNode f) {
            String t = text(f.path("$type"));
            return t != null && t.endsWith("UserIssueCustomField") && named(f, ASSIGNEE);
        }

        private static boolean named(JsonNode f, Set<String> names) {
            String n = text(f.path("name"));
            return n != null && names.contains(n.toLowerCase(Locale.ROOT));
        }

        /** Wert eines Custom Fields als Text: Name, Benutzer oder Präsentation; Listen kommagetrennt; Datum als ISO. */
        static String fieldValue(JsonNode f) {
            JsonNode v = f.path("value");
            String type = text(f.path("$type"));
            if (v.isArray()) {
                List<String> parts = new ArrayList<>();
                v.forEach(e -> {
                    String s = valueText(e, type);
                    if (s != null) {
                        parts.add(s);
                    }
                });
                return parts.isEmpty() ? null : String.join(", ", parts);
            }
            return valueText(v, type);
        }

        private static String valueText(JsonNode v, String type) {
            if (v.isObject()) {
                return HttpJson.first(text(v.path("fullName")), text(v.path("name")), text(v.path("login")),
                        text(v.path("presentation")), text(v.path("text")));
            }
            if (v.isNumber() && type != null && type.startsWith("Date")) {
                String iso = time(v);
                return "DateIssueCustomField".equals(type) ? day(iso) : iso;
            }
            return text(v);
        }

        private static String name(JsonNode u) {
            return HttpJson.first(text(u.path("fullName")), text(u.path("login")));
        }

        /** Zeitstempel (Millisekunden seit 1970) als ISO-8601. */
        private static String time(JsonNode n) {
            return n.isNumber() ? Instant.ofEpochMilli(n.asLong()).toString() : text(n);
        }

        static StatusCategory category(String status, boolean resolved) {
            if (resolved) {
                return StatusCategory.DONE;
            }
            if (status == null) {
                return StatusCategory.UNKNOWN;
            }
            String s = status.toLowerCase(Locale.ROOT);
            return IN_PROGRESS.stream().anyMatch(s::contains) ? StatusCategory.IN_PROGRESS : StatusCategory.TODO;
        }
    }
}
