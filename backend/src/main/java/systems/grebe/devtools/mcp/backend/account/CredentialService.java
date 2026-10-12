package systems.grebe.devtools.mcp.backend.account;

import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import systems.grebe.devtools.mcp.api.CredentialInfo;
import systems.grebe.devtools.mcp.backend.BackendChanged;
import systems.grebe.devtools.mcp.config.SecretCipher;

/**
 * Zugangsdaten der Benutzer (Modul „Zugangsdaten“ der Desktop-App, wie beim Git Credential Manager): Token bzw.
 * Passwort je Server-URL, je Eintrag mit dem Schlüssel aus {@code secret.key} verschlüsselt. Jeder Benutzer sieht und
 * ändert nur seine eigenen – auch Administratoren kommen über die API nicht an fremde. Änderungen gehen als
 * {@link BackendChanged.Topic#SETTINGS} an die Desktop-Apps des Benutzers (die Zugangsdaten stehen im Settings-Stand).
 */
@Service
public class CredentialService {

    static final int MAX_NAME = 64;
    static final int MAX_URL = 1000;
    static final int MAX_USERNAME = 200;
    static final int MAX_SECRET = 4000;
    static final int MAX_DESCRIPTION = 500;
    static final int MAX_ENTRIES = 200;

    private final CredentialRepository repo;
    private final SecretCipher cipher;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;
    private final Clock clock = Clock.systemUTC();

    public CredentialService(CredentialRepository repo, SecretCipher cipher,
                             @Qualifier("coreTransactions") TransactionTemplate tx, ApplicationEventPublisher events) {
        this.repo = repo;
        this.cipher = cipher;
        this.tx = tx;
        this.events = events;
    }

    /** Zugangsdaten des Benutzers in ihrer Reihenfolge, entschlüsselt. */
    public List<CredentialInfo> list(long userId) {
        return repo.list(userId).stream()
                .map(r -> new CredentialInfo(r.id(), r.name(), r.url(), r.username(), cipher.decrypt(r.secret()),
                        r.description()))
                .toList();
    }

    /**
     * Zugangsdaten des Benutzers ohne Geheimnisse ({@code secret = null}) – für Oberflächen, die sie nur anzeigen und
     * über {@link #replace} mit {@code id} ändern.
     */
    public List<CredentialInfo> summaries(long userId) {
        return repo.list(userId).stream()
                .map(r -> new CredentialInfo(r.id(), r.name(), r.url(), r.username(), null, r.description()))
                .toList();
    }

    /**
     * Ersetzt alle Zugangsdaten des Benutzers durch {@code entries} (deren Reihenfolge gilt). Ein Eintrag ohne Geheimnis
     * behält das des bisherigen Eintrags mit seiner {@code id}.
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

    private CredentialRepository.Row row(CredentialInfo e, int number, Map<Long, CredentialRepository.Row> existing,
                                         Set<String> names) {
        String prefix = "Zugangsdaten, Eintrag " + number + ": ";
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
        String secret;
        if (e.secret() != null && !e.secret().isEmpty()) {
            if (e.secret().length() > MAX_SECRET) {
                throw new IllegalArgumentException(prefix + "Token/Passwort ist länger als " + MAX_SECRET + " Zeichen.");
            }
            secret = cipher.encrypt(e.secret());
        } else {
            CredentialRepository.Row old = e.id() == null ? null : existing.get(e.id());
            if (old == null) {
                throw new IllegalArgumentException(prefix + "Token/Passwort fehlt.");
            }
            secret = old.secret();
        }
        return new CredentialRepository.Row(0, name, url, text(e.username(), MAX_USERNAME, prefix + "Benutzer"),
                secret, text(e.description(), MAX_DESCRIPTION, prefix + "Beschreibung"));
    }

    /** Getrimmter Text oder {@code null}, wenn leer. */
    private static String text(String value, int max, String label) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.strip();
        if (v.length() > max) {
            throw new IllegalArgumentException(label + " ist länger als " + max + " Zeichen.");
        }
        return v;
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
