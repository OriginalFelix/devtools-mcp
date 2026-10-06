package systems.grebe.devtools.mcp.modules.chat.spi;

import java.util.List;
import java.util.function.Consumer;

/**
 * Ein angebundenes Chat-System (ein Konto). Alle Methoden sind blockierend und werfen {@link IllegalStateException}
 * bzw. {@link IllegalArgumentException} mit einer für das LLM verständlichen Meldung (was ist falsch, was ist zu tun).
 *
 * <p>Freigaben (welche Unterhaltungen, welche Absender) wertet das System selbst aus, weil Kennungen je System anders
 * aussehen: {@link #resolve} lehnt nicht freigegebene Unterhaltungen ab, {@link #poll} liefert nur Nachrichten aus
 * freigegebenen und markiert Absender als {@link Message#trusted() freigegeben}. Was neu ist, welche Nachricht eine
 * Antwort ist und wie lange gewartet wird, entscheidet das Modul.
 */
public interface ChatSystem {

    /** ID des Providers, der dieses System erzeugt hat. */
    String id();

    /** Eigenes Konto; meldet sich dafür ggf. an. */
    Account account();

    /**
     * Prüfung für „Verbindung testen“: Server, Anmeldung, Standard-Unterhaltung, Freigaben. Darf bei Fehlern werfen.
     *
     * @param defaultConversation konfigurierte Standard-Unterhaltung oder {@code null}
     */
    Status test(String defaultConversation);

    /** Unterhaltungen (Räume, Chats), in denen das Konto Mitglied ist – nur freigegebene. */
    List<Conversation> conversations();

    /**
     * Unterhaltung zu einer Angabe des LLM (ID, Alias, Name …). Wirft, wenn sie nicht existiert oder nicht freigegeben
     * ist.
     */
    String resolve(String ref);

    /** Anzeigename einer Unterhaltung, z.B. {@code „DevTools“}; die ID, wenn nichts bekannt ist. Darf zwischenspeichern. */
    String label(String conversationId);

    /** Ob die Angabe eindeutig eine Unterhaltung dieses Systems bezeichnet (Format der ID) – zur Wahl des Systems. */
    default boolean ownsConversation(String ref) {
        return false;
    }

    /** Ob die Kennung eindeutig eine Nachricht dieses Systems bezeichnet (Format der ID) – zur Wahl des Systems. */
    default boolean ownsMessage(String messageId) {
        return false;
    }

    /** Sendet eine Nachricht in eine (aufgelöste, freigegebene) Unterhaltung. */
    Sent send(String conversationId, Outgoing message);

    /**
     * Holt, was seit {@code cursor} eingegangen ist. Darf bis {@code timeoutMs} warten, bis etwas eintrifft
     * (Long-Polling oder wiederholtes Abfragen), und soll dann zurückkehren.
     *
     * @param cursor Stand des letzten Abrufs (vom System selbst geliefert) oder {@code null} beim allerersten Abruf –
     *               dann nur liefern, was der Nutzer noch nicht gelesen hat (bzw. nichts), keinen ganzen Verlauf
     */
    Poll poll(String cursor, int timeoutMs);

    /**
     * Ob {@link #poll} warten darf (Long-Polling oder erlaubtes wiederholtes Abfragen). Sonst ruft das Modul je
     * Tool-Aufruf nur einmal ab und wartet nicht auf Antworten.
     */
    default boolean canWait() {
        return true;
    }

    /** Die letzten {@code limit} Nachrichten einer Unterhaltung, älteste zuerst – auch eigene und nicht freigegebene. */
    List<Message> history(String conversationId, int limit);

    /** Reagiert auf eine Nachricht (Emoji). */
    default void react(String conversationId, String messageId, String reaction) {
        throw unsupported("Reaktionen");
    }

    /** Lesebestätigung bis einschließlich {@code messageId}; ohne Unterstützung wirkungslos. */
    default void markRead(String conversationId, String messageId) {
    }

    /** Ob {@link Outgoing#thread()} unterstützt wird. */
    default boolean supportsThreads() {
        return false;
    }

