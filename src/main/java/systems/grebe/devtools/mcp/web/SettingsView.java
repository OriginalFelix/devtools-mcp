package systems.grebe.devtools.mcp.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.listbox.ListBox;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.data.renderer.TextRenderer;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.spring.security.AuthenticationContext;
import jakarta.annotation.security.PermitAll;
import systems.grebe.devtools.mcp.account.AccountService;
import systems.grebe.devtools.mcp.account.AccountService.AccountPrincipal;
import systems.grebe.devtools.mcp.account.UserAccount;
import systems.grebe.devtools.mcp.config.ModuleSettings;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.ToolModule;
import systems.grebe.devtools.mcp.core.ToolRegistry;
import systems.grebe.devtools.mcp.core.ToolScope;
import systems.grebe.devtools.mcp.profile.Overrides;
import systems.grebe.devtools.mcp.profile.Profile;
import systems.grebe.devtools.mcp.profile.ProfileService;
import systems.grebe.devtools.mcp.project.ProjectService;
import systems.grebe.devtools.mcp.server.UserRuntimes;

/**
 * Eigene Einstellungen: je Modul überschreiben, was vom Globalen (bzw. für ein Profil: vom Benutzer) abweichen soll.
 * Ziel ist „alle meine Profile“ (Ebene Benutzer) oder ein einzelnes Profil. Gesperrte Felder sind nicht änderbar.
 */
@Route("einstellungen")
@PageTitle("Einstellungen – DevTools MCP")
@PermitAll
public class SettingsView extends HorizontalLayout {

    private static final String INHERIT = "erben";
    private static final String ON = "an";
    private static final String OFF = "aus";

    /** Ziel der Überschreibungen: Benutzer (profile == null) oder ein Profil. */
    private record Target(Profile profile) {
        Overrides.Level level() {
            return profile == null ? Overrides.Level.USER : Overrides.Level.PROFILE;
        }
    }

    private final ToolRegistry registry;
    private final ProfileService profiles;
    private final UserAccount user;
    private final Select<Target> target = new Select<>();
    private final ListBox<ToolModule> moduleList = new ListBox<>();
    private final VerticalLayout panel = new VerticalLayout();

