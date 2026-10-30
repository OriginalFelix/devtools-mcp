package systems.grebe.devtools.mcp.modules.sentry;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.Text;
import systems.grebe.devtools.mcp.modules.ticket.spi.HttpJson;
import tools.jackson.databind.JsonNode;

import static systems.grebe.devtools.mcp.modules.ticket.spi.HttpJson.enc;
import static systems.grebe.devtools.mcp.modules.ticket.spi.HttpJson.query;

/** Sentry-Tools. Ausgaben sind bewusst kompakt gehalten. */
public class SentryTools {

    private static final Pattern NEXT_CURSOR = Pattern.compile("rel=\"next\"; results=\"true\"; cursor=\"([^\"]+)\"");
    private static final int MAX_FRAMES = 40;

    private final Supplier<HttpJson> client;
    private final String org;
    private final String defaultProject;

    SentryTools(Supplier<HttpJson> client, String org, String defaultProject) {
        this.client = client;
        this.org = org;
        this.defaultProject = defaultProject;
    }

    @Tool(name = "list_projects", description = "Projekte der Sentry-Organisation (Slug, Name, Plattform)."
            + " Statt der Web-API `/api/0/organizations/…/projects/` verwenden." + ShellHints.SENTRY)
    public String listProjects() {
        List<String> lines = new ArrayList<>();
        for (JsonNode p : client.get().getJson("/organizations/" + enc(org) + "/projects/" + query("per_page", 100))) {
            lines.add(p.path("slug").asString() + "  –  " + p.path("name").asString()
                    + "  (" + p.path("platform").asString("-") + ")");
        }
        return lines.isEmpty() ? "(keine Projekte gefunden)" : String.join("\n", lines);
    }

    @Tool(name = "issues", description = "Sucht Fehler (Issues) in Sentry, standardmäßig offene. Liefert Short-ID, "
            + "Level, Anzahl Events/Nutzer, zuletzt gesehen, Titel und Ort."
            + " Statt der Web-API `/api/0/organizations/…/issues/` verwenden." + ShellHints.SENTRY)
    public String issues(
            @ToolParam(required = false, description = "Projekt-Slug; leer = Standardprojekt bzw. alle") String project,
            @ToolParam(required = false, description = "Sentry-Suche, Standard `is:unresolved`, z.B. "
                    + "`is:unresolved level:error release:1.2.0 NullPointerException`") String search,
            @ToolParam(required = false, description = "Sortierung: date (zuletzt gesehen, Standard), new, freq, user") String sort,
            @ToolParam(required = false, description = "Zeitraum, z.B. 24h, 14d (Standard), 90d") String period,
            @ToolParam(required = false, description = "Anzahl (Standard 25, max. 100)") Integer limit,
            @ToolParam(required = false, description = "Cursor für die nächste Seite (aus dem vorigen Ergebnis)") String cursor) {
        String slug = project == null || project.isBlank() ? defaultProject : project.trim();
        HttpJson http = client.get();
        String projectId = slug == null || slug.isBlank() ? null
                : http.getJson("/projects/" + enc(org) + "/" + enc(slug) + "/").path("id").asString();
        HttpJson.Response res = http.get("/organizations/" + enc(org) + "/issues/" + query(
                "project", projectId,
                "query", search == null || search.isBlank() ? "is:unresolved" : search,
                "sort", sort == null || sort.isBlank() ? "date" : sort,
                "statsPeriod", period,
                "limit", limit == null ? 25 : Math.clamp(limit, 1, 100),
                "cursor", cursor));
        List<String> lines = new ArrayList<>();
        for (JsonNode i : res.body()) {
            lines.add(i.path("shortId").asString() + "  " + pad(i.path("level").asString(), 7)
                    + " " + i.path("count").asString("0") + "×/" + i.path("userCount").asString("0") + " Nutzer"
                    + "  zuletzt " + i.path("lastSeen").asString("-")
                    + (projectId == null ? "  [" + i.path("project").path("slug").asString() + "]" : "")
                    + "  " + i.path("title").asString() + "  –  " + i.path("culprit").asString(""));
        }
        String out = lines.isEmpty() ? "(keine Issues gefunden)" : String.join("\n", lines);
        String next = nextCursor(res.header("Link"));
        return next == null ? out : out + "\nWeitere Treffer: cursor=" + next;
    }

