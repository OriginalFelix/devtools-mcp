package systems.grebe.devtools.mcp.web;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.listbox.ListBox;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.data.renderer.TextRenderer;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;
import systems.grebe.devtools.mcp.api.ModuleDescriptor;
import systems.grebe.devtools.mcp.catalog.ModuleCatalog;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.profile.Overrides;
import systems.grebe.devtools.mcp.profile.ProfileService;

/**
 * Globale Vorgaben und Sperren (Administratoren): Was hier vorgegeben ist, gilt in allen Desktop-Apps, soweit Benutzer
 * oder Profile nichts überschreiben; gesperrte Felder können Benutzer und Profile nicht überschreiben. Was nicht
 * vorgegeben ist, stellt jeder in seiner Desktop-App selbst ein.
 */
@Route("global")
@PageTitle("Globale Einstellungen – DevTools MCP")
@RolesAllowed("ADMIN")
public class GlobalSettingsView extends HorizontalLayout {

    private final ProfileService profiles;
    private final ListBox<ModuleDescriptor> moduleList = new ListBox<>();
    private final VerticalLayout panel = new VerticalLayout();

    public GlobalSettingsView(ModuleCatalog catalog, ProfileService profiles) {
        this.profiles = profiles;
        setSizeFull();
        List<ModuleDescriptor> modules = catalog.modules();
        moduleList.setItems(modules);
        moduleList.setRenderer(new TextRenderer<>(ModuleDescriptor::displayName));
        moduleList.addValueChangeListener(e -> show());
        VerticalLayout left = new VerticalLayout(new H2("Global"), new Paragraph("Vorgaben für alle Desktop-Apps, "
                + "soweit Benutzer oder Profile nichts überschreiben. „Sperren“ verbietet das Überschreiben."),
                moduleList);
        left.setWidth("20rem");
        left.setPadding(false);
        panel.setPadding(false);
        panel.setWidthFull();
        add(left, panel);
        setFlexGrow(1, panel);
        if (modules.isEmpty()) {
            panel.add(SettingsView.noCatalog());
        } else {
            moduleList.setValue(modules.getFirst());
        }
    }

    private void show() {
        panel.removeAll();
        ModuleDescriptor m = moduleList.getValue();
        if (m == null) {
            return;
        }
        Overrides current = profiles.overrides(Overrides.Level.GLOBAL, 0, m.id());
        Set<String> locks = profiles.locks(m.id());
        Map<String, Checkbox> lockBoxes = new LinkedHashMap<>();

        panel.add(new H3(m.displayName()), new Paragraph(m.description()));

        Select<String> enabled = SettingsView.tristate("Modul aktiv", current.enabled());
        if (m.hasTools()) {
            panel.add(row(enabled, lockBox(Overrides.ENABLED, locks, lockBoxes)));
        }

        Map<String, Checkbox> presetBoxes = new LinkedHashMap<>();
        Map<String, FieldEditor> editors = new LinkedHashMap<>();
        for (ConfigField f : m.schema()) {
            FieldEditor ed = FieldEditor.of(f);
            boolean preset = current.values().containsKey(f.key());
            ed.setValue(preset ? current.values().get(f.key()) : f.defaultValue());
            ed.setReadOnly(!preset);
            Checkbox box = new Checkbox("vorgeben", preset);
            box.addValueChangeListener(e -> ed.setReadOnly(!e.getValue()));
            presetBoxes.put(f.key(), box);
            editors.put(f.key(), ed);
            VerticalLayout side = new VerticalLayout(box, lockBox(f.key(), locks, lockBoxes));
            side.setPadding(false);
            side.setSpacing(false);
            side.setWidth("10rem");
            HorizontalLayout row = new HorizontalLayout(ed.component(), side);
            row.setWidthFull();
            row.setFlexGrow(1, ed.component());
            row.setAlignItems(FlexComponent.Alignment.CENTER);
            panel.add(row);
        }

        Map<String, Select<String>> tools = new LinkedHashMap<>();
        if (m.hasTools()) {
            panel.add(new H3("Tools"), lockBox(Overrides.TOOLS, locks, lockBoxes));
            Set<String> names = new TreeSet<>();
            m.tools().forEach(d -> names.add(d.name()));
            names.addAll(current.tools().keySet());
            for (String name : names) {
                Select<String> s = SettingsView.tristate(name, current.tools().get(name));
                tools.put(name, s);
                panel.add(s);
            }
        }

        Button save = new Button("Speichern", e -> {
            Map<String, String> values = new LinkedHashMap<>();
            presetBoxes.forEach((k, box) -> {
                if (box.getValue()) {
                    values.put(k, editors.get(k).value());
                }
            });
            List<ConfigField> preset = m.schema().stream().filter(f -> values.containsKey(f.key())).toList();
            List<String> errors = ModuleConfig.of(preset, values).validate();
            if (!errors.isEmpty()) {
                Ui.error(String.join("\n", errors));
                return;
            }
            Set<String> newLocks = new LinkedHashSet<>();
            lockBoxes.forEach((k, cb) -> {
                if (cb.getValue()) {
                    newLocks.add(k);
                }
            });
            if (Ui.run(() -> {
                profiles.saveGlobal(m.id(), new Overrides(SettingsView.tristateValue(enabled),
                        SettingsView.tristateValues(tools), values));
                profiles.setLocks(m.id(), newLocks);
            }, "Gespeichert – verbundene Desktop-Apps übernehmen die Änderung beim nächsten Abgleich")) {
                show();
            }
        });
        save.addThemeVariants(ButtonVariant.PRIMARY);
        panel.add(save);
    }

    private static Checkbox lockBox(String key, Set<String> locks, Map<String, Checkbox> boxes) {
        Checkbox cb = new Checkbox(Overrides.TOOLS.equals(key) ? "Tool-Schalter sperren" : "sperren",
                locks.contains(key));
        boxes.put(key, cb);
        return cb;
    }

    private static HorizontalLayout row(Select<String> main, Checkbox lock) {
        HorizontalLayout row = new HorizontalLayout(main, lock);
        row.setAlignItems(FlexComponent.Alignment.BASELINE);
        return row;
    }
}
