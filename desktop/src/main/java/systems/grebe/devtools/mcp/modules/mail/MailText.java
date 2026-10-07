package systems.grebe.devtools.mcp.modules.mail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.mail.Address;
import jakarta.mail.BodyPart;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeUtility;

/** Darstellung von Mails für das LLM: Adressen, Datum, Text (HTML als Text) und Anhänge. */
final class MailText {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());
    private static final Pattern DROP = Pattern.compile("(?is)<(script|style|head|title)\\b.*?</\\1\\s*>|<!--.*?-->");
    private static final Pattern BREAK = Pattern.compile("(?i)<\\s*(br|/p|/div|/tr|/li|/h[1-6]|/table|hr)\\b[^>]*>");
    private static final Pattern ITEM = Pattern.compile("(?i)<\\s*li\\b[^>]*>");
    private static final Pattern CELL = Pattern.compile("(?i)</\\s*t[dh]\\s*>");
    private static final Pattern LINK = Pattern.compile("(?is)<a\\b[^>]*href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</a\\s*>");
    private static final Pattern TAG = Pattern.compile("(?s)<[^>]*>");
    private static final Pattern ENTITY = Pattern.compile("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);");

    /** Ein Anhang (oder eingebetteter Teil mit Dateinamen). */
    record Attachment(String name, String type, int size) {
    }

    /** Text und Anhänge einer Mail. */
    record Content(String text, boolean html, List<Attachment> attachments) {
    }

    private MailText() {
    }

    static String time(Date d) {
        return d == null ? "?" : TIME.format(d.toInstant());
    }

    static String addresses(Address[] list) {
        if (list == null || list.length == 0) {
            return "";
        }
        List<String> out = new ArrayList<>();
        for (Address a : list) {
            out.add(a instanceof InternetAddress ia ? ia.toUnicodeString() : a.toString());
        }
        return String.join(", ", out);
    }

    /** Erste Absenderadresse (nur die Adresse, klein geschrieben) oder leer. */
    static String senderAddress(Message m) throws MessagingException {
        Address[] from = m.getFrom();
        if (from != null && from.length > 0 && from[0] instanceof InternetAddress ia && ia.getAddress() != null) {
            return ia.getAddress().toLowerCase(Locale.ROOT);
        }
        return "";
    }

    static String subject(Message m) throws MessagingException {
        String s = m.getSubject();
        return s == null || s.isBlank() ? "(ohne Betreff)" : s.strip();
    }

    // ------------------------------------------------------------------ Inhalt

    static Content content(Part message) throws MessagingException, IOException {
        Collector c = new Collector();
        c.walk(message, false);
        String text = c.plain.isEmpty() && !c.html.isEmpty() ? htmlToText(String.join("\n\n", c.html))
                : String.join("\n\n", c.plain);
        return new Content(text.strip(), c.plain.isEmpty() && !c.html.isEmpty(), c.attachments);
    }

    private static final class Collector {
        final List<String> plain = new ArrayList<>();
        final List<String> html = new ArrayList<>();
        final List<Attachment> attachments = new ArrayList<>();

        void walk(Part p, boolean inAlternative) throws MessagingException, IOException {
            String filename = filename(p);
            boolean attachment = Part.ATTACHMENT.equalsIgnoreCase(p.getDisposition()) || filename != null
                    && !p.isMimeType("multipart/*");
            if (attachment) {
                attachments.add(new Attachment(filename == null ? "(ohne Namen)" : filename, baseType(p), p.getSize()));
                return;
            }
            if (p.isMimeType("multipart/alternative")) {
                Multipart mp = (Multipart) p.getContent();
                // die einfachste Darstellung genügt: text/plain, sonst HTML, sonst was es gibt
                BodyPart best = null;
                for (int i = 0; i < mp.getCount(); i++) {
                    BodyPart b = mp.getBodyPart(i);
                    if (b.isMimeType("text/plain")) {
                        best = b;
                        break;
                    }
                    if (best == null || b.isMimeType("text/html")) {
                        best = b;
                    }
                }
                if (best != null) {
                    walk(best, true);
                }
            } else if (p.isMimeType("multipart/*")) {
                Multipart mp = (Multipart) p.getContent();
                for (int i = 0; i < mp.getCount(); i++) {
                    walk(mp.getBodyPart(i), inAlternative);
                }
            } else if (p.isMimeType("message/rfc822")) {
                attachments.add(new Attachment("weitergeleitete Mail", "message/rfc822", p.getSize()));
            } else if (p.isMimeType("text/plain")) {
                plain.add(text(p));
            } else if (p.isMimeType("text/html")) {
                if (plain.isEmpty()) {
                    html.add(text(p));
                }
            } else {
                attachments.add(new Attachment("(eingebettet)", baseType(p), p.getSize()));
            }
        }
    }

    private static String filename(Part p) {
        try {
            String name = p.getFileName();
            return name == null ? null : MimeUtility.decodeText(name);
        } catch (MessagingException | IOException | RuntimeException e) {
            return null;
        }
    }

    private static String baseType(Part p) throws MessagingException {
        String t = p.getContentType();
        if (t == null) {
            return "application/octet-stream";
        }
        int semi = t.indexOf(';');
        return (semi < 0 ? t : t.substring(0, semi)).strip().toLowerCase(Locale.ROOT);
    }

    /** Text eines Teils; unbekannte Zeichensätze als UTF-8 statt Fehler. */
    private static String text(Part p) throws MessagingException, IOException {
        try {
            Object c = p.getContent();
            if (c instanceof String s) {
                return s;
            }
        } catch (IOException e) {
            // z.B. UnsupportedEncodingException für exotische Zeichensätze – unten roh lesen
        }
        try (InputStream in = p.getInputStream()) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            in.transferTo(buf);
            return buf.toString(StandardCharsets.UTF_8);
        }
    }

    // ------------------------------------------------------------------ HTML

    static String htmlToText(String html) {
        String s = DROP.matcher(html).replaceAll("");
        Matcher links = LINK.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (links.find()) {
            String label = TAG.matcher(links.group(2)).replaceAll("").strip();
            String href = links.group(1).strip();
            String repl = label.isEmpty() || label.equals(href) || href.startsWith("mailto:") ? label.isEmpty() ? href : label
                    : label + " (" + href + ")";
            links.appendReplacement(sb, Matcher.quoteReplacement(repl));
        }
        links.appendTail(sb);
        s = BREAK.matcher(sb.toString()).replaceAll("\n");
        s = ITEM.matcher(s).replaceAll("\n- ");
        s = CELL.matcher(s).replaceAll("\t");
        s = TAG.matcher(s).replaceAll("");
        s = unescape(s);
        s = s.replace('\u00a0', ' ').replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n{3,}", "\n\n");
        return s.strip();
    }

    private static String unescape(String s) {
        Matcher m = ENTITY.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String e = m.group(1);
            String r;
            try {
                if (e.startsWith("#x") || e.startsWith("#X")) {
                    r = Character.toString(Integer.parseInt(e.substring(2), 16));
                } else if (e.startsWith("#")) {
                    r = Character.toString(Integer.parseInt(e.substring(1)));
                } else {
                    r = switch (e.toLowerCase(Locale.ROOT)) {
                        case "amp" -> "&";
                        case "lt" -> "<";
                        case "gt" -> ">";
                        case "quot" -> "\"";
                        case "apos" -> "'";
                        case "nbsp" -> " ";
                        case "auml" -> e.equals("Auml") ? "Ä" : "ä";
                        case "ouml" -> e.equals("Ouml") ? "Ö" : "ö";
                        case "uuml" -> e.equals("Uuml") ? "Ü" : "ü";
                        case "szlig" -> "ß";
                        case "euro" -> "€";
                        case "ndash" -> "–";
                        case "mdash" -> "—";
                        case "hellip" -> "…";
                        default -> m.group();
                    };
                }
            } catch (IllegalArgumentException ex) {
                r = m.group();
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(r));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Kürzt auf {@code max} Zeichen und sagt, wie viel fehlt. */
    static String limit(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "\n… (" + (text.length() - max) + " weitere Zeichen; mit maxChars mehr lesen)";
    }
}
