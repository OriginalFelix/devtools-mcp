package systems.grebe.devtools.mcp.backend.account;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import systems.grebe.devtools.mcp.api.CredentialInfo;
import systems.grebe.devtools.mcp.backend.BackendChanged;
import systems.grebe.devtools.mcp.config.SecretCipher;
import systems.grebe.devtools.mcp.oauth.OAuthClient;
import systems.grebe.devtools.mcp.oauth.OAuthException;
import systems.grebe.devtools.mcp.oauth.OAuthOptions;
import systems.grebe.devtools.mcp.oauth.OAuthServer;
import systems.grebe.devtools.mcp.oauth.OAuthTokens;

/**
 * Zugangsdaten der Benutzer (Modul „Zugangsdaten“ der Desktop-App, wie beim Git Credential Manager): Token bzw.
 * Passwort je Server-URL oder Anmeldung per OAuth 2.0 / OpenID Connect, Geheimnisse mit dem Schlüssel aus
 * {@code secret.key} verschlüsselt. Jeder Benutzer sieht und ändert nur seine eigenen – auch Administratoren kommen über
 * die API nicht an fremde. Änderungen gehen als {@link BackendChanged.Topic#SETTINGS} an die Desktop-Apps des Benutzers
 * (die Zugangsdaten stehen im Settings-Stand).
 *
 * <p>OAuth: Angemeldet wird in der Desktop-App (Browser); sie speichert Access- und Refresh-Token hier. Erneuert wird
 * hier ({@link #refresh}), je Benutzer nacheinander – Server, die Refresh-Tokens bei jeder Verwendung austauschen
 * (GitLab), würden sonst zwei gleichzeitig erneuernde Desktop-Apps gegenseitig abmelden. Das Refresh-Token verlässt
 * das Backend nie; das Client-Geheimnis bekommt nur der Benutzer selbst (für die Anmeldung in der Desktop-App).
 */
@Service
public class CredentialService {

    static final int MAX_NAME = 64;
    static final int MAX_URL = 1000;
    static final int MAX_USERNAME = 200;
    static final int MAX_SECRET = 4000;
    static final int MAX_DESCRIPTION = 500;
    static final int MAX_CLIENT_ID = 500;
    static final int MAX_SCOPES = 1000;
    static final int MAX_OPTIONS = 2000;
    static final int MAX_ENTRIES = 200;

    private final CredentialRepository repo;
    private final SecretCipher cipher;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;
    private final OAuthClient oauth;
    private final Clock clock;
    private final Map<Long, Object> refreshLocks = new ConcurrentHashMap<>();

    @Autowired
    public CredentialService(CredentialRepository repo, SecretCipher cipher,
                             @Qualifier("coreTransactions") TransactionTemplate tx, ApplicationEventPublisher events) {
        this(repo, cipher, tx, events, new OAuthClient(Duration.ofSeconds(30)), Clock.systemUTC());
    }

    CredentialService(CredentialRepository repo, SecretCipher cipher, TransactionTemplate tx,
                      ApplicationEventPublisher events, OAuthClient oauth, Clock clock) {
        this.repo = repo;
        this.cipher = cipher;
        this.tx = tx;
        this.events = events;
        this.oauth = oauth;
        this.clock = clock;
    }

    /**
     * Zugangsdaten des Benutzers in ihrer Reihenfolge, Access-Token bzw. Token entschlüsselt – leer bei OAuth ohne
     * Anmeldung (API-Version 1 verlangt einen Wert).
     */
    public List<CredentialInfo> list(long userId) {
        return repo.list(userId).stream()
                .map(r -> info(r, r.secret() == null ? "" : cipher.decrypt(r.secret()), cipher.decrypt(r.clientSecret())))
                .toList();
    }

    /**
     * Zugangsdaten des Benutzers ohne Geheimnisse ({@code secret = null}) – für Oberflächen, die sie nur anzeigen und
     * über {@link #replace} mit {@code id} ändern.
     */
    public List<CredentialInfo> summaries(long userId) {
        return repo.list(userId).stream().map(r -> info(r, null, null)).toList();
    }

    /** Ausgabe; das Refresh-Token bleibt immer im Backend. */
    private static CredentialInfo info(CredentialRepository.Row r, String secret, String clientSecret) {
        return new CredentialInfo(r.id(), r.name(), r.url(), r.username(), secret, r.description(), r.auth(),
                r.issuer(), r.clientId(), clientSecret, r.scopes(), null,
                r.expiresAt() == null ? null : r.expiresAt().toString(), r.options());
    }

