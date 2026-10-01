package systems.grebe.devtools.mcp.modules.ticket.openproject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import static systems.grebe.devtools.mcp.modules.ticket.spi.HttpJson.enc;
import static systems.grebe.devtools.mcp.modules.ticket.spi.HttpJson.query;
import static systems.grebe.devtools.mcp.modules.ticket.spi.HttpJson.text;

/**
 * OpenProject (Cloud und selbst betrieben) über API v3 (HAL+JSON): Arbeitspakete mit Filtern, Statuswechsel nach
 * Workflow (Formular-Endpunkt), Beziehungen und Boards (Grids, Spalten = gespeicherte Abfragen). Arbeitspakete haben
 * instanzweit eindeutige Nummern ({@code #123}); Labels kennt OpenProject nicht.
 */
public class OpenProjectTicketProvider implements TicketProvider {

    static final String BASE_URL = "baseUrl";
    static final String TOKEN = "token";

    @Override
    public String id() {
        return "openproject";
    }

    @Override
    public String displayName() {
        return "OpenProject";
    }

    @Override
    public int priority() {
        return 50;
    }

    @Override
    public List<ConfigField> configFields() {
        return List.of(
                ConfigField.of(BASE_URL, "Server-URL", FieldType.URL)
                        .withHelp("Ohne /api/v3, z.B. https://firma.openproject.com oder https://openproject.firma.de"),
                ConfigField.of(TOKEN, "API-Schlüssel", FieldType.SECRET)
                        .withHelp("Mein Konto → Zugangstoken → API → Token erzeugen. Wird verschlüsselt gespeichert."));
    }

    @Override
    public String projectHelp() {
        return "Projekt-Kennung (identifier aus der URL), z.B. mein-projekt";
    }

    @Override
    public String keyHelp() {
        return "Arbeitspaket-Nummer wie #123 bzw. 123 oder Arbeitspaket-URL";
    }

    @Override
    public String queryHelp() {
        return "Filter der API v3 als JSON-Array, UND-verknüpft, z.B. [{\"type\":{\"operator\":\"=\",\"values\":[\"1\"]}}]";
    }

    @Override
    public TicketSystem create(ProviderSettings s) {
        String base = HttpJson.stripSlash(s.getString(BASE_URL, ""));
        if (base.endsWith("/api/v3")) {
            base = base.substring(0, base.length() - 7);
        }
        Map<String, String> headers = new LinkedHashMap<>();
        s.get(TOKEN).ifPresent(t -> headers.put("Authorization", "Basic "
                + Base64.getEncoder().encodeToString(("apikey:" + t).getBytes(StandardCharsets.UTF_8))));
        return new OpenProject(base.isEmpty() ? null : new HttpJson("OpenProject", base + "/api/v3", headers, s.timeout()), base);
    }

    /** OpenProject-Anbindung. Paketsichtbar für Tests. */
    static final class OpenProject implements TicketSystem {

        private static final Pattern URL_KEY = Pattern.compile("https?://.+?/work_packages/(?:details/)?(\\d+)(?:[/?#].*)?");
        private static final Pattern SHORT_KEY = Pattern.compile("#?(\\d+)");
        private static final Pattern BOARD_SCOPE = Pattern.compile(".*/projects/([^/]+)/boards/?");
        private static final String SORT = "[[\"updatedAt\",\"desc\"]]";
        private static final List<String> IN_PROGRESS = List.of("progress", "arbeit", "bearbeitung", "review", "test",
                "doing", "develop", "entwicklung");

        private final HttpJson http;
        private final String webBase;
        /** Arbeitspaket → Projekt-Kennung; Arbeitspakete wechseln selten das Projekt. */
        private final Map<Integer, String> projectOfPackage = new ConcurrentHashMap<>();
        /** Status-href → Status (isClosed); erst bei Bedarf geladen. */
        private volatile Map<String, JsonNode> statuses;

        OpenProject(HttpJson http, String webBase) {
            this.http = http;
            this.webBase = webBase;
        }

        @Override
        public String id() {
            return "openproject";
        }

