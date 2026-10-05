package systems.grebe.devtools.mcp.modules.ticket;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.modules.ticket.spi.TicketSystem;

import static systems.grebe.devtools.mcp.modules.ticket.TicketTools.PROJECT;
import static systems.grebe.devtools.mcp.modules.ticket.TicketTools.PROVIDER;

/** Zeiten buchen (Schalter {@code allowLogTime}). */
public class TicketTimeTools {

    private static final Pattern PART = Pattern.compile(
            "(\\d+(?:[.,]\\d+)?)\\s*(stunden|stunde|std|hours|hour|hrs|hr|h|minuten|minute|minutes|mins|min|m)(?![a-zäöü])");
    private static final Pattern CLOCK = Pattern.compile("(\\d+):([0-5]\\d)");
    private static final Pattern DAYS = Pattern.compile("\\d+(?:[.,]\\d+)?\\s*(d|tage?|days?|w|wochen?|weeks?)(?![a-zäöü])");
    private static final DateTimeFormatter GERMAN_DATE = DateTimeFormatter.ofPattern("d.M.uuuu");

    private final TicketEnvironment env;

    TicketTimeTools(TicketEnvironment env) {
        this.env = env;
    }

    @Tool(name = "log_time", description = "Bucht Arbeitszeit auf ein Ticket: Jira-Worklog, GitLab-Zeiterfassung, "
            + "YouTrack-Arbeitselement, OpenProject-Zeiteintrag (GitHub kennt keine Zeiterfassung). Dauer nicht schätzen, "
            + "sondern so buchen, wie der Nutzer sie nennt; vorher mit ticket_worklogs prüfen, ob schon gebucht ist. Nur "
            + "auf ausdrückliche Anweisung des Nutzers." + ShellHints.TICKET)
    public String logTime(
            @ToolParam(description = "Ticket-Schlüssel oder URL") String key,
            @ToolParam(description = "Dauer, z.B. 1h 30m, 45m, 1,5h oder 1:30 (keine Tage – die rechnet jedes System anders)") String duration,
            @ToolParam(required = false, description = "Tag der Arbeit: yyyy-MM-dd, dd.MM.yyyy, heute oder gestern. Leer = heute.") String date,
            @ToolParam(required = false, description = "Beschreibung der Tätigkeit") String comment,
            @ToolParam(required = false, description = "Tätigkeitsart: YouTrack Work Item Type (z.B. Development), "
                    + "OpenProject-Aktivität (z.B. Entwicklung). Leer = Standard. Jira/GitLab kennen keine.") String activity,
            @ToolParam(required = false, description = PROJECT) String project,
            @ToolParam(required = false, description = PROVIDER) String provider) {
        TicketSystem.WorkLog work = new TicketSystem.WorkLog(parseDuration(duration), parseDate(date, LocalDate.now()),
                blankToNull(comment), blankToNull(activity));
        TicketEnvironment.Entry e = env.resolve(provider, key);
        env.checkWrite(e, key, project, "Zeit buchen");
        return TicketTools.written(e.system().logTime(key.trim(), e.project(project), work));
    }

    /** {@code 1h 30m}, {@code 1h30}, {@code 90m}, {@code 1,5h}, {@code 1:30}, {@code PT1H30M}; auf Minuten gerundet. */
    static Duration parseDuration(String s) {
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException("Keine Dauer angegeben – z.B. duration=\"1h 30m\".");
        }
        String t = s.trim().toLowerCase(Locale.ROOT);
        Duration d;
        Matcher clock = CLOCK.matcher(t);
        if (t.startsWith("pt")) {
            try {
                d = Duration.parse(t.toUpperCase(Locale.ROOT));
            } catch (DateTimeParseException e) {
                throw invalidDuration(s);
            }
        } else if (clock.matches()) {
            d = Duration.ofHours(Long.parseLong(clock.group(1))).plusMinutes(Long.parseLong(clock.group(2)));
        } else {
            if (DAYS.matcher(t).find()) {
                throw new IllegalArgumentException("Dauer '" + s.trim() + "': Tage und Wochen rechnet jedes System "
                        + "anders (8h, 24h …) – in Stunden und Minuten angeben, z.B. 8h.");
            }
            if (t.matches(".*\\dh\\s*\\d+")) {
                t += "m"; // 1h30 = 1h 30m
            }
            Matcher m = PART.matcher(t);
            double minutes = 0;
            StringBuilder rest = new StringBuilder();
            int end = 0;
            while (m.find()) {
                rest.append(t, end, m.start());
                end = m.end();
                double value = Double.parseDouble(m.group(1).replace(',', '.'));
                minutes += m.group(2).startsWith("m") ? value : value * 60;
            }
            rest.append(t.substring(end));
            if (end == 0 || !rest.toString().replaceAll("[\\s,+]|und|and", "").isEmpty()) {
                throw invalidDuration(s);
            }
            d = Duration.ofMinutes(Math.round(minutes));
        }
        d = Duration.ofMinutes(Math.round(d.getSeconds() / 60.0));
        if (d.isZero() || d.isNegative()) {
            throw new IllegalArgumentException("Dauer '" + s.trim() + "' ist kleiner als eine Minute.");
        }
        if (d.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException("Dauer '" + s.trim() + "' ist länger als 24 Stunden – je Tag einzeln buchen.");
        }
        return d;
    }

    private static IllegalArgumentException invalidDuration(String s) {
        return new IllegalArgumentException("Dauer '" + s.trim() + "' nicht verstanden – z.B. 1h 30m, 45m, 1,5h oder 1:30. "
                + "Eine Zahl ohne Einheit ist mehrdeutig.");
    }

    /** {@code yyyy-MM-dd}, {@code dd.MM.yyyy}, heute/gestern; leer = heute. Zukünftige Tage werden abgelehnt. */
    static LocalDate parseDate(String s, LocalDate today) {
        if (s == null || s.isBlank()) {
            return today;
        }
        String t = s.trim().toLowerCase(Locale.ROOT);
        LocalDate d = switch (t) {
            case "heute", "today" -> today;
            case "gestern", "yesterday" -> today.minusDays(1);
            case "vorgestern" -> today.minusDays(2);
            default -> {
                try {
                    yield t.contains(".") ? LocalDate.parse(t, GERMAN_DATE) : LocalDate.parse(t);
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException("Datum '" + s.trim() + "' nicht verstanden – yyyy-MM-dd, "
                            + "dd.MM.yyyy, heute oder gestern angeben.");
                }
            }
        };
        if (d.isAfter(today)) {
            throw new IllegalArgumentException("Datum " + d + " liegt in der Zukunft – Zeit erst nach getaner Arbeit buchen.");
        }
        return d;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
