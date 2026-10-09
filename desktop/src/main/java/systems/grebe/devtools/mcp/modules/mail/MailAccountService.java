package systems.grebe.devtools.mcp.modules.mail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import jakarta.annotation.PreDestroy;
import jakarta.mail.FetchProfile;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Part;
import jakarta.mail.UIDFolder;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolRegistry;
import systems.grebe.devtools.mcp.core.UserConfirmation;
import systems.grebe.devtools.mcp.modules.mail.spi.MailAccountInfo;
import systems.grebe.devtools.mcp.modules.mail.spi.MailAccountProvider;
import systems.grebe.devtools.mcp.modules.mail.spi.MailAttachment;
import systems.grebe.devtools.mcp.modules.mail.spi.MailAttachmentInfo;
import systems.grebe.devtools.mcp.modules.mail.spi.MailDraft;
import systems.grebe.devtools.mcp.modules.mail.spi.MailFolderInfo;
import systems.grebe.devtools.mcp.modules.mail.spi.MailMessage;
import systems.grebe.devtools.mcp.modules.mail.spi.MailQuery;
import systems.grebe.devtools.mcp.modules.mail.spi.MailSend;
import systems.grebe.devtools.mcp.modules.mail.spi.MailSummary;
import systems.grebe.devtools.mcp.modules.mail.spi.NewMail;
import systems.grebe.devtools.mcp.core.ContextClassLoader;

/**
 * Stellt die Konten des Mail-Moduls anderen Modulen und Plugins bereit ({@link MailAccountProvider} aus der Plugin-API).
 * Es gelten die wirksamen Einstellungen des Moduls, seine Anmeldungen und Freigaben: Lesen nur mit dem Recht auf
 * {@code mail_read}, Schreiben zusätzlich mit dem Schalter und dem Recht auf das jeweilige Tool. Verbindungen hält die
 * Bean selbst (eigener Pool), damit Plugins die Tools nicht ausbremsen.
 */
