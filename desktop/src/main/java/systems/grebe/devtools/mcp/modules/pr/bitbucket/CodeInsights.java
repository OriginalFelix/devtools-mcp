package systems.grebe.devtools.mcp.modules.pr.bitbucket;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import systems.grebe.devtools.mcp.modules.ticket.spi.HttpJson;
import tools.jackson.databind.JsonNode;

import static systems.grebe.devtools.mcp.modules.ticket.spi.HttpJson.text;

/** Gemeinsames für Code Insights (Berichte von Integrationen) in Bitbucket Cloud und Data Center. */
final class CodeInsights {

    private CodeInsights() {
    }

    /** Kennzahlen eines Berichts ({@code data}: {@code title}, {@code type}, {@code value}) in Anzeigereihenfolge. */
    static Map<String, String> data(JsonNode data) {
        Map<String, String> out = new LinkedHashMap<>();
        for (JsonNode d : data) {
            String title = text(d.path("title"));
            if (title != null) {
                out.put(title, value(text(d.path("type")), d.path("value")));
            }
        }
        return out;
    }

    static String value(String type, JsonNode v) {
        if (type == null) {
            return text(v);
        }
        return switch (type) {
            case "PERCENTAGE" -> text(v) == null ? null : text(v) + " %";
            case "DATE" -> v.isNumber() ? Instant.ofEpochMilli(v.asLong()).toString() : text(v);
            case "DURATION" -> v.isNumber() ? Duration.ofMillis(v.asLong()).toString().substring(2).toLowerCase(Locale.ROOT) : text(v);
            case "BOOLEAN" -> v.isBoolean() ? (v.asBoolean() ? "ja" : "nein") : text(v);
            // Data Center: linktext, Cloud: text
            case "LINK" -> v.isObject() ? HttpJson.first(text(v.path("linktext")), text(v.path("text")), "Link")
                    + (text(v.path("href")) == null ? "" : " " + text(v.path("href"))) : text(v);
            default -> text(v);
        };
    }

    /** Konto einer App bzw. eines Dienstkontos statt einer Person (Cloud: {@code app_user}, DC: {@code SERVICE}). */
    static boolean integration(JsonNode user) {
        String type = text(user.path("type"));
        return "app_user".equals(type) || "SERVICE".equals(type);
    }
}
