package systems.grebe.devtools.mcp.backend.account;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import systems.grebe.devtools.mcp.api.Permission;
import systems.grebe.devtools.mcp.config.SecretCipher;
import systems.grebe.devtools.mcp.backend.BackendChanged;
import systems.grebe.devtools.mcp.backend.BackendHome;

/**
 * Zugriffstokens als JWT (HS512, Schlüssel {@code jwt.key} neben {@code settings.json}): persönliche Tokens aus
 * „Mein Konto“ ({@link ApiToken.Kind#TOKEN}) und Anmeldungen der Desktop-Apps ({@link ApiToken.Kind#SESSION}, enden
 * mit der Abmeldung).
 *
 * <p>Ein Token gilt, wenn Signatur und Ablauf stimmen, seine ID ({@code jti}) in der Datenbank steht und nicht
 * widerrufen ist und der Benutzer aktiv ist. Das Ergebnis der Datenbank-Prüfung wird 30 s gemerkt; Widerruf,
 * Sperren und Löschen leeren den Speicher sofort. {@code last_used_at} wird höchstens einmal pro Minute geschrieben.
 */
@Service
public class TokenService {

    static final String ISSUER = "devtools-mcp";
    private static final Duration CACHE = Duration.ofSeconds(30);
    private static final Duration TOUCH = Duration.ofMinutes(1);
    private static final Logger LOG = LoggerFactory.getLogger(TokenService.class);

    /** Ein frisch erzeugtes Token – {@code jwt} wird nur dieses eine Mal angezeigt. */
    public record IssuedToken(String jwt, ApiToken token) {
    }

    /** Wer ein gültiges Token vorgelegt hat. */
    public record TokenUser(UserAccount user, String tokenId, ApiToken.Kind kind) {
    }

    private record Checked(TokenUser user, Instant until) {
    }

    private final AccountRepository repo;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final byte[] key;
    private final Map<String, Checked> cache = new ConcurrentHashMap<>();
    private final Map<String, Instant> touched = new ConcurrentHashMap<>();

    @Autowired
    public TokenService(AccountRepository repo, BackendHome home, ApplicationEventPublisher events) {
        this(repo, events, Clock.systemUTC(), loadOrCreateKey(home.resolve("jwt.key")));
    }

    TokenService(AccountRepository repo, ApplicationEventPublisher events, Clock clock, byte[] key) {
        this.repo = repo;
        this.events = events;
        this.clock = clock;
        this.key = key.clone();
    }

    /**
     * Stellt ein neues persönliches Token aus (Recht {@link Permission#TOKENS_CREATE}).
     *
     * @param validity Gültigkeit, {@code null} = unbegrenzt (bis zum Widerruf)
     */
    public IssuedToken issue(UserAccount user, String name, Duration validity) {
        if (!user.has(Permission.TOKENS_CREATE)) {
            throw new IllegalStateException("Dafür fehlt das Recht „" + Permission.TOKENS_CREATE.label() + "“.");
        }
        return issue(user, name, validity, ApiToken.Kind.TOKEN);
    }

    /**
     * Stellt das Token einer Anmeldung aus (Desktop-App mit Benutzername und Passwort) und räumt beendete Anmeldungen
     * des Benutzers ab.
     *
     * @param client z.B. „Desktop-App (rechner)“
     */
    public IssuedToken issueSession(UserAccount user, String client, Duration validity) {
        try {
            repo.deleteEndedSessions(user.id(), clock.instant());
        } catch (RuntimeException e) {
            LOG.debug("Beendete Anmeldungen von {} nicht entfernt", user.username(), e);
        }
        String name = client == null || client.isBlank() ? "Desktop-App" : client.strip();
        return issue(user, name.length() > 100 ? name.substring(0, 100) : name, validity, ApiToken.Kind.SESSION);
    }

