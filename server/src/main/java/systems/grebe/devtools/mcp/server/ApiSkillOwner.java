package systems.grebe.devtools.mcp.server;

import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import systems.grebe.devtools.mcp.account.UserAccount;
import systems.grebe.devtools.mcp.modules.skills.SkillOwner;

/**
 * Eigentümer der Skills auf dem Server: der per Desktop-Token angemeldete Benutzer der laufenden API-Anfrage
 * ({@link ApiTokenFilter}) mit seiner Konto-E-Mail; globale Vorlagen verwalten Administratoren.
 */
@Component
public class ApiSkillOwner implements SkillOwner {

    @Override
    public String email() {
        return emailIfKnown().orElseThrow(() -> new IllegalStateException(user().isPresent()
                ? "Kein Benutzer für die Skills: im Konto von '" + user().get().username() + "' ist keine E-Mail "
                        + "hinterlegt (Web-UI → Mein Konto)."
                : "Skills nur über die REST-API mit Desktop-Token."));
    }

    @Override
    public Optional<String> emailIfKnown() {
        return user().map(UserAccount::email).filter(e -> !e.isBlank()).map(e -> e.strip().toLowerCase(Locale.ROOT));
    }

    @Override
    public boolean admin() {
        return user().map(UserAccount::admin).orElse(false);
    }

    private static Optional<UserAccount> user() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        return attrs == null ? Optional.empty() : Optional.ofNullable(
                (UserAccount) attrs.getAttribute(ApiTokenFilter.USER, RequestAttributes.SCOPE_REQUEST));
    }
}
