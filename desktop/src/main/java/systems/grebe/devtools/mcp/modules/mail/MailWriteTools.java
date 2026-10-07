package systems.grebe.devtools.mcp.modules.mail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

import jakarta.mail.Address;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.IMAPStore;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.ToolHints;

/** Verändernde Mail-Tools, je mit eigenem Schalter: markieren, verschieben, Entwürfe anlegen. Gelöscht wird nie. */
public final class MailWriteTools {

    static final String UIDS = "UID oder mehrere, durch Komma getrennt (z.B. 12,15,16)";

    private MailWriteTools() {
    }

    static long[] uids(String uids) {
        if (uids == null || uids.isBlank()) {
            throw new IllegalArgumentException("'uids' fehlt.");
        }
        Set<Long> out = new LinkedHashSet<>();
        for (String s : uids.split("[,\\s]+")) {
            if (s.isBlank()) {
                continue;
            }
            try {
                long v = Long.parseLong(s.strip());
                if (v <= 0) {
                    throw new NumberFormatException();
                }
                out.add(v);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Ungültige UID '" + s + "' – Zahlen durch Komma getrennt angeben.");
            }
        }
        if (out.size() > 500) {
            throw new IllegalArgumentException("Höchstens 500 Mails auf einmal.");
        }
        return out.stream().mapToLong(Long::longValue).toArray();
    }

    static Message[] messages(Folder f, long[] uids) throws MessagingException {
        List<Message> out = new ArrayList<>();
        List<Long> missing = new ArrayList<>();
        for (long uid : uids) {
            Message m = ((IMAPFolder) f).getMessageByUID(uid);
            if (m == null || m.isExpunged()) {
                missing.add(uid);
            } else {
                out.add(m);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Keine Mail mit UID " + missing + " in " + f.getFullName()
                    + " – nichts geändert.");
        }
        return out.toArray(Message[]::new);
    }

    /** {@code mail_mark}. */
    public static class Mark {
        private final MailEnvironment env;

        Mark(MailEnvironment env) {
            this.env = env;
        }

        @Tool(name = "mark", description = "Markiert Mails als gelesen/ungelesen oder setzt bzw. entfernt die "
                + "Markierung (Stern/Fahne)." + ShellHints.MAIL)
        @ToolHints(destructive = false, idempotent = true)
        public String mark(
                @ToolParam(required = false, description = MailTools.ACCOUNT) String account,
                @ToolParam(required = false, description = MailTools.FOLDER) String folder,
                @ToolParam(description = UIDS) String uids,
                @ToolParam(description = "seen, unseen, flagged oder unflagged") String action) {
            String act = action == null ? "" : action.strip().toLowerCase(Locale.ROOT);
            Flags.Flag flag = switch (act) {
                case "seen", "unseen" -> Flags.Flag.SEEN;
                case "flagged", "unflagged" -> Flags.Flag.FLAGGED;
                default -> throw new IllegalArgumentException("'action' ist seen, unseen, flagged oder unflagged.");
            };
            boolean set = !act.startsWith("un");
            long[] ids = uids(uids);
            MailAccount a = env.resolve(account);
            String name = env.folderName(a, folder);
            return env.inFolder(a, name, true, f -> {
                Message[] msgs = messages(f, ids);
                f.setFlags(msgs, new Flags(flag), set);
                return msgs.length + " Mail(s) in " + a.name() + "/" + name + " als " + act + " markiert.";
            });
        }
    }

    /** {@code mail_move}. */
    public static class Move {
        private final MailEnvironment env;

        Move(MailEnvironment env) {
            this.env = env;
        }

