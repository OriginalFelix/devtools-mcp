package systems.grebe.devtools.mcp.core;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Kennzeichnung von Texten, die Tools im Namen des Nutzers veröffentlichen (Kommentare, Antworten, Beschreibungen von
 * Tickets und Pull Requests): eine frei konfigurierbare Zeile unter dem Text, z.B. {@value #DEFAULT_TEMPLATE}.
 * {@value #MODEL} wird durch den Namen des KI-Modells ersetzt.
 *
 * <p>Die Vorlage kommt aus dem Modul „Kennzeichnung“; Module mit {@link ToolModule#signsTexts()} finden sie in ihrer
 * Konfiguration unter {@link #CONFIG_KEY} (leer = keine Kennzeichnung).
 */
public final class Signature {

    /** Platzhalter für den Namen des KI-Modells. */
    public static final String MODEL = "{AI_MODEL_NAME}";
    public static final String DEFAULT_TEMPLATE = "🤖 Generated with " + MODEL;
    /** Schlüssel, unter dem die wirksame Vorlage in der Konfiguration signierender Module steht. */
    public static final String CONFIG_KEY = "@signature";
    /** Name, wenn weder das Modell noch der Client bekannt ist. */
    public static final String UNKNOWN_MODEL = "AI";

    /** Beschreibung des Tool-Parameters, über den das LLM seinen Modellnamen mitteilt. */
    public static final String MODEL_PARAM = "Dein eigener Modellname mit Version, z.B. „Claude Sonnet 4.5“ – für die "
            + "Kennzeichnung, die der Server unter den Text setzt. Die Kennzeichnung nicht selbst in den Text schreiben.";

    private Signature() {
    }

    /** Wirksame Vorlage signierender Module (leer = keine Kennzeichnung). */
    public static String template(ModuleConfig config) {
        return config.getString(CONFIG_KEY, "");
    }

    /** Vorlage mit eingesetztem Modellnamen (blank = {@link #UNKNOWN_MODEL}). */
    public static String render(String template, String model) {
        String name = model == null || model.isBlank() ? UNKNOWN_MODEL : model.strip();
        return template.strip().replace(MODEL, name);
    }

    /**
     * Hängt die Kennzeichnung mit Leerzeile an den Text an. Steht schon eine Kennzeichnung nach derselben Vorlage am
     * Ende (z.B. aus einer übernommenen Beschreibung oder vom LLM selbst geschrieben, ggf. mit anderem Modellnamen),
     * wird sie ersetzt statt verdoppelt. Leere Vorlage: Text unverändert (ohne Rand-Leerraum).
     */
    public static String sign(String text, String template, String model) {
        String body = text == null ? "" : text.strip();
        if (template == null || template.isBlank()) {
            return body;
        }
        String line = render(template, model);
        body = existing(template).matcher(body).replaceFirst("").strip();
        return body.isEmpty() ? line : body + "\n\n" + line;
    }

    /** Eine Kennzeichnung nach der Vorlage am Textende – mit beliebigem Modellnamen. */
    private static Pattern existing(String template) {
        String t = template.strip();
        int i = t.indexOf(MODEL);
        String regex = i < 0 ? Pattern.quote(t)
                : Pattern.quote(t.substring(0, i)) + "[^\\n]*?" + Pattern.quote(t.substring(i + MODEL.length()));
        return Pattern.compile("(?:^|\\n)\\s*" + regex + "\\s*$");
    }

    /** Lesbarer Name aus der Kennung eines MCP-Clients, z.B. {@code claude-code} → „Claude Code“. */
    public static String clientName(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            return null;
        }
        String id = clientId.strip();
        if (id.equalsIgnoreCase("claude-ai")) {
            return "Claude";
        }
        if (!id.matches("[a-z0-9]+([-_][a-z0-9]+)*")) {
            return id; // schon ein Anzeigename wie „Visual Studio Code“
        }
        StringBuilder sb = new StringBuilder();
        for (String part : id.split("[-_]")) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(part.substring(0, 1).toUpperCase(Locale.ROOT)).append(part.substring(1));
        }
        return sb.toString();
    }
}
