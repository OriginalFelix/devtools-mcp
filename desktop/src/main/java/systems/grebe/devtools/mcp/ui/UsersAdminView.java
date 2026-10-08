package systems.grebe.devtools.mcp.ui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckBoxTreeItem;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.control.cell.CheckBoxTreeCell;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.springframework.ai.tool.definition.ToolDefinition;
import systems.grebe.devtools.mcp.api.Grants;
import systems.grebe.devtools.mcp.api.Permission;
import systems.grebe.devtools.mcp.api.RoleInfo;
import systems.grebe.devtools.mcp.api.UserInfo;
import systems.grebe.devtools.mcp.core.ToolModule;
import systems.grebe.devtools.mcp.core.ToolRegistry;
import systems.grebe.devtools.mcp.remote.BackendConnection;

/**
 * Tab „Benutzer“ (Recht „Benutzer und Rollen verwalten“): Benutzer anlegen, Rollen zuordnen, sperren, Passwort
 * setzen, löschen – und Rollen mit ihren Rechten: Systemrechte, alle Module oder einzelne Module und Tools dieser App.
 * Arbeitet über die GraphQL-API, also eingebettet wie mit Team-Server; Netzwerk im Hintergrund.
 */
public class UsersAdminView extends BorderPane {

    private static final String USER_FIELDS = "id username displayName email enabled passwordChangeRequired "
            + "createdAt roles";
    private static final String ROLE_FIELDS = "id name description builtin permissions users";

    private final BackendConnection backend;
    private final ToolRegistry registry;
    private final ObservableList<UserInfo> users = FXCollections.observableArrayList();
    private final ObservableList<RoleInfo> roles = FXCollections.observableArrayList();
    private final TableView<UserInfo> userTable = new TableView<>(users);
    private final TableView<RoleInfo> roleTable = new TableView<>(roles);
    private final Label status = new Label();

    public UsersAdminView(BackendConnection backend, ToolRegistry registry) {
        this.backend = backend;
        this.registry = registry;
        setPadding(new Insets(16));
        TabPane tabs = new TabPane(new Tab("Benutzer", usersPane()), new Tab("Rollen", rolesPane()));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        status.getStyleClass().add("status-text");
        status.setWrapText(true);
        setCenter(tabs);
        setBottom(status);
        BorderPane.setMargin(status, new Insets(8, 0, 0, 0));
    }

    // ---------------------------------------------------------------- Benutzer

    private VBox usersPane() {
        column(userTable, "Benutzer", UserInfo::username, 120);
        column(userTable, "Name", u -> nz(u.displayName()), 150);
        column(userTable, "E-Mail", u -> nz(u.email()), 200);
        column(userTable, "Rollen", u -> String.join(", ", u.roles()), 200);
        column(userTable, "Status", u -> !u.enabled() ? "gesperrt"
                : u.passwordChangeRequired() ? "muss Passwort ändern" : "aktiv", 150);
        userTable.setPlaceholder(new Label("Keine Benutzer."));
        Button create = new Button("Neuer Benutzer…");
        create.getStyleClass().add("accent");
        create.setOnAction(e -> editUser(null));
        Button edit = new Button("Bearbeiten…");
        edit.setOnAction(e -> selected(userTable).ifPresent(this::editUser));
        Button password = new Button("Passwort setzen…");
        password.setOnAction(e -> selected(userTable).ifPresent(this::setPassword));
        Button delete = new Button("Löschen");
        delete.setOnAction(e -> selected(userTable).ifPresent(this::deleteUser));
        Label help = help("Rechte bekommen Benutzer über ihre Rollen (Tab „Rollen“); mehrere Rollen addieren sich. "
                + "Gesperrte Benutzer verlieren sofort ihre Anmeldungen.");
        VBox box = new VBox(10, help, new HBox(8, create, edit, password, delete), userTable);
        box.setPadding(new Insets(12, 0, 0, 0));
        return box;
    }

