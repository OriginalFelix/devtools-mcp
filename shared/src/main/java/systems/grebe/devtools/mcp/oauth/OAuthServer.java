package systems.grebe.devtools.mcp.oauth;

import java.net.URI;
import java.util.List;

/**
 * Endpunkte eines Autorisierungsservers – aus der Discovery (RFC 8414 bzw. OpenID Connect Discovery) oder für GitHub,
 * das keine Discovery anbietet, fest vorgegeben.
 *
 * @param deviceAuthorizationEndpoint {@code null}, wenn der Server keinen Device Authorization Grant (RFC 8628) kann
 * @param authorizationEndpoint       {@code null}, wenn der Server keinen Authorization Code Grant kann
 * @param tokenAuthMethods            {@code token_endpoint_auth_methods_supported}; leer = nicht angegeben
 */
public record OAuthServer(String issuer, String authorizationEndpoint, String tokenEndpoint,
                          String deviceAuthorizationEndpoint, List<String> tokenAuthMethods) {

    public OAuthServer {
        tokenAuthMethods = tokenAuthMethods == null ? List.of() : List.copyOf(tokenAuthMethods);
    }

    /**
     * GitHub (github.com und GitHub Enterprise Server): OAuth-Apps ohne Discovery, Endpunkte unter
     * {@code /login/…} der Web-Adresse.
     */
    public static OAuthServer github(String webBase) {
        String base = webBase.endsWith("/") ? webBase.substring(0, webBase.length() - 1) : webBase;
        return new OAuthServer(base, base + "/login/oauth/authorize", base + "/login/oauth/access_token",
                base + "/login/device/code", List.of("client_secret_post"));
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

    /** Scopes, wenn keine angegeben sind: für GitHub Lesen und Schreiben von Repositories (Pull Requests, Issues, Actions); sonst keine. */
    public String defaultScopes() {
        return tokenEndpoint.endsWith("/login/oauth/access_token") ? "repo read:org" : "";
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
