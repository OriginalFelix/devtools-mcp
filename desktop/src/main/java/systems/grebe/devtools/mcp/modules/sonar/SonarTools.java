package systems.grebe.devtools.mcp.modules.sonar;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.Text;
import tools.jackson.databind.JsonNode;

import static systems.grebe.devtools.mcp.modules.sonar.SonarClient.params;

/** SonarQube-Tools. Ausgaben sind bewusst kompakt gehalten. */
public class SonarTools {

    private static final String PROJECT_PARAM = "Projektschlüssel; leer = Standardprojekt";
    private static final String BRANCH_PARAM = "Branch-Name (optional)";
    private static final String PR_PARAM = "Pull-Request-ID (optional, statt Branch)";
    private static final String DEFAULT_METRICS = "alert_status,bugs,vulnerabilities,code_smells,security_hotspots,"
            + "coverage,duplicated_lines_density,ncloc,sqale_index,reliability_rating,security_rating,sqale_rating,"
            + "new_bugs,new_vulnerabilities,new_code_smells,new_coverage,new_duplicated_lines_density";

    private final Supplier<SonarClient> client;
    private final String defaultProject;

    SonarTools(Supplier<SonarClient> client, String defaultProject) {
        this.client = client;
        this.defaultProject = defaultProject;
    }

    private String project(String key) {
        if (key != null && !key.isBlank()) {
            return key.trim();
        }
        if (defaultProject == null || defaultProject.isBlank()) {
            throw new IllegalArgumentException("Kein Projektschlüssel angegeben und kein Standardprojekt konfiguriert. "
                    + "Verfügbare Projekte liefert sonar_list_projects.");
        }
        return defaultProject;
    }

    @Tool(name = "list_projects", description = "Sucht SonarQube-Projekte (Schlüssel, Name, letzte Analyse)."
            + " Statt der Web-API `/api/projects/search` verwenden." + ShellHints.SONAR)
    public String listProjects(
            @ToolParam(required = false, description = "Suchtext im Namen oder Schlüssel") String query,
            @ToolParam(required = false, description = "Seite (1-basiert)") Integer page) {
        JsonNode res = client.get().get("/api/components/search",
                params("qualifiers", "TRK", "q", query, "ps", 100, "p", page == null ? 1 : page));
        List<String> lines = new ArrayList<>();
        for (JsonNode c : res.path("components")) {
            lines.add(c.path("key").asString() + "  –  " + c.path("name").asString());
        }
        return paging(res) + (lines.isEmpty() ? "(keine Projekte gefunden)" : String.join("\n", lines));
    }

    @Tool(name = "quality_gate", description = "Status des Quality Gates eines Projekts inkl. aller Bedingungen (Metrik, Schwelle, Istwert)."
            + " Statt der Web-API `/api/qualitygates/project_status` verwenden." + ShellHints.SONAR)
    public String qualityGate(
            @ToolParam(required = false, description = PROJECT_PARAM) String projectKey,
            @ToolParam(required = false, description = BRANCH_PARAM) String branch,
            @ToolParam(required = false, description = PR_PARAM) String pullRequest) {
        String project = project(projectKey);
        JsonNode ps = client.get().get("/api/qualitygates/project_status",
                params("projectKey", project, "branch", branch, "pullRequest", pullRequest)).path("projectStatus");
        StringBuilder sb = new StringBuilder("Quality Gate ").append(project);
        if (branch != null && !branch.isBlank()) {
            sb.append(" [").append(branch).append(']');
        }
        sb.append(": ").append(ps.path("status").asString("NONE")).append('\n');
        for (JsonNode c : ps.path("conditions")) {
            sb.append("  ").append(pad(c.path("status").asString(), 5)).append(' ')
                    .append(c.path("metricKey").asString()).append(' ')
                    .append(c.path("comparator").asString()).append(' ')
                    .append(c.path("errorThreshold").asString())
                    .append("  (Ist: ").append(c.path("actualValue").asString("-")).append(")\n");
        }
        return sb.toString().trim();
    }

