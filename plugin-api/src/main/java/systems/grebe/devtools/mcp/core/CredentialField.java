package systems.grebe.devtools.mcp.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Geheimnis eines Moduls, das aus den zentralen Zugangsdaten kommen darf (Modul „Zugangsdaten“, wie beim Git
 * Credential Manager): Bleibt das Feld leer, setzt die App das Token bzw. Passwort des Eintrags ein, dessen URL zur
 * Server-URL des Moduls passt – und, falls auch leer, den Benutzer.
 *
 * <pre>{@code
 * CredentialField.of("baseUrl", "user", "token")
 * CredentialField.of(null, null, "apiKey").withDefaultUrl("https://api.anthropic.com")
 * }</pre>
 *
 * @param urlKey     Feld mit der Server-URL, {@code null} = immer {@code defaultUrl}
 * @param userKey    Feld für den Benutzer, {@code null} = keins
 * @param secretKey  Feld des Geheimnisses ({@link FieldType#SECRET})
 * @param defaultUrl URL, wenn das URL-Feld leer ist und keinen Default hat; {@code null} = dann keine Zugangsdaten
 */
public record CredentialField(String urlKey, String userKey, String secretKey, String defaultUrl) {

    /** Feldnamen (ohne Gruppen-Präfix), die {@link #derive} als Benutzer erkennt. */
    private static final Set<String> USER_KEYS = Set.of("user", "username", "email", "login");

    public CredentialField {
        Objects.requireNonNull(secretKey, "secretKey");
    }

    public static CredentialField of(String urlKey, String userKey, String secretKey) {
        return new CredentialField(urlKey, userKey, secretKey, null);
    }

    public CredentialField withDefaultUrl(String url) {
        return new CredentialField(urlKey, userKey, secretKey, url);
    }

    /**
     * Leitet die Zuordnung aus dem Schema ab – Standard von {@link ToolModule#credentialFields()}: je
     * {@link ConfigGroup Gruppe} (und für die Felder ohne Gruppe) mit genau einem {@link FieldType#URL}-Feld gehört das
     * erste Geheimnis zu dieser URL, dazu das erste Textfeld namens {@code user}, {@code username}, {@code email} oder
     * {@code login}. Bereiche mit mehreren URLs bleiben außen vor – dort muss das Modul selbst zuordnen.
     */
    public static List<CredentialField> derive(List<ConfigField> schema) {
        Map<String, List<ConfigField>> scopes = new LinkedHashMap<>();
        for (ConfigField f : schema) {
            scopes.computeIfAbsent(f.group() == null ? "" : f.group().id(), k -> new ArrayList<>()).add(f);
        }
        List<CredentialField> out = new ArrayList<>();
        for (List<ConfigField> fields : scopes.values()) {
            List<ConfigField> urls = fields.stream().filter(f -> f.type() == FieldType.URL).toList();
            ConfigField secret = fields.stream().filter(f -> f.type() == FieldType.SECRET).findFirst().orElse(null);
            if (urls.size() != 1 || secret == null) {
                continue;
            }
            String user = fields.stream()
                    .filter(f -> f.type() == FieldType.STRING && USER_KEYS.contains(shortKey(f.key())))
                    .map(ConfigField::key).findFirst().orElse(null);
            out.add(of(urls.getFirst().key(), user, secret.key()));
        }
        return List.copyOf(out);
    }

    private static String shortKey(String key) {
        return key.substring(key.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }
}
