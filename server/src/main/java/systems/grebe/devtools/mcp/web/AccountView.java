package systems.grebe.devtools.mcp.web;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.EmailField;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.spring.security.AuthenticationContext;
import jakarta.annotation.security.PermitAll;
import systems.grebe.devtools.mcp.account.AccountService;
import systems.grebe.devtools.mcp.account.AccountService.AccountPrincipal;
import systems.grebe.devtools.mcp.account.ApiToken;
import systems.grebe.devtools.mcp.account.TokenService;
import systems.grebe.devtools.mcp.account.UserAccount;

/** Eigenes Konto: Name/E-Mail, Passwort und die persönlichen Desktop-Tokens (Anmeldung der Desktop-App). */
@Route("konto")
@PageTitle("Mein Konto – DevTools MCP")
@PermitAll
public class AccountView extends VerticalLayout {

    private static final Map<String, Duration> VALIDITY = new LinkedHashMap<>();

    static {
        VALIDITY.put("30 Tage", Duration.ofDays(30));
        VALIDITY.put("90 Tage", Duration.ofDays(90));
        VALIDITY.put("1 Jahr", Duration.ofDays(365));
        VALIDITY.put("unbegrenzt", null);
    }

    private final AccountService accounts;
    private final TokenService tokens;
    private final String serverUrl;
    private final long userId;
    private final Grid<ApiToken> grid = new Grid<>();

    public AccountView(AccountService accounts, TokenService tokens, AuthenticationContext auth) {
        this.accounts = accounts;
        this.tokens = tokens;
        this.serverUrl = Ui.serverUrl();
        this.userId = auth.getAuthenticatedUser(AccountPrincipal.class).orElseThrow().id();
        UserAccount me = accounts.user(userId).orElseThrow();

        add(new H2("Profil"), profile(me), new H2("Passwort"), password(), new H2("Desktop-Tokens"),
                new Paragraph("Mit einem Token meldet sich deine Desktop-App am Server an (Server → Adresse " + serverUrl
                        + " und Token). Die MCP-Clients verbinden sich weiter mit der Desktop-App. Das Token wird nur "
                        + "beim Erzeugen angezeigt; verlorene Tokens widerrufen und neu erzeugen."),
                tokenGrid());
        refresh();
    }

    private FormLayout profile(UserAccount me) {
        TextField username = new TextField("Benutzername");
        username.setValue(me.username());
        username.setReadOnly(true);
        TextField name = new TextField("Anzeigename");
        name.setValue(me.displayName() == null ? "" : me.displayName());
        EmailField email = new EmailField("E-Mail");
        email.setHelperText("Eigentümer deiner Skills");
        email.setValue(me.email() == null ? "" : me.email());
        Button save = new Button("Speichern", e -> Ui.run(() -> {
            UserAccount current = accounts.user(userId).orElseThrow();
            accounts.update(userId, name.getValue(), email.getValue(), current.role(), current.enabled());
        }, "Gespeichert"));
        save.addThemeVariants(ButtonVariant.PRIMARY);
        FormLayout form = new FormLayout(username, name, email, save);
        form.setMaxWidth("40rem");
        return form;
    }

    private FormLayout password() {
        PasswordField current = new PasswordField("Bisheriges Passwort");
        PasswordField next = new PasswordField("Neues Passwort");
        PasswordField repeat = new PasswordField("Wiederholen");
        Button change = new Button("Passwort ändern", e -> {
            if (!next.getValue().equals(repeat.getValue())) {
                Ui.error("Die neuen Passwörter stimmen nicht überein.");
                return;
            }
            if (Ui.run(() -> accounts.changePassword(userId, current.getValue(), next.getValue()),
                    "Passwort geändert")) {
                current.clear();
                next.clear();
                repeat.clear();
            }
        });
        FormLayout form = new FormLayout(current, next, repeat, change);
        form.setMaxWidth("40rem");
        return form;
    }

    private VerticalLayout tokenGrid() {
        grid.addColumn(ApiToken::name).setHeader("Name").setAutoWidth(true);
        grid.addColumn(t -> Ui.time(t.createdAt())).setHeader("Erstellt");
        grid.addColumn(t -> t.expiresAt() == null ? "unbegrenzt" : Ui.time(t.expiresAt())).setHeader("Läuft ab");
        grid.addColumn(t -> t.lastUsedAt() == null ? "nie" : Ui.time(t.lastUsedAt())).setHeader("Zuletzt benutzt");
        grid.addColumn(t -> t.revokedAt() != null ? "widerrufen"
                : t.activeAt(Instant.now()) ? "gültig" : "abgelaufen").setHeader("Status");
        grid.addComponentColumn(t -> {
            if (t.activeAt(Instant.now())) {
                Button revoke = new Button("Widerrufen", e -> {
                    Ui.run(() -> tokens.revoke(userId, t.id()), "Token widerrufen");
                    refresh();
                });
                revoke.addThemeVariants(ButtonVariant.ERROR, ButtonVariant.TERTIARY);
                return revoke;
            }
            Button remove = new Button("Entfernen", e -> {
                Ui.run(() -> tokens.delete(userId, t.id()), null);
                refresh();
            });
            remove.addThemeVariants(ButtonVariant.TERTIARY);
            return remove;
        });
        grid.setAllRowsVisible(true);

        TextField name = new TextField("Name");
        name.setPlaceholder("z.B. Laptop");
        Select<String> validity = new Select<>();
        validity.setLabel("Gültigkeit");
        validity.setItems(VALIDITY.keySet());
        validity.setValue("90 Tage");
        Button create = new Button("Token erzeugen", e -> {
            UserAccount me = accounts.user(userId).orElseThrow();
            if (Ui.run(() -> showToken(tokens.issue(me, name.getValue(), VALIDITY.get(validity.getValue()))), null)) {
                name.clear();
                refresh();
            }
        });
        create.addThemeVariants(ButtonVariant.PRIMARY);
        HorizontalLayout bar = new HorizontalLayout(name, validity, create);
        bar.setAlignItems(Alignment.BASELINE);
        VerticalLayout box = new VerticalLayout(bar, grid);
        box.setPadding(false);
        return box;
    }

    private void showToken(TokenService.IssuedToken issued) {
        Dialog d = new Dialog();
        d.setHeaderTitle("Token „" + issued.token().name() + "“");
        TextArea jwt = new TextArea("Token – jetzt kopieren, es wird nicht wieder angezeigt");
        jwt.setValue(issued.jwt());
        jwt.setReadOnly(true);
        jwt.setWidth("40rem");
        TextField url = new TextField("Server-Adresse für die Desktop-App");
        url.setValue(serverUrl);
        url.setReadOnly(true);
        url.setWidth("40rem");
        d.add(new VerticalLayout(url, jwt));
        d.getFooter().add(new Button("Schließen", e -> d.close()));
        d.open();
    }

    private void refresh() {
        grid.setItems(tokens.tokens(userId));
    }
}
