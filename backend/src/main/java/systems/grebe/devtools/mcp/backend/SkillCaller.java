package systems.grebe.devtools.mcp.backend;

import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.api.Permission;
import systems.grebe.devtools.mcp.backend.account.UserAccount;
import systems.grebe.devtools.mcp.backend.skills.SkillOwner;

/**
 * Eigentümer der Skills im Backend: der angemeldete Benutzer der laufenden GraphQL-Operation mit seiner Konto-E-Mail;
 * globale Vorlagen verwaltet, wer das Recht {@link Permission#TEMPLATES_PUBLISH} hat. Gesetzt für die Dauer eines
 * Aufrufs über {@link #as}.
 */
@Component
public class SkillCaller implements SkillOwner {

    private static final ScopedValue<UserAccount> CURRENT = ScopedValue.newInstance();

    /** Der Benutzer des laufenden Aufrufs oder {@code null}. */
    private static UserAccount current() {
        return CURRENT.isBound() ? CURRENT.get() : null;
    }

    /** Führt {@code body} als {@code user} aus. */
    public static <T> T as(UserAccount user, Supplier<T> body) {
        return ScopedValue.where(CURRENT, user).call(body::get);
    }

    @Override
    public String email() {
        UserAccount u = current();
        return emailIfKnown().orElseThrow(() -> new IllegalStateException(u == null
                ? "Skills nur über die GraphQL-API mit Anmeldung."
                : "Kein Benutzer für die Skills: im Konto von '" + u.username() + "' ist keine E-Mail hinterlegt."));
    }

    @Override
    public Optional<String> emailIfKnown() {
        return Optional.ofNullable(current()).map(UserAccount::email).filter(e -> !e.isBlank())
                .map(e -> e.strip().toLowerCase(Locale.ROOT));
    }

    @Override
    public boolean admin() {
        UserAccount u = current();
        return u != null && u.has(Permission.TEMPLATES_PUBLISH);
    }
}
