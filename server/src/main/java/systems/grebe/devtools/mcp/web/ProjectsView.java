package systems.grebe.devtools.mcp.web;

import java.util.List;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.spring.security.AuthenticationContext;
import jakarta.annotation.security.PermitAll;
import systems.grebe.devtools.mcp.backend.account.AccountService;
import systems.grebe.devtools.mcp.web.WebLogin.AccountPrincipal;
import systems.grebe.devtools.mcp.backend.account.Role;
import systems.grebe.devtools.mcp.backend.account.UserAccount;
import systems.grebe.devtools.mcp.backend.project.Project;
import systems.grebe.devtools.mcp.backend.project.ProjectService;

/** Eigene und freigegebene Projekte; Eigentümer (und Administratoren) legen an, ändern und geben frei. */
@Route("projekte")
@PageTitle("Projekte – DevTools MCP")
@PermitAll
public class ProjectsView extends VerticalLayout {

    private final ProjectService projects;
    private final AccountService accounts;
    private final long userId;
    private final boolean admin;
    private final Grid<Project.Visible> grid = new Grid<>();
    private final Checkbox showAll = new Checkbox("Alle Projekte (Administrator)");

    public ProjectsView(ProjectService projects, AccountService accounts, AuthenticationContext auth) {
        this.projects = projects;
        this.accounts = accounts;
        this.userId = auth.getAuthenticatedUser(AccountPrincipal.class).orElseThrow().id();
        this.admin = auth.hasRole(Role.ADMIN.name());

        grid.addColumn(Project.Visible::toolName).setHeader("Name (in Tools)").setAutoWidth(true);
        grid.addColumn(v -> v.access().label()).setHeader("Zugriff").setAutoWidth(true);
        grid.addColumn(v -> nz(v.project().description())).setHeader("Beschreibung");
        grid.addColumn(v -> nz(v.project().sonarKey())).setHeader("Sonar");
        grid.addColumn(v -> nz(v.project().ticketProject())).setHeader("Tickets");
        grid.addComponentColumn(v -> {
            HorizontalLayout actions = new HorizontalLayout();
            if (v.access() == Project.Access.OWNER || admin) {
                Button edit = new Button("Bearbeiten", e -> edit(v.project()));
                Button share = new Button("Freigaben", e -> shares(v.project()));
                Button delete = new Button("Löschen", e -> delete(v.project()));
                edit.addThemeVariants(ButtonVariant.TERTIARY);
                share.addThemeVariants(ButtonVariant.TERTIARY);
                delete.addThemeVariants(ButtonVariant.TERTIARY, ButtonVariant.ERROR);
                actions.add(edit, share, delete);
            }
            return actions;
        }).setAutoWidth(true);
        grid.setAllRowsVisible(true);

        Button create = new Button("Neues Projekt", e -> edit(null));
        create.addThemeVariants(ButtonVariant.PRIMARY);
        showAll.setVisible(admin);
        showAll.addValueChangeListener(e -> refresh());
        HorizontalLayout bar = new HorizontalLayout(create, showAll);
        bar.setAlignItems(FlexComponent.Alignment.CENTER);

        add(new H2("Projekte"), new Paragraph("Git, Build und Code-Graph deiner Desktop-App arbeiten mit diesen "
                + "Projekten – das Verzeichnis ordnest du in der Desktop-App zu. Fremde Projekte heißen in den Tools "
                + "name@eigentümer; „nur lesen“ verbietet Commits, Builds und Graph-Aufbau."), bar, grid);
        refresh();
    }

