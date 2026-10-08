package systems.grebe.devtools.mcp.core;

import java.util.List;
import java.util.Locale;

/**
 * Ergänzt Fehlermeldungen von Tools um den nächsten sinnvollen Schritt. Ein LLM, das nur „geht nicht“ liest, probiert
 * es gern mehrmals oder weicht auf die Shell aus – jede Runde kostet Tokens. Ein Satz mit dem richtigen Tool spart das.
 */
public final class ErrorHints {

    /**
     * @param needles woran die Meldung erkannt wird (klein geschrieben)
     * @param marker  steht das schon in der Meldung, nennt sie den Weg selbst – kein Hinweis
     */
    private record Rule(List<String> needles, String marker, String hint) {
    }

    private static final List<Rule> RULES = List.of(
            new Rule(List.of("nicht freigegeben", "not permitted", "keine berechtigung"), "permissions_",
                    "permissions_check(tool=… oder path=…) zeigt, was fehlt; permissions_request fragt beim Nutzer an "
                            + "– nicht per Shell umgehen."),
            new Rule(List.of("zeitlimit", "timeout", "timed out"), "invocation",
                    "Nicht in kurzen Abständen erneut aufrufen – bei lang laufenden Aktionen auf einen Rückruf warten "
                            + "(Memory vom Typ INVOCATION) oder das Zeitlimit des Moduls in der App erhöhen."),
            new Rule(List.of("unbekanntes tool", "nicht aktiv", "unknown tool"), "context_find",
                    "context_find(query=…) sucht passende aktive Tools."));

    private ErrorHints() {
    }

    /** Meldung plus Hinweis, oder {@code null}, wenn keiner passt bzw. die Meldung den Weg schon nennt. */
    public static String hint(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        String m = message.toLowerCase(Locale.ROOT);
        for (Rule r : RULES) {
            if (r.needles().stream().anyMatch(m::contains)) {
                return m.contains(r.marker()) ? null : message.strip() + "\n→ " + r.hint();
            }
        }
        return null;
    }
}
