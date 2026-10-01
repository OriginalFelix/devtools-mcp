package systems.grebe.devtools.mcp.modules.pr.bitbucket;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.modules.pr.spi.GitServer;
import systems.grebe.devtools.mcp.modules.pr.spi.GitServerProvider;
import systems.grebe.devtools.mcp.modules.ticket.spi.HttpJson;
import systems.grebe.devtools.mcp.modules.ticket.spi.ProviderSettings;

/**
 * Bitbucket Cloud (bitbucket.org, API 2.0) und Bitbucket Data Center/Server (REST API 1.0) – welches, ergibt sich aus
 * der Server-URL oder der Einstellung {@code deployment}.
 */
public class BitbucketServerProvider implements GitServerProvider {

    static final String BASE_URL = "baseUrl";
    static final String DEPLOYMENT = "deployment";
    static final String USER = "user";
    static final String TOKEN = "token";

    @Override
    public String id() {
        return "bitbucket";
    }

    @Override
    public String displayName() {
        return "Bitbucket";
    }

    @Override
    public int priority() {
        return 30;
    }

    @Override
    public List<ConfigField> configFields() {
        return List.of(
                ConfigField.of(BASE_URL, "Server-URL", FieldType.URL).withDefault("https://bitbucket.org")
                        .withHelp("Cloud: https://bitbucket.org; Data Center: Basis-URL, z.B. https://bitbucket.firma.de"),
                ConfigField.of(DEPLOYMENT, "Variante", FieldType.ENUM).withDefault("auto")
                        .withOptions("auto", "cloud", "datacenter")
                        .withHelp("auto = Cloud bei bitbucket.org, sonst Data Center/Server."),
                ConfigField.of(USER, "Benutzer", FieldType.STRING)
                        .withHelp("Cloud: Atlassian-E-Mail zum API-Token; Data Center: Benutzername. Leer = Token als "
                                + "Bearer (Access Token von Workspace/Repository bzw. HTTP Access Token)."),
                ConfigField.of(TOKEN, "Token", FieldType.SECRET)
                        .withHelp("Cloud: API-Token mit Scopes für Pull Requests und Repositories (lesen/schreiben); "
                                + "Data Center: HTTP Access Token mit Repository-Schreibrecht."));
    }

    @Override
    public String projectHelp() {
        return "Cloud: workspace/repo; Data Center: PROJEKT/repo (persönlich: ~benutzer/repo)";
    }

    @Override
    public String keyHelp() {
        return "workspace/repo#12 bzw. PROJEKT/repo#12, #12 bzw. 12 (mit Repository) oder Pull-Request-URL";
    }

    @Override
    public GitServer create(ProviderSettings s) {
        String base = HttpJson.stripSlash(s.getString(BASE_URL, "https://bitbucket.org"));
        Map<String, String> headers = new LinkedHashMap<>();
        String token = s.get(TOKEN).orElse(null);
        String user = s.get(USER).orElse(null);
        if (token != null) {
            headers.put("Authorization", user == null ? "Bearer " + token
                    : "Basic " + Base64.getEncoder().encodeToString((user + ":" + token).getBytes(StandardCharsets.UTF_8)));
        }
        if (cloud(s.getString(DEPLOYMENT, "auto"), base)) {
            String host = GitServer.hostOf(base);
            // bitbucket.org → öffentliche API; eine eigene URL (Proxy, Tests) wird direkt als API-Basis verwendet
            String api = host.equals("bitbucket.org") || host.equals("www.bitbucket.org") ? "https://api.bitbucket.org/2.0"
                    : base;
            return new BitbucketCloud(new HttpJson("Bitbucket", api, headers, s.timeout(), "Pull Requests"), token != null);
        }
        if (base.endsWith("/rest/api/1.0")) {
            base = base.substring(0, base.length() - 13);
        }
        headers.put("X-Atlassian-Token", "no-check");
        return new BitbucketDataCenter(new HttpJson("Bitbucket", base, headers, s.timeout(), "Pull Requests"), base,
                token != null);
    }

    static boolean cloud(String deployment, String base) {
        return switch (deployment.toLowerCase(Locale.ROOT)) {
            case "cloud" -> true;
            case "datacenter", "server" -> false;
            default -> GitServer.hostOf(base).endsWith("bitbucket.org");
        };
    }
}