        @Tool(name = "move", description = "Verschiebt Mails in einen anderen freigegebenen Ordner desselben Kontos (auch "
                + "Archiv oder Papierkorb). Endgültig gelöscht wird nie. Nur auf ausdrückliche Anweisung des Nutzers."
                + ShellHints.MAIL)
        @ToolHints(destructive = false)
        public String move(
                @ToolParam(required = false, description = MailTools.ACCOUNT) String account,
                @ToolParam(required = false, description = MailTools.FOLDER + " (Quelle)") String folder,
                @ToolParam(description = UIDS) String uids,
                @ToolParam(description = "Zielordner, z.B. Archiv oder Trash (muss freigegeben sein)") String target) {
            long[] ids = uids(uids);
            MailAccount a = env.resolve(account);
            String source = env.folderName(a, folder);
            if (target == null || target.isBlank()) {
                throw new IllegalArgumentException("'target' fehlt.");
            }
            String dest = target.strip();
            MailEnvironment.requireAllowed(a, dest);
            if (MailAccount.matches(source, dest)) {
                throw new IllegalArgumentException("Quelle und Ziel sind derselbe Ordner.");
            }
            return env.inFolder(a, source, true, f -> {
                Folder to = f.getStore().getFolder(dest);
                if (!to.exists()) {
                    throw new IllegalArgumentException("Zielordner '" + dest + "' gibt es nicht (siehe mail_folders).");
                }
                Message[] msgs = messages(f, ids);
                IMAPStore store = (IMAPStore) f.getStore();
                if (store.hasCapability("MOVE")) {
                    ((IMAPFolder) f).moveMessages(msgs, to);
                    return msgs.length + " Mail(s) von " + source + " nach " + dest + " verschoben.";
                }
                f.copyMessages(msgs, to);
                f.setFlags(msgs, new Flags(Flags.Flag.DELETED), true);
                if (store.hasCapability("UIDPLUS")) {
                    ((IMAPFolder) f).expunge(msgs); // UID EXPUNGE: nur genau diese Mails
                    return msgs.length + " Mail(s) von " + source + " nach " + dest + " verschoben.";
                }
                return msgs.length + " Mail(s) nach " + dest + " kopiert und in " + source + " als gelöscht markiert "
                        + "(der Server kann weder MOVE noch UIDPLUS; endgültig entfernt sie erst das Mail-Programm).";
            });
        }
    }

    /** {@code mail_draft}. */
    public static class Draft {
        private static final List<String> DRAFT_NAMES = List.of("Drafts", "Entwürfe", "INBOX.Drafts", "INBOX/Drafts",
                "[Gmail]/Drafts", "[Gmail]/Entwürfe", "Draft");
        private final MailEnvironment env;

        Draft(MailEnvironment env) {
            this.env = env;
        }

        @Tool(name = "draft", description = "Legt einen Entwurf im Entwurfsordner des Kontos an, optional als Antwort auf "
                + "eine Mail (Betreff „Re:“, Empfänger, Zitat, In-Reply-To). Gesendet wird nie – der Nutzer prüft und "
                + "sendet den Entwurf selbst. Nur auf ausdrückliche Anweisung des Nutzers." + ShellHints.MAIL)
        @ToolHints(destructive = false)
        public String draft(
                @ToolParam(required = false, description = MailTools.ACCOUNT) String account,
                @ToolParam(required = false, description = "Empfänger, durch Komma getrennt; bei Antworten leer = "
                        + "Absender der Mail") String to,
                @ToolParam(required = false, description = "Kopie an, durch Komma getrennt") String cc,
                @ToolParam(required = false, description = "Betreff; bei Antworten leer = „Re: …“") String subject,
                @ToolParam(description = "Text (reiner Text)") String body,
                @ToolParam(required = false, description = "Antwort auf: UID der Mail") Long replyToUid,
                @ToolParam(required = false, description = "Antwort auf: Ordner der Mail; leer = INBOX") String replyToFolder) {
            if (body == null) {
                throw new IllegalArgumentException("'body' fehlt.");
            }
            MailAccount a = env.resolve(account);
            MimeMessage draft = new MimeMessage(Session.getInstance(new Properties()));
            try {
                if (a.username().contains("@")) {
                    draft.setFrom(new InternetAddress(a.username()));
                }
                String text = body;
                String subj = subject == null ? "" : subject.strip();
                Address[] recipients = parse(to);
                if (replyToUid != null) {
                    String src = env.folderName(a, replyToFolder);
                    Reply r = env.inFolder(a, src, false, f -> Reply.of(MailTools.message(f, replyToUid)));
                    subj = subj.isEmpty() ? r.subject() : subj;
                    recipients = recipients.length == 0 ? r.recipients() : recipients;
                    text = body + "\n\n" + r.quote();
                    if (r.messageId() != null) {
                        draft.setHeader("In-Reply-To", r.messageId());
                        draft.setHeader("References", r.references());
                    }
                }
                if (recipients.length > 0) {
                    draft.setRecipients(Message.RecipientType.TO, recipients);
                }
                Address[] copies = parse(cc);
                if (copies.length > 0) {
                    draft.setRecipients(Message.RecipientType.CC, copies);
                }
                draft.setSubject(subj, "UTF-8");
                draft.setText(text, "UTF-8");
                draft.setSentDate(new java.util.Date());
                draft.setFlag(Flags.Flag.DRAFT, true);
                draft.setFlag(Flags.Flag.SEEN, true);
                draft.saveChanges();
            } catch (AddressException e) {
                throw new IllegalArgumentException("Ungültige Adresse: " + e.getMessage());
            } catch (MessagingException e) {
                throw new IllegalStateException("Entwurf nicht erstellbar: " + e.getMessage(), e);
            }
            String folder = env.withStore(a, Draft::draftsFolder);
            MailEnvironment.requireAllowed(a, folder);
            return env.inFolder(a, folder, true, f -> {
                f.appendMessages(new Message[]{draft});
                return "Entwurf in " + a.name() + "/" + folder + " angelegt – an "
                        + (draft.getAllRecipients() == null ? "(noch ohne Empfänger)"
                        : MailText.addresses(draft.getAllRecipients())) + ", Betreff „" + MailText.subject(draft)
                        + "“. Der Nutzer prüft und sendet ihn im Mail-Programm.";
            });
        }