    @Tool(name = "issues", description = "Sucht offene Issues eines Projekts (Bugs, Vulnerabilities, Code Smells). "
            + "Filterbar nach Schweregrad, Typ und Datei. Liefert Schlüssel, Regel, Datei:Zeile und Meldung."
            + " Statt der Web-API `/api/issues/search` verwenden." + ShellHints.SONAR)
    public String issues(
            @ToolParam(required = false, description = PROJECT_PARAM) String projectKey,
            @ToolParam(required = false, description = BRANCH_PARAM) String branch,
            @ToolParam(required = false, description = PR_PARAM) String pullRequest,
            @ToolParam(required = false, description = "Kommagetrennt: BLOCKER,CRITICAL,MAJOR,MINOR,INFO") String severities,
            @ToolParam(required = false, description = "Kommagetrennt: BUG,VULNERABILITY,CODE_SMELL") String types,
            @ToolParam(required = false, description = "Relativer Dateipfad im Projekt, z.B. src/main/java/Foo.java") String file,
            @ToolParam(required = false, description = "Nur Issues im neuen Code (seit Leak-Periode)") Boolean newCodeOnly,
            @ToolParam(required = false, description = "Auch gelöste/geschlossene Issues (Standard false)") Boolean includeResolved,
            @ToolParam(required = false, description = "Seite (1-basiert, 100 pro Seite)") Integer page) {
        String project = project(projectKey);
        String component = file == null || file.isBlank() ? project : project + ":" + file.trim().replace('\\', '/');
        JsonNode res = client.get().get("/api/issues/search", params(
                // 'components' ist seit 10.2 der Name, in älteren Versionen der (weiterhin akzeptierte) Alias
                "components", component,
                "branch", branch, "pullRequest", pullRequest,
                "severities", upper(severities), "types", upper(types),
                "resolved", Boolean.TRUE.equals(includeResolved) ? null : "false",
                "inNewCodePeriod", Boolean.TRUE.equals(newCodeOnly) ? "true" : null,
                "s", "SEVERITY", "asc", "false",
                "ps", 100, "p", page == null ? 1 : page));
        List<String> lines = new ArrayList<>();
        for (JsonNode i : res.path("issues")) {
            lines.add(formatIssue(i, project));
        }
        return paging(res) + (lines.isEmpty() ? "(keine Issues gefunden)" : String.join("\n", lines));
    }

    @Tool(name = "issue_detail", description = "Details zu einem Issue: Regel, Schweregrad, Position, Aufwand, Tags, Kommentare und den betroffenen Quelltextausschnitt."
            + " Statt der Web-API `/api/issues/search?issues=…` verwenden." + ShellHints.SONAR)
    public String issueDetail(@ToolParam(description = "Issue-Schlüssel (aus sonar_issues)") String issueKey) {
        JsonNode res = client.get().get("/api/issues/search", params("issues", issueKey, "additionalFields", "_all"));
        JsonNode issues = res.path("issues");
        if (!issues.isArray() || issues.isEmpty()) {
            return "Issue nicht gefunden: " + issueKey;
        }
        JsonNode i = issues.get(0);
        StringBuilder sb = new StringBuilder();
        sb.append("Issue:    ").append(i.path("key").asString()).append('\n');
        sb.append("Regel:    ").append(i.path("rule").asString()).append('\n');
        sb.append("Typ:      ").append(i.path("type").asString()).append(" / ").append(i.path("severity").asString()).append('\n');
        JsonNode impacts = i.path("impacts");
        if (impacts.isArray() && !impacts.isEmpty()) {
            sb.append("Impacts:  ");
            impacts.forEach(im -> sb.append(im.path("softwareQuality").asString()).append('=')
                    .append(im.path("severity").asString()).append(' '));
            sb.append('\n');
        }
        sb.append("Status:   ").append(i.path("status").asString()).append(' ').append(i.path("resolution").asString("")).append('\n');
        sb.append("Datei:    ").append(i.path("component").asString()).append(':').append(i.path("line").asString("-")).append('\n');
        sb.append("Aufwand:  ").append(i.path("effort").asString("-")).append('\n');
        sb.append("Erstellt: ").append(i.path("creationDate").asString("-")).append('\n');
        if (!i.path("tags").isEmpty()) {
            sb.append("Tags:     ").append(i.path("tags").toString()).append('\n');
        }
        sb.append("Meldung:  ").append(i.path("message").asString()).append('\n');
        for (JsonNode c : i.path("comments")) {
            sb.append("Kommentar (").append(c.path("login").asString()).append("): ")
                    .append(c.path("markdown").asString(c.path("htmlText").asString())).append('\n');
        }
        JsonNode range = i.path("textRange");
        if (range.isObject()) {
            int start = range.path("startLine").asInt(1);
            int end = range.path("endLine").asInt(start);
            try {
                sb.append("\nQuelltext:\n").append(sourceLines(i.path("component").asString(), start - 3, end + 3, null));
            } catch (RuntimeException e) {
                sb.append("\n(Quelltext nicht abrufbar: ").append(e.getMessage()).append(')');
            }
        }
        return sb.toString().trim();
    }

