package systems.grebe.devtools.mcp.oauth;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Endpunkte eines Autorisierungsservers – aus der Discovery (RFC 8414 bzw. OpenID Connect Discovery) oder für GitHub,
 * das keine Discovery anbietet, fest vorgegeben.
 *
 * @param deviceAuthorizationEndpoint {@code null}, wenn der Server keinen Device Authorization Grant (RFC 8628) kann
 * @param authorizationEndpoint       {@code null}, wenn der Server keinen Authorization Code Grant kann
 * @param tokenAuthMethods            {@code token_endpoint_auth_methods_supported}; leer = nicht angegeben
 * @param authorizationParameters     Parameter, die der Server bei der Anmeldung zusätzlich verlangt (Atlassian:
 *                                    {@code audience})
 */
public record OAuthServer(String issuer, String authorizationEndpoint, String tokenEndpoint,
                          String deviceAuthorizationEndpoint, List<String> tokenAuthMethods,
                          Map<String, String> authorizationParameters) {

    /** Atlassian Cloud (Jira, Confluence): OAuth 2.0 (3LO) ohne Discovery und ohne Device Flow. */
    static final String ATLASSIAN = "https://auth.atlassian.com";

    public OAuthServer {
        tokenAuthMethods = tokenAuthMethods == null ? List.of() : List.copyOf(tokenAuthMethods);
        authorizationParameters = authorizationParameters == null ? Map.of() : Map.copyOf(authorizationParameters);
    }

    public OAuthServer(String issuer, String authorizationEndpoint, String tokenEndpoint,
                       String deviceAuthorizationEndpoint, List<String> tokenAuthMethods) {
        this(issuer, authorizationEndpoint, tokenEndpoint, deviceAuthorizationEndpoint, tokenAuthMethods, Map.of());
    }

    /**
     * Atlassian Cloud: Anmeldung mit {@code audience=api.atlassian.com} und {@code prompt=consent}; die Tokens gelten für
     * {@code https://api.atlassian.com/ex/jira/<cloudId>}. Atlassian verlangt ein Client-Geheimnis und vergleicht die
     * Callback-URL exakt – in den OAuth-Optionen eine feste {@code redirect_uri} angeben.
     */
    public static OAuthServer atlassian() {
        return new OAuthServer(ATLASSIAN, ATLASSIAN + "/authorize", ATLASSIAN + "/oauth/token", null,
                List.of("client_secret_post"), Map.of("audience", "api.atlassian.com", "prompt", "consent"));
    }

    /** Ob Aussteller bzw. URL zu Atlassian Cloud gehören ({@code auth.atlassian.com} bzw. {@code *.atlassian.net}). */
    public static boolean isAtlassian(String issuer, String url) {
        String host = host(issuer != null && !issuer.isBlank() ? issuer : url);
        return host != null && (host.equals("auth.atlassian.com") || host.endsWith(".atlassian.net"));
    }

    private static String host(String url) {
        try {
            String h = URI.create(url.strip()).getHost();
            return h == null ? null : h.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException | NullPointerException e) {
            return null;
        }
    }

    /**
     * GitHub (github.com und GitHub Enterprise Server): OAuth-Apps ohne Discovery, Endpunkte unter
     * {@code /login/…} der Web-Adresse.
     */
    public static OAuthServer github(String webBase) {
        String base = webBase.endsWith("/") ? webBase.substring(0, webBase.length() - 1) : webBase;
        return new OAuthServer(base, base + "/login/oauth/authorize", base + "/login/oauth/access_token",
                base + "/login/device/code", List.of("client_secret_post"), Map.of());
    }

    /**
     * Web-Adresse von GitHub zu einer Server-URL, falls es nach GitHub aussieht: {@code github.com} bzw.
     * {@code api.github.com} oder eine GitHub-Enterprise-API ({@code …/api/v3}); sonst {@code null}.
     */
    public static String githubWebBase(String url) {
        URI uri;
        try {
            uri = URI.create(url.strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
        String host = uri.getHost();
        if (host == null) {
            return null;
        }
        if (host.equalsIgnoreCase("github.com") || host.equalsIgnoreCase("api.github.com")) {
            return "https://github.com";
        }
        String path = uri.getPath() == null ? "" : uri.getPath();
        if (path.startsWith("/api/v3")) {
            return uri.getScheme() + "://" + uri.getRawAuthority();
        }
        return null;
    }

    /** Aussteller für die Discovery: angegeben oder die Herkunft der URL ({@code https://host[:port]}). */
    public static String issuerFor(String issuer, String url) {
        if (issuer != null && !issuer.isBlank()) {
            return issuer.strip();
        }
        URI uri = URI.create(url.strip());
        return uri.getScheme() + "://" + uri.getRawAuthority();
    }

    /**
     * Scopes, wenn keine angegeben sind: für GitHub Lesen und Schreiben von Repositories (Pull Requests, Issues,
     * Actions), für Atlassian Jira lesen/schreiben mit Refresh-Token; sonst keine (Vorgabe des Servers).
     */
    public String defaultScopes() {
        if (tokenEndpoint.endsWith("/login/oauth/access_token")) {
            return "repo read:org";
        }
        return ATLASSIAN.equals(issuer) ? "read:jira-work write:jira-work read:jira-user offline_access" : "";
    }

    public boolean supportsDeviceFlow() {
        return deviceAuthorizationEndpoint != null;
    }

    public boolean supportsAuthorizationCode() {
        return authorizationEndpoint != null;
    }

    /** Ob das Client-Geheimnis im Formular mitgeht ({@code client_secret_post}) statt per HTTP-Basic. */
    boolean secretInBody() {
        return tokenAuthMethods.isEmpty() || tokenAuthMethods.contains("client_secret_post")
                || !tokenAuthMethods.contains("client_secret_basic");
    }
}