    private void editUser(UserInfo u) {
        Dialog<ButtonType> d = dialog(u == null ? "Neuer Benutzer" : "Benutzer " + u.username());
        TextField username = new TextField(u == null ? "" : u.username());
        username.setDisable(u != null);
        TextField name = new TextField(u == null ? "" : nz(u.displayName()));
        TextField email = new TextField(u == null ? "" : nz(u.email()));
        email.setPromptText("Eigentümer der Skills, Memories und Skripte");
        PasswordField password = new PasswordField();
        password.setPromptText("mindestens 8 Zeichen");
        CheckBox mustChange = new CheckBox("Muss das Passwort bei der ersten Anmeldung ändern");
        mustChange.setSelected(true);
        CheckBox enabled = new CheckBox("Aktiv");
        enabled.setSelected(u == null || u.enabled());
        Map<String, CheckBox> roleBoxes = new LinkedHashMap<>();
        VBox roleList = new VBox(4);
        for (RoleInfo r : roles) {
            CheckBox cb = new CheckBox(r.name());
            cb.setSelected(u == null ? r.name().equals("Benutzer") : u.roles().contains(r.name()));
            if (r.description() != null) {
                cb.setTooltip(new Tooltip(r.description()));
            }
            roleBoxes.put(r.name(), cb);
            roleList.getChildren().add(cb);
        }
        GridPane form = grid();
        int row = 0;
        form.addRow(row++, new Label("Benutzername"), username);
        form.addRow(row++, new Label("Anzeigename"), name);
        form.addRow(row++, new Label("E-Mail"), email);
        if (u == null) {
            form.addRow(row++, new Label("Startpasswort"), password);
            form.add(mustChange, 1, row++);
        } else {
            form.add(enabled, 1, row++);
        }
        form.addRow(row, new Label("Rollen"), roleList);
        d.getDialogPane().setContent(form);
        d.showAndWait().filter(b -> b == ButtonType.OK).ifPresent(b -> {
            List<String> chosen = roleBoxes.entrySet().stream().filter(en -> en.getValue().isSelected())
                    .map(Map.Entry::getKey).toList();
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("displayName", name.getText());
            vars.put("email", email.getText());
            vars.put("roles", chosen);
            if (u == null) {
                vars.put("username", username.getText());
                vars.put("password", password.getText());
                vars.put("mustChange", mustChange.isSelected());
                background(() -> backend.query("mutation($username: String!, $displayName: String, $email: String, "
                                + "$roles: [String!]!, $password: String!, $mustChange: Boolean) { createUser("
                                + "username: $username, displayName: $displayName, email: $email, roles: $roles, "
                                + "password: $password, passwordChangeRequired: $mustChange) { id } }", vars,
                        "createUser", Object.class), r -> done("Benutzer angelegt"));
            } else {
                vars.put("id", u.id());
                vars.put("enabled", enabled.isSelected());
                background(() -> backend.query("mutation($id: Int!, $displayName: String, $email: String, "
                                + "$roles: [String!]!, $enabled: Boolean!) { updateUser(id: $id, displayName: "
                                + "$displayName, email: $email, roles: $roles, enabled: $enabled) { id } }", vars,
                        "updateUser", Object.class), r -> done("Gespeichert"));
            }
        });
    }

    private void setPassword(UserInfo u) {
        Dialog<ButtonType> d = dialog("Passwort für " + u.username());
        PasswordField password = new PasswordField();
        password.setPromptText("mindestens 8 Zeichen");
        CheckBox mustChange = new CheckBox("Muss es bei der nächsten Anmeldung ändern");
        mustChange.setSelected(true);
        GridPane form = grid();
        form.addRow(0, new Label("Neues Passwort"), password);
        form.add(mustChange, 1, 1);
        d.getDialogPane().setContent(form);
        d.showAndWait().filter(b -> b == ButtonType.OK).ifPresent(b -> background(() -> backend.query(
                "mutation($id: Int!, $p: String!, $m: Boolean) { setUserPassword(id: $id, password: $p, "
                        + "passwordChangeRequired: $m) }", Map.of("id", u.id(), "p", password.getText(),
                        "m", mustChange.isSelected()), "setUserPassword", Boolean.class),
                r -> done("Passwort gesetzt")));
    }

    private void deleteUser(UserInfo u) {
        if (confirm("Benutzer „" + u.username() + "“ samt Profilen, Projekten und Tokens löschen?")) {
            background(() -> backend.query("mutation($id: Int!) { deleteUser(id: $id) }", Map.of("id", u.id()),
                    "deleteUser", Boolean.class), r -> done("Benutzer gelöscht"));
        }
    }

    // ---------------------------------------------------------------- Rollen

    private VBox rolesPane() {
        column(roleTable, "Rolle", RoleInfo::name, 140);
        column(roleTable, "Beschreibung", r -> nz(r.description()), 240);
        column(roleTable, "Rechte", UsersAdminView::summary, 260);
        column(roleTable, "Benutzer", r -> Integer.toString(r.users()), 80);
        roleTable.setPlaceholder(new Label("Keine Rollen."));
        Button create = new Button("Neue Rolle…");
        create.getStyleClass().add("accent");
        create.setOnAction(e -> editRole(null, "", "", Set.of()));
        Button edit = new Button("Bearbeiten…");
        edit.setOnAction(e -> selected(roleTable).filter(r -> !builtin(r))
                .ifPresent(r -> editRole(r.id(), r.name(), nz(r.description()), Set.copyOf(r.permissions()))));
        Button copy = new Button("Kopieren…");
        copy.setOnAction(e -> selected(roleTable).filter(r -> !builtin(r))
                .ifPresent(r -> editRole(null, r.name() + " (Kopie)", nz(r.description()),
                        Set.copyOf(r.permissions()))));
        Button delete = new Button("Löschen");
        delete.setOnAction(e -> selected(roleTable).filter(r -> !builtin(r)).ifPresent(this::deleteRole));
        Label help = help("Eine Rolle bündelt Systemrechte und Rechte auf Module und Tools. Module und Tools ohne "
                + "Recht sind in der Desktop-App aus. Die Rolle „Administrator“ hat alle Rechte und ist nicht "
                + "änderbar.");
        VBox box = new VBox(10, help, new HBox(8, create, edit, copy, delete), roleTable);
        box.setPadding(new Insets(12, 0, 0, 0));
        return box;
    }