    @Tool(name = "rule", description = "Beschreibung einer Sonar-Regel (warum problematisch, wie beheben)."
            + " Statt der Web-API `/api/rules/show` verwenden." + ShellHints.SONAR)
    public String rule(@ToolParam(description = "Regelschlüssel, z.B. java:S1192") String ruleKey) {
        JsonNode r = client.get().get("/api/rules/show", params("key", ruleKey)).path("rule");
        StringBuilder sb = new StringBuilder();
        sb.append(r.path("key").asString()).append(" – ").append(r.path("name").asString()).append('\n');
        sb.append("Sprache: ").append(r.path("langName").asString("-"))
                .append(", Typ: ").append(r.path("type").asString("-"))
                .append(", Schweregrad: ").append(r.path("severity").asString("-")).append("\n\n");
        JsonNode sections = r.path("descriptionSections");
        if (sections.isArray() && !sections.isEmpty()) {
            for (JsonNode s : sections) {
                sb.append("## ").append(s.path("key").asString()).append('\n')
                        .append(stripHtml(s.path("content").asString())).append("\n\n");
            }
        } else {
            sb.append(stripHtml(r.path("htmlDesc").asString(r.path("mdDesc").asString(""))));
        }
        return Text.limitLines(sb.toString().trim(), 300);
    }

    @Tool(name = "measures", description = "Kennzahlen eines Projekts (Coverage, Duplikate, Bugs, technische Schuld, Ratings, New-Code-Werte)."
            + " Statt der Web-API `/api/measures/component` verwenden." + ShellHints.SONAR)
    public String measures(
            @ToolParam(required = false, description = PROJECT_PARAM) String projectKey,
            @ToolParam(required = false, description = BRANCH_PARAM) String branch,
            @ToolParam(required = false, description = PR_PARAM) String pullRequest,
            @ToolParam(required = false, description = "Kommagetrennte Metrikschlüssel; leer = gängige Auswahl") String metrics) {
        String project = project(projectKey);
        JsonNode comp = client.get().get("/api/measures/component", params("component", project, "branch", branch,
                "pullRequest", pullRequest, "metricKeys", metrics == null || metrics.isBlank() ? DEFAULT_METRICS : metrics))
                .path("component");
        StringBuilder sb = new StringBuilder("Metriken ").append(project).append(":\n");
        for (JsonNode m : comp.path("measures")) {
            String value = m.has("value") ? m.path("value").asString()
                    : m.path("period").path("value").asString(m.path("periods").path(0).path("value").asString("-"));
            sb.append("  ").append(pad(m.path("metric").asString(), 30)).append(value).append('\n');
        }
        return sb.toString().trim();
    }

