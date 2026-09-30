package systems.grebe.devtools.mcp.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.core.ModuleConfig;

/**
 * Eingabeelement für ein {@link ConfigField}, Gegenstück zu {@code ui/ConfigForm} der Desktop-App. Werte sind immer
 * Strings wie in den Einstellungen gespeichert (Listen zeilenweise, Datensätze als JSON).
 *
 * <p>Geheimnisse gehen nie an den Browser: das Feld bleibt leer mit dem Hinweis „gesetzt“, und solange nichts
 * eingegeben wird, liefert {@link #value()} den bisherigen Wert. Das gilt auch für geheime Spalten von Datensätzen.
 */
abstract class FieldEditor {

    final ConfigField field;

    FieldEditor(ConfigField field) {
        this.field = field;
    }

    abstract Component component();

    abstract String value();

    abstract void setValue(String value);

    abstract void setReadOnly(boolean readOnly);

    static FieldEditor of(ConfigField f) {
        return switch (f.type()) {
            case SECRET -> new Secret(f);
            case BOOLEAN -> new Bool(f);
            case ENUM -> new Choice(f);
            case DIRECTORY_LIST, STRING_LIST -> new Lines(f);
            case RECORD_LIST -> new Records(f);
            default -> new Text(f);
        };
    }

    static String help(ConfigField f) {
        String h = f.help() == null ? "" : f.help();
        if (f.defaultValue() != null && !f.defaultValue().isBlank() && f.type() != FieldType.RECORD_LIST) {
            h = (h.isBlank() ? "" : h + " ") + "Standard: " + f.defaultValue();
        }
        return h;
    }

    // ---------------------------------------------------------------- einfache Typen

    private static final class Text extends FieldEditor {
        private final TextField tf = new TextField();

        Text(ConfigField f) {
            super(f);
            tf.setLabel(f.label());
            tf.setHelperText(help(f));
            tf.setWidthFull();
            if (f.type() == FieldType.INT) {
                tf.setAllowedCharPattern("[0-9-]");
            }
        }

        @Override
        Component component() {
            return tf;
        }

        @Override
        String value() {
            return tf.getValue().strip();
        }

        @Override
        void setValue(String v) {
            tf.setValue(v == null ? "" : v);
        }

        @Override
        void setReadOnly(boolean ro) {
            tf.setReadOnly(ro);
        }
    }

    private static final class Secret extends FieldEditor {
        private final PasswordField pf = new PasswordField();
        private String stored = "";

        Secret(ConfigField f) {
            super(f);
            pf.setLabel(f.label());
            pf.setHelperText(help(f));
            pf.setWidthFull();
            pf.setRevealButtonVisible(false);
        }

        @Override
        Component component() {
            return pf;
        }

        @Override
        String value() {
            return pf.getValue().isEmpty() ? stored : pf.getValue();
        }

        @Override
        void setValue(String v) {
            stored = v == null ? "" : v;
            pf.clear();
            pf.setPlaceholder(stored.isEmpty() ? "" : "•••••• gesetzt – leer lassen zum Behalten");
        }

        @Override
        void setReadOnly(boolean ro) {
            pf.setReadOnly(ro);
        }
    }

    private static final class Bool extends FieldEditor {
        private final Checkbox cb = new Checkbox();

        Bool(ConfigField f) {
            super(f);
            cb.setLabel(f.label());
            cb.setHelperText(help(f));
        }

        @Override
        Component component() {
            return cb;
        }

        @Override
        String value() {
            return Boolean.toString(cb.getValue());
        }

        @Override
        void setValue(String v) {
            cb.setValue(Boolean.parseBoolean(v == null || v.isBlank() ? field.defaultValue() : v));
        }

        @Override
        void setReadOnly(boolean ro) {
            cb.setReadOnly(ro);
        }
    }

    private static final class Choice extends FieldEditor {
        private final Select<String> sel = new Select<>();

        Choice(ConfigField f) {
            super(f);
            sel.setLabel(f.label());
            sel.setHelperText(help(f));
            List<String> items = new ArrayList<>();
            items.add("");
            items.addAll(f.options());
            sel.setItems(items);
            sel.setItemLabelGenerator(s -> s.isEmpty() ? "(Standard)" : s);
            sel.setWidthFull();
        }

        @Override
        Component component() {
            return sel;
        }

        @Override
        String value() {
            return sel.getValue() == null ? "" : sel.getValue();
        }

        @Override
        void setValue(String v) {
            sel.setValue(v != null && field.options().contains(v) ? v : "");
        }