    private boolean builtin(RoleInfo r) {
        if (r.builtin()) {
            status.setText("Die Rolle „" + r.name() + "“ ist eingebaut und nicht änderbar.");
        }
        return r.builtin();
    }

    /** Dialog zum Anlegen ({@code id == null}) oder Ändern einer Rolle. */
    private void editRole(Long id, String name, String description, Set<String> permissions) {
        Dialog<ButtonType> d = dialog(id == null ? "Neue Rolle" : "Rolle „" + name + "“");
        Set<String> rest = new LinkedHashSet<>(permissions);
        TextField nameField = new TextField(name);
        TextField descriptionField = new TextField(description);

        VBox system = new VBox(4);
        Map<Permission, CheckBox> systemBoxes = new LinkedHashMap<>();
        for (Permission p : Permission.values()) {
            CheckBox cb = new CheckBox(p.label());
            cb.setSelected(rest.remove(p.key()));
            cb.setTooltip(new Tooltip(p.description()));
            systemBoxes.put(p, cb);
            system.getChildren().add(cb);
        }

        CheckBox allModules = new CheckBox("Alle Module und Tools – auch künftige aus Plugins und Skripten");
        allModules.setSelected(rest.remove(Grants.ALL_MODULES));
        CheckBoxTreeItem<String> root = new CheckBoxTreeItem<>("Module");
        Map<CheckBoxTreeItem<String>, ToolModule> moduleItems = new LinkedHashMap<>();
        for (ToolModule m : registry.modules()) {
            List<ToolDefinition> tools = registry.availableTools(m.id());
            if (tools.isEmpty()) {
                continue;
            }
            CheckBoxTreeItem<String> item = new CheckBoxTreeItem<>(m.displayName() + " (" + m.id() + ")");
            boolean full = rest.remove(Grants.module(m.id()));
            for (ToolDefinition t : tools) {
                CheckBoxTreeItem<String> leaf = new CheckBoxTreeItem<>(t.name());
                item.getChildren().add(leaf); // erst einhängen: die Auswahl der Tools bestimmt die des Moduls
                leaf.setSelected(rest.remove(Grants.tool(t.name())));
            }
            if (full) {
                item.setSelected(true);
            }
            moduleItems.put(item, m);
            root.getChildren().add(item);
        }
        TreeView<String> tree = new TreeView<>(root);
        tree.setShowRoot(false);
        tree.setCellFactory(CheckBoxTreeCell.forTreeView());
        tree.setPrefHeight(260);
        tree.disableProperty().bind(allModules.selectedProperty());
        TextArea extra = new TextArea(String.join("\n", rest));
        extra.setPrefRowCount(2);
        extra.setPromptText("weitere Rechte je Zeile, z.B. module:jira oder tool:jira_issue");

        GridPane head = grid();
        head.addRow(0, new Label("Name"), nameField);
        head.addRow(1, new Label("Beschreibung"), descriptionField);
        VBox content = new VBox(10, head, title("Systemrechte"), system, title("Module und Tools"),
                help("Ein ganz angehaktes Modul umfasst auch Tools, die später dazukommen."), allModules, tree,
                title("Weitere Rechte"), extra);
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setPrefSize(560, 620);
        d.getDialogPane().setContent(scroll);
        d.showAndWait().filter(b -> b == ButtonType.OK).ifPresent(b -> {
            List<String> result = new ArrayList<>();
            systemBoxes.forEach((p, cb) -> {
                if (cb.isSelected()) {
                    result.add(p.key());
                }
            });
            if (allModules.isSelected()) {
                result.add(Grants.ALL_MODULES);
            } else {
                moduleItems.forEach((item, m) -> {
                    if (item.isSelected() && !item.isIndeterminate()) {
                        result.add(Grants.module(m.id()));
                    } else {
                        item.getChildren().stream().map(c -> (CheckBoxTreeItem<String>) c)
                                .filter(CheckBoxTreeItem::isSelected)
                                .forEach(c -> result.add(Grants.tool(c.getValue())));
                    }
                });
            }
            extra.getText().lines().map(String::strip).filter(l -> !l.isEmpty()).forEach(result::add);
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("name", nameField.getText());
            vars.put("description", descriptionField.getText());
            vars.put("permissions", result);
            if (id == null) {
                background(() -> backend.query("mutation($name: String!, $description: String, $permissions: "
                                + "[String!]!) { createRole(name: $name, description: $description, permissions: "
                                + "$permissions) { id } }", vars, "createRole", Object.class),
                        r -> done("Rolle angelegt"));
            } else {
                vars.put("id", id);
                background(() -> backend.query("mutation($id: Int!, $name: String!, $description: String, "
                                + "$permissions: [String!]!) { updateRole(id: $id, name: $name, description: "
                                + "$description, permissions: $permissions) { id } }", vars, "updateRole",
                        Object.class), r -> done("Gespeichert"));
            }
        });
    }

