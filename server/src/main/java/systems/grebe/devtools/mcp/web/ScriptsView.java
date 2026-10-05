package systems.grebe.devtools.mcp.web;

import java.util.Optional;
import java.util.function.Supplier;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.splitlayout.SplitLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.spring.security.AuthenticationContext;
import jakarta.annotation.security.PermitAll;
import systems.grebe.devtools.mcp.backend.SkillCaller;
import systems.grebe.devtools.mcp.backend.account.AccountService;
import systems.grebe.devtools.mcp.backend.account.Role;
import systems.grebe.devtools.mcp.backend.account.UserAccount;
import systems.grebe.devtools.mcp.backend.scripts.ScriptService;
import systems.grebe.devtools.mcp.modules.scripts.ScriptTemplates;
import systems.grebe.devtools.mcp.modules.scripts.ScriptViews;
import systems.grebe.devtools.mcp.web.WebLogin.AccountPrincipal;

/**
 * Skripte (Groovy oder Java) des Benutzers und globale Vorlagen: ansehen, bearbeiten (mit Syntaxprüfung, ohne Ausführung),
 * Historie, löschen; Administratoren veröffentlichen und ziehen Vorlagen zurück. Ausgeführt werden Skripte nur in den
 * Desktop-Apps – die übernehmen Änderungen sofort (Subscription {@code scriptsChanged}) und melden dort, ob das
 * Skript lädt.
 */
@Route("skripte")
@PageTitle("Skripte – DevTools MCP")
@PermitAll
public class ScriptsView extends VerticalLayout {

    private final ScriptService scripts;
    private final AccountService accounts;
    private final long userId;
    private final boolean admin;
    private final Grid<ScriptViews.Summary> grid = new Grid<>();
    private final H3 title = new H3();
    private final Span meta = new Span();
    private final TextArea editor = new TextArea();
    private final TextField note = new TextField("Notiz zur Änderung");
    private final Grid<ScriptViews.Revision> history = new Grid<>();
    private final Button save = new Button("Speichern");
    private final Button delete = new Button("Löschen");
    private final Button publish = new Button("Als Vorlage veröffentlichen");
    private final Button unpublish = new Button("Vorlage zurückziehen");
    private final VerticalLayout detail = new VerticalLayout();

    /** Skript im Editor: Name und Revision, auf der eine Änderung beruht; {@code null} = keines gewählt. */
    private String current;
    private Integer currentRevision;

    public ScriptsView(ScriptService scripts, AccountService accounts, AuthenticationContext auth) {
        this.scripts = scripts;
        this.accounts = accounts;
        this.userId = auth.getAuthenticatedUser(AccountPrincipal.class).orElseThrow().id();
        this.admin = auth.hasRole(Role.ADMIN.name());
        setSizeFull();

        grid.addColumn(ScriptViews.Summary::name).setHeader("Name").setAutoWidth(true);
        grid.addColumn(s -> label(s.language())).setHeader("Sprache").setAutoWidth(true);
        grid.addColumn(s -> s.global() ? "global" : "eigen").setHeader("Herkunft").setAutoWidth(true);
        grid.addColumn(ScriptViews.Summary::revision).setHeader("Rev.").setAutoWidth(true);
        grid.addColumn(ScriptViews.Summary::description).setHeader("Beschreibung").setFlexGrow(1);
        grid.addColumn(s -> Ui.time(s.updatedAt())).setHeader("Geändert").setAutoWidth(true);
        grid.addSelectionListener(e -> e.getFirstSelectedItem().ifPresent(s -> show(s.name())));
        grid.setHeightFull();

        Button create = new Button("Neues Skript", e -> create());
        create.addThemeVariants(ButtonVariant.PRIMARY);

        buildDetail();
        SplitLayout split = new SplitLayout(new VerticalLayout(create, grid), detail);
        split.setSplitterPosition(40);
        split.setSizeFull();

        add(new H2("Skripte"), new Paragraph("Skripte (Groovy oder Java) ergänzen deine Desktop-Apps zur Laufzeit um eigene "
                + "Module mit Tools (Präfix = Skriptname). Hier prüft der Server nur die Syntax – ob ein Skript lädt "
                + "und welche Tools entstehen, zeigt die Desktop-App (Tab „Skripte“), die Änderungen sofort "
                + "übernimmt." + (admin ? " Globale Vorlagen laufen in den Desktop-Apps aller Benutzer." : "")),
                split);
        refresh();
        showNone();
    }

