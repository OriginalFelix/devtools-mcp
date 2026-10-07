package systems.grebe.devtools.mcp.modules.mail;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Properties;

import javax.net.ssl.SSLException;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;

/** Baut IMAP-Verbindungen (Angus Mail) und übersetzt Fehler in verständliche Meldungen – nie mit Zugangsdaten. */
final class MailConnector {

    private MailConnector() {
    }

    static String protocol(MailAccount a) {
        return a.security().equals(MailAccount.SSL) ? "imaps" : "imap";
    }

    /**
     * Verbundener Store.
     *
     * @param oauth       Anmeldung für Exchange-Online-Konten (XOAUTH2)
     * @param readTimeout Zeitlimit für Antworten des Servers; für IDLE länger als die Auffrischung
     */
    static Store connect(MailAccount a, MailOAuth oauth, Duration connectTimeout, Duration readTimeout) {
        if (a.host().isEmpty() || a.username().isEmpty()) {
            throw new IllegalStateException("Konto '" + a.name() + "': Host und Benutzer sind Pflicht.");
        }
        if (!a.microsoft() && a.password() == null) {
            throw new IllegalStateException("Konto '" + a.name() + "': kein Passwort hinterlegt (in der DevTools-App).");
        }
        // vor dem Verbinden: ohne Anmeldung gar nicht erst den Server fragen
        String secret = a.microsoft() ? oauth.accessToken(a) : a.password();
        String p = protocol(a);
        Properties props = new Properties();
        props.put("mail.store.protocol", p);
        props.put("mail." + p + ".host", a.host());
        props.put("mail." + p + ".port", Integer.toString(a.port()));
        props.put("mail." + p + ".connectiontimeout", Long.toString(connectTimeout.toMillis()));
        props.put("mail." + p + ".timeout", Long.toString(readTimeout.toMillis()));
        props.put("mail." + p + ".writetimeout", Long.toString(connectTimeout.toMillis()));
        // Lesen setzt nie nebenbei \Seen – gelesen markiert nur mail_read(markSeen) bzw. mail_mark
        props.put("mail." + p + ".peek", "true");
        if (a.microsoft()) {
            // SASL XOAUTH2 mit dem Access-Token statt des Passworts; nichts anderes versuchen
            props.put("mail." + p + ".auth.mechanisms", "XOAUTH2");
            props.put("mail." + p + ".auth.login.disable", "true");
            props.put("mail." + p + ".auth.plain.disable", "true");
        }
        if (a.security().equals(MailAccount.SSL)) {
            props.put("mail.imaps.ssl.checkserveridentity", "true");
        } else if (a.security().equals(MailAccount.STARTTLS)) {
            props.put("mail.imap.starttls.enable", "true");
            props.put("mail.imap.starttls.required", "true");
            props.put("mail.imap.ssl.checkserveridentity", "true");
        }
        try {
            Store store = Session.getInstance(props).getStore(p);
            store.connect(a.host(), a.port(), a.username(), secret);
            return store;
        } catch (MessagingException e) {
            if (a.microsoft() && e instanceof AuthenticationFailedException) {
                oauth.invalidate(a);
            }
            throw new IllegalStateException(describe(a, e), e);
        }
    }

    /** Meldung für das LLM bzw. die App. */
    static String describe(MailAccount a, Exception e) {
        String where = "IMAP '" + a.name() + "' (" + a.target() + "): ";
        if (e instanceof IllegalStateException && e.getMessage() != null && e.getMessage().startsWith("IMAP '")) {
            return e.getMessage();
        }
        if (e instanceof AuthenticationFailedException && a.microsoft()) {
            return where + "Exchange lehnt das Token ab – in der App-Registrierung die delegierte Berechtigung "
                    + "IMAP.AccessAsUser.All (Office 365 Exchange Online) prüfen, IMAP muss für das Postfach aktiv sein; "
                    + "bei einem freigegebenen Postfach braucht der angemeldete Benutzer Vollzugriff darauf."
                    + suffix(e.getMessage());
        }
        if (e instanceof AuthenticationFailedException) {
            return where + "Anmeldung fehlgeschlagen – Benutzer und Passwort prüfen (in der DevTools-App; manche "
                    + "Anbieter verlangen ein App-Passwort)." + suffix(e.getMessage());
        }
        return where + network(e, a.port(), a.security());
    }

    /** Netzwerk- und TLS-Fehler (IMAP wie SMTP) als Satz ohne Präfix. */
    static String network(Exception e, int port, String security) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof UnknownHostException) {
                return "Host nicht gefunden.";
            }
            if (t instanceof ConnectException) {
                return "Verbindung abgelehnt – stimmen Port " + port + " und Verschlüsselung (" + security + ")?";
            }
            if (t instanceof SocketTimeoutException) {
                return "Zeitüberschreitung.";
            }
            if (t instanceof SSLException) {
                return "TLS-Fehler (" + t.getMessage() + ") – Verschlüsselung (ssl/starttls/none) und Port prüfen.";
            }
        }
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    static String suffix(String msg) {
        return msg == null || msg.isBlank() ? "" : " Server: " + msg.strip();
    }
}