        private static Address[] parse(String list) throws AddressException {
            return list == null || list.isBlank() ? new Address[0] : InternetAddress.parse(list.strip(), false);
        }

        /** Entwurfsordner nach SPECIAL-USE ({@code \Drafts}), sonst nach üblichen Namen. */
        static String draftsFolder(Store store) throws MessagingException {
            Folder[] all = store.getDefaultFolder().list("*");
            for (Folder f : all) {
                if (f instanceof IMAPFolder imf && Arrays.asList(imf.getAttributes()).contains("\\Drafts")) {
                    return f.getFullName();
                }
            }
            for (String name : DRAFT_NAMES) {
                for (Folder f : all) {
                    if (f.getFullName().equalsIgnoreCase(name)) {
                        return f.getFullName();
                    }
                }
            }
            throw new IllegalStateException("Kein Entwurfsordner gefunden (weder \\Drafts noch Drafts/Entwürfe).");
        }

        /** Was eine Antwort von der ursprünglichen Mail braucht. */
        private record Reply(String subject, Address[] recipients, String messageId, String references, String quote) {
            static Reply of(Message m) throws MessagingException {
                String subj = MailText.subject(m);
                if (!subj.regionMatches(true, 0, "Re:", 0, 3) && !subj.regionMatches(true, 0, "AW:", 0, 3)) {
                    subj = "Re: " + subj;
                }
                Address[] to = m.getReplyTo() != null && m.getReplyTo().length > 0 ? m.getReplyTo() : m.getFrom();
                String id = null;
                String refs = null;
                if (m instanceof MimeMessage mm) {
                    id = mm.getMessageID();
                    String old = mm.getHeader("References", " ");
                    refs = old == null ? id : old + " " + id;
                }
                String original;
                try {
                    original = MailText.content(m).text();
                } catch (java.io.IOException e) {
                    original = "";
                }
                StringBuilder q = new StringBuilder("Am ").append(MailText.time(m.getSentDate())).append(" schrieb ")
                        .append(MailText.addresses(m.getFrom())).append(":\n");
                for (String line : MailText.limit(original, 20_000).split("\n", -1)) {
                    q.append("> ").append(line).append('\n');
                }
                return new Reply(subj, to == null ? new Address[0] : to, id, refs, q.toString());
            }
        }
    }
}