    private void buildDetail() {
        meta.getStyle().set("color", "var(--lumo-secondary-text-color)").set("font-size", "var(--lumo-font-size-s)");
        editor.setWidthFull();
        editor.setHeight("26rem");
        editor.getStyle().set("font-family", "var(--lumo-font-family-monospace, monospace)")
                .set("font-size", "var(--lumo-font-size-s)");
        note.setWidthFull();
        save.addThemeVariants(ButtonVariant.PRIMARY);
        save.addClickListener(e -> save());
        delete.addThemeVariants(ButtonVariant.ERROR);
        delete.addClickListener(e -> confirm("Skript löschen?", "„" + current + "“ samt Historie wird gelöscht; "
                + "seine Tools verschwinden aus deinen Desktop-Apps.", "Löschen", () -> scripts.delete(current), true));
        publish.addClickListener(e -> confirm("Als globale Vorlage veröffentlichen?", "„" + current + "“ läuft "
                + "danach in den Desktop-Apps aller Benutzer.", "Veröffentlichen", () -> scripts.publish(current), false));
        unpublish.addThemeVariants(ButtonVariant.ERROR);
        unpublish.addClickListener(e -> confirm("Vorlage zurückziehen?", "Die globale Vorlage „" + current
                + "“ verschwindet aus allen Desktop-Apps (eigene Skripte gleichen Namens bleiben).", "Zurückziehen",
                () -> scripts.unpublish(current), true));
        publish.setVisible(admin);
        unpublish.setVisible(admin);
        HorizontalLayout actions = new HorizontalLayout(save, delete, publish, unpublish);
        actions.setAlignItems(FlexComponent.Alignment.CENTER);

        history.addColumn(ScriptViews.Revision::revision).setHeader("Rev.").setAutoWidth(true);
        history.addColumn(r -> Ui.time(r.changedAt())).setHeader("Zeit").setAutoWidth(true);
        history.addColumn(ScriptViews.Revision::action).setHeader("Aktion").setAutoWidth(true);
        history.addColumn(r -> r.changedBy() == null ? "" : r.changedBy()).setHeader("Von").setAutoWidth(true);
        history.addColumn(r -> r.note() == null ? "" : r.note()).setHeader("Notiz").setFlexGrow(1);
        history.addComponentColumn(r -> {
            Button take = new Button("In den Editor", e -> editor.setValue(r.content()));
            take.addThemeVariants(ButtonVariant.TERTIARY);
            return take;
        }).setAutoWidth(true);
        history.setAllRowsVisible(true);

        detail.add(title, meta, editor, note, actions, new H3("Historie"), history);
    }

    /** Liste neu laden; ohne E-Mail im Konto gibt es keine Skripte – dann nur der Hinweis. */
    private void refresh() {
        Ui.run(() -> grid.setItems(as(scripts::overview)), null);
    }

    private void show(String name) {
        Optional<ScriptViews.Details> d = as(() -> scripts.details(name));
        if (d.isEmpty()) {
            showNone();
            return;
        }
        ScriptViews.Summary s = d.get().summary();
        current = s.name();
        currentRevision = s.revision();
        title.setText(s.name());
        meta.setText(label(s.language()) + " · " + (s.global() ? "Globale Vorlage – Speichern legt ein eigenes Skript an, das sie verdeckt"
                : "Eigenes Skript") + " · Revision " + s.revision() + " · geändert " + Ui.time(s.updatedAt())
                + (s.updatedBy() == null ? "" : " von " + s.updatedBy()));
        editor.setValue(d.get().content());
        note.clear();
        history.setItems(d.get().revisions());
        delete.setEnabled(!s.global());
        publish.setEnabled(!s.global());
        unpublish.setEnabled(s.global());
        detail.setVisible(true);
    }

    private void showNone() {
        current = null;
        currentRevision = null;
        detail.setVisible(false);
    }

    private void create() {
        Dialog d = new Dialog();
        d.setHeaderTitle("Neues Skript");
        TextField name = new TextField("Name");
        name.setHelperText("2–32 Kleinbuchstaben/Ziffern, z.B. jira – Modul-ID und Tool-Präfix");
        name.setWidthFull();
        Select<ScriptViews.Language> language = new Select<>();
        language.setLabel("Sprache");
        language.setItems(ScriptViews.Language.values());
        language.setItemLabelGenerator(ScriptsView::label);
        language.setValue(ScriptViews.Language.GROOVY);
        language.setHelperText("Java braucht ein JDK auf den Rechnern mit der Desktop-App");
        d.add(new VerticalLayout(name, language));
        Button ok = new Button("Anlegen", e -> {
            String n = name.getValue().strip();
            ScriptViews.Language l = language.getValue();
            if (Ui.run(() -> as(() -> scripts.save(n, l, null, ScriptTemplates.of(l), "angelegt in der Web-UI",
                    null)), "Angelegt")) {
                d.close();
                refresh();
                show(n);
            }
        });
        ok.addThemeVariants(ButtonVariant.PRIMARY);
        d.getFooter().add(new Button("Abbrechen", e -> d.close()), ok);
        d.setWidth("28rem");
        d.open();
    }

    private void save() {
        String name = current;
        String[] message = new String[1];
        if (Ui.run(() -> message[0] = as(() -> scripts.save(name, null, editor.getValue(), note.getValue(),
                currentRevision)), null)) {
            Ui.ok(message[0]);
            refresh();
            show(name);
        }
    }

    private void confirm(String header, String text, String confirmText, Supplier<String> action, boolean danger) {
        ConfirmDialog c = new ConfirmDialog(header, text, confirmText, e -> {
            String[] message = new String[1];
            if (Ui.run(() -> message[0] = as(action), null)) {
                Ui.ok(message[0]);
                refresh();
                Optional.ofNullable(current).filter(n -> as(scripts::overview).stream()
                        .anyMatch(s -> s.name().equals(n))).ifPresentOrElse(this::show, this::showNone);
            }
        });
        c.setCancelable(true);
        c.setCancelText("Abbrechen");
        if (danger) {
            c.setConfirmButtonTheme("error primary");
        }
        c.open();
    }

    static String label(ScriptViews.Language l) {
        return l == ScriptViews.Language.JAVA ? "Java" : "Groovy";
    }

    /** Skripte gehören der Konto-E-Mail – wie bei den Desktop-Apps über GraphQL. */
    private <T> T as(Supplier<T> body) {
        UserAccount user = accounts.user(userId).orElseThrow();
        return SkillCaller.as(user, body);
    }
}