    private void edit(Project p) {
        Dialog d = new Dialog();
        d.setHeaderTitle(p == null ? "Neues Projekt" : "Projekt „" + p.name() + "“");
        TextField name = new TextField("Name");
        TextField description = new TextField("Beschreibung");
        TextField sonar = new TextField("Sonar-Projektschlüssel");
        TextField ticket = new TextField("Ticket-Projekt");
        ticket.setHelperText("z.B. ABC, owner/repo, gruppe/projekt");
        if (p != null) {
            name.setValue(p.name());
            description.setValue(nz(p.description()));
            sonar.setValue(nz(p.sonarKey()));
            ticket.setValue(nz(p.ticketProject()));
        }
        FormLayout form = new FormLayout(name, description, sonar, ticket);
        d.add(form);
        Button save = new Button("Speichern", e -> {
            boolean ok = Ui.run(() -> {
                if (p == null) {
                    projects.create(userId, name.getValue(), description.getValue(),
                            sonar.getValue(), ticket.getValue());
                } else {
                    projects.update(userId, p.id(), name.getValue(), description.getValue(),
                            sonar.getValue(), ticket.getValue());
                }
            }, "Gespeichert");
            if (ok) {
                d.close();
                refresh();
            }
        });
        save.addThemeVariants(ButtonVariant.PRIMARY);
        d.getFooter().add(new Button("Abbrechen", e -> d.close()), save);
        d.setWidth("44rem");
        d.open();
    }

    private void shares(Project p) {
        Dialog d = new Dialog();
        d.setHeaderTitle("Freigaben „" + p.name() + "“");
        Grid<Project.Share> list = new Grid<>();
        list.addColumn(Project.Share::userName).setHeader("Benutzer");
        list.addComponentColumn(s -> {
            Select<Project.Access> access = accessSelect(s.access());
            access.addValueChangeListener(e -> {
                if (e.isFromClient()) {
                    Ui.run(() -> projects.share(userId, p.id(), s.userName(), e.getValue()), "Freigabe geändert");
                }
            });
            return access;
        }).setHeader("Zugriff");
        list.addComponentColumn(s -> {
            Button remove = new Button("Entfernen", e -> {
                Ui.run(() -> projects.unshare(userId, p.id(), s.userId()), "Freigabe entfernt");
                list.setItems(projects.shares(userId, p.id()));
            });
            remove.addThemeVariants(ButtonVariant.TERTIARY, ButtonVariant.ERROR);
            return remove;
        });
        list.setAllRowsVisible(true);
        list.setItems(projects.shares(userId, p.id()));

        ComboBox<String> user = new ComboBox<>("Benutzer");
        user.setItems(accounts.users().stream().filter(u -> u.id() != p.ownerId()).map(UserAccount::username).toList());
        Select<Project.Access> access = accessSelect(Project.Access.READ);
        Button add = new Button("Freigeben", e -> {
            if (user.getValue() != null && Ui.run(() -> projects.share(userId, p.id(), user.getValue(),
                    access.getValue()), "Freigegeben")) {
                user.clear();
                list.setItems(projects.shares(userId, p.id()));
            }
        });
        add.addThemeVariants(ButtonVariant.PRIMARY);
        HorizontalLayout addRow = new HorizontalLayout(user, access, add);
        addRow.setAlignItems(FlexComponent.Alignment.BASELINE);
        d.add(new VerticalLayout(addRow, list));
        d.getFooter().add(new Button("Schließen", e -> d.close()));
        d.setWidth("40rem");
        d.open();
    }

    private void delete(Project p) {
        ConfirmDialog c = new ConfirmDialog("Projekt löschen?", "„" + p.name() + "“ und alle Freigaben werden "
                + "entfernt. Die Verzeichnisse in den Desktop-Apps bleiben unverändert.", "Löschen", e -> {
                    Ui.run(() -> projects.delete(userId, p.id()), "Projekt gelöscht");
                    refresh();
                });
        c.setCancelable(true);
        c.setCancelText("Abbrechen");
        c.setConfirmButtonTheme("error primary");
        c.open();
    }

    private static Select<Project.Access> accessSelect(Project.Access value) {
        Select<Project.Access> s = new Select<>();
        s.setItems(Project.Access.READ, Project.Access.WRITE);
        s.setItemLabelGenerator(Project.Access::label);
        s.setValue(value);
        return s;
    }

    private void refresh() {
        if (admin && showAll.getValue()) {
            grid.setItems(projects.all().stream().map(p -> new Project.Visible(p,
                    p.ownerId() == userId ? Project.Access.OWNER : Project.Access.WRITE)).toList());
        } else {
            grid.setItems(projects.visible(userId));
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