    @Tool(name = "issue_detail", description = "Details zu einem Sentry-Issue mit dem letzten Event: Ausnahme(n) samt "
            + "Stacktrace (eigener Code markiert, mit Quelltextzeile), Request, Tags (Release, Umgebung …) und die "
            + "letzten Breadcrumbs. Statt der Web-API `/api/0/organizations/…/issues/…/events/latest/` verwenden."
            + ShellHints.SENTRY)
    public String issueDetail(@ToolParam(description = "Short-ID (z.B. BACKEND-1A) oder numerische ID aus sentry_issues") String issue) {
        String path = "/organizations/" + enc(org) + "/issues/" + enc(issue.trim()) + "/";
        HttpJson http = client.get();
        JsonNode i = http.getJson(path);
        StringBuilder sb = new StringBuilder();
        sb.append(i.path("shortId").asString()).append(" (ID ").append(i.path("id").asString()).append(") – ")
                .append(i.path("title").asString()).append('\n');
        sb.append("Ort:      ").append(i.path("culprit").asString("-")).append('\n');
        sb.append("Level:    ").append(i.path("level").asString("-"))
                .append(", Status ").append(i.path("status").asString("-")).append('\n');
        sb.append("Umfang:   ").append(i.path("count").asString("0")).append(" Events, ")
                .append(i.path("userCount").asString("0")).append(" Nutzer; erstmals ")
                .append(i.path("firstSeen").asString("-")).append(", zuletzt ").append(i.path("lastSeen").asString("-")).append('\n');
        sb.append("Link:     ").append(i.path("permalink").asString("-")).append('\n');
        JsonNode e = http.getJson(path + "events/latest/");
        sb.append("\nLetztes Event ").append(e.path("eventID").asString()).append(" vom ")
                .append(e.path("dateCreated").asString("-")).append('\n');
        appendEvent(sb, e);
        return Text.limitLines(sb.toString().trim(), 400);
    }

    static void appendEvent(StringBuilder sb, JsonNode event) {
        for (JsonNode entry : event.path("entries")) {
            JsonNode data = entry.path("data");
            switch (entry.path("type").asString()) {
                case "message" -> sb.append("Meldung: ").append(data.path("formatted").asString()).append('\n');
                case "exception" -> {
                    // Sentry speichert die Kette von der Ursache zur gefangenen Ausnahme, Frames vom Aufrufer zum Fehler
                    List<JsonNode> values = new ArrayList<>();
                    data.path("values").forEach(values::add);
                    for (int v = values.size() - 1; v >= 0; v--) {
                        JsonNode ex = values.get(v);
                        sb.append(v == values.size() - 1 ? "Ausnahme: " : "Verursacht durch: ")
                                .append(ex.path("module").isString() ? ex.path("module").asString() + "." : "")
                                .append(ex.path("type").asString()).append(": ").append(ex.path("value").asString("")).append('\n');
                        appendFrames(sb, ex.path("stacktrace").path("frames"));
                    }
                }
                case "request" -> sb.append("Request: ").append(data.path("method").asString("")).append(' ')
                        .append(data.path("url").asString("")).append('\n');
                case "breadcrumbs" -> {
                    JsonNode crumbs = data.path("values");
                    if (!crumbs.isEmpty()) {
                        sb.append("Breadcrumbs (letzte 10):\n");
                        for (int c = Math.max(0, crumbs.size() - 10); c < crumbs.size(); c++) {
                            JsonNode b = crumbs.get(c);
                            sb.append("  ").append(b.path("timestamp").asString("")).append(' ')
                                    .append(b.path("category").asString("-")).append(' ')
                                    .append(b.path("level").asString("")).append("  ")
                                    .append(b.path("message").asString("")).append('\n');
                        }
                    }
                }
                default -> {
                    // Debug-Images, Threads u.ä. helfen beim Beheben selten
                }
            }
        }
        List<String> tags = new ArrayList<>();
        event.path("tags").forEach(t -> tags.add(t.path("key").asString() + "=" + t.path("value").asString()));
        if (!tags.isEmpty()) {
            sb.append("Tags: ").append(String.join(", ", tags)).append('\n');
        }
    }

    private static void appendFrames(StringBuilder sb, JsonNode frames) {
        int shown = 0;
        for (int f = frames.size() - 1; f >= 0 && shown < MAX_FRAMES; f--, shown++) {
            JsonNode fr = frames.get(f);
            boolean inApp = fr.path("inApp").asBoolean(false);
            int line = fr.path("lineNo").asInt(-1);
            sb.append(inApp ? "  * at " : "    at ")
                    .append(fr.path("module").isString() ? fr.path("module").asString() + "." : "")
                    .append(fr.path("function").asString("?")).append(" (")
                    .append(fr.path("filename").asString(fr.path("absPath").asString("?")))
                    .append(line < 0 ? "" : ":" + line).append(")\n");
            if (inApp) {
                for (JsonNode ctx : fr.path("context")) {
                    if (ctx.path(0).asInt() == line) {
                        sb.append("        > ").append(ctx.path(1).asString("").strip()).append('\n');
                    }
                }
            }
        }
        if (frames.size() > MAX_FRAMES) {
            sb.append("    … ").append(frames.size() - MAX_FRAMES).append(" weitere Frames\n");
        }
    }

    static String nextCursor(String link) {
        Matcher m = NEXT_CURSOR.matcher(link == null ? "" : link);
        return m.find() ? m.group(1) : null;
    }

    private static String pad(String s, int width) {
        return String.format("%-" + width + "s", s == null ? "" : s);
    }
}
