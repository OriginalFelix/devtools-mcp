package systems.grebe.devtools.mcp.ui;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import systems.grebe.devtools.mcp.plugin.PluginKeys;
import systems.grebe.devtools.mcp.plugin.PluginManager;
import systems.grebe.devtools.mcp.plugin.PluginManager.PluginInfo;
import systems.grebe.devtools.mcp.plugin.store.PluginCatalog;
import systems.grebe.devtools.mcp.plugin.store.PluginRepository;
import systems.grebe.devtools.mcp.plugin.store.PluginStore;

/**
 * Tab „Plugins“: installierte Plugins (an/aus, entfernen, aktualisieren), der Plugin-Store über Maven-Repositories
 * und die Verwaltung dieser Repositories. Alles, was Netzwerk oder Plugin-Code berührt, läuft im Hintergrund.
 */
public class PluginsView extends BorderPane {

    private final PluginManager manager;
    private final PluginStore store;
    private final Label status = new Label();

    // Installiert
    private final ObservableList<PluginInfo> installed = FXCollections.observableArrayList();
    private final TableView<PluginInfo> installedTable = new TableView<>(installed);
    private final TextArea installedDetail = new TextArea();
    private final Button toggle = new Button("Deaktivieren");
    private final Button uninstall = new Button("Entfernen…");
    private final Button update = new Button("Aktualisieren");
    private Map<String, PluginStore.Update> updates = Map.of();

    // Store
    private final ObservableList<PluginCatalog.Entry> catalog = FXCollections.observableArrayList();
    private final FilteredList<PluginCatalog.Entry> catalogFiltered = new FilteredList<>(catalog);
    private final TableView<PluginCatalog.Entry> catalogTable = new TableView<>(catalogFiltered);
    private final TextField search = new TextField();
    private final ComboBox<String> versions = new ComboBox<>();
    private final Button installSelected = new Button("Installieren");
    private final Label catalogErrors = new Label();

    // Repositories
    private final ObservableList<PluginRepository> repos = FXCollections.observableArrayList();
    private final TableView<PluginRepository> repoTable = new TableView<>(repos);

    public PluginsView(PluginManager manager, PluginStore store) {
        this.manager = manager;
        this.store = store;
        getStyleClass().add("plugins-view");

        Label dir = new Label(manager.directory().toString());
        dir.getStyleClass().add("form-help");
        Button openFolder = new Button("Ordner öffnen");
        openFolder.setOnAction(e -> openFolder());
        Button installJar = new Button("Jar installieren…");
        installJar.setOnAction(e -> chooseJar());
        Button reload = new Button("Neu laden");
        reload.setOnAction(e -> background("Lade Plugins neu …", () -> {
            manager.reload();
            return "Plugins neu geladen.";
        }));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(8, new Label("Plugin-Ordner:"), dir, spacer, openFolder, installJar, reload);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(10, 12, 10, 12));

        TabPane tabs = new TabPane(new Tab("Installiert", installedPane()), new Tab("Store", storePane()),
                new Tab("Repositories", repositoriesPane()), new Tab("Signaturen", signaturesPane()));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        status.getStyleClass().add("status-text");
        status.setWrapText(true);
        status.setPadding(new Insets(6, 12, 8, 12));

        setTop(bar);
        setCenter(tabs);
        setBottom(status);

