package systems.grebe.devtools.mcp.modules.share;

import java.util.Locale;

/**
 * Topics der Kooperation – gemeinsam für den Client in der Desktop-App und den Broker im Backend, der die Rechte
 * danach vergibt:
 * <ul>
 *   <li>{@code <präfix>/inbox/<adresse>} – Angebote und Antworten an eine Adresse,</li>
 *   <li>{@code <präfix>/presence/<adresse>/<instanz>} – Anwesenheit einer Instanz (retained).</li>
 * </ul>
 */
public final class ShareTopics {

    public static final String DEFAULT_PREFIX = "devtools-mcp";

    /**
     * User Property, mit der der Broker des Backends jede Nachricht stempelt: die geprüfte Adresse des Absenders (aus
     * seiner Anmeldung). Ein mitgeschickter Wert wird vorher entfernt.
     */
    public static final String SENDER_PROPERTY = "devtools-sender";

    private ShareTopics() {
    }

    /** Adresse in der Form, die in Topics und Vergleichen gilt: klein, ohne Zeichen mit Sonderbedeutung in MQTT. */
    public static String address(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.strip().toLowerCase(Locale.ROOT).replaceAll("[/+#\\s\\p{Cntrl}]+", "_");
    }

    public static String inbox(String prefix, String address) {
        return prefix + "/inbox/" + address(address);
    }

    public static String presence(String prefix, String address, String instance) {
        return prefix + "/presence/" + address(address) + "/" + instance;
    }

    /** Alle Anwesenheiten. */
    public static String presenceFilter(String prefix) {
        return prefix + "/presence/+/+";
    }
}
