package systems.grebe.devtools.mcp.modules.share;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Was zwischen den Instanzen über den Broker geht: Angebote (Nutzer 1 sendet), Antworten darauf (Nutzer 2 nimmt an
 * oder lehnt ab) und die Anwesenheit einer Instanz. Alles als JSON; mit gemeinsamem Schlüssel des Teams zusätzlich
 * mit AES-GCM verschlüsselt und damit auch gegen untergeschobene Absender geschützt ({@link Codec}).
 */
final class ShareMessages {

    static final int VERSION = 1;
    static final String OFFER = "offer";
    static final String RECEIPT = "receipt";

    private ShareMessages() {
    }

    /** Hülle jeder Nachricht im Eingang: genau eines von {@code offer} und {@code receipt}. */
    record Wire(String type, Offer offer, Receipt receipt) {

        static Wire of(Offer o) {
            return new Wire(OFFER, o, null);
        }

        static Wire of(Receipt r) {
            return new Wire(RECEIPT, null, r);
        }
    }

    /**
     * Ein Angebot: Kontext (Titel, Notiz), Memories, Skills und Dateien von {@code from} an {@code to}.
     *
     * @param instance Instanz des Absenders – Rückmeldungen und eigene Nachrichten erkennen
     * @param sent     Zeitpunkt (ISO-8601)
     */
    record Offer(int v, String id, String from, String fromName, String instance, String to, String sent,
                 String title, String note, List<MemoryItem> memories, List<SkillItem> skills,
                 List<FileItem> files) {

        Offer {
            memories = memories == null ? List.of() : List.copyOf(memories);
            skills = skills == null ? List.of() : List.copyOf(skills);
            files = files == null ? List.of() : List.copyOf(files);
        }

        /** Kurzfassung des Inhalts, z.B. „Notiz, 2 Memories, 1 Skill, 1 Datei“. */
        String summary() {
            StringBuilder sb = new StringBuilder();
            if (note != null && !note.isBlank()) {
                sb.append("Notiz");
            }
            count(sb, memories.size(), "Memory", "Memories");
            count(sb, skills.size(), "Skill", "Skills");
            count(sb, files.size(), "Datei", "Dateien");
            return sb.isEmpty() ? "nur Titel" : sb.toString();
        }

        /** Absender mit Namen, z.B. {@code Felix Grebe <felix@example.com>}. */
        String sender() {
            return fromName == null || fromName.isBlank() ? from : fromName + " <" + from + ">";
        }

        /** Ohne die Inhalte (nach Annehmen oder Ablehnen nicht mehr nötig). */
        Offer withoutContent() {
            return new Offer(v, id, from, fromName, instance, to, sent, title, null,
                    memories.stream().map(m -> new MemoryItem(m.title(), null, null, null, null, null)).toList(),
                    skills.stream().map(s -> new SkillItem(s.name(), s.description(), null, null, null, null, null))
                            .toList(),
                    files.stream().map(f -> new FileItem(f.name(), f.size(), null)).toList());
        }

        private static void count(StringBuilder sb, int n, String one, String many) {
            if (n > 0) {
                sb.append(sb.isEmpty() ? "" : ", ").append(n).append(' ').append(n == 1 ? one : many);
            }
        }
    }

    record MemoryItem(String title, String content, String project, String skill, String reference,
                      List<String> tags) {
    }

    record SkillItem(String name, String description, String content, String category, List<String> tags,
                     List<String> triggers, List<SkillFile> files) {

        SkillItem {
            files = files == null ? List.of() : List.copyOf(files);
        }
    }

    /**
     * Zusatzdatei eines Skills: Text in {@code content} oder – bei einem Anhang – Inhalt Base64-kodiert in {@code data}
     * (ältere Instanzen kennen nur Text und übergehen {@code data}).
     */
    record SkillFile(String path, String content, String data, String mediaType) {

        SkillFile(String path, String content) {
            this(path, content, null, null);
        }
    }

    /** @param data Inhalt Base64-kodiert */
    record FileItem(String name, long size, String data) {
    }

