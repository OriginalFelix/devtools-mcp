package systems.grebe.devtools.mcp.web;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.checkbox.CheckboxGroup;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;
import systems.grebe.devtools.mcp.api.Grants;
import systems.grebe.devtools.mcp.api.ModuleDescriptor;
import systems.grebe.devtools.mcp.api.Permission;
import systems.grebe.devtools.mcp.backend.account.Role;
import systems.grebe.devtools.mcp.backend.account.RoleService;
import systems.grebe.devtools.mcp.backend.catalog.ModuleCatalog;

/**
 * Rollen und ihre Rechte (Recht „Benutzer und Rollen verwalten“): Systemrechte, dazu alle Module, einzelne Module
 * oder einzelne Tools. Die Module kommen aus dem Katalog, den die Desktop-Apps melden; Rechte auf noch unbekannte
 * Module (z.B. Plugins anderer Benutzer) lassen sich als Text eintragen.
 */
@Route("rollen")
@PageTitle("Rollen – DevTools MCP")
@RolesAllowed("USERS_MANAGE")
public class RolesView extends VerticalLayout {

    private final RoleService roles;
    private final ModuleCatalog catalog;
    private final Grid<Role> grid = new Grid<>();

    public RolesView(RoleService roles, ModuleCatalog catalog) {
        this.roles = roles;
        this.catalog = catalog;

        grid.addColumn(Role::name).setHeader("Rolle").setAutoWidth(true);
        grid.addColumn(r -> r.description() == null ? "" : r.description()).setHeader("Beschreibung");
        grid.addColumn(RolesView::summary).setHeader("Rechte");
        grid.addColumn(Role::users).setHeader("Benutzer").setAutoWidth(true);
        grid.addComponentColumn(r -> {
            Button edit = new Button("Bearbeiten", e -> edit(r));
            edit.addThemeVariants(ButtonVariant.TERTIARY);
            edit.setEnabled(!r.builtin());
            Button copy = new Button("Kopieren", e -> copy(r));
            copy.addThemeVariants(ButtonVariant.TERTIARY);
            copy.setEnabled(!r.builtin());
            Button delete = new Button("Löschen", e -> confirmDelete(r));
            delete.addThemeVariants(ButtonVariant.TERTIARY, ButtonVariant.ERROR);
            delete.setEnabled(!r.builtin());
            if (r.builtin()) {
                edit.setTooltipText("Eingebaut: alle Rechte, nicht änderbar");
            }
            return new HorizontalLayout(edit, copy, delete);
        }).setAutoWidth(true);
        grid.setAllRowsVisible(true);

        Button create = new Button("Neue Rolle", e -> open(null, "", null, Set.of()));
        create.addThemeVariants(ButtonVariant.PRIMARY);
        add(new H2("Rollen"), new Paragraph("Eine Rolle bündelt Rechte: Systemrechte (Verwaltung, Einstellungen, "
                + "Projekte, Vorlagen) und Rechte auf Module und Tools. Module und Tools ohne Recht sind in der "
                + "Desktop-App des Benutzers aus; schreibende Skills-, Memories- und Skript-Operationen prüft auch der "
                + "Server. Änderungen gelten sofort."), create, grid);
        refresh();
    }

    private void edit(Role r) {
        open(r.id(), r.name(), r.description(), r.permissions());
    }

    private void copy(Role r) {
        open(null, r.name() + " (Kopie)", r.description(), r.permissions());
    }

    /** Dialog zum Anlegen ({@code id == null}) oder Ändern. */
    private void open(Long id, String name, String description, Set<String> permissions) {
        Dialog d = new Dialog();
        d.setHeaderTitle(id == null ? "Neue Rolle" : "Rolle „" + name + "“");
        d.setWidth("min(60rem, 95vw)");
        Set<String> rest = new LinkedHashSet<>(permissions);

        TextField nameField = new TextField("Name");
        nameField.setValue(name);
        TextField descriptionField = new TextField("Beschreibung");
        descriptionField.setValue(description == null ? "" : description);
        FormLayout head = new FormLayout(nameField, descriptionField);

        CheckboxGroup<Permission> system = new CheckboxGroup<>("Systemrechte");
        system.setItems(Permission.values());
        system.setItemLabelGenerator(Permission::label);
        system.setItemHelperGenerator(Permission::description);
        system.setValue(Arrays.stream(Permission.values()).filter(p -> rest.remove(p.key()))
                .collect(Collectors.toSet()));

        Checkbox allModules = new Checkbox("Alle Module und Tools – auch künftige aus Plugins und Skripten");
        allModules.setValue(rest.remove(Grants.ALL_MODULES));
        VerticalLayout moduleList = new VerticalLayout();
        moduleList.setPadding(false);
        moduleList.setSpacing(false);
        List<ModuleEditor> editors = new ArrayList<>();
        for (ModuleDescriptor m : modules()) {
            ModuleEditor editor = new ModuleEditor(m, rest);
            editors.add(editor);
            moduleList.add(editor.details);
        }
        if (editors.isEmpty()) {
            moduleList.add(new Paragraph("Noch keine Module bekannt – sie erscheinen, sobald sich eine Desktop-App "
                    + "verbunden hat."));
        }
        moduleList.setEnabled(!allModules.getValue());
        allModules.addValueChangeListener(e -> moduleList.setEnabled(!e.getValue()));

        TextArea extra = new TextArea("Weitere Rechte");
        extra.setHelperText("Je Zeile ein Recht auf ein Modul oder Tool, das hier nicht aufgeführt ist, "
                + "z.B. module:jira oder tool:jira_issue.");
        extra.setValue(String.join("\n", rest));
        extra.setWidthFull();

        d.add(new VerticalLayout(head, system, new H3("Module und Tools"), allModules, moduleList, extra));
        Button save = new Button(id == null ? "Anlegen" : "Speichern", e -> {
            List<String> result = new ArrayList<>();
            system.getValue().stream().sorted().map(Permission::key).forEach(result::add);
            if (allModules.getValue()) {
                result.add(Grants.ALL_MODULES);
            } else {
                editors.forEach(ed -> result.addAll(ed.permissions()));
            }
            extra.getValue().lines().map(String::strip).filter(l -> !l.isEmpty()).forEach(result::add);
            boolean ok = Ui.run(() -> {
                if (id == null) {
                    roles.create(nameField.getValue(), descriptionField.getValue(), result);
                } else {
                    roles.update(id, nameField.getValue(), descriptionField.getValue(), result);
                }
            }, id == null ? "Rolle angelegt" : "Gespeichert");
            if (ok) {
                d.close();
                refresh();
            }
        });
        save.addThemeVariants(ButtonVariant.PRIMARY);
        d.getFooter().add(new Button("Abbrechen", e -> d.close()), save);
        d.open();
    }