        private HttpJson http() {
            if (http == null) {
                throw new IllegalStateException("OpenProject: keine Server-URL konfiguriert – in der DevTools-App unter "
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
                JsonNode root = http.getJson("");
                String version = text(root.path("coreVersion"));
                JsonNode me = http.getJson("/users/me");
                return Availability.ok(version == null ? text(root.path("instanceName")) : "OpenProject " + version,
                        HttpJson.first(text(me.path("name")), text(me.path("login"))));
            } catch (RuntimeException e) {
                return Availability.unavailable(e.getMessage());
            }
        }

        @Override
        public boolean ownsKey(String key) {
            return key != null && http != null && key.trim().startsWith(webBase + "/") && URL_KEY.matcher(key.trim()).matches();
        }

        /** Nummer aus {@code #123}, {@code 123} oder einer Arbeitspaket-URL. */
        static int packageId(String key) {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("OpenProject: kein Arbeitspaket angegeben (z.B. #123).");
            }
            String k = key.trim();
            Matcher m = URL_KEY.matcher(k);
            if (!m.matches()) {
                m = SHORT_KEY.matcher(k);
            }
            if (!m.matches()) {
                throw new IllegalArgumentException("OpenProject: '" + k + "' ist kein Arbeitspaket (erwartet #123, 123 oder URL).");
            }
            return Integer.parseInt(m.group(1));
        }

        private static String path(int id) {
            return "/work_packages/" + id;
        }

        private String webUrl(int id) {
            return webBase + "/work_packages/" + id;
        }

        // ------------------------------------------------------------------ Boards

        @Override
        public List<Board> boards(String project) {
            Set<String> keys = project == null || project.isBlank() ? null : projectKeys(project.trim());
            List<Board> out = new ArrayList<>();
            for (JsonNode g : http().getJson("/grids" + query("pageSize", 200)).path("_embedded").path("elements")) {
                Matcher m = BOARD_SCOPE.matcher(HttpJson.first(href(g, "scope"), ""));
                if (!m.matches() || keys != null && !keys.contains(m.group(1).toLowerCase(Locale.ROOT))) {
                    continue;
                }
                String id = text(g.path("id"));
                out.add(new Board(id, text(g.path("name")), boardType(g.path("options")), m.group(1),
                        webBase + "/projects/" + m.group(1) + "/boards/" + id));
            }
            return out;
        }