    private void deleteRole(RoleInfo r) {
        if (confirm("Rolle „" + r.name() + "“ löschen? " + r.users() + " Benutzer verlieren ihre Rechte daraus.")) {
            background(() -> backend.query("mutation($id: Int!) { deleteRole(id: $id) }", Map.of("id", r.id()),
                    "deleteRole", Boolean.class), x -> done("Rolle gelöscht"));
        }
    }

    /** Kurzfassung der Rechte für die Tabelle. */
    static String summary(RoleInfo r) {
        Grants g = Grants.of(r.permissions());
        if (g.all()) {
            return "alle Rechte";
        }
        long system = Arrays.stream(Permission.values()).filter(g::has).count();
        long modules = r.permissions().stream().filter(p -> p.startsWith(Grants.MODULE_PREFIX)
                && !p.equals(Grants.ALL_MODULES)).count();
        long tools = r.permissions().stream().filter(p -> p.startsWith(Grants.TOOL_PREFIX)).count();
        return system + " Systemrechte, " + (g.allModules() ? "alle Module"
                : modules + " Module" + (tools > 0 ? ", " + tools + " einzelne Tools" : ""));
    }

    // ---------------------------------------------------------------- Laden, Hilfen

    /** Lädt Benutzer und Rollen neu (beliebiger Thread). */
    public void refresh() {
        if (!backend.grants().has(Permission.USERS_MANAGE)) {
            Platform.runLater(() -> {
                users.clear();
                roles.clear();
            });
            return;
        }
        CompletableFuture.supplyAsync(() -> List.of(
                backend.queryList("{ users { " + USER_FIELDS + " } }", "users", UserInfo.class),
                backend.queryList("{ roles { " + ROLE_FIELDS + " } }", "roles", RoleInfo.class)))
                .whenComplete((r, e) -> Platform.runLater(() -> {
                    if (e != null) {
                        status.setText("Fehler: " + (e.getCause() != null ? e.getCause() : e).getMessage());
                        return;
                    }
                    @SuppressWarnings("unchecked") List<UserInfo> u = (List<UserInfo>) r.get(0);
                    @SuppressWarnings("unchecked") List<RoleInfo> ro = (List<RoleInfo>) r.get(1);
                    users.setAll(u);
                    roles.setAll(ro);
                }));
    }

    private void done(String message) {
        status.setText(message);
        refresh();
    }

    private <T> void background(Supplier<T> action, Consumer<T> success) {
        status.setText("…");
        CompletableFuture.supplyAsync(action).whenComplete((r, e) -> Platform.runLater(() -> {
            if (e != null) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                status.setText("Fehler: " + cause.getMessage());
            } else {
                success.accept(r);
            }
        }));
    }

    private Dialog<ButtonType> dialog(String title) {
        Dialog<ButtonType> d = new Dialog<>();
        d.setTitle(title);
        d.setHeaderText(title);
        if (getScene() != null) {
            d.initOwner(getScene().getWindow());
        }
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        return d;
    }

    private boolean confirm(String text) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, text, ButtonType.OK, ButtonType.CANCEL);
        a.setHeaderText(null);
        if (getScene() != null) {
            a.initOwner(getScene().getWindow());
        }
        return a.showAndWait().filter(b -> b == ButtonType.OK).isPresent();
    }

    private static <T> Optional<T> selected(TableView<T> table) {
        return Optional.ofNullable(table.getSelectionModel().getSelectedItem());
    }

    private static <T> void column(TableView<T> table, String title, java.util.function.Function<T, String> value,
                                   double width) {
        TableColumn<T, String> c = new TableColumn<>(title);
        c.setCellValueFactory(cell -> new SimpleStringProperty(value.apply(cell.getValue())));
        c.setPrefWidth(width);
        table.getColumns().add(c);
    }

    private static GridPane grid() {
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(8);
        grid.setAlignment(Pos.TOP_LEFT);
        return grid;
    }

    private static Label title(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("section-title");
        return l;
    }

    private static Label help(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.getStyleClass().add("form-help");
        return l;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