    public SettingsView(ToolRegistry registry, ProfileService profiles, AccountService accounts,
                        AuthenticationContext auth) {
        this.registry = registry;
        this.profiles = profiles;
        this.user = accounts.user(auth.getAuthenticatedUser(AccountPrincipal.class).orElseThrow().id()).orElseThrow();
        setSizeFull();

        List<Target> targets = new ArrayList<>();
        targets.add(new Target(null));
        profiles.profiles(user.id()).forEach(p -> targets.add(new Target(p)));
        target.setLabel("Einstellungen für");
        target.setItems(targets);
        target.setItemLabelGenerator(t -> t.profile() == null ? "Alle meine Profile" : "Profil „" + t.profile().name()
                + "“");
        long active = profiles.activeProfile(user.id()).id();
        target.setValue(targets.stream().filter(t -> t.profile() != null && t.profile().id() == active).findFirst()
                .orElse(targets.getFirst()));
        target.addValueChangeListener(e -> show());

        moduleList.setItems(registry.modules());
        moduleList.setRenderer(new TextRenderer<>(ToolModule::displayName));
        moduleList.addValueChangeListener(e -> show());
        VerticalLayout left = new VerticalLayout(new H2("Einstellungen"), target, moduleList);
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
        Target t = target.getValue();
        if (m == null || t == null) {
            return;
        }
        long levelId = t.profile() == null ? user.id() : t.profile().id();
        // geerbt: für den Benutzer das Globale, für ein Profil Globales + Benutzer
        ModuleSettings inherited = t.profile() == null ? registry.settings(m.id())
                : registry.effectiveSettings(new ToolScope(UserRuntimes.scopeId(user.id()), Long.toString(user.id()),
                        user.username(), user.email(), null, user.admin()), m.id());
        Overrides current = profiles.overrides(t.level(), levelId, m.id());
        Set<String> locks = profiles.locks(m.id());
        String inheritedFrom = t.profile() == null ? "global" : "Benutzer/global";

        panel.add(new H3(m.displayName()), new Paragraph(m.description()));

        Select<String> enabled = tristate("Modul aktiv (geerbt: " + (inherited.enabled() ? ON : OFF) + ")",
                current.enabled());
        lockHint(enabled, locks.contains(Overrides.ENABLED));
        if (m.hasTools()) {
            panel.add(enabled);
        }

        Map<String, Checkbox> overrideBoxes = new LinkedHashMap<>();
        Map<String, FieldEditor> editors = new LinkedHashMap<>();
        for (ConfigField f : m.configSchema()) {
            FieldEditor ed = FieldEditor.of(f);
            boolean fromProjects = ProjectService.projectField(m.id(), f.key());
            boolean locked = locks.contains(f.key()) || fromProjects;
            boolean overridden = current.values().containsKey(f.key()) && !locked;
            ed.setValue(overridden ? current.values().get(f.key()) : inherited.values().get(f.key()));
            ed.setReadOnly(!overridden);
            Checkbox box = new Checkbox("überschreiben", overridden);
            box.setEnabled(!locked);
            box.addValueChangeListener(e -> {
                ed.setReadOnly(!e.getValue());
                if (!e.getValue()) {
                    ed.setValue(inherited.values().get(f.key()));
                }
            });
            Span origin = new Span(fromProjects ? "aus deinen Projekten"
                    : locked ? "gesperrt (global)"
                    : overridden ? "überschrieben" : "geerbt: " + inheritedFrom);
            origin.getStyle().set("font-size", "var(--vaadin-font-size-s, 0.875rem)").set("opacity", "0.7");
            VerticalLayout side = new VerticalLayout(box, origin);
            side.setPadding(false);
            side.setSpacing(false);
            side.setWidth("10rem");
            HorizontalLayout row = new HorizontalLayout(ed.component(), side);
            row.setWidthFull();
            row.setFlexGrow(1, ed.component());
            row.setAlignItems(FlexComponent.Alignment.CENTER);
            panel.add(row);
            overrideBoxes.put(f.key(), box);
            editors.put(f.key(), ed);
        }

        Map<String, Select<String>> toolSelects = new LinkedHashMap<>();
        if (m.hasTools()) {
            Set<String> tools = new TreeSet<>();
            registry.availableTools(m.id()).forEach(d -> tools.add(d.name()));
            tools.addAll(current.tools().keySet());
            tools.addAll(inherited.disabledTools());
            boolean toolsLocked = locks.contains(Overrides.TOOLS);
            panel.add(new H3("Tools"));
            for (String tool : tools) {
                Select<String> s = tristate(tool + " (geerbt: " + (inherited.disabledTools().contains(tool) ? OFF : ON)
                        + ")", current.tools().get(tool));
                lockHint(s, toolsLocked);
                toolSelects.put(tool, s);
                panel.add(s);
            }
        }

        Button save = new Button("Speichern", e -> {
            Map<String, String> values = new LinkedHashMap<>();
            overrideBoxes.forEach((k, box) -> {
                if (box.getValue() && box.isEnabled()) {
                    values.put(k, editors.get(k).value());
                }
            });
            Map<String, Boolean> tools = new LinkedHashMap<>();
            toolSelects.forEach((tool, s) -> {
                if (!INHERIT.equals(s.getValue()) && !s.isReadOnly()) {
                    tools.put(tool, ON.equals(s.getValue()));
                }
            });
            Boolean en = enabled.isReadOnly() || INHERIT.equals(enabled.getValue()) ? null : ON.equals(enabled.getValue());
            if (Ui.run(() -> profiles.saveOverrides(user.id(), t.level(), levelId, m,
                    new Overrides(en, tools, values)), "Gespeichert – verbundene Clients bekommen die neuen Tools")) {
                show();
            }
        });
        save.addThemeVariants(ButtonVariant.PRIMARY);
        Button reset = new Button("Alle Überschreibungen entfernen", e -> {
            if (Ui.run(() -> profiles.saveOverrides(user.id(), t.level(), levelId, m, Overrides.NONE),
                    "Überschreibungen entfernt")) {
                show();
            }
        });
        reset.addThemeVariants(ButtonVariant.TERTIARY);
        panel.add(new HorizontalLayout(save, reset));
    }

    private static Select<String> tristate(String label, Boolean value) {
        Select<String> s = new Select<>();
        s.setLabel(label);
        s.setItems(INHERIT, ON, OFF);
        s.setValue(value == null ? INHERIT : value ? ON : OFF);
        s.setWidth("24rem");
        return s;
    }

    private static void lockHint(Select<String> s, boolean locked) {
        if (locked) {
            s.setValue(INHERIT);
            s.setReadOnly(true);
            s.setHelperText("vom Administrator gesperrt");
        }
    }
}
