package systems.grebe.devtools.mcp.backend;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.api.Permission;
import systems.grebe.devtools.mcp.backend.account.UserAccount;
import systems.grebe.devtools.mcp.backend.skills.SkillOwner;

/**
 * Eigentümer der Skills im Backend: der angemeldete Benutzer der laufenden GraphQL-Operation mit seiner Konto-E-Mail;
 * globale Vorlagen verwaltet, wer das Recht {@link Permission#TEMPLATES_PUBLISH} hat; für alle teilen darf, wer
 * {@link Permission#SHARES_ALL} hat. Gesetzt für die Dauer eines
 * Aufrufs über {@link #as}.
 */
@Component
public class SkillCaller implements SkillOwner {

    private static final ThreadLocal<UserAccount> CURRENT = new ThreadLocal<>();

    /** Führt {@code body} als {@code user} aus. */
    public static <T> T as(UserAccount user, Supplier<T> body) {
        UserAccount previous = CURRENT.get();
        CURRENT.set(user);
        try {
            return body.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    @Override
    public String email() {
        UserAccount u = CURRENT.get();
        return emailIfKnown().orElseThrow(() -> new IllegalStateException(u == null
                ? "Skills nur über die GraphQL-API mit Anmeldung."
                : "Kein Benutzer für die Skills: im Konto von '" + u.username() + "' ist keine E-Mail hinterlegt."));
    }

    @Override
    public Optional<String> emailIfKnown() {
        return Optional.ofNullable(CURRENT.get()).map(UserAccount::email).filter(e -> !e.isBlank())
                .map(e -> e.strip().toLowerCase(Locale.ROOT));
    }

    @Override
    public boolean admin() {
        UserAccount u = CURRENT.get();
        return u != null && u.has(Permission.TEMPLATES_PUBLISH);
    }

    @Override
    public List<String> roles() {
        UserAccount u = CURRENT.get();
        return u == null ? List.of() : u.roles();
    }

    @Override
    public boolean shareWithAll() {
        UserAccount u = CURRENT.get();
        return u != null && u.has(Permission.SHARES_ALL);
    }
}