    /** Wessen Nachrichten das LLM erhält, für die Anzeige, z.B. „@felix:example.org“ oder „alle Mitglieder“. */
    default String senderPolicy() {
        return "alle Mitglieder";
    }

    /**
     * Interaktive Anmeldung (nur, wenn {@link ChatProvider#interactiveLogin()}), z.B. OAuth Device Code: meldet die
     * Anweisung für den Nutzer (URL und Code) über {@code prompt} und blockiert dann bis zum Abschluss. Ein
     * Thread-Interrupt bricht ab.
     *
     * @return Ergebnis für die Anzeige, z.B. „Angemeldet als …“
     */
    default String login(Consumer<String> prompt) {
        throw unsupported("Anmeldung im Browser");
    }

    /** Anmeldestatus für die Anzeige oder {@code null}. Muss schnell sein (keine Anfrage). */
    default String loginStatus() {
        return null;
    }

    static IllegalStateException unsupported(String what) {
        return new IllegalStateException(what + " werden von diesem Chat-System nicht unterstützt.");
    }

    // ------------------------------------------------------------------ Datentypen

    /** Ergebnis von {@link #test}: mehrzeilige Meldung; {@code ok = false} bei Problemen, die den Betrieb verhindern. */
    record Status(boolean ok, String message) {
    }

    /**
     * @param key stabile Kennung für den gespeicherten Abrufstand, z.B. Homeserver und Benutzer-ID
     */
    record Account(String id, String displayName, String key) {
    }

    /**
     * @param alias   zweite Bezeichnung (Matrix-Alias, Chat-Typ …) oder {@code null}
     * @param members Anzahl Mitglieder, negativ = unbekannt
     * @param note    Hinweis, z.B. „verschlüsselt – Nachrichten nicht lesbar“, oder {@code null}
     */
    record Conversation(String id, String name, String alias, int members, String note) {
    }

    /**
     * @param text    Markdown-Text, wie das LLM ihn geschrieben hat (inkl. Kennzeichnung)
     * @param html    derselbe Text als HTML oder {@code null}, wenn er keine Formatierung enthält
     * @param replyTo Nachricht, auf die geantwortet wird, oder {@code null}
     * @param thread  im Thread von {@code replyTo} antworten statt als Antwort in der Unterhaltung
     */
    record Outgoing(String text, String html, String replyTo, boolean thread) {
    }

    /**
     * @param note Hinweis für das LLM, z.B. dass der Raum verschlüsselt ist, oder {@code null}
     */
    record Sent(String messageId, String note) {
    }

    /**
     * Eine Nachricht.
     *
     * @param sender     Kennung des Absenders (Matrix-ID, E-Mail …)
     * @param senderName Anzeigename oder {@code null}
     * @param text       Klartext (Markdown/HTML des Systems bereits umgewandelt), Platzhalter für Dateien u.ä.
     * @param replyTo    Nachricht, auf die geantwortet wird, oder {@code null}
     * @param threadRoot Wurzel des Threads oder {@code null}
     * @param editOf     bei Bearbeitungen die ursprüngliche Nachricht, sonst {@code null}
     * @param own        sicher vom Modul selbst gesendet (z.B. an der Transaktions-ID erkannt)
     * @param fromMe     vom eigenen Konto – ohne {@code own} hat sie der Nutzer über dasselbe Konto geschrieben
     * @param trusted    Absender ist freigegeben (das eigene Konto immer)
     */
    record Message(String id, String conversationId, String sender, String senderName, long timestamp, String text,
                   String replyTo, String threadRoot, String editOf, boolean own, boolean fromMe, boolean trusted) {

        /** Ob die Nachricht auf {@code messageId} antwortet (direkt oder im Thread dieser Nachricht). */
        public boolean answers(String messageId) {
            return messageId.equals(replyTo) || messageId.equals(threadRoot);
        }
    }

    /**
     * Ergebnis eines Abrufs.
     *
     * @param messages neue Nachrichten in Eingangsreihenfolge, auch eigene
     * @param notices  Hinweise für das LLM (angenommene Einladungen, Lücken …)
     * @param cursor   neuer Stand für den nächsten Abruf
     */
    record Poll(List<Message> messages, List<String> notices, String cursor) {
    }
}