        /** Kennung und numerische ID eines Projekts – der Scope eines Boards kann beides enthalten. */
        private Set<String> projectKeys(String project) {
            JsonNode p = http().getJson("/projects/" + enc(project));
            return Stream.of(project, text(p.path("identifier")), text(p.path("id")))
                    .filter(s -> s != null).map(s -> s.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
        }

        private static String boardType(JsonNode options) {
            String type = text(options.path("type"));
            String attribute = text(options.path("attribute"));
            if ("action".equals(type)) {
                return "Aktions-Board" + (attribute == null ? "" : " (" + attribute + ")");
            }
            return "free".equals(type) ? "Basis-Board" : "Board";
        }

        @Override
        public BoardView board(String boardRef, String project, BoardOptions options) {
            Board board = TicketSystem.pickBoard(boards(project), boardRef, "OpenProject");
            List<JsonNode> widgets = new ArrayList<>();
            http.getJson("/grids/" + enc(board.id())).path("widgets").forEach(widgets::add);
            widgets.sort(Comparator.comparingInt((JsonNode w) -> w.path("startColumn").asInt(0))
                    .thenComparingInt(w -> w.path("startRow").asInt(0)));

            boolean filtered = options.assignee() != null && !options.assignee().isBlank();
            Predicate<JsonNode> filter = assigneeFilter(options.assignee());
            int pageSize = filtered ? 100 : options.maxPerColumn();
            List<Column> columns = new ArrayList<>();
            for (JsonNode w : widgets) {
                String queryId = text(w.path("options").path("queryId"));
                if (queryId == null) {
                    continue;
                }
                // jede Spalte ist eine gespeicherte Abfrage; ihre Filter (Status, Version …) bleiben unangetastet
                JsonNode q = http.getJson("/queries/" + enc(queryId) + query("pageSize", pageSize, "offset", 1));
                JsonNode results = q.path("_embedded").path("results");
                int total = results.path("total").asInt(0);
                List<Ticket> tickets = new ArrayList<>();
                int matching = 0;
                for (JsonNode wp : results.path("_embedded").path("elements")) {
                    if (filter.test(wp)) {
                        matching++;
                        if (tickets.size() < options.maxPerColumn()) {
                            tickets.add(ticket(wp));
                        }
                    }
                }
                // gefiltert: Gesamtzahl nur bekannt, wenn die Spalte vollständig geladen wurde
                columns.add(new Column(HttpJson.first(text(q.path("name")), "Abfrage " + queryId), tickets,
                        !filtered ? total : total <= pageSize ? matching : -1));
            }
            return new BoardView(board, "Spalten = gespeicherte Abfragen des Boards", columns, null);
        }

        private Predicate<JsonNode> assigneeFilter(String assignee) {
            if (assignee == null || assignee.isBlank()) {
                return wp -> true;
            }
            if (TicketSystem.isNone(assignee)) {
                return wp -> href(wp, "assignee") == null;
            }
            if (TicketSystem.isMe(assignee)) {
                String me = href(http.getJson("/users/me"), "self");
                return wp -> me != null && me.equals(href(wp, "assignee"));
            }
            String who = assignee.trim().replaceFirst("^@", "");
            return wp -> who.equalsIgnoreCase(title(wp, "assignee"));
        }

        // ------------------------------------------------------------------ Suche

        /** Filter der API aus der Anfrage; eine eigene Filterliste (JSON-Array) wird angehängt. */
        ArrayNode filters(TicketQuery q) {
            ArrayNode filters = HttpJson.JSON.createArrayNode();
            switch (q.state()) {
                case OPEN -> filter(filters, "status", "o");
                case CLOSED -> filter(filters, "status", "c");
                default -> { }
            }
            String a = q.assignee();
            if (a != null && !a.isBlank()) {
                if (TicketSystem.isMe(a)) {
                    filter(filters, "assignee", "=", "me");
                } else if (TicketSystem.isNone(a)) {
                    filter(filters, "assignee", "!*");
                } else {
                    filter(filters, "assignee", "=", principalId(a));
                }
            }
            if (!q.labels().isEmpty()) {
                throw new IllegalArgumentException("OpenProject kennt keine Labels – Kategorie, Typ o.ä. über 'query' "
                        + "filtern, z.B. [{\"category\":{\"operator\":\"=\",\"values\":[\"3\"]}}].");
            }
            if (q.text() != null && !q.text().isBlank()) {
                filter(filters, "search", "**", q.text().trim());
            }
            if (q.rawQuery() != null && !q.rawQuery().isBlank()) {
                JsonNode raw;
                try {
                    raw = HttpJson.JSON.readTree(q.rawQuery().trim());
                } catch (RuntimeException e) {
                    raw = null;
                }
                if (raw == null || !(raw.isArray() || raw.isObject())) {
                    throw new IllegalArgumentException("OpenProject: 'query' erwartet Filter als JSON-Array, z.B. "
                            + "[{\"type\":{\"operator\":\"=\",\"values\":[\"1\"]}}]. Freitext gehört in 'text'.");
                }
                if (raw.isArray()) {
                    raw.forEach(filters::add);
                } else {
                    filters.add(raw);
                }
            }
            return filters;
        }

        private static void filter(ArrayNode filters, String name, String operator, String... values) {
            ObjectNode f = filters.addObject().putObject(name);
            f.put("operator", operator);
            ArrayNode v = f.putArray("values");
            for (String s : values) {
                v.add(s);
            }
        }

        /** ID eines Benutzers aus Login, Name oder E-Mail (für Filter). */
        private String principalId(String who) {
            String q = who.trim().replaceFirst("^@", "");
            if (q.matches("\\d+")) {
                return q;
            }
            ArrayNode f = HttpJson.JSON.createArrayNode();
            filter(f, "any_name_attribute", "~", q);
            List<JsonNode> list = new ArrayList<>();
            http().getJson("/principals" + query("filters", HttpJson.JSON.writeValueAsString(f), "pageSize", 20))
                    .path("_embedded").path("elements").forEach(list::add);
            return text(pickUser(list, q).path("id"));
        }

        @Override
        public TicketPage search(TicketQuery q) {
            String filters = HttpJson.JSON.writeValueAsString(filters(q));
            String path = q.project() == null || q.project().isBlank() ? "/work_packages"
                    : "/projects/" + enc(q.project().trim()) + "/work_packages";
            int limit = Math.max(1, Math.min(q.limit(), 100));
            int page = parseCursor(q.cursor());
            // filters immer mitschicken: ohne Parameter liefert die API nur offene Arbeitspakete
            JsonNode res = http().getJson(path + query("filters", filters, "sortBy", SORT, "pageSize", limit, "offset", page));
            List<Ticket> tickets = new ArrayList<>();
            res.path("_embedded").path("elements").forEach(wp -> tickets.add(ticket(wp)));
            int total = res.path("total").asInt(tickets.size());
            String next = !tickets.isEmpty() && (long) page * limit < total ? String.valueOf(page + 1) : null;
            return new TicketPage(tickets, total, next, "GET " + path + "?filters=" + filters);
        }

        private static int parseCursor(String cursor) {
            if (cursor == null || cursor.isBlank()) {
                return 1;
            }
            try {
                return Math.max(1, Integer.parseInt(cursor.trim()));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("OpenProject: ungültiger cursor '" + cursor + "' – den Wert aus der "
                        + "vorigen ticket_search-Ausgabe verwenden.");
            }
        }

        // ------------------------------------------------------------------ Verknüpfungen, Statuswechsel, Schreiben

        /**
         * Nummern sind instanzweit eindeutig und verraten das Projekt nicht – daher wird das Arbeitspaket einmal
         * gelesen (nur lesend, gemerkt). Eine Projekt-URL im Schlüssel zählt nicht, sie ließe sich frei wählen.
         */
        @Override
        public String projectOf(String key, String project) {
            int id = packageId(key);
            return projectOfPackage.computeIfAbsent(id, i -> projectIdentifier(http().getJson(path(i))));
        }

        private String projectIdentifier(JsonNode wp) {
            String identifier = text(wp.path("_embedded").path("project").path("identifier"));
            if (identifier != null) {
                return identifier;
            }
            String href = href(wp, "project");
            return href == null ? null : HttpJson.first(text(http.getJson(href.replaceFirst("^/api/v3", "")).path("identifier")), href);
        }

        @Override
        public String markup() {
            return "Markdown (CommonMark)";
        }

        @Override
        public List<Link> links(String key, String project) {
            int id = packageId(key);
            JsonNode wp = http().getJson(path(id));
            List<Link> out = new ArrayList<>();
            JsonNode parent = wp.path("_links").path("parent");
            if (text(parent.path("href")) != null) {
                out.add(link("Parent", parent));
            }
            wp.path("_links").path("children").forEach(c -> out.add(link("Unteraufgabe", c)));
            for (JsonNode r : http.getJson(path(id) + "/relations").path("_embedded").path("elements")) {
                JsonNode from = r.path("_links").path("from");
                boolean outgoing = String.valueOf(id).equals(lastSegment(text(from.path("href"))));
                out.add(link(relation(text(r.path(outgoing ? "type" : "reverseType"))),
                        outgoing ? r.path("_links").path("to") : from));
            }
            return out;
        }

        private Link link(String relation, JsonNode ref) {
            String other = lastSegment(text(ref.path("href")));
            return new Link(relation, "#" + other, text(ref.path("title")), null, webBase + "/work_packages/" + other);
        }

        static String relation(String type) {
            if (type == null) {
                return "relates to";
            }
            return switch (type) {
                case "relates" -> "relates to";
                case "duplicated" -> "duplicated by";
                case "blocked" -> "blocked by";
                case "partof" -> "part of";
                case "required" -> "required by";
                default -> type;
            };
        }

        /** Statuswerte, die der Workflow für dieses Arbeitspaket erlaubt (Formular-Endpunkt); ID {@code status:<id>}. */
        @Override
        public List<Transition> transitions(String key, String project) {
            int id = packageId(key);
            JsonNode wp = http().getJson(path(id));
            var body = HttpJson.object();
            body.put("lockVersion", wp.path("lockVersion").asInt());
            JsonNode schema = http.post(path(id) + "/form", body).body().path("_embedded").path("schema").path("status");
            List<JsonNode> allowed = new ArrayList<>();
            schema.path("_embedded").path("allowedValues").forEach(allowed::add);
            if (allowed.isEmpty()) {
                schema.path("_links").path("allowedValues").forEach(allowed::add);
            }
            String current = href(wp, "status");
            List<Transition> out = new ArrayList<>();
            for (JsonNode s : allowed) {
                String href = HttpJson.first(href(s, "self"), text(s.path("href")));
                if (href == null || href.equals(current)) {
                    continue;
                }
                String name = HttpJson.first(text(s.path("name")), text(s.path("title")));
                JsonNode full = s.has("isClosed") ? s : status(href);
                out.add(new Transition("status:" + lastSegment(href), "Status → " + name, name, category(name, full),
                        "Workflow"));
            }
            return out;
        }

        @Override
        public WriteResult transition(String key, String project, Transition t) {
            int id = packageId(key);
            if (!t.id().matches("status:\\d+")) {
                throw new IllegalArgumentException("OpenProject: unbekannter Statuswechsel '" + t.id()
                        + "' – ticket_transitions liefert die möglichen.");
            }
            JsonNode res = patch(id, b -> b.putObject("_links").putObject("status")
                    .put("href", "/api/v3/statuses/" + t.id().substring(7)));
            return new WriteResult("#" + id, "Status → " + HttpJson.first(title(res, "status"), t.to()), webUrl(id));
        }

        /** PATCH mit aktueller {@code lockVersion} (optimistische Sperre). */
        private JsonNode patch(int id, Consumer<ObjectNode> fill) {
            JsonNode wp = http().getJson(path(id));
            var body = HttpJson.object();
            body.put("lockVersion", wp.path("lockVersion").asInt());
            fill.accept(body);
            try {
                return http.patch(path(id), body).body();
            } catch (HttpJson.StatusException e) {
                if (e.status() == 409) {
                    throw new IllegalStateException(e.getMessage() + " – das Arbeitspaket wurde gleichzeitig geändert; "
                            + "erneut versuchen.", e);
                }
                if (e.status() == 422) {
                    throw new IllegalArgumentException(e.getMessage(), e);
                }
                throw e;
            }
        }

        @Override
        public WriteResult comment(String key, String project, String body) {
            int id = packageId(key);
            var req = HttpJson.object();
            req.putObject("comment").put("raw", body);
            String cid = text(http().post(path(id) + "/activities", req).body().path("id"));
            return new WriteResult("#" + id, "Kommentar " + Text.orDash(cid) + " hinzugefügt", webUrl(id) + "/activity", cid);
        }

        @Override
        public WriteResult assign(String key, String project, List<String> assignees) {
            int id = packageId(key);
            List<String> who = assignees.stream().filter(a -> !TicketSystem.isNone(a)).toList();
            if (who.size() > 1) {
                throw new IllegalArgumentException("OpenProject: ein Arbeitspaket hat genau einen Zuständigen – nur "
                        + "einen Benutzer angeben.");
            }
            String href = who.isEmpty() ? null : assigneeHref(id, who.getFirst());
            JsonNode res = patch(id, b -> {
                ObjectNode a = b.putObject("_links").putObject("assignee");
                if (href == null) {
                    a.putNull("href");
                } else {
                    a.put("href", href);
                }
            });
            return new WriteResult("#" + id, "zugewiesen an " + HttpJson.first(title(res, "assignee"), "niemand"), webUrl(id));
        }

        /** Unter den für das Arbeitspaket zuweisbaren Benutzern – braucht keine Admin-Rechte. */
        private String assigneeHref(int id, String who) {
            if (TicketSystem.isMe(who)) {
                return href(http().getJson("/users/me"), "self");
            }
            String q = who.trim().replaceFirst("^@", "");
            List<JsonNode> list = new ArrayList<>();
            http().getJson(path(id) + "/available_assignees").path("_embedded").path("elements").forEach(list::add);
            return href(pickUser(list, q), "self");
        }

        private static JsonNode pickUser(List<JsonNode> list, String q) {
            List<JsonNode> exact = list.stream().filter(u -> Stream.of(text(u.path("login")), text(u.path("email")),
                    text(u.path("name"))).anyMatch(v -> v != null && v.equalsIgnoreCase(q))).toList();
            if (exact.size() == 1) {
                return exact.getFirst();
            }
            String lower = q.toLowerCase(Locale.ROOT);
            List<JsonNode> partial = list.stream().filter(u -> Stream.of(text(u.path("login")), text(u.path("name")))
                    .anyMatch(v -> v != null && v.toLowerCase(Locale.ROOT).contains(lower))).toList();
            if (partial.size() == 1) {
                return partial.getFirst();
            }
            if (partial.isEmpty()) {
                throw new IllegalArgumentException("OpenProject: kein (zuweisbarer) Benutzer '" + q + "' (Login, Name oder E-Mail).");
            }
            throw new IllegalArgumentException("OpenProject: '" + q + "' ist mehrdeutig: " + String.join(", ", partial
                    .stream().limit(10).map(u -> text(u.path("name"))).toList()) + " – genauer angeben.");
        }

        @Override
        public WriteResult update(String key, String project, TicketUpdate u) {
            int id = packageId(key);
            if (u.labels() != null) {
                throw new IllegalArgumentException("OpenProject kennt keine Labels – nur Titel und Beschreibung sind änderbar.");
            }
            patch(id, b -> {
                if (u.title() != null) {
                    b.put("subject", u.title());
                }
                if (u.description() != null) {
                    b.putObject("description").put("raw", u.description());
                }
            });
            return new WriteResult("#" + id, "geändert: " + u.summary(), webUrl(id));
        }

        @Override
        public WriteResult create(String project, NewTicket t) {
            if (project == null || project.isBlank()) {
                throw new IllegalArgumentException("OpenProject: 'project' (Projekt-Kennung) angeben oder Standardprojekt setzen.");
            }
            if (!t.labels().isEmpty()) {
                throw new IllegalArgumentException("OpenProject kennt keine Labels – ohne 'labels' anlegen.");
            }
            String p = project.trim();
            var body = HttpJson.object();
            body.put("subject", t.title());
            if (t.description() != null) {
                body.putObject("description").put("raw", t.description());
            }
            if (t.type() != null && !t.type().isBlank()) {
                body.putObject("_links").putObject("type").put("href", typeHref(p, t.type().trim()));
            }
            JsonNode res;
            try {
                res = http().post("/projects/" + enc(p) + "/work_packages", body).body();
            } catch (HttpJson.StatusException e) {
                if (e.status() == 422) {
                    throw new IllegalArgumentException(e.getMessage(), e);
                }
                throw e;
            }
            int id = res.path("id").asInt();
            projectOfPackage.put(id, HttpJson.first(text(res.path("_embedded").path("project").path("identifier")), p));
            String msg = "angelegt (" + HttpJson.first(title(res, "type"), t.type(), "Standardtyp") + ")";
            if (!t.assignees().isEmpty()) {
                // Zuweisung getrennt: zuweisbar ist nur, wer im Projekt Mitglied ist – das prüft available_assignees
                try {
                    msg += ", " + assign("#" + id, p, t.assignees()).message();
                } catch (RuntimeException e) {
                    msg += ", Zuweisung fehlgeschlagen: " + e.getMessage();
                }
            }
            return new WriteResult("#" + id, msg, webUrl(id));
        }

        private String typeHref(String project, String type) {
            List<JsonNode> types = new ArrayList<>();
            http().getJson("/projects/" + enc(project) + "/types").path("_embedded").path("elements").forEach(types::add);
            return types.stream().filter(x -> type.equalsIgnoreCase(text(x.path("name")))).map(x -> href(x, "self"))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("OpenProject: Typ '" + type + "' gibt es "
                            + "in " + project + " nicht. Gültig: " + String.join(", ", types.stream()
                            .map(x -> text(x.path("name"))).toList())));
        }

