package systems.grebe.devtools.mcp.core;

import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Die Einträge des Moduls {@link CredentialsModule „Zugangsdaten“} und ihre Zuordnung zu Server-URLs – wie beim Git
 * Credential Manager: Schema und Port müssen stimmen, der Host gleich oder eine Subdomain sein ({@code github.com} gilt
 * für {@code api.github.com}), der Pfad des Eintrags ein Präfix. Der genaueste Eintrag gewinnt (gleicher Host vor
 * Subdomain, dann längerer Pfad, dann der weiter oben).
 */
public final class Credentials {

    /**
     * Ein hinterlegter Eintrag; {@code user} ist leer, wenn keiner angegeben ist.
     *
     * @param oauth {@code secret} ist ein OAuth-Access-Token (Anmeldung im Browser)
     */
    public record Entry(String name, String url, String user, String secret, boolean oauth) {
    }

    public static final Credentials NONE = new Credentials(List.of());

    private final List<Entry> entries;

    private Credentials(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    /** Aus der Konfiguration des Moduls „Zugangsdaten“; Einträge ohne URL oder Geheimnis zählen nicht. */
    public static Credentials of(ModuleConfig config) {
        return new Credentials(config.getRecords(CredentialsModule.ENTRIES).stream()
                .map(r -> new Entry(r.getOrDefault(CredentialsModule.NAME, ""), r.getOrDefault(CredentialsModule.URL, ""),
                        r.getOrDefault(CredentialsModule.USER, "").trim(), r.getOrDefault(CredentialsModule.SECRET, ""),
                        CredentialsModule.AUTH_OAUTH.equals(r.get(CredentialsModule.AUTH))))
                .filter(e -> !e.url().isBlank() && !e.secret().isBlank())
                .toList());
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** Der passende Eintrag für eine Server-URL. */
    public Optional<Entry> lookup(String url) {
        Target target = Target.parse(url);
        if (target == null) {
            return Optional.empty();
        }
        Entry best = null;
        int bestScore = -1;
        for (Entry e : entries) {
            int score = target.score(Target.parse(e.url()));
            if (score > bestScore) {
                best = e;
                bestScore = score;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Setzt in leere Geheimnisse des Moduls die passenden Zugangsdaten ein (und den Benutzer, falls der auch leer ist).
     * Eigene Werte des Moduls bleiben unangetastet. Ein OAuth-Access-Token bekommt die Markierung
     * {@link CredentialField#oauthKey} – Provider schicken es dann als Bearer.
     */
    public Map<String, String> apply(ToolModule module, Map<String, String> values) {
        if (entries.isEmpty()) {
            return values;
        }
        Map<String, String> out = null;
        for (Map.Entry<CredentialField, Entry> m : matches(module, values).entrySet()) {
            if (out == null) {
                out = new LinkedHashMap<>(values);
            }
            CredentialField f = m.getKey();
            out.put(f.secretKey(), m.getValue().secret());
            if (m.getValue().oauth()) {
                out.put(CredentialField.oauthKey(f.secretKey()), "true");
            }
            if (f.userKey() != null && blank(values.get(f.userKey())) && !m.getValue().user().isEmpty()) {
                out.put(f.userKey(), m.getValue().user());
            }
        }
        return out == null ? values : out;
    }

    /** Welche leeren Geheimnisse des Moduls aus welchem Eintrag kämen (für die Anzeige in der UI). */
    public Map<CredentialField, Entry> matches(ToolModule module, Map<String, String> values) {
        Map<CredentialField, Entry> out = new LinkedHashMap<>();
        if (entries.isEmpty()) {
            return out;
        }
        ModuleConfig config = ModuleConfig.of(module.configSchema(), values);
        for (CredentialField f : module.credentialFields()) {
            if (!blank(values.get(f.secretKey()))) {
                continue; // eigenes Geheimnis hat Vorrang
            }
            Optional<String> url = f.urlKey() == null ? Optional.empty() : config.get(f.urlKey());
            url.or(() -> Optional.ofNullable(f.defaultUrl())).flatMap(this::lookup).ifPresent(e -> out.put(f, e));
        }
        return out;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** Zerlegte http(s)-URL: Schema, Host, wirksamer Port, Pfadsegmente. */
    private record Target(String scheme, String host, int port, List<String> path) {

        static Target parse(String url) {
            if (url == null || url.isBlank()) {
                return null;
            }
            try {
                URI uri = URI.create(url.strip());
                String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
                if (uri.getHost() == null || !"http".equals(scheme) && !"https".equals(scheme)) {
                    return null;
                }
                int port = uri.getPort() != -1 ? uri.getPort() : "https".equals(scheme) ? 443 : 80;
                String path = uri.getPath() == null ? "" : uri.getPath();
                return new Target(scheme, uri.getHost().toLowerCase(Locale.ROOT), port,
                        Arrays.stream(path.split("/")).filter(s -> !s.isEmpty()).toList());
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        /** Wie gut der Eintrag zu dieser URL passt; -1 = gar nicht. */
        int score(Target entry) {
            if (entry == null || !scheme.equals(entry.scheme) || port != entry.port
                    || entry.path.size() > path.size() || !path.subList(0, entry.path.size()).equals(entry.path)) {
                return -1;
            }
            if (host.equals(entry.host)) {
                return 1000 + entry.path.size();
            }
            return host.endsWith("." + entry.host) ? entry.path.size() : -1;
        }
    }
}
