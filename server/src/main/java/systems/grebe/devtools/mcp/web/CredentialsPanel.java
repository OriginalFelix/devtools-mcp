package systems.grebe.devtools.mcp.web;

import java.util.ArrayList;
import java.util.List;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import systems.grebe.devtools.mcp.api.CredentialInfo;
import systems.grebe.devtools.mcp.backend.account.CredentialService;

/**
 * Eigene Zugangsdaten unter „Mein Konto“ (Modul „Zugangsdaten“ der Desktop-App, wie beim Git Credential Manager).
 * Die Seite bekommt die Geheimnisse nie: sie arbeitet mit {@link CredentialService#summaries} und schickt beim
 * Speichern nur ein neu eingegebenes Token mit – die übrigen Einträge behalten ihres über ihre ID.
 */
class CredentialsPanel extends VerticalLayout {

    private final CredentialService credentials;
    private final long userId;
    private final boolean editable;
    private final Grid<CredentialInfo> grid = new Grid<>();
    private List<CredentialInfo> entries = List.of();

    CredentialsPanel(CredentialService credentials, long userId, boolean editable) {
        this.credentials = credentials;
        this.userId = userId;
        this.editable = editable;
        setPadding(false);

        grid.addColumn(CredentialInfo::name).setHeader("Name").setAutoWidth(true);
        grid.addColumn(CredentialInfo::url).setHeader("URL").setAutoWidth(true);
        grid.addColumn(c -> c.username() == null ? "" : c.username()).setHeader("Benutzer").setAutoWidth(true);
        grid.addColumn(c -> "••••••").setHeader("Token / Passwort");
        grid.addColumn(c -> c.description() == null ? "" : c.description()).setHeader("Beschreibung");
        if (editable) {
            grid.addComponentColumn(this::actions).setAutoWidth(true).setFrozenToEnd(true);
        }
        grid.setAllRowsVisible(true);

        Button create = new Button("Neue Zugangsdaten", e -> edit(null));
        create.addThemeVariants(ButtonVariant.PRIMARY);
        create.setVisible(editable);
        add(new Paragraph("Tokens und Passwörter je Server, einmal hinterlegt: Bleibt in einem Modul der Desktop-App "
                        + "(Tickets, Pull Requests, CI, SonarQube, Maven …) das Token leer, nimmt es den Eintrag, dessen "
                        + "URL zu seiner Server-URL passt – auch für Subdomains (https://github.com gilt für "
                        + "api.github.com) und Pfade darunter; der genaueste gewinnt, bei Gleichstand der weiter oben. "
                        + "Gespeichert verschlüsselt im Backend, nur für dich, in allen deinen Profilen. "
                        + (editable ? "" : "Zum Ändern fehlt dir das Recht „Eigene Einstellungen“.")),
                create, grid);
        refresh();
    }

    private HorizontalLayout actions(CredentialInfo c) {
        int index = entries.indexOf(c);
        Button up = new Button("↑", e -> move(index, -1));
        up.setEnabled(index > 0);
        up.setTooltipText("Nach oben – gewinnt bei gleich gut passenden Einträgen");
        Button down = new Button("↓", e -> move(index, 1));
        down.setEnabled(index < entries.size() - 1);
        Button edit = new Button("Bearbeiten", e -> edit(c));
        Button delete = new Button("Löschen", e -> delete(c));
        up.addThemeVariants(ButtonVariant.TERTIARY);
        down.addThemeVariants(ButtonVariant.TERTIARY);
        edit.addThemeVariants(ButtonVariant.TERTIARY);
        delete.addThemeVariants(ButtonVariant.TERTIARY, ButtonVariant.ERROR);
        return new HorizontalLayout(up, down, edit, delete);
    }

    private void edit(CredentialInfo c) {
        Dialog d = new Dialog();
        d.setHeaderTitle(c == null ? "Neue Zugangsdaten" : "Zugangsdaten „" + c.name() + "“");
        TextField name = new TextField("Name");
        name.setPlaceholder("z.B. GitHub");
        TextField url = new TextField("URL");
        url.setPlaceholder("https://github.com");
        url.setHelperText("Server, für den sie gelten – auch Subdomains und Pfade darunter");
        TextField username = new TextField("Benutzer");
        username.setHelperText("Optional, z.B. die E-Mail zum Jira-Cloud-Token");
        PasswordField secret = new PasswordField("Token / Passwort");
        TextField description = new TextField("Beschreibung");
        if (c != null) {
            name.setValue(c.name());
            url.setValue(c.url());
            username.setValue(nz(c.username()));
            description.setValue(nz(c.description()));
            secret.setHelperText("Leer lassen, um das bisherige zu behalten");
        }
        d.add(new FormLayout(name, url, username, secret, description));
        Button save = new Button("Speichern", e -> {
            CredentialInfo edited = new CredentialInfo(c == null ? null : c.id(), name.getValue(), url.getValue(),
                    username.getValue(), secret.getValue(), description.getValue());
            List<CredentialInfo> next = new ArrayList<>(entries);
            if (c == null) {
                next.add(edited);
            } else {
                next.set(entries.indexOf(c), edited);
            }
            if (save(next, "Gespeichert")) {
                d.close();
            }
        });
        save.addThemeVariants(ButtonVariant.PRIMARY);
        d.getFooter().add(new Button("Abbrechen", e -> d.close()), save);
        d.setWidth("44rem");
        d.open();
    }

    private void delete(CredentialInfo c) {
        ConfirmDialog confirm = new ConfirmDialog("Zugangsdaten löschen",
                "„" + c.name() + "“ löschen? Module, die sie verwenden, haben danach kein Token mehr.",
                "Löschen", e -> {
                    List<CredentialInfo> next = new ArrayList<>(entries);
                    next.remove(c);
                    save(next, "Gelöscht");
                });
        confirm.setCancelable(true);
        confirm.setCancelText("Abbrechen");
        confirm.setConfirmButtonTheme("error primary");
        confirm.open();
    }

    private void move(int index, int delta) {
        List<CredentialInfo> next = new ArrayList<>(entries);
        next.add(index + delta, next.remove(index));
        save(next, null);
    }

    /** Speichert die ganze Liste; Einträge ohne neues Token behalten ihres über die ID. */
    private boolean save(List<CredentialInfo> next, String success) {
        boolean ok = Ui.run(() -> credentials.replace(userId, next), success);
        refresh();
        return ok;
    }

    private void refresh() {
        entries = credentials.summaries(userId);
        grid.setItems(entries);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
