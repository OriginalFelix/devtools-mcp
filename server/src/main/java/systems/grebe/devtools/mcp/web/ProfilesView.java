package systems.grebe.devtools.mcp.web;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.spring.security.AuthenticationContext;
import jakarta.annotation.security.PermitAll;
import systems.grebe.devtools.mcp.web.WebLogin.AccountPrincipal;
import systems.grebe.devtools.mcp.backend.profile.Profile;
import systems.grebe.devtools.mcp.backend.profile.ProfileService;

/** Eigene Profile anlegen, kopieren, umbenennen, löschen und aktivieren. */
@Route("profile")
@PageTitle("Profile – DevTools MCP")
@PermitAll
public class ProfilesView extends VerticalLayout {

    private final ProfileService profiles;
    private final long userId;
    private final Grid<Profile> grid = new Grid<>();

    public ProfilesView(ProfileService profiles, AuthenticationContext auth) {
        this.profiles = profiles;
        this.userId = auth.getAuthenticatedUser(AccountPrincipal.class).orElseThrow().id();

        grid.addColumn(Profile::name).setHeader("Profil").setAutoWidth(true);
        grid.addColumn(p -> p.description() == null ? "" : p.description()).setHeader("Beschreibung");
        grid.addColumn(p -> p.id() == profiles.activeProfile(userId).id() ? "aktiv" : "").setHeader("Status");
        grid.addComponentColumn(p -> {
            Button activate = new Button("Aktivieren", e -> {
                Ui.run(() -> profiles.activate(userId, p.id()), "Profil „" + p.name() + "“ aktiv");
                refresh();
            });
            activate.setEnabled(p.id() != profiles.activeProfile(userId).id());
            Button edit = new Button("Bearbeiten", e -> edit(p));
            Button copy = new Button("Kopieren", e -> copy(p));
            Button delete = new Button("Löschen", e -> delete(p));
            for (Button b : new Button[] {activate, edit, copy, delete}) {
                b.addThemeVariants(ButtonVariant.TERTIARY);
            }
            delete.addThemeVariants(ButtonVariant.ERROR);
            return new HorizontalLayout(activate, edit, copy, delete);
        }).setAutoWidth(true);
        grid.setAllRowsVisible(true);

        Button create = new Button("Neues Profil", e -> edit(null));
        create.addThemeVariants(ButtonVariant.PRIMARY);
        add(new H2("Profile"), new Paragraph("Ein Profil bündelt Einstellungen (z.B. Work, Home). Deine MCP-Clients "
                + "arbeiten mit dem aktiven Profil; ein Wechsel gilt sofort, offene SSH-Sitzungen u.ä. des alten "
                + "Profils werden geschlossen."), create, grid);
        refresh();
    }

    private void edit(Profile p) {
        Dialog d = new Dialog();
        d.setHeaderTitle(p == null ? "Neues Profil" : "Profil bearbeiten");
        TextField name = new TextField("Name");
        TextField description = new TextField("Beschreibung");
        if (p != null) {
            name.setValue(p.name());
            description.setValue(p.description() == null ? "" : p.description());
        }
        d.add(new FormLayout(name, description));
        Button save = new Button("Speichern", e -> {
            boolean ok = Ui.run(() -> {
                if (p == null) {
                    profiles.create(userId, name.getValue(), description.getValue());
                } else {
                    profiles.update(userId, p.id(), name.getValue(), description.getValue());
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

    private void copy(Profile p) {
        Dialog d = new Dialog();
        d.setHeaderTitle("„" + p.name() + "“ kopieren");
        TextField name = new TextField("Name der Kopie");
        name.setValue(p.name() + " (Kopie)");
        d.add(name);
        Button save = new Button("Kopieren", e -> {
            if (Ui.run(() -> profiles.copy(userId, p.id(), name.getValue()), "Profil kopiert")) {
                d.close();
                refresh();
            }
        });
        save.addThemeVariants(ButtonVariant.PRIMARY);
        d.getFooter().add(new Button("Abbrechen", e -> d.close()), save);
        d.open();
    }

    private void delete(Profile p) {
        ConfirmDialog c = new ConfirmDialog("Profil löschen?",
                "„" + p.name() + "“ und seine Einstellungen werden gelöscht.", "Löschen", e -> {
                    Ui.run(() -> profiles.delete(userId, p.id()), "Profil gelöscht");
                    refresh();
                });
        c.setCancelable(true);
        c.setCancelText("Abbrechen");
        c.setConfirmButtonTheme("error primary");
        c.open();
    }

    private void refresh() {
        grid.setItems(profiles.profiles(userId));
        // Profilauswahl im Kopf mitziehen (im Konstruktor noch nicht eingehängt)
        MainLayout layout = findAncestor(MainLayout.class);
        if (layout != null) {
            layout.refreshProfiles();
        }
    }
}