    /**
     * Ersetzt alle Zugangsdaten des Benutzers durch {@code entries} (deren Reihenfolge gilt). Ein Eintrag mit {@code id}
     * behält, was er nicht neu angibt: ohne bzw. mit unverändertem Geheimnis das bisherige samt Refresh-Token und
     * Ablauf, ohne Client-Geheimnis das bisherige, Felder mit {@code null} (Clients älterer API-Versionen) ihren Wert.
     *
     * @throws IllegalArgumentException bei ungültigen Einträgen – mit Nummer und Grund
     */
    public void replace(long userId, List<CredentialInfo> entries) {
        if (entries.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("Höchstens " + MAX_ENTRIES + " Zugangsdaten.");
        }
        tx.executeWithoutResult(s -> {
            Map<Long, CredentialRepository.Row> existing = repo.list(userId).stream()
                    .collect(Collectors.toMap(CredentialRepository.Row::id, Function.identity()));
            Set<String> names = new HashSet<>();
            List<CredentialRepository.Row> rows = new ArrayList<>();
            for (int i = 0; i < entries.size(); i++) {
                rows.add(row(entries.get(i), i + 1, existing, names));
            }
            repo.replace(userId, rows, clock.instant());
        });
        events.publishEvent(BackendChanged.of(BackendChanged.Topic.SETTINGS, userId));
    }

    /**
     * Erneuert das Access-Token eines OAuth-Eintrags mit seinem Refresh-Token, wenn es in weniger als
     * {@link CredentialInfo#REFRESH_BEFORE} abläuft – sonst hat das schon eine andere Desktop-App getan und es bleibt.
     *
     * @throws IllegalArgumentException wenn der Eintrag fehlt, keiner per OAuth ist oder der Server ablehnt
     */
    public void refresh(long userId, long id) {
        synchronized (refreshLocks.computeIfAbsent(userId, k -> new Object())) {
            CredentialRepository.Row r = repo.find(userId, id)
                    .orElseThrow(() -> new IllegalArgumentException("Unbekannte Zugangsdaten " + id + "."));
            if (!CredentialInfo.OAUTH.equals(r.auth())) {
                throw new IllegalArgumentException("„" + r.name() + "“ meldet sich nicht per OAuth an.");
            }
            Instant now = clock.instant();
            if (r.expiresAt() == null || r.expiresAt().isAfter(now.plus(CredentialInfo.REFRESH_BEFORE))) {
                return; // läuft nicht (bald) ab – inzwischen erneuert
            }
            if (r.refreshToken() == null) {
                throw new IllegalArgumentException("„" + r.name() + "“ hat kein Refresh-Token – in der Desktop-App "
                        + "neu anmelden.");
            }
            OAuthTokens t;
            try {
                OAuthServer server = oauth.resolve(r.issuer(), r.url(), OAuthOptions.parse(r.options()));
                t = oauth.refresh(server, r.clientId(), cipher.decrypt(r.clientSecret()),
                        cipher.decrypt(r.refreshToken()));
            } catch (OAuthException e) {
                throw new IllegalArgumentException("„" + r.name() + "“: Erneuern abgelehnt (" + e.code() + ": "
                        + e.getMessage() + ") – in der Desktop-App neu anmelden.", e);
            } catch (IllegalStateException e) {
                throw new IllegalArgumentException("„" + r.name() + "“: Erneuern fehlgeschlagen – " + e.getMessage(), e);
            }
            repo.updateTokens(r.id(), cipher.encrypt(t.accessToken()), cipher.encrypt(t.refreshToken()),
                    t.expiresAt(), now);
        }
        events.publishEvent(BackendChanged.of(BackendChanged.Topic.SETTINGS, userId));
    }

