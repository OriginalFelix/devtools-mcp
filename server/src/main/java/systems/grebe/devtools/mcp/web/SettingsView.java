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
import systems.grebe.devtools.mcp.backend.account.AccountService;
import systems.grebe.devtools.mcp.web.WebLogin.AccountPrincipal;
import systems.grebe.devtools.mcp.backend.account.UserAccount;
import systems.grebe.devtools.mcp.api.ModuleDescriptor;
import systems.grebe.devtools.mcp.api.ModuleOverlay;
import systems.grebe.devtools.mcp.api.ProjectInfo;
import systems.grebe.devtools.mcp.backend.catalog.ModuleCatalog;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.profile.Overrides;
import systems.grebe.devtools.mcp.backend.profile.Profile;
import systems.grebe.devtools.mcp.backend.profile.ProfileService;

/**
 * Eigene Einstellungen: je Modul überschreiben, was vom Globalen (bzw. für ein Profil: vom Benutzer) abweichen soll.
 * Ziel ist „alle meine Profile“ (Ebene Benutzer) oder ein einzelnes Profil. Gesperrte Felder sind nicht änderbar.
 * Was keine Ebene vorgibt, stellt jeder in seiner Desktop-App selbst ein.
 */
@Route("einstellungen")
@PageTitle("Einstellungen – DevTools MCP")
@PermitAll
public class SettingsView extends HorizontalLayout {

    static final String INHERIT = "erben";
    static final String ON = "an";
    static final String OFF = "aus";

    /** Ziel der Überschreibungen: Benutzer (profile == null) oder ein Profil. */
    private record Target(Profile profile) {
        Overrides.Level level() {
            return profile == null ? Overrides.Level.USER : Overrides.Level.PROFILE;
        }
    }

    private final ModuleCatalog catalog;
    private final ProfileService profiles;
    private final UserAccount user;
    private final Select<Target> target = new Select<>();
    private final ListBox<ModuleDescriptor> moduleList = new ListBox<>();
    private final VerticalLayout panel = new VerticalLayout();

    public SettingsView(ModuleCatalog catalog, ProfileService profiles, AccountService accounts,
                        AuthenticationContext auth) {
        this.catalog = catalog;
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

        List<ModuleDescriptor> modules = catalog.modules();
        moduleList.setItems(modules);
        moduleList.setRenderer(new TextRenderer<>(ModuleDescriptor::displayName));
        moduleList.addValueChangeListener(e -> show());
        VerticalLayout left = new VerticalLayout(new H2("Einstellungen"), target, moduleList);
        left.setWidth("20rem");
        left.setPadding(false);

        panel.setPadding(false);
        panel.setWidthFull();
        add(left, panel);
        setFlexGrow(1, panel);
        if (modules.isEmpty()) {
            panel.add(noCatalog());
        } else {
            moduleList.setValue(modules.getFirst());
        }
    }

    /** Hinweis, solange sich noch keine Desktop-App gemeldet hat. */
    static Paragraph noCatalog() {
        return new Paragraph("Noch keine Module bekannt: Die Einstellungen erscheinen, sobald sich eine Desktop-App "
                + "mit einem Desktop-Token (Mein Konto) verbunden hat.");
    }