    /** Antwort des Empfängers an den Absender des Angebots {@code offer}. */
    record Receipt(int v, String offer, String from, String fromName, String instance, String to, boolean accepted,
                   String comment, String sent) {

        String sender() {
            return fromName == null || fromName.isBlank() ? from : fromName + " <" + from + ">";
        }
    }

    /** Anwesenheit einer Instanz (retained, je Adresse und Instanz). */
    record Presence(String address, String name, String device, String instance, boolean online, String since) {
    }

    /** Adresse in der Form, die in Topics und Vergleichen gilt: klein, ohne Zeichen mit Sonderbedeutung in MQTT. */
    static String address(String raw) {
        return ShareTopics.address(raw);
    }

    /**
     * Liest und schreibt Nachrichten; mit Schlüssel verschlüsselt ({@code DTS1}, 12 Byte IV, AES-256-GCM). Mit
     * Schlüssel werden unverschlüsselte Nachrichten verworfen, ohne Schlüssel verschlüsselte – jeweils mit
     * {@link IllegalArgumentException}.
     */
    static final class Codec {

        private static final JsonMapper JSON = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        private static final byte[] MAGIC = "DTS1".getBytes(StandardCharsets.US_ASCII);
        private static final int IV_BYTES = 12;
        private static final int TAG_BITS = 128;
        private static final SecureRandom RANDOM = new SecureRandom();

        private final SecretKey key;

        private Codec(SecretKey key) {
            this.key = key;
        }

        static final Codec PLAIN = new Codec(null);

        /**
         * Schlüssel aus dem gemeinsamen Passwort des Teams (PBKDF2, Salz aus dem Topic-Präfix – wer dasselbe Präfix
         * und Passwort hat, liest mit); leer = unverschlüsselt.
         */
        static Codec of(String teamKey, String topicPrefix) {
            if (teamKey == null || teamKey.isEmpty()) {
                return PLAIN;
            }
            try {
                byte[] salt = ("devtools-mcp/share/" + topicPrefix).getBytes(StandardCharsets.UTF_8);
                PBEKeySpec spec = new PBEKeySpec(teamKey.toCharArray(), salt, 210_000, 256);
                byte[] raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
                spec.clearPassword();
                return new Codec(new SecretKeySpec(raw, "AES"));
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("Schlüssel nicht ableitbar: " + e.getMessage(), e);
            }
        }

        boolean encrypted() {
            return key != null;
        }

        byte[] write(Object message) {
            byte[] json = JSON.writeValueAsBytes(message);
            if (key == null) {
                return json;
            }
            try {
                byte[] iv = new byte[IV_BYTES];
                RANDOM.nextBytes(iv);
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
                c.updateAAD(MAGIC);
                byte[] sealed = c.doFinal(json);
                return ByteBuffer.allocate(MAGIC.length + IV_BYTES + sealed.length).put(MAGIC).put(iv).put(sealed)
                        .array();
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("Verschlüsseln fehlgeschlagen: " + e.getMessage(), e);
            }
        }

        <T> T read(byte[] payload, Class<T> type) {
            boolean sealed = payload.length > MAGIC.length + IV_BYTES
                    && Arrays.equals(payload, 0, MAGIC.length, MAGIC, 0, MAGIC.length);
            if (key == null) {
                if (sealed) {
                    throw new IllegalArgumentException("verschlüsselt, aber kein Team-Schlüssel eingetragen");
                }
                return JSON.readValue(payload, type);
            }
            if (!sealed) {
                throw new IllegalArgumentException("unverschlüsselt, obwohl ein Team-Schlüssel eingetragen ist");
            }
            try {
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, payload, MAGIC.length, IV_BYTES));
                c.updateAAD(MAGIC);
                int offset = MAGIC.length + IV_BYTES;
                return JSON.readValue(c.doFinal(payload, offset, payload.length - offset), type);
            } catch (GeneralSecurityException e) {
                throw new IllegalArgumentException("nicht entschlüsselbar – anderer Team-Schlüssel?");
            }
        }
    }
}
