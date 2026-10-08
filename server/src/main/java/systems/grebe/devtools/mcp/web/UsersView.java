package systems.grebe.devtools.mcp.web;

import java.util.List;
import java.util.Set;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.checkbox.CheckboxGroup;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.EmailField;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.spring.security.AuthenticationContext;
import jakarta.annotation.security.RolesAllowed;
import systems.grebe.devtools.mcp.backend.account.AccountService;
import systems.grebe.devtools.mcp.backend.account.Role;
import systems.grebe.devtools.mcp.backend.account.RoleService;
import systems.grebe.devtools.mcp.backend.account.UserAccount;
import systems.grebe.devtools.mcp.web.WebLogin.AccountPrincipal;

/**
 * Benutzerverwaltung (Recht „Benutzer und Rollen verwalten“): anlegen, Rollen zuordnen, sperren, Passwort setzen,
 * löschen. Ein hier gesetztes Passwort muss der Benutzer standardmäßig bei der nächsten Anmeldung ändern.
 */
@Route("benutzer")
@PageTitle("Benutzer – DevTools MCP")
@RolesAllowed("USERS_MANAGE")
public class UsersView extends VerticalLayout {

    private final AccountService accounts;
    private final RoleService roles;
    private final long selfId;
    private final Grid<UserAccount> grid = new Grid<>();

    public UsersView(AccountService accounts, RoleService roles, AuthenticationContext auth) {
        this.accounts = accounts;
        this.roles = roles;
        this.selfId = auth.getAuthenticatedUser(AccountPrincipal.class).orElseThrow().id();

        grid.addColumn(UserAccount::username).setHeader("Benutzer").setAutoWidth(true);
        grid.addColumn(u -> u.displayName() == null ? "" : u.displayName()).setHeader("Name");
        grid.addColumn(u -> u.email() == null ? "" : u.email()).setHeader("E-Mail");
        grid.addColumn(u -> String.join(", ", u.roles())).setHeader("Rollen");
        grid.addColumn(u -> !u.enabled() ? "gesperrt"
                : u.passwordChangeRequired() ? "aktiv, muss Passwort ändern" : "aktiv").setHeader("Status");
        grid.addColumn(u -> u.lastLoginAt() == null ? "nie" : Ui.time(u.lastLoginAt())).setHeader("Letzte Anmeldung");
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
        add(new H2("Benutzer"), new Paragraph("Rechte vergeben die Rollen (Seite „Rollen“); hat ein Benutzer mehrere, "
                + "gelten alle ihre Rechte zusammen. Gesperrte Benutzer verlieren sofort ihre Anmeldungen."), create,
                grid);
        refresh();
    }

    private void create() {
        Dialog d = new Dialog();
        d.setHeaderTitle("Neuer Benutzer");
        TextField username = new TextField("Benutzername");
        TextField name = new TextField("Anzeigename");
        EmailField email = new EmailField("E-Mail");
        email.setHelperText("Eigentümer der Skills, Memories und Skripte");
        CheckboxGroup<String> roleBox = roleBox(roles.roles().stream().map(Role::name)
                .filter(Role.USER::equals).toList());
        PasswordField password = new PasswordField("Startpasswort");
        password.setHelperText("Mindestens 8 Zeichen.");
        Checkbox mustChange = new Checkbox("Muss das Passwort bei der ersten Anmeldung ändern", true);
        d.add(new FormLayout(username, name, email, password, mustChange, roleBox));
        Button save = new Button("Anlegen", e -> {
            if (Ui.run(() -> accounts.create(username.getValue(), name.getValue(), email.getValue(),
                    roleBox.getValue(), password.getValue(), mustChange.getValue()), "Benutzer angelegt")) {
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
        CheckboxGroup<String> roleBox = roleBox(u.roles());
        Checkbox enabled = new Checkbox("Aktiv", u.enabled());
        PasswordField password = new PasswordField("Neues Passwort");
        password.setHelperText("Leer lassen, um es nicht zu ändern.");
        Checkbox mustChange = new Checkbox("Muss das neue Passwort bei der nächsten Anmeldung ändern", true);
        mustChange.setEnabled(false);
        password.addValueChangeListener(e -> mustChange.setEnabled(!password.isEmpty()));
        d.add(new FormLayout(name, email, enabled, password, mustChange, roleBox));
        Button save = new Button("Speichern", e -> {
            boolean ok = Ui.run(() -> {
                accounts.update(u.id(), name.getValue(), email.getValue(), roleBox.getValue(), enabled.getValue());
                if (!password.isEmpty()) {
                    accounts.resetPassword(u.id(), password.getValue(), mustChange.getValue());
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
                "„" + u.username() + "“ samt Profilen, Projekten und Tokens wird gelöscht. Verbundene Desktop-Apps "
                        + "verlieren sofort den Zugriff.",
                "Löschen", e -> {
                    Ui.run(() -> accounts.delete(u.id()), "Benutzer gelöscht");
                    refresh();
                });
        c.setCancelable(true);
        c.setCancelText("Abbrechen");
        c.setConfirmButtonTheme("error primary");
        c.open();
    }

    private CheckboxGroup<String> roleBox(List<String> selected) {
        CheckboxGroup<String> box = new CheckboxGroup<>("Rollen");
        List<Role> all = roles.roles();
        box.setItems(all.stream().map(Role::name).toList());
        box.setItemHelperGenerator(name -> all.stream().filter(r -> r.name().equals(name)).findFirst()
                .map(r -> r.description() == null ? "" : r.description()).orElse(""));
        box.setValue(Set.copyOf(selected));
        return box;
    }

    private void refresh() {
        grid.setItems(accounts.users());
    }
}