@Component
public class MailAccountService implements MailAccountProvider, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(MailAccountService.class);
    private static final int MAX_LIST = 500;

    private final MailWatcher watcher;
    private final UserConfirmation confirmation;
    private final Supplier<ModuleConfig> config;
    private final BiPredicate<String, String> toolPermitted;
    private final MailEnvironment.Sessions sessions = new MailEnvironment.Sessions();

    @Autowired
    public MailAccountService(MailWatcher watcher, UserConfirmation confirmation,
                              ObjectProvider<ToolRegistry> registry) {
        // Registry erst beim Aufruf holen – sie wird mit allen Modulen aufgebaut
        this(watcher, confirmation, () -> registry.getObject().config(MailModule.ID),
                (moduleId, tool) -> registry.getObject().toolPermitted(moduleId, tool));
    }

    /** Für Tests: Konfiguration und Rechte direkt. */
    MailAccountService(MailWatcher watcher, UserConfirmation confirmation, Supplier<ModuleConfig> config,
                       BiPredicate<String, String> toolPermitted) {
        this.watcher = watcher;
        this.confirmation = confirmation;
        this.config = config;
        this.toolPermitted = toolPermitted;
    }

    private MailEnvironment env() {
        return new MailEnvironment(config.get(), sessions, watcher, confirmation);
    }

    private void require(String tool) {
        if (!toolPermitted.test(MailModule.ID, tool)) {
            throw new IllegalStateException("Nicht erlaubt: Die Rollen des Benutzers erlauben " + tool + " nicht – damit "
                    + "ist es auch über andere Module und Plugins gesperrt.");
        }
    }

    private void requireSwitch(String key, String label, String tool) {
        if (!config.get().getBoolean(key)) {
            throw new IllegalStateException("Nicht erlaubt: Schalter „" + label + "“ im Mail-Modul ist aus.");
        }
        require(tool);
    }

    // ------------------------------------------------------------------ Lesen

    @Override
    public List<MailAccountInfo> accounts() {
        MailEnvironment env = env();
        return env.accounts().stream().map(a -> new MailAccountInfo(a.name(), a.target(), a.auth(), a.description(),
                a.folders(), a.watch().stream().filter(a::allows).toList(),
                !a.microsoft() || watcher.oauth().loggedIn(a), a.canSend() ? a.sender() : "")).toList();
    }

    @Override
    public List<MailFolderInfo> folders(String account) {
        require("mail_read");
        MailEnvironment env = env();
        MailAccount a = env.resolve(account);
        return env.withStore(a, store -> {
            List<MailFolderInfo> out = new ArrayList<>();
            for (Folder f : store.getDefaultFolder().list("*")) {
                if (!a.allows(f.getFullName())) {
                    continue;
                }
                int total = -1;
                int unread = -1;
                if ((f.getType() & Folder.HOLDS_MESSAGES) != 0) {
                    try {
                        total = f.getMessageCount();
                        unread = f.getUnreadMessageCount();
                    } catch (MessagingException e) {
                        // Anzahl bleibt unbekannt
                    }
                }
                out.add(new MailFolderInfo(f.getFullName(), total, unread, watcher.status(a, f.getFullName()) != null));
            }
            return out;
        });
    }

    @Override
    public List<MailSummary> list(String account, String folder, MailQuery query, int limit) {
        require("mail_read");
        MailEnvironment env = env();
        MailAccount a = env.resolve(account);
        String name = env.folderName(a, folder);
        MailQuery q = query == null ? MailQuery.ALL : query;
        var term = MailTools.term(q.text(), q.from(), q.subject(), q.unseenOnly(),
                q.since() == null ? null : q.since().toString());
        int max = Math.max(1, Math.min(MAX_LIST, limit));
        return env.inFolder(a, name, false, f -> {
            Message[] msgs = term == null ? f.getMessages() : f.search(term);
            Message[] page = Arrays.copyOfRange(msgs, Math.max(0, msgs.length - max), msgs.length);
            FetchProfile fp = new FetchProfile();
            fp.add(FetchProfile.Item.ENVELOPE);
            fp.add(FetchProfile.Item.FLAGS);
            fp.add(FetchProfile.Item.CONTENT_INFO);
            fp.add(UIDFolder.FetchProfileItem.UID);
            f.fetch(page, fp);
            List<MailSummary> out = new ArrayList<>();
            for (int i = page.length - 1; i >= 0; i--) {
                out.add(summary(a, name, f, page[i]));
            }
            return out;
        });
    }

    @Override
    public MailMessage read(String account, String folder, long uid) {
        require("mail_read");
        MailEnvironment env = env();
        MailAccount a = env.resolve(account);
        String name = env.folderName(a, folder);
        return env.inFolder(a, name, false, f -> {
            Message m = MailTools.message(f, uid);
            MailText.Content c;
            try {
                c = MailText.content(m);
            } catch (IOException e) {
                throw new IllegalStateException("Inhalt nicht lesbar: " + e.getMessage(), e);
            }
            List<MailAttachmentInfo> attachments = new ArrayList<>();
            for (int i = 0; i < c.attachments().size(); i++) {
                MailText.Attachment att = c.attachments().get(i);
                attachments.add(new MailAttachmentInfo(i, att.name(), att.type(), att.size()));
            }
            String replyTo = m.getReplyTo() == null || Arrays.equals(m.getReplyTo(), m.getFrom()) ? ""
                    : MailText.addresses(m.getReplyTo());
            return new MailMessage(summary(a, name, f, m), MailText.addresses(m.getRecipients(Message.RecipientType.TO)),
                    MailText.addresses(m.getRecipients(Message.RecipientType.CC)), replyTo,
                    m instanceof MimeMessage mm ? mm.getMessageID() : null, c.text(), c.html(), attachments);
        });
    }

    @Override
    public MailAttachment attachment(String account, String folder, long uid, int index, int maxBytes) {
        require("mail_read");
        MailEnvironment env = env();
        MailAccount a = env.resolve(account);
        String name = env.folderName(a, folder);
        int limit = Math.max(1, maxBytes);
        return env.inFolder(a, name, false, f -> {
            Message m = MailTools.message(f, uid);
            try {
                Part p = MailText.attachmentPart(m, index);
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                try (InputStream in = p.getInputStream()) {
                    byte[] chunk = new byte[16 * 1024];
                    int n;
                    while ((n = in.read(chunk)) > 0) {
                        if (buf.size() + n > limit) {
                            throw new IllegalStateException("Anhang " + index + " ist größer als " + limit + " Bytes.");
                        }
                        buf.write(chunk, 0, n);
                    }
                }
                List<MailText.Attachment> list = MailText.content(m).attachments();
                MailText.Attachment info = list.get(index);
                return new MailAttachment(info.name(), info.type(), buf.toByteArray());
            } catch (IOException e) {
                throw new IllegalStateException("Anhang nicht lesbar: " + e.getMessage(), e);
            }
        });
    }

    @Override
    public AutoCloseable onNewMail(Consumer<NewMail> listener) {
        ClassLoader loader = listener.getClass().getClassLoader();
        return watcher.addListener(mails -> {
            // Listener eines Plugins sieht dessen Klassen
            ContextClassLoader.run(loader, () -> {
                for (MailWatcher.NewMail m : mails) {
                    try {
                        listener.accept(new NewMail(new MailSummary(m.account(), m.folder(), m.uid(), instant(m.date()),
                                m.from(), m.fromAddress(), m.subject(), m.seen(), false, false), m.seen()));
                    } catch (RuntimeException e) {
                        LOG.warn("Listener für neue Mails ({}) fehlgeschlagen", listener.getClass().getName(), e);
                    }
                }
            });
        });
    }

    // ------------------------------------------------------------------ Schreiben (über die Tools, gleiche Prüfungen)

    @Override
    public String mark(String account, String folder, List<Long> uids, String flag, boolean set) {
        requireSwitch(MailModule.ALLOW_FLAGS, "Markieren erlauben", "mail_mark");
        String f = flag == null ? "" : flag.strip().toLowerCase(Locale.ROOT);
        if (!f.equals("seen") && !f.equals("flagged")) {
            throw new IllegalArgumentException("'flag' ist seen oder flagged.");
        }
        return new MailWriteTools.Mark(env()).mark(account, folder, join(uids), set ? f : "un" + f);
    }

    @Override
    public String move(String account, String folder, List<Long> uids, String target) {
        requireSwitch(MailModule.ALLOW_MOVE, "Verschieben erlauben", "mail_move");
        return new MailWriteTools.Move(env()).move(account, folder, join(uids), target);
    }

    @Override
    public String draft(String account, MailDraft draft) {
        requireSwitch(MailModule.ALLOW_DRAFTS, "Entwürfe anlegen erlauben", "mail_draft");
        if (draft == null) {
            throw new IllegalArgumentException("'draft' fehlt.");
        }
        return new MailWriteTools.Draft(env()).draft(account, draft.to(), draft.cc(), draft.subject(), draft.body(),
                draft.replyToUid(), draft.replyToFolder());
    }

    @Override
    public String send(String account, MailSend mail) {
        requireSwitch(MailModule.ALLOW_SEND, "Senden erlauben", "mail_send");
        if (mail == null) {
            throw new IllegalArgumentException("'mail' fehlt.");
        }
        // ohne MCP-Client: Rückfrage per Dialog der App (bzw. abgelehnt, wenn nur der Client fragen darf)
        return new MailWriteTools.Send(env()).sendMail(account, mail.to(), mail.cc(), mail.bcc(), mail.subject(),
                mail.body(), mail.replyToUid(), mail.replyToFolder(), mail.quote(), null);
    }

    @PreDestroy
    @Override
    public void close() {
        sessions.close();
    }

    // ------------------------------------------------------------------ intern

    private static String join(List<Long> uids) {
        if (uids == null || uids.isEmpty()) {
            throw new IllegalArgumentException("'uids' fehlt.");
        }
        return uids.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private static MailSummary summary(MailAccount a, String folder, Folder f, Message m) throws MessagingException {
        Date d = m.getReceivedDate() != null ? m.getReceivedDate() : m.getSentDate();
        return new MailSummary(a.name(), folder, ((UIDFolder) f).getUID(m), instant(d), MailText.addresses(m.getFrom()),
                MailText.senderAddress(m), MailText.subject(m), m.isSet(Flags.Flag.SEEN), m.isSet(Flags.Flag.FLAGGED),
                m.isMimeType("multipart/mixed"));
    }

    private static Instant instant(Date d) {
        return d == null ? null : d.toInstant();
    }
}
