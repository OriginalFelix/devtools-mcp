package systems.grebe.devtools.mcp.web;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.EmailField;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.spring.security.AuthenticationContext;
import jakarta.annotation.security.RolesAllowed;
import systems.grebe.devtools.mcp.backend.account.AccountService;
import systems.grebe.devtools.mcp.web.WebLogin.AccountPrincipal;
import systems.grebe.devtools.mcp.backend.account.Role;
import systems.grebe.devtools.mcp.backend.account.UserAccount;

/** Benutzerverwaltung für Administratoren: anlegen, ändern, sperren, Passwort setzen, löschen. */
@Route("benutzer")
@PageTitle("Benutzer – DevTools MCP")
@RolesAllowed("ADMIN")
public class UsersView extends VerticalLayout {

    private final AccountService accounts;
    private final long selfId;
    private final Grid<UserAccount> grid = new Grid<>();

    public UsersView(AccountService accounts, AuthenticationContext auth) {
        this.accounts = accounts;
        this.selfId = auth.getAuthenticatedUser(AccountPrincipal.class).orElseThrow().id();

        grid.addColumn(UserAccount::username).setHeader("Benutzer").setAutoWidth(true);
        grid.addColumn(u -> u.displayName() == null ? "" : u.displayName()).setHeader("Name");
        grid.addColumn(u -> u.email() == null ? "" : u.email()).setHeader("E-Mail");
        grid.addColumn(u -> u.role() == Role.ADMIN ? "Administrator" : "Benutzer").setHeader("Rolle");
        grid.addColumn(u -> u.enabled() ? "aktiv" : "gesperrt").setHeader("Status");
        grid.addColumn(u -> Ui.time(u.createdAt())).setHeader("Angelegt");
        grid.addComponentColumn(u -> {
            Button edit = new Button("Bearbeiten", e -> edit(u));
            edit.addThemeVariants(ButtonVariant.TERTIARY);
            Button delete = new Button("Löschen", e -> confirmDelete(u));
            delete.addThemeVariants(ButtonVariant.TERTIARY, ButtonVariant.ERROR);
            delete.setEnabled(u.id() != selfId);
            return new HorizontalLayout(edit, delete);
        }).setAutoWidth(true);
        grid.setAllRowsVisible(true);

        Button create = new Button("Neuer Benutzer", e -> create());
        create.addThemeVariants(ButtonVariant.PRIMARY);
        add(new H2("Benutzer"), create, grid);
        refresh();
    }

    private void create() {
        Dialog d = new Dialog();
        d.setHeaderTitle("Neuer Benutzer");
        TextField username = new TextField("Benutzername");
        TextField name = new TextField("Anzeigename");
        EmailField email = new EmailField("E-Mail");
        Select<Role> role = roleSelect(Role.USER);
        PasswordField password = new PasswordField("Passwort");
        password.setHelperText("Mindestens 8 Zeichen; der Benutzer ändert es unter „Mein Konto“.");
        d.add(new FormLayout(username, name, email, role, password));
        Button save = new Button("Anlegen", e -> {
            if (Ui.run(() -> accounts.create(username.getValue(), name.getValue(), email.getValue(), role.getValue(),
                    password.getValue()), "Benutzer angelegt")) {
                d.close();
                refresh();
            }
        });
        save.addThemeVariants(ButtonVariant.PRIMARY);
        d.getFooter().add(new Button("Abbrechen", e -> d.close()), save);
        d.open();
    }

    private void edit(UserAccount u) {
        Dialog d = new Dialog();
        d.setHeaderTitle("Benutzer " + u.username());
        TextField name = new TextField("Anzeigename");
        name.setValue(u.displayName() == null ? "" : u.displayName());
        EmailField email = new EmailField("E-Mail");
        email.setValue(u.email() == null ? "" : u.email());
        Select<Role> role = roleSelect(u.role());
        Checkbox enabled = new Checkbox("Aktiv", u.enabled());
        PasswordField password = new PasswordField("Neues Passwort");
        password.setHelperText("Leer lassen, um es nicht zu ändern.");
        d.add(new FormLayout(name, email, role, enabled, password));
        Button save = new Button("Speichern", e -> {
            boolean ok = Ui.run(() -> {
                accounts.update(u.id(), name.getValue(), email.getValue(), role.getValue(), enabled.getValue());
                if (!password.isEmpty()) {
                    accounts.resetPassword(u.id(), password.getValue());
                }
            }, "Gespeichert");
            if (ok) {
                d.close();
                refresh();
            }
        });
        save.addThemeVariants(ButtonVariant.PRIMARY);
        d.getFooter().add(new Button("Abbrechen", e -> d.close()), save);
        d.open();
    }

    private void confirmDelete(UserAccount u) {
        ConfirmDialog c = new ConfirmDialog("Benutzer löschen?",
                "„" + u.username() + "“ und alle Tokens werden gelöscht. Verbundene Clients verlieren den Zugriff.",
                "Löschen", e -> {
                    Ui.run(() -> accounts.delete(u.id()), "Benutzer gelöscht");
                    refresh();
                });
        c.setCancelable(true);
        c.setCancelText("Abbrechen");
        c.setConfirmButtonTheme("error primary");
        c.open();
    }

    private static Select<Role> roleSelect(Role value) {
        Select<Role> role = new Select<>();
        role.setLabel("Rolle");
        role.setItems(Role.values());
        role.setItemLabelGenerator(r -> r == Role.ADMIN ? "Administrator" : "Benutzer");
        role.setValue(value);
        return role;
    }

    private void refresh() {
        grid.setItems(accounts.users());
    }
}
