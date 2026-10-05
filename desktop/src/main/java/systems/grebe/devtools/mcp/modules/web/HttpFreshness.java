package systems.grebe.devtools.mcp.modules.web;

import java.net.http.HttpHeaders;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Wie lange eine Antwort laut HTTP-Headern gültig ist (RFC 9111, Sicht eines privaten Caches): {@code Cache-Control}
 * ({@code no-store}, {@code no-cache}, {@code max-age}) vor {@code Expires}, {@code Pragma: no-cache} nur ohne
 * {@code Cache-Control}; {@code Age} wird abgezogen. {@code s-maxage} gilt nur für geteilte Caches und bleibt außen vor.
 * Ohne jede Angabe ist eine Seite unbegrenzt gültig – bis zum Neuladen mit {@code refresh="force"}.
 */
final class HttpFreshness {

    /**
     * @param store     ob die Antwort gespeichert werden darf ({@code false} bei {@code no-store})
     * @param expiresAt gültig bis; {@code null} = unbegrenzt
     * @param rule      woher die Gültigkeit stammt, für die Ausgabe (z.B. „max-age=600“), leer = keine Angabe
     */
    record Result(boolean store, Instant expiresAt, String rule) {
        static final Result UNLIMITED = new Result(true, null, "");
    }

    private HttpFreshness() {
    }

    static Result of(HttpHeaders headers, Instant now) {
        Optional<String> cc = joined(headers, "Cache-Control");
        if (cc.isPresent()) {
            Map<String, String> d = directives(cc.get());
            if (d.containsKey("no-store")) {
                return new Result(false, now, "no-store");
            }
            // no-cache="Feld" betrifft nur einzelne Header-Felder, nicht die Antwort
            if (d.containsKey("no-cache") && d.get("no-cache").isEmpty()) {
                return new Result(true, now, "no-cache");
            }
            String maxAge = d.get("max-age");
            if (maxAge != null) {
                long seconds = seconds(maxAge);
                return new Result(true, now.plusSeconds(Math.max(0, seconds - age(headers))), "max-age=" + maxAge);
            }
        }
        Optional<String> expires = headers.firstValue("Expires");
        if (expires.isPresent()) {
            Optional<Instant> at = date(expires.get());
            if (at.isEmpty()) {
                // ungültiges Expires (z.B. „0“) heißt laut RFC 9111: schon abgelaufen
                return new Result(true, now, "Expires");
            }
            Instant until = headers.firstValue("Date").flatMap(HttpFreshness::date)
                    .map(date -> now.plus(Duration.between(date, at.get())).minusSeconds(age(headers)))
                    .orElse(at.get());
            return new Result(true, until.isBefore(now) ? now : until, "Expires");
        }
        if (cc.isEmpty() && joined(headers, "Pragma").map(p -> directives(p).containsKey("no-cache")).orElse(false)) {
            return new Result(true, now, "Pragma: no-cache");
        }
        return Result.UNLIMITED;
    }

    /** Direktiven klein geschrieben, Werte ohne Anführungszeichen; ohne Wert leer. */
    static Map<String, String> directives(String header) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String part : header.split(",")) {
            String p = part.strip();
            if (p.isEmpty()) {
                continue;
            }
            int eq = p.indexOf('=');
            String name = (eq < 0 ? p : p.substring(0, eq)).strip().toLowerCase(Locale.ROOT);
            String value = eq < 0 ? "" : p.substring(eq + 1).strip();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            out.putIfAbsent(name, value);
        }
        return out;
    }

    private static Optional<String> joined(HttpHeaders headers, String name) {
        var values = headers.allValues(name);
        return values.isEmpty() ? Optional.empty() : Optional.of(String.join(",", values));
    }

    /** Sekunden aus max-age bzw. Age; Ungültiges zählt als 0 (sofort abgelaufen bzw. kein Alter). */
    private static long seconds(String s) {
        try {
            return Math.max(0, Long.parseLong(s.strip()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long age(HttpHeaders headers) {
        return headers.firstValue("Age").map(HttpFreshness::seconds).orElse(0L);
    }

    private static Optional<Instant> date(String s) {
        try {
            return Optional.of(ZonedDateTime.parse(s.strip(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