        @Override
        public String canonicalKey(String key, String project) {
            return "#" + packageId(key);
        }

        @Override
        public String instance() {
            return http == null ? id() : webBase;
        }

        @Override
        public WriteResult delete(String key, String project) {
            int id = packageId(key);
            // OpenProject löscht Kinder mit – das soll ticket_delete nicht
            JsonNode children = http().getJson(path(id)).path("_links").path("children");
            if (children.isArray() && !children.isEmpty()) {
                throw new IllegalArgumentException("OpenProject: #" + id + " hat " + children.size() + " Unteraufgabe(n), "
                        + "die mitgelöscht würden – Unteraufgaben zuerst einzeln löschen oder das Arbeitspaket schließen "
                        + "(ticket_transition).");
            }
            http.delete(path(id));
            projectOfPackage.remove(id);
            return new WriteResult("#" + id, "Arbeitspaket gelöscht", null);
        }

        // ------------------------------------------------------------------ Einzelnes Ticket

        @Override
        public TicketDetails ticket(String key, String project, int maxComments) {
            int id = packageId(key);
            JsonNode wp = http().getJson(path(id));
            String identifier = text(wp.path("_embedded").path("project").path("identifier"));
            if (identifier != null) {
                projectOfPackage.put(id, identifier);
            }
            Map<String, String> extra = new LinkedHashMap<>();
            put(extra, "Projekt", title(wp, "project"));
            JsonNode parent = wp.path("_links").path("parent");
            if (text(parent.path("href")) != null) {
                extra.put("Parent", "#" + lastSegment(text(parent.path("href"))) + " " + HttpJson.first(text(parent.path("title")), ""));
            }
            put(extra, "Verantwortlich", title(wp, "responsible"));
            put(extra, "Kategorie", title(wp, "category"));
            put(extra, "Version", title(wp, "version"));
            put(extra, "Beginn", text(wp.path("startDate")));
            put(extra, "Fällig", text(wp.path("dueDate")));
            put(extra, "Geschätzt", text(wp.path("estimatedTime")));
            put(extra, "Fortschritt", wp.path("percentageDone").isNumber() ? wp.path("percentageDone").asInt() + " %" : null);

            List<Comment> all = new ArrayList<>();
            if (maxComments > 0) {
                // Aktivitäten enthalten auch reine Feldänderungen – nur die mit Kommentar zählen
                for (JsonNode a : http.getJson(path(id) + "/activities").path("_embedded").path("elements")) {
                    String raw = text(a.path("comment").path("raw"));
                    if (raw != null) {
                        all.add(new Comment(text(a.path("id")), title(a, "user"), text(a.path("createdAt")), raw));
                    }
                }
            }
            return new TicketDetails(ticket(wp), title(wp, "author"), text(wp.path("createdAt")),
                    text(wp.path("description").path("raw")), extra,
                    all.subList(Math.max(0, all.size() - maxComments), all.size()), all.size());
        }