    /** Module mit Tools aus dem Katalog, in der Reihenfolge der Desktop-App. */
    private List<ModuleDescriptor> modules() {
        return catalog.modules().stream().filter(m -> !m.tools().isEmpty())
                .sorted(Comparator.comparingInt(ModuleDescriptor::order)
                        .thenComparing(ModuleDescriptor::displayName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /** Ein Modul im Dialog: ganz oder einzelne Tools; nimmt seine Rechte aus {@code rest}. */
    private static final class ModuleEditor {
        final ModuleDescriptor module;
        final Details details;
        final Checkbox full;
        final CheckboxGroup<String> tools = new CheckboxGroup<>();

        ModuleEditor(ModuleDescriptor module, Set<String> rest) {
            this.module = module;
            Map<String, String> descriptions = new LinkedHashMap<>();
            module.tools().forEach(t -> descriptions.put(t.name(), t.description() == null ? ""
                    : shorten(t.description())));
            full = new Checkbox("Ganzes Modul (alle Tools, auch künftige)", rest.remove(Grants.module(module.id())));
            tools.setItems(descriptions.keySet());
            tools.setItemHelperGenerator(descriptions::get);
            tools.setValue(descriptions.keySet().stream().filter(t -> rest.remove(Grants.tool(t)))
                    .collect(Collectors.toSet()));
            tools.setEnabled(!full.getValue());
            full.addValueChangeListener(e -> {
                tools.setEnabled(!e.getValue());
                updateSummary();
            });
            tools.addValueChangeListener(e -> updateSummary());
            VerticalLayout content = new VerticalLayout(full, tools);
            content.setPadding(false);
            details = new Details("", content);
            updateSummary();
        }

        void updateSummary() {
            int n = tools.getValue().size();
            String state = full.getValue() ? "ganzes Modul"
                    : n == 0 ? "kein Zugriff" : n + " von " + module.tools().size() + " Tools";
            details.setSummaryText(module.displayName() + " (" + module.id() + ") – " + state);
        }

        List<String> permissions() {
            if (full.getValue()) {
                return List.of(Grants.module(module.id()));
            }
            return tools.getValue().stream().sorted().map(Grants::tool).toList();
        }

        private static String shorten(String s) {
            String line = s.lines().findFirst().orElse("").strip();
            return line.length() > 140 ? line.substring(0, 139) + "…" : line;
        }
    }

    private void confirmDelete(Role r) {
        ConfirmDialog c = new ConfirmDialog("Rolle löschen?",
                "„" + r.name() + "“ wird gelöscht; " + r.users() + " Benutzer verlieren ihre Rechte aus dieser Rolle.",
                "Löschen", e -> {
                    Ui.run(() -> roles.delete(r.id()), "Rolle gelöscht");
                    refresh();
                });
        c.setCancelable(true);
        c.setCancelText("Abbrechen");
        c.setConfirmButtonTheme("error primary");
        c.open();
    }

    /** Kurzfassung der Rechte für die Tabelle. */
    static String summary(Role r) {
        Grants g = r.grants();
        if (g.all()) {
            return "alle Rechte";
        }
        long system = Arrays.stream(Permission.values()).filter(g::has).count();
        long modules = r.permissions().stream().filter(p -> p.startsWith(Grants.MODULE_PREFIX)
                && !p.equals(Grants.ALL_MODULES)).count();
        long tools = r.permissions().stream().filter(p -> p.startsWith(Grants.TOOL_PREFIX)).count();
        List<String> parts = new ArrayList<>();
        parts.add(system + " Systemrecht" + (system == 1 ? "" : "e"));
        if (g.allModules()) {
            parts.add("alle Module");
        } else {
            parts.add(modules + " Modul" + (modules == 1 ? "" : "e"));
            if (tools > 0) {
                parts.add(tools + " einzelne Tool" + (tools == 1 ? "" : "s"));
            }
        }
        return String.join(", ", parts);
    }

    private void refresh() {
        grid.setItems(roles.roles());
    }
}
