package systems.grebe.devtools.mcp.modules.chat;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.Text;
import systems.grebe.devtools.mcp.core.ToolHints;
import systems.grebe.devtools.mcp.core.ToolProgress;
import systems.grebe.devtools.mcp.modules.chat.ChatEnvironment.Entry;
import systems.grebe.devtools.mcp.modules.chat.ChatEnvironment.Target;
import systems.grebe.devtools.mcp.modules.chat.ChatInbox.Received;
import systems.grebe.devtools.mcp.modules.chat.spi.ChatSystem;

/** Chat-Tools: Unterhaltungen, Senden, Fragen mit Warten auf die Antwort, neue Nachrichten, Verlauf, Reaktionen. */
public class ChatTools {

    static final String PROVIDER_PARAM = "Chat-System (z.B. matrix, teams); leer = aus Unterhaltung/Nachricht, "
            + "Standard-System bzw. das einzige aktive";
    static final String CONVERSATION_PARAM = "Unterhaltung (Raum/Chat): ID, Alias oder Name; leer = Standard-Unterhaltung";
    private static final int MAX_RECEIVE = 50;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final ChatEnvironment env;

    ChatTools(ChatEnvironment env) {
        this.env = env;
    }

    @Tool(name = "conversations", description = "Aktive Chat-Systeme mit Konto, Standard-Unterhaltung, freigegebenen "
            + "Absendern und den Unterhaltungen (Räume/Chats: ID, Name, Mitglieder, Hinweise wie „verschlüsselt“)."
            + ShellHints.CHAT)
    @ToolHints(readOnly = true)
    public String conversations(@ToolParam(required = false, description = PROVIDER_PARAM) String provider) {
        List<Entry> entries = provider == null || provider.isBlank() ? env.entries()
                : List.of(env.entry(provider, null, null));
        if (entries.isEmpty()) {
            env.entry(null, null, null); // wirft mit Hinweis, wie man ein System aktiviert
        }
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) {
            sb.append("## ").append(e.provider().displayName()).append(" (").append(e.id()).append(")\n");
            try {
                describe(e, sb);
            } catch (RuntimeException ex) {
                sb.append("Nicht verfügbar: ").append(ex.getMessage()).append('\n');
                if (e.system().loginStatus() != null) {
                    sb.append("Anmeldung: ").append(e.system().loginStatus()).append('\n');
                }
            }
            sb.append('\n');
        }
        return Text.limitLines(sb.toString().strip(), env.maxLines());
    }

    private void describe(Entry e, StringBuilder sb) {
        ChatSystem system = e.system();
        ChatSystem.Account account = system.account();
        ChatInbox inbox = e.inbox();
        inbox.catchUp(); // nimmt Einladungen an und füllt Namen
        sb.append("Konto: ").append(account.displayName() == null || account.displayName().equals(account.id())
                ? account.id() : account.displayName() + " <" + account.id() + ">").append('\n');
        if (system.loginStatus() != null) {
            sb.append("Anmeldung: ").append(system.loginStatus()).append('\n');
        }
        String defaultId = null;
        if (e.defaultConversation() != null) {
            try {
                defaultId = system.resolve(e.defaultConversation());
                sb.append("Standard: ").append(system.label(defaultId)).append(" – ").append(defaultId).append('\n');
            } catch (RuntimeException ex) {
                sb.append("Standard ").append(e.defaultConversation()).append(": ").append(ex.getMessage()).append('\n');
            }
        }
        sb.append("Freigegebene Absender: ").append(system.senderPolicy()).append('\n');
        if (!system.canWait()) {
            sb.append("Warten auf Antworten: aus – Nachrichten nur je Abruf (chat_receive).\n");
        }
        List<ChatSystem.Conversation> all = system.conversations();
        sb.append("Unterhaltungen (").append(all.size()).append("):\n");
        for (ChatSystem.Conversation c : all.stream().limit(50).toList()) {
            sb.append("  ").append(c.id()).append("  ").append(c.name() == null ? "(ohne Namen)" : "„" + c.name() + "“");
            if (c.alias() != null) {
                sb.append("  ").append(c.alias());
            }
            if (c.members() >= 0) {
                sb.append("  ").append(c.members()).append(" Mitglieder");
            }
            if (c.id().equals(defaultId)) {
                sb.append("  [Standard]");
            }
            if (c.note() != null) {
                sb.append("  [").append(c.note()).append(']');
            }
            sb.append('\n');
        }
        appendNotices(sb, inbox);
    }

    @Tool(name = "send", description = "Sendet eine Nachricht (Markdown) in einen Chat (Matrix-Raum, Teams-Chat), optional "
            + "als Antwort auf eine Nachricht oder in deren Thread. Für Statusmeldungen, Ergebnisse und Benachrichtigungen "
            + "an den Nutzer. Erwartest du eine Antwort, chat_ask verwenden." + ShellHints.CHAT)
    @ToolHints(destructive = false)
    public String send(
            @ToolParam(description = "Nachricht, Markdown erlaubt (wird als HTML formatiert)") String message,
            @ToolParam(required = false, description = CONVERSATION_PARAM + " bzw. die von replyTo") String conversation,
            @ToolParam(required = false, description = PROVIDER_PARAM) String provider,
            @ToolParam(required = false, description = "ID der Nachricht, auf die geantwortet wird") String replyTo,
            @ToolParam(required = false, description = "true = im Thread von replyTo antworten (nur Matrix)") Boolean thread) {
        Target t = env.target(provider, conversation, replyTo);
        ChatSystem.Sent sent = sendMessage(t, message, replyTo, Boolean.TRUE.equals(thread));
        return "Gesendet in " + t.entry().system().label(t.conversationId()) + " (id " + sent.messageId() + ")."
                + (sent.note() == null ? "" : "\nHinweis: " + sent.note());
    }

    @Tool(name = "ask", description = "Stellt dem Nutzer per Chat eine Frage und wartet auf die Antwort (blockiert bis "
            + "waitSeconds). Für Rückfragen, Entscheidungen und Freigaben, wenn der Nutzer per Chat erreichbar sein will. "
            + "Als Antwort zählt eine Antwort/Thread-Nachricht auf die Frage, sonst die erste Nachricht eines "
            + "freigegebenen Absenders danach. Ohne Antwort bis zum Ablauf: später mit chat_receive nachsehen."
            + ShellHints.CHAT)
    @ToolHints(destructive = false)
    public String ask(
            @ToolParam(description = "Frage, Markdown erlaubt; Antwortmöglichkeiten ggf. nennen") String question,
            @ToolParam(required = false, description = CONVERSATION_PARAM) String conversation,
            @ToolParam(required = false, description = PROVIDER_PARAM) String provider,
            @ToolParam(required = false, description = "Höchstens so lange warten (Sekunden); leer = Standard aus der App") Integer waitSeconds,
            @ToolParam(required = false, description = "ID einer Nachricht, auf die sich die Frage bezieht (wird als Antwort darauf gesendet)") String replyTo) {
        Target t = env.target(provider, conversation, replyTo);
        ChatSystem system = t.entry().system();
        ChatInbox inbox = t.entry().inbox();
        // Stand holen, bevor die Frage rausgeht: ältere Nachrichten gelten so nicht als Antwort
        inbox.catchUp();
        ChatSystem.Sent sent = sendMessage(t, question, replyTo, false);
        String questionId = sent.messageId();
        int wait = system.canWait() ? env.waitSeconds(waitSeconds, env.askWaitSeconds()) : 0;
        long start = System.currentTimeMillis();
        String label = system.label(t.conversationId());
        List<Received> answer = ChatInbox.await(List.of(inbox), i -> i.takeAnswer(t.conversationId(), questionId),
                wait * 1000L, () -> ToolProgress.report("Warte auf Antwort in " + label + " … "
                        + (System.currentTimeMillis() - start) / 1000 + "/" + wait + " s"));
        StringBuilder sb = new StringBuilder();
        if (!answer.isEmpty()) {
            sb.append("Antwort:\n");
            answer.forEach(r -> sb.append(format(t.entry(), r.message(), false, false)).append("\n\n"));
            markRead(t.entry(), answer);
        } else if (!system.canWait()) {
            sb.append("Frage gesendet (id ").append(questionId).append(" in ").append(label).append("). Warten auf ")
                    .append("Antworten ist für ").append(t.entry().provider().displayName()).append(" abgeschaltet – die ")
                    .append("Antwort später mit chat_receive abholen.");
        } else {
            sb.append("Keine Antwort innerhalb von ").append(wait).append(" s (Frage: id ").append(questionId)
                    .append(" in ").append(label).append("). Eine spätere Antwort liefert chat_receive – sie ist dort ")
                    .append("als „Antwort auf ").append(questionId).append("“ erkennbar, wenn der Nutzer direkt antwortet.");
        }
        if (sent.note() != null) {
            sb.append("\nHinweis: ").append(sent.note());
        }
        int more = inbox.pendingCount(m -> true);
        if (more > 0) {
            sb.append("\n").append(more).append(" weitere ungelesene Nachricht(en) – chat_receive.");
        }
        appendNotices(sb, inbox);
        return Text.limitLines(sb.toString().strip(), env.maxLines());
    }

    @Tool(name = "receive", description = "Neue Chat-Nachrichten seit dem letzten Abruf (Anweisungen, Antworten), "
            + "optional mit Warten, bis etwas eingeht. Jede Nachricht wird nur einmal geliefert. Soll auf Anweisungen "
            + "gewartet werden, mit waitSeconds in einer Schleife aufrufen." + ShellHints.CHAT)
    @ToolHints(destructive = false)
    public String receive(
            @ToolParam(required = false, description = PROVIDER_PARAM + "; leer und ohne conversation = alle aktiven") String provider,
            @ToolParam(required = false, description = "Nur diese Unterhaltung (ID, Alias oder Name); leer = alle freigegebenen") String conversation,
            @ToolParam(required = false, description = "Warten, bis mindestens eine Nachricht da ist (Sekunden); leer/0 = nicht warten") Integer waitSeconds,
            @ToolParam(required = false, description = "Höchstens so viele Nachrichten (Standard 20, max. 50)") Integer limit) {
        String conversationId = null;
        List<Entry> entries;
        if (conversation != null && !conversation.isBlank()) {
            Target t = env.target(provider, conversation, null);
            entries = List.of(t.entry());
            conversationId = t.conversationId();
        } else if (provider != null && !provider.isBlank()) {
            entries = List.of(env.entry(provider, null, null));
        } else {
            entries = env.entries();
            if (entries.isEmpty()) {
                env.entry(null, null, null); // wirft mit Hinweis
            }
        }
        StringBuilder errors = new StringBuilder();
        Map<ChatInbox, Entry> inboxes = new LinkedHashMap<>();
        for (Entry e : entries) {
            try {
                inboxes.put(e.inbox(), e);
            } catch (RuntimeException ex) {
                if (entries.size() == 1) {
                    throw ex;
                }
                errors.append("- ").append(e.provider().displayName()).append(" nicht verfügbar: ")
                        .append(ex.getMessage()).append('\n');
            }
        }
        String only = conversationId;
        Predicate<ChatSystem.Message> filter = m -> only == null || m.conversationId().equals(only);
        AtomicInteger left = new AtomicInteger(Math.max(1, Math.min(MAX_RECEIVE, limit == null ? 20 : limit)));
        int wait = env.waitSeconds(waitSeconds, 0);
        long start = System.currentTimeMillis();
        List<Received> messages = ChatInbox.await(List.copyOf(inboxes.keySet()), i -> {
            List<Received> taken = i.take(filter, left.get());
            left.addAndGet(-taken.size());
            return taken;
        }, wait * 1000L, () -> ToolProgress.report("Warte auf Nachrichten … "
                + (System.currentTimeMillis() - start) / 1000 + "/" + wait + " s"));
        StringBuilder sb = new StringBuilder();
        if (messages.isEmpty()) {
            sb.append(wait > 0 ? "Keine neuen Nachrichten innerhalb von " + wait + " s." : "Keine neuen Nachrichten.");
        } else {
            sb.append("Neue Nachrichten (").append(messages.size()).append("):\n\n");
            for (Received r : messages) {
                sb.append(format(inboxes.get(r.inbox()), r.message(), true, false)).append("\n\n");
            }
            inboxes.forEach((inbox, e) -> markRead(e, messages.stream().filter(r -> r.inbox() == inbox).toList()));
        }
        int more = inboxes.keySet().stream().mapToInt(i -> i.pendingCount(filter)).sum();
        if (more > 0) {
            sb.append("\n").append(more).append(" weitere – erneut chat_receive aufrufen.");
        }
        for (Entry e : inboxes.values()) {
            if (!e.system().canWait() && wait > 0) {
                sb.append("\n").append(e.provider().displayName()).append(": einmal abgerufen, Warten ist abgeschaltet.");
            }
        }
        if (!errors.isEmpty()) {
            sb.append("\n\n").append(errors.toString().strip());
        }
        inboxes.keySet().forEach(i -> appendNotices(sb, i));
        return Text.limitLines(sb.toString().strip(), env.maxLines());
    }

    @Tool(name = "history", description = "Die letzten Nachrichten einer Chat-Unterhaltung (Kontext, Vorgeschichte, eigene "
            + "Nachrichten). Ändert nichts daran, was chat_receive als neu liefert." + ShellHints.CHAT)
    @ToolHints(readOnly = true)
    public String history(
            @ToolParam(required = false, description = CONVERSATION_PARAM) String conversation,
            @ToolParam(required = false, description = PROVIDER_PARAM) String provider,
            @ToolParam(required = false, description = "Anzahl (Standard 20, max. 50)") Integer limit) {
        Target t = env.target(provider, conversation, null);
        ChatSystem system = t.entry().system();
        ChatInbox inbox = t.entry().inbox();
        int n = Math.max(1, Math.min(50, limit == null ? 20 : limit));
        List<String> lines = new ArrayList<>();
        int hidden = 0;
        for (ChatSystem.Message m : system.history(t.conversationId(), n)) {
            inbox.noteMessage(t.conversationId(), m.id());
            boolean own = inbox.own(m);
            if (!own && !m.trusted()) {
                hidden++;
                continue;
            }
            lines.add(format(t.entry(), m, false, own));
        }
        StringBuilder sb = new StringBuilder("Verlauf ").append(system.label(t.conversationId())).append(" – ")
                .append(t.conversationId()).append(" (älteste zuerst):\n\n");
        sb.append(lines.isEmpty() ? "(keine Nachrichten)" : String.join("\n\n", lines));
        if (hidden > 0) {
            sb.append("\n\n").append(hidden).append(" Nachricht(en) nicht freigegebener Absender ausgeblendet.");
        }
        return Text.limitLines(sb.toString().strip(), env.maxLines());
    }

    @Tool(name = "react", description = "Reagiert mit einem Emoji auf eine Chat-Nachricht – z.B. 👀 beim Beginnen einer "
            + "Anweisung und ✅ wenn erledigt, ohne eigene Nachricht." + ShellHints.CHAT)
    @ToolHints(destructive = false, idempotent = true)
    public String react(
            @ToolParam(description = "ID der Nachricht (aus chat_receive/chat_history)") String messageId,
            @ToolParam(description = "Emoji, z.B. 👍, ✅, 👀") String reaction,
            @ToolParam(required = false, description = CONVERSATION_PARAM + " bzw. die der Nachricht") String conversation,
            @ToolParam(required = false, description = PROVIDER_PARAM) String provider) {
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("'messageId' fehlt (aus chat_receive/chat_history).");
        }
        if (reaction == null || reaction.isBlank()) {
            throw new IllegalArgumentException("'reaction' fehlt, z.B. ✅.");
        }
        Target t = env.target(provider, conversation, messageId);
        t.entry().system().react(t.conversationId(), messageId.strip(), reaction.strip());
        return "Reaktion " + reaction.strip() + " auf " + messageId.strip() + " gesendet.";
    }

    // ------------------------------------------------------------------ intern

    private ChatSystem.Sent sendMessage(Target t, String message, String replyTo, boolean thread) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("Leere Nachricht – Text angeben.");
        }
        String reply = replyTo == null || replyTo.isBlank() ? null : replyTo.strip();
        ChatSystem system = t.entry().system();
        if (thread && reply == null) {
            throw new IllegalArgumentException("'thread' braucht 'replyTo' (ID einer Nachricht im Thread).");
        }
        if (thread && !system.supportsThreads()) {
            throw new IllegalArgumentException(t.entry().provider().displayName() + " kennt keine Threads – ohne "
                    + "'thread' als Antwort senden.");
        }
        String text = env.prefix().isEmpty() ? message.strip() : env.prefix().strip() + " " + message.strip();
        ChatInbox inbox = t.entry().inbox();
        ChatSystem.Sent sent = system.send(t.conversationId(),
                new ChatSystem.Outgoing(text, ChatMarkdown.html(text), reply, thread));
        inbox.sent(t.conversationId(), sent.messageId());
        return sent;
    }

    /** Lesebestätigung je Unterhaltung bis zur letzten gelieferten Nachricht – der Nutzer sieht so, dass sie ankam. */
    private void markRead(Entry e, List<Received> messages) {
        if (!env.readReceipts() || messages.isEmpty()) {
            return;
        }
        Map<String, String> last = new LinkedHashMap<>();
        messages.forEach(r -> last.put(r.message().conversationId(), r.message().id()));
        last.forEach((conversationId, messageId) -> {
            try {
                e.system().markRead(conversationId, messageId);
            } catch (RuntimeException ex) {
                // Komfort – die Nachricht ist zugestellt
            }
        });
    }

    /**
     * Kopfzeile und Text, z.B. {@code [2026-10-04 17:22:05] @felix:example.org in „DevTools“ (id $abc, Antwort auf $xyz):}.
     */
    String format(Entry e, ChatSystem.Message m, boolean withConversation, boolean own) {
        StringBuilder sb = new StringBuilder();
        if (env.multiple()) {
            sb.append('[').append(e.id()).append("] ");
        }
        sb.append('[').append(m.timestamp() <= 0 ? "?" : TIME.format(Instant.ofEpochMilli(m.timestamp()))).append("] ");
        sb.append(m.senderName() == null || m.senderName().equals(m.sender()) ? m.sender()
                : m.senderName() + " <" + m.sender() + ">");
        if (own) {
            sb.append(" (ich)");
        } else if (m.fromMe()) {
            sb.append(" (gleiches Konto)");
        }
        if (withConversation) {
            sb.append(" in ").append(e.system().label(m.conversationId()));
        }
        sb.append(" (id ").append(m.id());
        if (m.replyTo() != null) {
            sb.append(", Antwort auf ").append(m.replyTo());
        }
        if (m.threadRoot() != null) {
            sb.append(", Thread ").append(m.threadRoot());
        }
        if (m.editOf() != null) {
            sb.append(", bearbeitet ").append(m.editOf());
        }
        return sb.append("):\n").append(m.text().strip()).toString();
    }

    private static void appendNotices(StringBuilder sb, ChatInbox inbox) {
        List<String> notices = inbox.drainNotices();
        if (!notices.isEmpty()) {
            sb.append("\n\nHinweise:\n");
            notices.forEach(n -> sb.append("- ").append(n).append('\n'));
        }
    }
}