        private static void put(Map<String, String> m, String k, String v) {
            if (v != null && !v.isBlank()) {
                m.put(k, v.strip());
            }
        }

        private Ticket ticket(JsonNode wp) {
            int id = wp.path("id").asInt();
            String assignee = title(wp, "assignee");
            String status = title(wp, "status");
            JsonNode embedded = wp.path("_embedded").path("status");
            return new Ticket("#" + id, text(wp.path("subject")), status,
                    category(status, embedded.has("isClosed") ? embedded : status(href(wp, "status"))),
                    title(wp, "type"), title(wp, "priority"), assignee == null ? List.of() : List.of(assignee), List.of(),
                    text(wp.path("updatedAt")), webUrl(id));
        }

        /** Status zu einem href aus {@code /statuses} (einmal geladen) oder {@code null}. */
        private JsonNode status(String href) {
            Map<String, JsonNode> s = statuses;
            if (s == null && http != null && href != null) {
                try {
                    Map<String, JsonNode> loaded = new HashMap<>();
                    http.getJson("/statuses").path("_embedded").path("elements").forEach(st -> loaded.put(href(st, "self"), st));
                    statuses = s = loaded;
                } catch (RuntimeException e) {
                    return null; // Kategorie dann aus dem Namen
                }
            }
            return s == null || href == null ? null : s.get(href);
        }

        static StatusCategory category(String name, JsonNode status) {
            if (status != null && status.path("isClosed").asBoolean(false)) {
                return StatusCategory.DONE;
            }
            if (name == null) {
                return StatusCategory.UNKNOWN;
            }
            String n = name.toLowerCase(Locale.ROOT);
            return IN_PROGRESS.stream().anyMatch(n::contains) ? StatusCategory.IN_PROGRESS : StatusCategory.TODO;
        }

        private static String href(JsonNode resource, String rel) {
            return text(resource.path("_links").path(rel).path("href"));
        }

        private static String title(JsonNode resource, String rel) {
            return text(resource.path("_links").path(rel).path("title"));
        }

        private static String lastSegment(String href) {
            return href == null ? null : href.substring(href.lastIndexOf('/') + 1);
        }
    }
}