    private void show() {
        panel.removeAll();
        ModuleDescriptor m = moduleList.getValue();
        Target t = target.getValue();
        if (m == null || t == null) {
            return;
        }
        long levelId = t.profile() == null ? user.id() : t.profile().id();
        // geerbt: für den Benutzer das Globale, für ein Profil Globales + Benutzer
        ModuleOverlay inherited = profiles.overlay(m.id(), t.profile() == null ? null : user.id(), null);
        Overrides current = profiles.overrides(t.level(), levelId, m.id());
        Set<String> locks = inherited.lockedKeys();
        String inheritedFrom = t.profile() == null ? "global" : "Benutzer/global";

        panel.add(new H3(m.displayName()), new Paragraph(m.description()));

        Select<String> enabled = tristate("Modul aktiv (geerbt: " + label(inherited.enabled()) + ")",
                current.enabled());
        lockHint(enabled, locks.contains(Overrides.ENABLED));
        if (m.hasTools()) {
            panel.add(enabled);
        }

        Map<String, Checkbox> overrideBoxes = new LinkedHashMap<>();
        Map<String, FieldEditor> editors = new LinkedHashMap<>();
        for (ConfigField f : m.schema()) {
            FieldEditor ed = FieldEditor.of(f);
            boolean fromProjects = ProjectInfo.projectField(m.id(), f.key());
            boolean locked = locks.contains(f.key()) || fromProjects;
            boolean overridden = current.values().containsKey(f.key()) && !locked;
            String inheritedValue = inherited.valueMap().get(f.key());
            ed.setValue(overridden ? current.values().get(f.key()) : inheritedValue);
            ed.setReadOnly(!overridden);
            Checkbox box = new Checkbox("überschreiben", overridden);
            box.setEnabled(!locked);
            box.addValueChangeListener(e -> {
                ed.setReadOnly(!e.getValue());
                if (!e.getValue()) {
                    ed.setValue(inheritedValue);
                }
            });
            Span origin = new Span(fromProjects ? "aus deinen Projekten"
                    : locked ? "gesperrt (global)"
                    : overridden ? "überschrieben"
                    : inheritedValue == null ? "Desktop-App" : "geerbt: " + inheritedFrom);
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
            m.tools().forEach(d -> tools.add(d.name()));
            tools.addAll(current.tools().keySet());
            tools.addAll(inherited.toolMap().keySet());
            boolean toolsLocked = locks.contains(Overrides.TOOLS);
            panel.add(new H3("Tools"));
            for (String tool : tools) {
                Select<String> s = tristate(tool + " (geerbt: " + label(inherited.toolMap().get(tool)) + ")",
                        current.tools().get(tool));
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
            if (Ui.run(() -> profiles.saveOverrides(user.id(), t.level(), levelId, m.id(),
                    new Overrides(tristateValue(enabled), tristateValues(toolSelects), values)),
                    "Gespeichert – verbundene Desktop-Apps übernehmen die Änderung beim nächsten Abgleich")) {
                show();
            }
        });
        save.addThemeVariants(ButtonVariant.PRIMARY);
        Button reset = new Button("Alle Überschreibungen entfernen", e -> {
            if (Ui.run(() -> profiles.saveOverrides(user.id(), t.level(), levelId, m.id(), Overrides.NONE),
                    "Überschreibungen entfernt")) {
                show();
            }
        });
        reset.addThemeVariants(ButtonVariant.TERTIARY);
        panel.add(new HorizontalLayout(save, reset));
    }

    /** Anzeige eines geerbten Schalters; {@code null} = nirgends vorgegeben, die Desktop-App entscheidet. */
    static String label(Boolean value) {
        return value == null ? "Desktop-App" : value ? ON : OFF;
    }

    static Select<String> tristate(String label, Boolean value) {
        Select<String> s = new Select<>();
        s.setLabel(label);
        s.setItems(INHERIT, ON, OFF);
        s.setValue(value == null ? INHERIT : value ? ON : OFF);
        s.setWidth("24rem");
        return s;
    }

    static Boolean tristateValue(Select<String> s) {
        return s.isReadOnly() || INHERIT.equals(s.getValue()) ? null : ON.equals(s.getValue());
    }

    static Map<String, Boolean> tristateValues(Map<String, Select<String>> selects) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        selects.forEach((tool, s) -> {
            Boolean v = tristateValue(s);
            if (v != null) {
                out.put(tool, v);
            }
        });
        return out;
    }

    private static void lockHint(Select<String> s, boolean locked) {
        if (locked) {
            s.setValue(INHERIT);
            s.setReadOnly(true);
            s.setHelperText("vom Administrator gesperrt");
        }
    }
}