    private IssuedToken issue(UserAccount user, String name, Duration validity, ApiToken.Kind kind) {
        String label = name == null || name.isBlank() ? "Token" : name.strip();
        if (label.length() > 100) {
            throw new IllegalArgumentException("Name: höchstens 100 Zeichen.");
        }
        Instant now = clock.instant();
        Instant expires = validity == null ? null : now.plus(validity);
        ApiToken token = new ApiToken(UUID.randomUUID().toString(), user.id(), label, now, expires, null, null, kind);
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(Long.toString(user.id()))
                .jwtID(token.id())
                .issueTime(Date.from(now))
                .claim("name", user.username());
        if (expires != null) {
            claims.expirationTime(Date.from(expires));
        }
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS512), claims.build());
            jwt.sign(new MACSigner(key));
            repo.insertToken(token);
            return new IssuedToken(jwt.serialize(), token);
        } catch (JOSEException e) {
            throw new IllegalStateException("Token konnte nicht signiert werden", e);
        }
    }

    public List<ApiToken> tokens(long userId) {
        return repo.tokens(userId);
    }

    /** Widerruft ein Token des Benutzers; fremde Tokens werden abgelehnt. */
    public void revoke(long userId, String tokenId) {
        ApiToken t = repo.token(tokenId).filter(x -> x.userId() == userId)
                .orElseThrow(() -> new IllegalArgumentException("Unbekanntes Token"));
        repo.revokeToken(t.id(), clock.instant());
        cache.remove(t.id());
        closeStreams(userId);
    }

    /** Beendet eine Anmeldung (Abmelden der Desktop-App): Token widerrufen und entfernen. */
    public void end(String tokenId) {
        Optional<ApiToken> found = repo.token(tokenId);
        found.ifPresent(t -> {
            repo.revokeToken(t.id(), clock.instant());
            if (t.kind() == ApiToken.Kind.SESSION) {
                repo.deleteToken(t.id());
            }
        });
        cache.remove(tokenId);
        found.ifPresent(t -> closeStreams(t.userId()));
    }

    /** Weckt die offenen Subscriptions des Benutzers: Sie prüfen ihr Token neu und enden, wenn es widerrufen wurde. */
    private void closeStreams(long userId) {
        for (BackendChanged.Topic topic : BackendChanged.Topic.values()) {
            events.publishEvent(BackendChanged.of(topic, userId));
        }
    }

    /** Entfernt ein widerrufenes oder abgelaufenes Token aus der Liste. */
    public void delete(long userId, String tokenId) {
        ApiToken t = repo.token(tokenId).filter(x -> x.userId() == userId)
                .orElseThrow(() -> new IllegalArgumentException("Unbekanntes Token"));
        if (t.activeAt(clock.instant())) {
            throw new IllegalStateException("Gültige Tokens erst widerrufen.");
        }
        repo.deleteToken(t.id());
    }

    /** Prüft ein vorgelegtes JWT; leer, wenn es nicht (mehr) gilt. */
    public Optional<TokenUser> verify(String jwt) {
        Instant now = clock.instant();
        SignedJWT parsed;
        JWTClaimsSet claims;
        try {
            parsed = SignedJWT.parse(jwt);
            if (!JWSAlgorithm.HS512.equals(parsed.getHeader().getAlgorithm()) || !parsed.verify(new MACVerifier(key))) {
                return Optional.empty();
            }
            claims = parsed.getJWTClaimsSet();
        } catch (ParseException | JOSEException e) {
            return Optional.empty();
        }
        if (!ISSUER.equals(claims.getIssuer()) || claims.getJWTID() == null
                || claims.getExpirationTime() != null && !now.isBefore(claims.getExpirationTime().toInstant())) {
            return Optional.empty();
        }
        return checked(claims.getJWTID(), claims.getSubject(), now);
    }

    /**
     * Prüft ein früher angenommenes Token erneut (offene WebSocket-Sitzung): leer, wenn es inzwischen widerrufen,
     * abgelaufen oder der Benutzer gesperrt/gelöscht ist; sonst mit dem aktuellen {@link UserAccount} (Rollen, Rechte).
     */
    public Optional<TokenUser> recheck(TokenUser presented) {
        return checked(presented.tokenId(), Long.toString(presented.user().id()), clock.instant());
    }

    private Optional<TokenUser> checked(String id, String subject, Instant now) {
        Checked c = cache.get(id);
        if (c == null || now.isAfter(c.until())) {
            c = new Checked(lookup(id, subject, now), now.plus(CACHE));
            cache.put(id, c);
        }
        if (c.user() != null) {
            touch(id, now);
        }
        return Optional.ofNullable(c.user());
    }

    private TokenUser lookup(String id, String subject, Instant now) {
        return repo.token(id)
                .filter(t -> t.activeAt(now) && Long.toString(t.userId()).equals(subject))
                .flatMap(t -> repo.user(t.userId()).filter(UserAccount::enabled)
                        .map(u -> new TokenUser(u, id, t.kind())))
                .orElse(null);
    }

    private void touch(String id, Instant now) {
        Instant last = touched.get(id);
        if (last == null || !now.isBefore(last.plus(TOUCH))) {
            touched.put(id, now);
            try {
                repo.touchToken(id, now);
            } catch (RuntimeException e) {
                LOG.debug("last_used_at für Token {} nicht gespeichert", id, e);
            }
        }
    }

    /** Sperren/Löschen eines Benutzers und geänderte Rollen wirken sofort auf seine Tokens. */
    @EventListener
    public void onAccountChanged(AccountService.AccountChangedEvent e) {
        if (e.userId() == null) {
            cache.clear();
        } else {
            cache.values().removeIf(c -> c.user() == null || c.user().user().id() == e.userId());
        }
    }

    // ---------------------------------------------------------------- Schlüssel

    static byte[] loadOrCreateKey(Path file) {
        try {
            if (Files.isRegularFile(file)) {
                byte[] k = Base64.getDecoder().decode(Files.readString(file).trim());
                if (k.length >= 64) {
                    return k;
                }
                LOG.warn("{} ist zu kurz für HS512 – erzeuge einen neuen Schlüssel (bestehende Tokens werden ungültig)",
                        file);
            }
            byte[] k = new byte[64];
            new SecureRandom().nextBytes(k);
            Files.createDirectories(file.getParent());
            Files.writeString(file, Base64.getEncoder().encodeToString(k));
            SecretCipher.restrictToOwner(file);
            return k;
        } catch (IOException e) {
            throw new UncheckedIOException("JWT-Schlüssel nicht lesbar: " + file, e);
        }
    }
}
