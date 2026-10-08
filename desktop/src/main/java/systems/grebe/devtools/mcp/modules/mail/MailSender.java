package systems.grebe.devtools.mcp.modules.mail;

import java.time.Duration;
import java.util.Properties;

import jakarta.mail.Address;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

/**
 * Versand per SMTP (Angus Mail): Anmeldung mit Benutzer und Passwort bzw. bei Exchange Online per SASL {@code XOAUTH2}
 * mit demselben Token wie IMAP. Fehlermeldungen nennen nie Zugangsdaten.
 */
final class MailSender {

    private MailSender() {
    }

    static String protocol(MailAccount a) {
        return a.smtpSecurity().equals(MailAccount.SSL) ? "smtps" : "smtp";
    }

    /** Session für den Versand, auch zum Erzeugen der Nachricht (Message-ID mit dem Host des Absenders). */
    static Session session(MailAccount a, Duration timeout) {
        String p = protocol(a);
        Properties props = new Properties();
        props.put("mail.transport.protocol", p);
        props.put("mail." + p + ".host", a.smtpHost());
        props.put("mail." + p + ".port", Integer.toString(a.smtpPort()));
        props.put("mail." + p + ".connectiontimeout", Long.toString(timeout.toMillis()));
        props.put("mail." + p + ".timeout", Long.toString(timeout.toMillis()));
        props.put("mail." + p + ".writetimeout", Long.toString(timeout.toMillis()));
        boolean auth = a.microsoft() || a.password() != null;
        props.put("mail." + p + ".auth", Boolean.toString(auth));
        if (a.microsoft()) {
            props.put("mail." + p + ".auth.mechanisms", "XOAUTH2");
            props.put("mail." + p + ".auth.login.disable", "true");
            props.put("mail." + p + ".auth.plain.disable", "true");
        }
        if (a.smtpSecurity().equals(MailAccount.SSL)) {
            props.put("mail.smtps.ssl.checkserveridentity", "true");
        } else if (a.smtpSecurity().equals(MailAccount.STARTTLS)) {
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.starttls.required", "true");
            props.put("mail.smtp.ssl.checkserveridentity", "true");
        }
        String sender = a.sender();
        if (sender.contains("@")) {
            // Envelope-From: nur die Adresse, ohne Anzeigenamen
            try {
                props.put("mail." + p + ".from", new InternetAddress(sender).getAddress());
            } catch (AddressException ignored) {
                // ungültig – fällt beim Erstellen der Nachricht auf
            }
        }
        return Session.getInstance(props);
    }

    /** Verbindet, meldet sich an und sendet an alle Empfänger (To, Cc, Bcc). */
    static void send(MailAccount a, MailOAuth oauth, MimeMessage message, Duration timeout) {
        if (!a.canSend()) {
            throw new IllegalStateException("Konto '" + a.name() + "' hat keinen SMTP-Server – der Nutzer trägt ihn in der "
                    + "DevTools-App ein (Module → Mail → Konto → SMTP-Server).");
        }
        String secret = a.microsoft() ? oauth.accessToken(a) : a.password();
        try {
            Address[] recipients = message.getAllRecipients();
            if (recipients == null || recipients.length == 0) {
                throw new IllegalArgumentException("Keine Empfänger.");
            }
            try (Transport t = session(a, timeout).getTransport(protocol(a))) {
                if (secret == null) {
                    t.connect(); // ohne Anmeldung, z.B. interner Relay
                } else {
                    t.connect(a.smtpHost(), a.smtpPort(), a.username(), secret);
                }
                t.sendMessage(message, recipients);
            }
        } catch (AuthenticationFailedException e) {
            if (a.microsoft()) {
                oauth.invalidate(a);
            }
            throw new IllegalStateException(where(a) + (a.microsoft()
                    ? "Exchange lehnt die Anmeldung ab – delegierte Berechtigung SMTP.Send prüfen und neu anmelden "
                    + "(mail_login); für das Postfach muss „Authentifiziertes SMTP“ erlaubt sein."
                    : "Anmeldung fehlgeschlagen – Benutzer und Passwort prüfen (in der DevTools-App).")
                    + MailConnector.suffix(e.getMessage()), e);
        } catch (SendFailedException e) {
            throw new IllegalStateException(where(a) + "nicht gesendet – " + describe(e), e);
        } catch (MessagingException e) {
            throw new IllegalStateException(where(a) + MailConnector.network(e, a.smtpPort(), a.smtpSecurity()), e);
        }
    }

    /** Prüft Verbindung und Anmeldung, ohne zu senden (Verbindung testen). */
    static String test(MailAccount a, MailOAuth oauth, Duration timeout) {
        String secret = a.microsoft() ? oauth.accessToken(a) : a.password();
        try (Transport t = session(a, timeout).getTransport(protocol(a))) {
            if (secret == null) {
                t.connect();
            } else {
                t.connect(a.smtpHost(), a.smtpPort(), a.username(), secret);
            }
            return "SMTP " + a.smtpTarget() + " (" + a.smtpSecurity() + "): verbunden, Absender "
                    + (a.sender().isEmpty() ? "FEHLT (Absenderadresse eintragen)" : a.sender());
        } catch (AuthenticationFailedException e) {
            return "SMTP " + a.smtpTarget() + ": FEHLER – Anmeldung fehlgeschlagen" + MailConnector.suffix(e.getMessage());
        } catch (MessagingException e) {
            return "SMTP " + a.smtpTarget() + ": FEHLER – " + MailConnector.network(e, a.smtpPort(), a.smtpSecurity());
        }
    }

    private static String describe(SendFailedException e) {
        StringBuilder sb = new StringBuilder(e.getMessage() == null ? "Server lehnt ab" : e.getMessage().strip());
        if (e.getInvalidAddresses() != null && e.getInvalidAddresses().length > 0) {
            sb.append("; ungültig: ").append(MailText.addresses(e.getInvalidAddresses()));
        }
        if (e.getValidUnsentAddresses() != null && e.getValidUnsentAddresses().length > 0) {
            sb.append("; nicht zugestellt: ").append(MailText.addresses(e.getValidUnsentAddresses()));
        }
        if (e.getValidSentAddresses() != null && e.getValidSentAddresses().length > 0) {
            sb.append("; trotzdem gesendet an: ").append(MailText.addresses(e.getValidSentAddresses()));
        }
        return sb.toString();
    }

    private static String where(MailAccount a) {
        return "SMTP '" + a.name() + "' (" + a.smtpTarget() + "): ";
    }
}
