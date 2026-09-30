package systems.grebe.devtools.mcp.web;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.listbox.ListBox;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.data.renderer.TextRenderer;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;
import systems.grebe.devtools.mcp.config.ModuleSettings;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.ConnectionTestResult;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolModule;
import systems.grebe.devtools.mcp.core.ToolRegistry;
import systems.grebe.devtools.mcp.profile.Overrides;
import systems.grebe.devtools.mcp.profile.ProfileService;

/**
 * Globale Einstellungen (dieselben wie in der Desktop-App, {@code settings.json}) und Sperren: gesperrte Felder
 * können Benutzer und Profile nicht überschreiben.
 */
@Route("global")
@PageTitle("Globale Einstellungen – DevTools MCP")
@RolesAllowed("ADMIN")
public class GlobalSettingsView extends HorizontalLayout {

    private final ToolRegistry registry;
    private final ProfileService profiles;
    private final ListBox<ToolModule> moduleList = new ListBox<>();
    private final VerticalLayout panel = new VerticalLayout();

    public GlobalSettingsView(ToolRegistry registry, ProfileService profiles) {
        this.registry = registry;
        this.profiles = profiles;
        setSizeFull();
        moduleList.setItems(registry.modules());
        moduleList.setRenderer(new TextRenderer<>(ToolModule::displayName));
        moduleList.addValueChangeListener(e -> show());
        VerticalLayout left = new VerticalLayout(new H2("Global"), new Paragraph("Gilt für alle, soweit Benutzer "
                + "oder Profile nichts überschreiben. „Sperren“ verbietet das Überschreiben."), moduleList);
        left.setWidth("20rem");
        left.setPadding(false);
        panel.setPadding(false);
        panel.setWidthFull();
        add(left, panel);
        setFlexGrow(1, panel);
        if (!registry.modules().isEmpty()) {
            moduleList.setValue(registry.modules().getFirst());
        }
    }

    private void show() {
        panel.removeAll();
        ToolModule m = moduleList.getValue();
        if (m == null) {
            return;
        }
        ModuleSettings settings = registry.settings(m.id());
        Set<String> locks = profiles.locks(m.id());
        Map<String, Checkbox> lockBoxes = new LinkedHashMap<>();

        panel.add(new H3(m.displayName()), new Paragraph(m.description()));
        registry.moduleError(m.id()).ifPresent(err -> panel.add(new Paragraph("Fehler: " + err)));

        Checkbox enabled = new Checkbox("Modul aktiv", settings.enabled());
        if (m.hasTools()) {
            panel.add(row(enabled, lockBox(Overrides.ENABLED, locks, lockBoxes)));
        }

        Map<String, FieldEditor> editors = new LinkedHashMap<>();
        for (ConfigField f : m.configSchema()) {
            FieldEditor ed = FieldEditor.of(f);
            ed.setValue(settings.values().get(f.key()));
            editors.put(f.key(), ed);
            panel.add(row(ed, lockBox(f.key(), locks, lockBoxes)));
        }

        Map<String, Checkbox> tools = new LinkedHashMap<>();
        if (m.hasTools()) {
            panel.add(new H3("Tools"), lockBox(Overrides.TOOLS, locks, lockBoxes));
            registry.availableTools(m.id()).forEach(d -> {
                Checkbox cb = new Checkbox(d.name(), !settings.disabledTools().contains(d.name()));
                tools.put(d.name(), cb);
                panel.add(cb);
            });
        }

        Button save = new Button("Speichern", e -> {
            Map<String, String> values = new LinkedHashMap<>();
            editors.forEach((k, ed) -> values.put(k, ed.value()));
            List<String> errors = ModuleConfig.of(m.configSchema(), values).validate();
            if (!errors.isEmpty() && enabled.getValue()) {
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
                registry.updateConfig(m.id(), values);
                tools.forEach((name, cb) -> {
                    if (cb.getValue() == settings.disabledTools().contains(name)) {
                        registry.setToolEnabled(m.id(), name, cb.getValue());
                    }
                });
                if (enabled.getValue() != settings.enabled()) {
                    registry.setModuleEnabled(m.id(), enabled.getValue());
                }
                profiles.setLocks(m.id(), newLocks);
            }, "Gespeichert")) {
                show();
            }
        });
        save.addThemeVariants(ButtonVariant.PRIMARY);
        Button test = new Button("Verbindung testen", e -> {
            Map<String, String> values = new LinkedHashMap<>();
            editors.forEach((k, ed) -> values.put(k, ed.value()));
            ConnectionTestResult r = registry.testConnection(m.id(), values);
            Notification.show(r.message(), 8000, Notification.Position.MIDDLE)
                    .addThemeVariants(r.success() ? NotificationVariant.SUCCESS : NotificationVariant.ERROR);
        });
        panel.add(new HorizontalLayout(save, test));
    }

    private static Checkbox lockBox(String key, Set<String> locks, Map<String, Checkbox> boxes) {
        Checkbox cb = new Checkbox(Overrides.TOOLS.equals(key) ? "Tool-Schalter sperren" : "sperren",
                locks.contains(key));
        boxes.put(key, cb);
        return cb;
    }

    private static HorizontalLayout row(FieldEditor ed, Checkbox lock) {
        HorizontalLayout row = new HorizontalLayout(ed.component(), lock);
        row.setWidthFull();
        row.setFlexGrow(1, ed.component());
        row.setAlignItems(FlexComponent.Alignment.CENTER);
        return row;
    }

    private static HorizontalLayout row(Checkbox main, Checkbox lock) {
        HorizontalLayout row = new HorizontalLayout(main, lock);
        row.setAlignItems(FlexComponent.Alignment.CENTER);
        return row;
    }
}