        manager.addChangeListener(() -> Platform.runLater(this::refreshInstalled));
        refreshInstalled();
        refreshRepositories();
    }

    // ------------------------------------------------------------------ Installiert

    private BorderPane installedPane() {
        installedTable.setPlaceholder(new Label("Keine Plugins installiert. Jar in den Plugin-Ordner legen, "
                + "„Jar installieren…“ oder den Store verwenden."));
        installedTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        installedTable.getColumns().addAll(List.of(
                col("Name", 160, PluginInfo::name),
                col("Version", 90, PluginInfo::version),
                col("Status", 110, p -> stateLabel(p, updates.containsKey(p.name()))),
                col("Module", 140, p -> String.join(", ", p.modules())),
                col("Quelle", 320, p -> p.source() == null ? "Datei " + p.file() : p.source())));
        installedDetail.setEditable(false);
        installedDetail.setWrapText(true);
        installedDetail.getStyleClass().add("mono");
        installedDetail.setPrefRowCount(8);

        toggle.setOnAction(e -> selectedInstalled().ifPresent(p -> {
            boolean enable = p.state() != PluginManager.State.ENABLED;
            background((enable ? "Aktiviere " : "Deaktiviere ") + p.name() + " …", () -> {
                PluginInfo after = manager.setEnabled(p.name(), enable);
                return after.state() == PluginManager.State.FAILED
                        ? "!" + p.name() + ": " + after.error()
                        : p.name() + (enable ? " aktiviert." : " deaktiviert.");
            });
        }));
        uninstall.setOnAction(e -> selectedInstalled().ifPresent(p -> {
            if (confirm("Plugin " + p.name() + " entfernen?", "Das Jar wird gelöscht. Datenordner und "
                    + "Modul-Einstellungen bleiben erhalten; Plugins, die davon abhängen, werden deaktiviert.")) {
                background("Entferne " + p.name() + " …", () -> {
                    manager.uninstall(p.valid() ? p.name() : p.file());
                    return p.name() + " entfernt.";
                });
            }
        }));
        update.setOnAction(e -> selectedInstalled().map(p -> updates.get(p.name())).ifPresent(u ->
                background("Aktualisiere " + u.plugin() + " auf " + u.latestVersion() + " …", () -> {
                    PluginInfo after = store.install(u.coordinates());
                    checkUpdatesQuietly();
                    return after.state() == PluginManager.State.FAILED ? "!" + after.name() + ": " + after.error()
                            : u.plugin() + " auf " + u.latestVersion() + " aktualisiert.";
                })));
        Button checkUpdates = new Button("Nach Updates suchen");
        checkUpdates.setOnAction(e -> background("Suche Updates …", () -> {
            Map<String, PluginStore.Update> found = checkUpdatesQuietly();
            return found.isEmpty() ? "Alle über den Store installierten Plugins sind aktuell."
                    : found.size() + " Update(s) verfügbar: " + String.join(", ", found.keySet());
        }));
        installedTable.getSelectionModel().selectedItemProperty().addListener((o, a, n) -> showInstalled(n));
        showInstalled(null);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox buttons = new HBox(8, toggle, update, uninstall, spacer, checkUpdates);
        buttons.setAlignment(Pos.CENTER_LEFT);
        buttons.setPadding(new Insets(8, 0, 8, 0));
        VBox bottom = new VBox(buttons, installedDetail);
        bottom.setPadding(new Insets(0, 12, 0, 12));
        BorderPane pane = new BorderPane(installedTable);
        pane.setBottom(bottom);
        return pane;
    }

    private Map<String, PluginStore.Update> checkUpdatesQuietly() {
        Map<String, PluginStore.Update> found = new java.util.LinkedHashMap<>();
        store.updates().forEach(u -> found.put(u.plugin(), u));
        Platform.runLater(() -> {
            updates = Map.copyOf(found);
            installedTable.refresh();
            showInstalled(installedTable.getSelectionModel().getSelectedItem());
        });
        return found;
    }

    private void refreshInstalled() {
        PluginInfo sel = installedTable.getSelectionModel().getSelectedItem();
        installed.setAll(manager.plugins());
        if (sel != null) {
            installed.stream().filter(p -> p.name().equals(sel.name())).findFirst()
                    .ifPresent(p -> installedTable.getSelectionModel().select(p));
        }
        showInstalled(installedTable.getSelectionModel().getSelectedItem());
        catalogTable.refresh();
    }

    private void showInstalled(PluginInfo p) {
        toggle.setDisable(p == null || !p.valid());
        uninstall.setDisable(p == null);
        update.setDisable(p == null || !updates.containsKey(p.name()));
        toggle.setText(p != null && p.state() == PluginManager.State.ENABLED ? "Deaktivieren" : "Aktivieren");
        installedDetail.setText(p == null ? "" : describe(p, updates.get(p.name())));
    }

    /** Detailtext eines Plugins (ohne JavaFX testbar). */
    static String describe(PluginInfo p, PluginStore.Update update) {
        StringBuilder sb = new StringBuilder();
        sb.append(p.name());
        if (!p.version().isEmpty()) {
            sb.append(' ').append(p.version());
        }
        sb.append(" – ").append(stateLabel(p, update != null)).append('\n');
        if (p.error() != null) {
            sb.append("Fehler: ").append(p.error()).append('\n');
        }
        if (p.signature() != null) {
            p.signature().warnings().forEach(w -> sb.append("Warnung: ").append(w).append('\n'));
        }
        if (update != null) {
            sb.append("Update verfügbar: ").append(update.installedVersion()).append(" → ")
                    .append(update.latestVersion()).append('\n');
        }
        if (p.description() != null) {
            sb.append('\n').append(p.description()).append("\n\n");
        }
        if (!p.authors().isEmpty()) {
            sb.append("Autor: ").append(String.join(", ", p.authors())).append('\n');
        }
        if (p.website() != null) {
            sb.append("Website: ").append(p.website()).append('\n');
        }
        if (p.signature() != null) {
            sb.append("Signatur: ").append(p.signature().summary()).append('\n');
        }
        sb.append("Datei: ").append(p.file()).append('\n');
        if (p.source() != null) {
            sb.append("Maven: ").append(p.source()).append('\n');
        }
        if (!p.modules().isEmpty()) {
            sb.append("Module: ").append(String.join(", ", p.modules())).append('\n');
        }
        if (!p.providers().isEmpty()) {
            sb.append("Provider: ").append(String.join(", ", p.providers())).append('\n');
        }
        if (!p.depend().isEmpty()) {
            sb.append("Benötigt: ").append(String.join(", ", p.depend())).append('\n');
        }
        if (!p.softDepend().isEmpty()) {
            sb.append("Optional: ").append(String.join(", ", p.softDepend())).append('\n');
        }
        if (!p.libraries().isEmpty()) {
            sb.append("Bibliotheken: ").append(String.join(", ", p.libraries())).append('\n');
        }
        return sb.toString().strip();
    }

    static String stateLabel(PluginInfo p, boolean updateAvailable) {
        String s = switch (p.state()) {
            case ENABLED -> "aktiv";
            case DISABLED -> "deaktiviert";
            case FAILED -> "Fehler";
        };
        if (p.signature() != null && !p.signature().warnings().isEmpty()) {
            s += " · Warnung";
        }
        return updateAvailable ? s + " · Update" : s;
    }

    private Optional<PluginInfo> selectedInstalled() {
        return Optional.ofNullable(installedTable.getSelectionModel().getSelectedItem());
    }

    // ------------------------------------------------------------------ Store

    private BorderPane storePane() {
        search.setPromptText("Suchen (Name, Koordinaten, Beschreibung, Tags)");
        search.textProperty().addListener((o, a, n) -> catalogFiltered.setPredicate(e -> e.matches(n)));
        HBox.setHgrow(search, Priority.ALWAYS);
        Button load = new Button("Katalog laden");
        load.getStyleClass().add("accent");
        load.setOnAction(e -> loadCatalog());
        HBox top = new HBox(8, search, load);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(10, 12, 8, 12));

        catalogTable.setPlaceholder(new Label("„Katalog laden“ liest die Plugin-Kataloge der Repositories. "
                + "Ohne Katalog: Plugin unten über seine Maven-Koordinaten installieren."));
        catalogTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        catalogTable.getColumns().addAll(List.of(
                col("Name", 150, PluginCatalog.Entry::name),
                col("Koordinaten", 260, PluginCatalog.Entry::coordinates),
                col("Repository", 110, PluginCatalog.Entry::repositoryId),
                col("Installiert", 90, e -> store.installed(e.groupId(), e.artifactId())
                        .map(PluginInfo::version).orElse("")),
                col("Beschreibung", 320, PluginCatalog.Entry::description)));
        catalogTable.getSelectionModel().selectedItemProperty().addListener((o, a, n) -> loadVersions(n));

        versions.setPromptText("Version");
        versions.setPrefWidth(160);
        installSelected.getStyleClass().add("accent");
        installSelected.setDisable(true);
        installSelected.setOnAction(e -> {
            PluginCatalog.Entry entry = catalogTable.getSelectionModel().getSelectedItem();
            String v = versions.getValue();
            if (entry != null && v != null) {
                install(entry.coordinates() + ":" + v);
            }
        });
        HBox selectedRow = new HBox(8, new Label("Ausgewähltes Plugin:"), versions, installSelected);
        selectedRow.setAlignment(Pos.CENTER_LEFT);

        TextField direct = new TextField();
        direct.setPromptText("groupId:artifactId[:version]  – ohne Version: neueste");
        HBox.setHgrow(direct, Priority.ALWAYS);
        Button installDirect = new Button("Installieren");
        installDirect.setOnAction(e -> {
            if (!direct.getText().isBlank()) {
                install(direct.getText().strip());
            }
        });
        HBox directRow = new HBox(8, new Label("Direkt über Koordinaten:"), direct, installDirect);
        directRow.setAlignment(Pos.CENTER_LEFT);

        catalogErrors.getStyleClass().addAll("status-text", "error");
        catalogErrors.setWrapText(true);
        Label warning = new Label("Plugins laufen im Prozess der App mit deinen Rechten – nur aus vertrauenswürdigen "
                + "Repositories installieren.");
        warning.getStyleClass().add("form-help");
        VBox bottom = new VBox(8, selectedRow, directRow, catalogErrors, warning);
        bottom.setPadding(new Insets(8, 12, 4, 12));

        BorderPane pane = new BorderPane(catalogTable);
        pane.setTop(top);
        pane.setBottom(bottom);
        return pane;
    }

    private void loadCatalog() {
        background("Lade Kataloge …", () -> {
            PluginStore.CatalogResult result = store.catalog();
            Platform.runLater(() -> {
                catalog.setAll(result.entries());
                catalogErrors.setText(result.errors().isEmpty() ? "" : "Nicht erreichbar: " + String.join("; ",
                        result.errors().entrySet().stream().map(e -> e.getKey() + " – " + e.getValue()).toList()));
            });
            long withCatalog = store.repositories().stream().filter(r -> r.enabled() && r.hasCatalog()).count();
            return withCatalog == 0 ? "Kein aktives Repository hat einen Katalog – unter „Repositories“ eintragen "
                    + "oder direkt über Koordinaten installieren."
                    : result.entries().size() + " Plugins in " + withCatalog + " Katalog(en).";
        });
    }

    private void loadVersions(PluginCatalog.Entry entry) {
        versions.getItems().clear();
        installSelected.setDisable(true);
        if (entry == null) {
            return;
        }
        background("Lade Versionen von " + entry.coordinates() + " …", () -> {
            List<String> list = store.versions(entry.groupId(), entry.artifactId());
            Platform.runLater(() -> {
                if (entry.equals(catalogTable.getSelectionModel().getSelectedItem())) {
                    versions.getItems().setAll(list);
                    if (!list.isEmpty()) {
                        versions.getSelectionModel().selectFirst();
                    }
                    installSelected.setDisable(list.isEmpty());
                }
            });
            return list.isEmpty() ? "!Keine Versionen von " + entry.coordinates() + " gefunden."
                    : list.size() + " Versionen von " + entry.coordinates() + ", neueste " + list.getFirst() + ".";
        });
    }

    private void install(String coordinates) {
        background("Installiere " + coordinates + " …", () -> {
            PluginInfo p = store.install(coordinates);
            return p.state() == PluginManager.State.FAILED
                    ? "!" + p.name() + " " + p.version() + " installiert, aber nicht aktiv: " + p.error()
                    : p.name() + " " + p.version() + " installiert und aktiviert"
                    + (p.modules().isEmpty() ? "." : " – Module: " + String.join(", ", p.modules()) + ".");
        });
    }

    // ------------------------------------------------------------------ Signaturen

    private BorderPane signaturesPane() {
        TextArea keys = new TextArea(String.join("\n\n", manager.trustedKeys()));
        keys.getStyleClass().add("mono");
        keys.setPromptText("-----BEGIN PUBLIC KEY-----\n…\n-----END PUBLIC KEY-----");
        Button save = new Button("Speichern");
        save.getStyleClass().add("accent");
        save.setOnAction(e -> {
            try {
                List<String> blocks = PluginKeys.blocks(keys.getText());
                if (blocks.isEmpty() && !keys.getText().isBlank()) {
                    PluginKeys.publicKeys(keys.getText()); // wirft mit verständlicher Meldung
                }
                manager.setTrustedKeys(blocks);
                keys.setText(String.join("\n\n", manager.trustedKeys()));
                status.setText("Vertrauenswürdige Schlüssel gespeichert, Signaturen neu geprüft.");
            } catch (IllegalArgumentException ex) {
                status.setText("Nicht gespeichert: " + ex.getMessage());
            }
        });
        Label hint = new Label("Öffentliche Schlüssel (PEM, RSA oder EC), gegen die die Signatur der Plugins "
                + "(plugin.jwt) geprüft wird – mehrere untereinander. Signieren: java -jar devtools-mcp.jar "
                + "sign-plugin --key <privat.pem> <plugin.jar>. Plugins laden auch ohne oder mit abweichender "
                + "Signatur; Abweichungen stehen als Warnung im Log und unter „Installiert“.");
        hint.getStyleClass().add("form-help");
        hint.setWrapText(true);
        HBox buttons = new HBox(8, save);
        buttons.setAlignment(Pos.CENTER_LEFT);
        VBox top = new VBox(8, buttons, hint);
        top.setPadding(new Insets(10, 12, 8, 12));
        BorderPane pane = new BorderPane(keys);
        pane.setTop(top);
        BorderPane.setMargin(keys, new Insets(0, 12, 12, 12));
        return pane;
    }

    // ------------------------------------------------------------------ Repositories

    private BorderPane repositoriesPane() {
        repoTable.setPlaceholder(new Label("Keine Repositories – „Hinzufügen…“."));
        repoTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        repoTable.getColumns().addAll(List.of(
                col("Aktiv", 50, r -> r.enabled() ? "✓" : ""),
                col("ID", 110, PluginRepository::id),
                col("Name", 140, PluginRepository::name),
                col("Benutzer", 100, PluginRepository::username),
                col("Katalog", 200, PluginRepository::catalog),
                col("URL", 320, PluginRepository::url)));
        repoTable.setRowFactory(tv -> {
            var row = new javafx.scene.control.TableRow<PluginRepository>();
            row.setOnMouseClicked(ev -> {
                if (ev.getClickCount() == 2 && !row.isEmpty()) {
                    editRepository(row.getItem());
                }
            });
            return row;
        });
        Button add = new Button("Hinzufügen…");
        add.getStyleClass().add("accent");
        add.setOnAction(e -> editRepository(null));
        Button edit = new Button("Bearbeiten…");
        edit.setOnAction(e -> selectedRepo().ifPresent(this::editRepository));
        Button remove = new Button("Entfernen");
        remove.setOnAction(e -> selectedRepo().ifPresent(r -> {
            if (confirm("Repository " + r.id() + " entfernen?", "Installierte Plugins bleiben erhalten.")) {
                store.removeRepository(r.id());
                refreshRepositories();
            }
        }));
        Button up = new Button("↑");
        up.setOnAction(e -> selectedRepo().ifPresent(r -> move(r, -1)));
        Button down = new Button("↓");
        down.setOnAction(e -> selectedRepo().ifPresent(r -> move(r, 1)));
        Button test = new Button("Testen");
        test.setOnAction(e -> selectedRepo().ifPresent(r -> background("Prüfe " + r.id() + " …",
                () -> r.id() + ": " + store.testRepository(r))));
        var sel = repoTable.getSelectionModel().selectedItemProperty();
        for (Button b : List.of(edit, remove, up, down, test)) {
            b.disableProperty().bind(sel.isNull());
        }
        Label hint = new Label("Reihenfolge = Suchreihenfolge. Ein Repository kann einen Katalog (groupId:artifactId "
                + "eines .yml-Artefakts) anbieten; Plugins lassen sich aber auch ohne Katalog über ihre Koordinaten "
                + "installieren. Passwörter werden verschlüsselt gespeichert.");
        hint.getStyleClass().add("form-help");
        hint.setWrapText(true);
        HBox buttons = new HBox(8, add, edit, remove, up, down, test);
        buttons.setAlignment(Pos.CENTER_LEFT);
        VBox top = new VBox(8, buttons, hint);
        top.setPadding(new Insets(10, 12, 8, 12));
        BorderPane pane = new BorderPane(repoTable);
        pane.setTop(top);
        return pane;
    }

    private void move(PluginRepository r, int delta) {
        store.moveRepository(r.id(), delta);
        refreshRepositories();
        repos.stream().filter(x -> x.id().equals(r.id())).findFirst()
                .ifPresent(x -> repoTable.getSelectionModel().select(x));
    }

    private void refreshRepositories() {
        repos.setAll(store.repositories());
    }

    private Optional<PluginRepository> selectedRepo() {
        return Optional.ofNullable(repoTable.getSelectionModel().getSelectedItem());
    }

    private void editRepository(PluginRepository existing) {
        Dialog<PluginRepository> dialog = new Dialog<>();
        dialog.setTitle(existing == null ? "Repository hinzufügen" : "Repository bearbeiten");
        if (getScene() != null) {
            dialog.initOwner(getScene().getWindow());
        }
        TextField id = new TextField(existing == null ? "" : existing.id());
        id.setDisable(existing != null);
        TextField name = new TextField(existing == null ? "" : existing.name());
        TextField url = new TextField(existing == null ? "https://" : existing.url());
        TextField user = new TextField(existing == null ? "" : existing.username());
        PasswordField password = new PasswordField();
        password.setPromptText(existing != null && existing.hasCredentials() ? "(unverändert)" : "");
        TextField catalogField = new TextField(existing == null ? "" : existing.catalog());
        catalogField.setPromptText("optional, z.B. com.acme:devtools-plugins");
        CheckBox snapshots = new CheckBox("SNAPSHOT-Versionen anbieten");
        snapshots.setSelected(existing != null && existing.snapshots());
        CheckBox enabled = new CheckBox("Aktiv");
        enabled.setSelected(existing == null || existing.enabled());
        Label result = new Label();
        result.getStyleClass().add("status-text");
        result.setWrapText(true);
        result.setMaxWidth(460);

        Supplier<PluginRepository> build = () -> new PluginRepository(id.getText(), name.getText(), url.getText(),
                user.getText(), password.getText(), snapshots.isSelected(), catalogField.getText(),
                enabled.isSelected());
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(12));
        int row = 0;
        for (var pair : List.of(Map.entry("ID *", id), Map.entry("Name", name), Map.entry("URL *", url),
                Map.entry("Benutzer", user), Map.entry("Passwort / Token", password),
                Map.entry("Katalog", catalogField))) {
            grid.add(new Label(pair.getKey()), 0, row);
            pair.getValue().setPrefColumnCount(32);
            grid.add(pair.getValue(), 1, row++);
        }
        grid.add(snapshots, 1, row++);
        grid.add(enabled, 1, row++);
        Button test = new Button("Verbindung testen");
        test.setOnAction(e -> {
            PluginRepository candidate = existing != null && password.getText().isEmpty()
                    && user.getText().strip().equals(existing.username())
                    ? withPassword(build.get(), existing.password()) : build.get();
            result.setText("Prüfe …");
            result.getStyleClass().removeAll("ok", "error");
            Thread.ofVirtual().start(() -> {
                String msg;
                boolean ok;
                try {
                    msg = store.testRepository(candidate);
                    ok = true;
                } catch (RuntimeException ex) {
                    msg = ex.getMessage();
                    ok = false;
                }
                String m = msg;
                boolean good = ok;
                Platform.runLater(() -> {
                    result.getStyleClass().add(good ? "ok" : "error");
                    result.setText(m);
                });
            });
        });
        grid.add(test, 1, row++);
        grid.add(result, 1, row);
        dialog.getDialogPane().setContent(grid);
        ButtonType save = new ButtonType("Speichern", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        dialog.getDialogPane().lookupButton(save).addEventFilter(javafx.event.ActionEvent.ACTION, ev -> {
            List<String> errors = build.get().validate();
            if (existing == null && store.repositories().stream().anyMatch(r -> r.id().equals(id.getText().strip()))) {
                errors = new java.util.ArrayList<>(errors);
                errors.add("Die ID ist bereits vergeben.");
            }
            if (!errors.isEmpty()) {
                result.getStyleClass().removeAll("ok");
                result.getStyleClass().add("error");
                result.setText(String.join("\n", errors));
                ev.consume();
            }
        });
        dialog.setResultConverter(bt -> bt == save ? build.get() : null);
        dialog.showAndWait().ifPresent(r -> {
            store.saveRepository(r);
            refreshRepositories();
            showStatus(true, "Repository " + r.id() + " gespeichert.");
        });
    }

    private static PluginRepository withPassword(PluginRepository r, String password) {
        return new PluginRepository(r.id(), r.name(), r.url(), r.username(), password, r.snapshots(), r.catalog(),
                r.enabled());
    }

    // ------------------------------------------------------------------ Hilfen

    private void chooseJar() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Plugin-Jar installieren");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Plugin-Jar", "*.jar"));
        File file = chooser.showOpenDialog(getScene() == null ? null : getScene().getWindow());
        if (file != null) {
            background("Installiere " + file.getName() + " …", () -> {
                PluginInfo p = manager.install(file.toPath(), null);
                return p.state() == PluginManager.State.FAILED ? "!" + p.name() + ": " + p.error()
                        : p.name() + " " + p.version() + " installiert und aktiviert.";
            });
        }
    }

    private void openFolder() {
        Thread.ofVirtual().start(() -> {
            try {
                Files.createDirectories(manager.directory());
                Desktop.getDesktop().open(manager.directory().toFile());
            } catch (IOException | UnsupportedOperationException ex) {
                Platform.runLater(() -> showStatus(false, "Ordner nicht zu öffnen: " + ex.getMessage()));
            }
        });
    }

    /**
     * Führt eine Aktion im Hintergrund aus und zeigt ihr Ergebnis in der Statuszeile; eine Meldung mit führendem
     * {@code !} gilt als Fehler.
     */
    private void background(String progress, Supplier<String> action) {
        showStatus(null, progress);
        Thread.ofVirtual().start(() -> {
            try {
                String msg = action.get();
                Platform.runLater(() -> {
                    boolean failed = msg != null && msg.startsWith("!");
                    showStatus(!failed, msg == null ? "" : failed ? msg.substring(1) : msg);
                });
            } catch (RuntimeException e) {
                String msg = systems.grebe.devtools.mcp.core.ManagedToolCallback.describe(e);
                Platform.runLater(() -> showStatus(false, msg));
            }
        });
    }

    private void showStatus(Boolean ok, String text) {
        status.getStyleClass().removeAll("ok", "error");
        if (ok != null) {
            status.getStyleClass().add(ok ? "ok" : "error");
        }
        status.setText(text);
    }

    private boolean confirm(String header, String text) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, text, ButtonType.OK, ButtonType.CANCEL);
        a.setHeaderText(header);
        if (getScene() != null) {
            a.initOwner(getScene().getWindow());
        }
        return a.showAndWait().filter(b -> b == ButtonType.OK).isPresent();
    }

    private static <T> TableColumn<T, String> col(String title, double width, Function<T, String> f) {
        TableColumn<T, String> c = new TableColumn<>(title);
        c.setPrefWidth(width);
        c.setReorderable(false);
        c.setCellValueFactory(cd -> new ReadOnlyStringWrapper(nullToEmpty(f.apply(cd.getValue()))));
        return c;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

}