    private CredentialRepository.Row row(CredentialInfo e, int number, Map<Long, CredentialRepository.Row> existing,
                                         Set<String> names) {
        String prefix = "Zugangsdaten, Eintrag " + number + ": ";
        CredentialRepository.Row old = e.id() == null ? null : existing.get(e.id());
        String name = text(e.name(), MAX_NAME, prefix + "Name");
        if (name == null) {
            throw new IllegalArgumentException(prefix + "Name fehlt.");
        }
        if (!names.add(name.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException(prefix + "Name „" + name + "“ ist doppelt.");
        }
        String url = text(e.url(), MAX_URL, prefix + "URL");
        if (url == null || !httpUrl(url)) {
            throw new IllegalArgumentException(prefix + "URL muss eine http(s)-URL sein.");
        }
        String auth = e.auth() != null ? e.auth().strip().toUpperCase(Locale.ROOT)
                : old != null ? old.auth() : CredentialInfo.TOKEN;
        if (!CredentialInfo.TOKEN.equals(auth) && !CredentialInfo.OAUTH.equals(auth)) {
            throw new IllegalArgumentException(prefix + "Anmeldung muss TOKEN oder OAUTH sein.");
        }
        String issuer = keep(e.issuer(), old == null ? null : old.issuer(), MAX_URL, prefix + "Aussteller");
        if (issuer != null && !httpUrl(issuer)) {
            throw new IllegalArgumentException(prefix + "Aussteller muss eine http(s)-URL sein.");
        }
        String clientId = keep(e.clientId(), old == null ? null : old.clientId(), MAX_CLIENT_ID, prefix + "Client-ID");
        String scopes = keep(e.scopes(), old == null ? null : old.scopes(), MAX_SCOPES, prefix + "Scopes");
        String options = keep(e.options(), old == null ? null : old.options(), MAX_OPTIONS, prefix + "OAuth-Optionen");
        try {
            OAuthOptions.parse(options);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(prefix + ex.getMessage(), ex);
        }
        String clientSecret = e.clientSecret() != null && !e.clientSecret().isEmpty()
                ? cipher.encrypt(limit(e.clientSecret(), MAX_SECRET, prefix + "Client-Geheimnis"))
                : old == null ? null : old.clientSecret();

        // Token: neu angegeben (mit eigenem Refresh-Token/Ablauf) oder bisheriges samt Refresh-Token und Ablauf
        String secret;
        String refresh;
        Instant expires;
        boolean newRefresh = e.refreshToken() != null && !e.refreshToken().isEmpty();
        boolean unchanged = e.secret() == null || e.secret().isEmpty()
                || old != null && Objects.equals(e.secret(), cipher.decrypt(old.secret()));
        if (unchanged && !newRefresh && old != null) {
            secret = old.secret();
            refresh = old.refreshToken();
            expires = old.expiresAt();
        } else if (e.secret() != null && !e.secret().isEmpty()) {
            secret = cipher.encrypt(limit(e.secret(), MAX_SECRET, prefix + "Token/Passwort"));
            refresh = newRefresh ? cipher.encrypt(limit(e.refreshToken(), MAX_SECRET, prefix + "Refresh-Token")) : null;
            expires = instant(e.expiresAt(), prefix);
        } else {
            secret = null;
            refresh = null;
            expires = null;
        }
        if (CredentialInfo.TOKEN.equals(auth) && secret == null) {
            throw new IllegalArgumentException(prefix + "Token/Passwort fehlt.");
        }
        if (CredentialInfo.OAUTH.equals(auth) && clientId == null) {
            throw new IllegalArgumentException(prefix + "Client-ID fehlt (Anmeldung per OAuth).");
        }
        return new CredentialRepository.Row(0, name, url, keep(e.username(), old == null ? null : old.username(),
                MAX_USERNAME, prefix + "Benutzer"), secret, keep(e.description(), old == null ? null : old.description(),
                MAX_DESCRIPTION, prefix + "Beschreibung"), auth, issuer, clientId, clientSecret, scopes, refresh,
                CredentialInfo.OAUTH.equals(auth) ? expires : null, options);
    }

    /** Neuer Wert, {@code null} = bisheriger (ältere API-Versionen kennen das Feld nicht), leer = keiner. */
    private static String keep(String value, String old, int max, String label) {
        return value == null ? old : text(value, max, label);
    }

    private static Instant instant(String iso, String prefix) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(iso.strip());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(prefix + "Ablauf ist kein ISO-8601-Zeitpunkt.", e);
        }
    }

    private static String limit(String value, int max, String label) {
        if (value.length() > max) {
            throw new IllegalArgumentException(label + " ist länger als " + max + " Zeichen.");
        }
        return value;
    }

    /** Getrimmter Text oder {@code null}, wenn leer. */
    private static String text(String value, int max, String label) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return limit(value.strip(), max, label);
    }

    private static boolean httpUrl(String url) {
        try {
            URI uri = URI.create(url);
            return uri.getScheme() != null && uri.getScheme().toLowerCase(Locale.ROOT).matches("https?")
                    && uri.getHost() != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