        @Override
        void setReadOnly(boolean ro) {
            sel.setReadOnly(ro);
        }
    }

    private static final class Lines extends FieldEditor {
        private final TextArea ta = new TextArea();

        Lines(ConfigField f) {
            super(f);
            ta.setLabel(f.label());
            String h = help(f);
            ta.setHelperText((h.isBlank() ? "" : h + " ") + "Ein Eintrag je Zeile"
                    + (f.type() == FieldType.DIRECTORY_LIST ? " (Pfade auf dem Server)." : "."));
            ta.setWidthFull();
            ta.setMinRows(2);
        }

        @Override
        Component component() {
            return ta;
        }

        @Override
        String value() {
            return String.join("\n", ModuleConfig.splitLines(ta.getValue()));
        }

        @Override
        void setValue(String v) {
            ta.setValue(v == null ? "" : v);
        }

        @Override
        void setReadOnly(boolean ro) {
            ta.setReadOnly(ro);
        }
    }

    // ---------------------------------------------------------------- Datensätze

    /** Tabelle mit Hinzufügen/Bearbeiten/Entfernen; geheime Spalten erscheinen nur als „gesetzt“. */
    private static final class Records extends FieldEditor {
        private final List<Map<String, String>> records = new ArrayList<>();
        private final Grid<Map<String, String>> grid = new Grid<>();
        private final Button add = new Button("Hinzufügen", e -> edit(null));
        private final VerticalLayout box;
        private boolean readOnly;

        Records(ConfigField f) {
            super(f);
            for (ConfigField c : f.columns()) {
                grid.addColumn(r -> c.secret() ? (r.getOrDefault(c.key(), "").isEmpty() ? "" : "••••••")
                        : r.getOrDefault(c.key(), "")).setHeader(c.label()).setAutoWidth(true);
            }
            grid.addComponentColumn(r -> {
                Button e = new Button("Bearbeiten", ev -> edit(r));
                Button d = new Button("Entfernen", ev -> {
                    records.removeIf(x -> x == r);
                    refresh();
                });
                e.addThemeVariants(ButtonVariant.TERTIARY);
                d.addThemeVariants(ButtonVariant.TERTIARY, ButtonVariant.ERROR);
                e.setEnabled(!readOnly);
                d.setEnabled(!readOnly);
                return new HorizontalLayout(e, d);
            }).setAutoWidth(true);
            grid.setAllRowsVisible(true);
            Span label = new Span(f.label());
            label.getStyle().set("font-weight", "600");
            box = new VerticalLayout(label, grid, add);
            box.setPadding(false);
            box.setSpacing(false);
        }

        private void edit(Map<String, String> existing) {
            Dialog d = new Dialog();
            d.setHeaderTitle(field.label());
            Map<String, FieldEditor> editors = new LinkedHashMap<>();
            FormLayout form = new FormLayout();
            for (ConfigField c : field.columns()) {
                FieldEditor ed = FieldEditor.of(c);
                ed.setValue(existing == null ? c.defaultValue() : existing.get(c.key()));
                editors.put(c.key(), ed);
                form.add(ed.component());
            }
            d.add(form);
            Button ok = new Button("Übernehmen", e -> {
                Map<String, String> r = new LinkedHashMap<>();
                editors.forEach((k, ed) -> r.put(k, ed.value()));
                if (existing == null) {
                    records.add(r);
                } else {
                    for (int i = 0; i < records.size(); i++) {
                        if (records.get(i) == existing) {
                            records.set(i, r);
                        }
                    }
                }
                refresh();
                d.close();
            });
            ok.addThemeVariants(ButtonVariant.PRIMARY);
            d.getFooter().add(new Button("Abbrechen", e -> d.close()), ok);
            d.setWidth("40rem");
            d.open();
        }

        private void refresh() {
            grid.setItems(new ArrayList<>(records));
        }

        @Override
        Component component() {
            return box;
        }

        @Override
        String value() {
            return ModuleConfig.formatRecords(records);
        }

        @Override
        void setValue(String v) {
            records.clear();
            try {
                ModuleConfig.parseRecords(v, field.columns()).forEach(r -> records.add(new LinkedHashMap<>(r)));
            } catch (IllegalArgumentException e) {
                // ungültiger gespeicherter Wert: leer anzeigen, Validierung meldet ihn beim Speichern
            }
            refresh();
        }

        @Override
        void setReadOnly(boolean ro) {
            readOnly = ro;
            add.setEnabled(!ro);
            refresh();
        }
    }
}
