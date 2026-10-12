package systems.grebe.devtools.mcp.oauth;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Angaben zu einem Autorisierungsserver von Hand – für Server ohne Discovery (Jira Data Center, OpenProject, …) oder
 * mit Besonderheiten. Text {@code name=wert}, getrennt durch {@code ;} oder Zeilenumbrüche:
 *
 * <ul>
 *   <li>{@code authorization_endpoint}, {@code token_endpoint}, {@code device_authorization_endpoint} – Endpunkte wie in
 *       den Metadaten nach RFC 8414; mit {@code token_endpoint} entfällt die Discovery.</li>
 *   <li>{@code redirect_uri} – feste Rückleitung an die App, z.B. {@code http://127.0.0.1:53682/callback}, für Server, die
 *       die Redirect-URI samt Port exakt vergleichen (RFC 8252 erlaubt den Port frei, nicht jeder Server hält sich
 *       daran). Nur Loopback-Adressen über http.</li>
 *   <li>alles andere – weitere Parameter der Anmeldung (Autorisierungs- bzw. Geräteanfrage), z.B. {@code audience=…}
 *       oder {@code prompt=consent}.</li>
 * </ul>
 */
public record OAuthOptions(String authorizationEndpoint, String tokenEndpoint, String deviceAuthorizationEndpoint,
                           String redirectUri, Map<String, String> parameters) {

    public static final OAuthOptions NONE = new OAuthOptions(null, null, null, null, Map.of());

    private static final List<String> LOOPBACK = List.of("127.0.0.1", "localhost", "[::1]");

    public OAuthOptions {
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }

    /**
     * Liest den Text der Zugangsdaten.
     *
     * @throws IllegalArgumentException bei einer Angabe ohne {@code =}, einem ungültigen Endpunkt oder einer
     *                                  {@code redirect_uri}, die keine Loopback-Adresse ist
     */
    public static OAuthOptions parse(String text) {
        if (text == null || text.isBlank()) {
            return NONE;
        }
        String auth = null;
        String token = null;
        String device = null;
        String redirect = null;
        Map<String, String> params = new LinkedHashMap<>();
        for (String part : text.split("[;\\n\\r]+")) {
            String p = part.strip();
            if (p.isEmpty()) {
                continue;
            }
            int eq = p.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("OAuth-Option „" + p + "“: erwartet name=wert.");
            }
            String name = p.substring(0, eq).strip();
            String value = p.substring(eq + 1).strip();
            switch (name) {
                case "authorization_endpoint" -> auth = endpoint(name, value);
                case "token_endpoint" -> token = endpoint(name, value);
                case "device_authorization_endpoint" -> device = endpoint(name, value);
                case "redirect_uri" -> redirect = loopback(value);
                default -> params.put(name, value);
            }
        }
        return new OAuthOptions(auth, token, device, redirect, params);
    }

    /** Ob die Endpunkte von Hand angegeben sind – dann ohne Discovery. */
    public boolean manualEndpoints() {
        return tokenEndpoint != null;
    }

    /** Server aus den Angaben von Hand. */
    public OAuthServer server(String issuer) {
        return new OAuthServer(issuer, authorizationEndpoint, tokenEndpoint, deviceAuthorizationEndpoint, List.of());
    }

    private static String endpoint(String name, String value) {
        try {
            URI uri = URI.create(value);
            if (uri.getHost() != null && ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))) {
                return value;
            }
        } catch (IllegalArgumentException e) {
            // unten
        }
        throw new IllegalArgumentException("OAuth-Option " + name + ": keine http(s)-URL: " + value);
    }

    private static String loopback(String value) {
        try {
            URI uri = URI.create(value);
            if ("http".equals(uri.getScheme()) && LOOPBACK.contains(uri.getHost()) && uri.getPort() > 0) {
                return value;
            }
        } catch (IllegalArgumentException e) {
            // unten
        }
        throw new IllegalArgumentException("OAuth-Option redirect_uri: nur http://127.0.0.1:<port>/… (Loopback mit "
                + "Port, RFC 8252) – nicht " + value);
    }
}
