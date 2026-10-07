package systems.grebe.devtools.mcp.modules.mail.spi;

import java.time.LocalDate;

/**
 * Filter für {@link MailAccountProvider#list}; {@code null}-Felder filtern nicht. Mehrere Filter gelten gemeinsam.
 *
 * @param text       Text in Betreff, Absender oder Inhalt
 * @param from       Absender (Teilstring von Name oder Adresse)
 * @param subject    Betreff (Teilstring)
 * @param unseenOnly nur ungelesene
 * @param since      nur ab diesem Tag (Eingangsdatum)
 */
public record MailQuery(String text, String from, String subject, boolean unseenOnly, LocalDate since) {

    /** Kein Filter. */
    public static final MailQuery ALL = new MailQuery(null, null, null, false, null);
}