    @Tool(name = "hotspots", description = "Security Hotspots eines Projekts (standardmäßig nur 'zu prüfen')."
            + " Statt der Web-API `/api/hotspots/search` verwenden." + ShellHints.SONAR)
    public String hotspots(
            @ToolParam(required = false, description = PROJECT_PARAM) String projectKey,
            @ToolParam(required = false, description = BRANCH_PARAM) String branch,
            @ToolParam(required = false, description = PR_PARAM) String pullRequest,
            @ToolParam(required = false, description = "TO_REVIEW (Standard) oder REVIEWED") String status,
            @ToolParam(required = false, description = "Seite (1-basiert)") Integer page) {
        String project = project(projectKey);
        JsonNode res = client.get().get("/api/hotspots/search", params("project", project, "branch", branch,
                "pullRequest", pullRequest, "status", status == null || status.isBlank() ? "TO_REVIEW" : upper(status),
                "ps", 100, "p", page == null ? 1 : page));
        List<String> lines = new ArrayList<>();
        for (JsonNode h : res.path("hotspots")) {
            lines.add(h.path("key").asString() + "  " + pad(h.path("vulnerabilityProbability").asString(), 6) + " "
                    + h.path("securityCategory").asString() + "  " + relative(h.path("component").asString(), project)
                    + ":" + h.path("line").asString("-") + "  " + h.path("message").asString());
        }
        return paging(res) + (lines.isEmpty() ? "(keine Hotspots)" : String.join("\n", lines));
    }

    @Tool(name = "source", description = "Quelltextzeilen einer Datei, wie sie SonarQube analysiert hat."
            + " Statt der Web-API `/api/sources/lines` verwenden." + ShellHints.SONAR)
    public String source(
            @ToolParam(required = false, description = PROJECT_PARAM) String projectKey,
            @ToolParam(description = "Relativer Dateipfad im Projekt") String file,
            @ToolParam(required = false, description = "Erste Zeile (Standard 1)") Integer from,
            @ToolParam(required = false, description = "Letzte Zeile (Standard from+100)") Integer to,
            @ToolParam(required = false, description = BRANCH_PARAM) String branch) {
        String component = project(projectKey) + ":" + file.trim().replace('\\', '/');
        int start = from == null ? 1 : Math.max(1, from);
        int end = to == null ? start + 100 : Math.max(start, to);
        return sourceLines(component, start, end, branch);
    }

    private String sourceLines(String component, int from, int to, String branch) {
        String raw = client.get().getRaw("/api/sources/raw", params("key", component, "branch", branch));
        String[] lines = raw.split("\\R", -1);
        int start = Math.max(1, from);
        int end = Math.min(lines.length, to);
        StringBuilder sb = new StringBuilder();
        for (int n = start; n <= end; n++) {
            sb.append(String.format("%5d | %s%n", n, lines[n - 1]));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ Formatierung

    private static String formatIssue(JsonNode i, String project) {
        String severity = i.path("severity").asString("");
        if (severity.isEmpty() && i.path("impacts").isArray() && !i.path("impacts").isEmpty()) {
            severity = i.path("impacts").get(0).path("severity").asString("");
        }
        return i.path("key").asString() + "  " + pad(severity, 8) + " " + pad(i.path("type").asString(""), 13)
                + " " + i.path("rule").asString() + "  " + relative(i.path("component").asString(), project)
                + ":" + i.path("line").asString("-") + "  " + i.path("message").asString();
    }

    private static String paging(JsonNode res) {
        JsonNode p = res.path("paging");
        int total = p.path("total").asInt(res.path("total").asInt(-1));
        if (total < 0) {
            return "";
        }
        int index = p.path("pageIndex").asInt(res.path("p").asInt(1));
        int size = p.path("pageSize").asInt(res.path("ps").asInt(100));
        int pages = size <= 0 ? 1 : (int) Math.ceil(total / (double) size);
        return "Treffer: " + total + " (Seite " + index + " von " + Math.max(1, pages) + ")\n";
    }

    private static String relative(String component, String project) {
        return component.startsWith(project + ":") ? component.substring(project.length() + 1) : component;
    }

    private static String upper(String s) {
        return s == null ? null : s.replace(" ", "").toUpperCase();
    }

    private static String pad(String s, int width) {
        return String.format("%-" + width + "s", s == null ? "" : s);
    }

    static String stripHtml(String html) {
        if (html == null) {
            return "";
        }
        return html.replaceAll("(?i)<br\\s*/?>|</p>|</li>|</h\\d>", "\n")
                .replaceAll("(?i)<li>", "- ")
                .replaceAll("<[^>]+>", "")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&amp;", "&")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
    }
}
