package systems.grebe.devtools.mcp.ui;

import java.security.SecureRandom;
import java.util.HexFormat;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.Window;
import systems.grebe.devtools.mcp.config.ServerSettings;

/** Dialog für allgemeine Einstellungen (Port, Token, Tray-Verhalten). */
public class SettingsDialog extends Dialog<ServerSettings> {

    public SettingsDialog(Window owner, ServerSettings current, int runningPort) {
        initOwner(owner);
        setTitle("Einstellungen");
        setHeaderText("Server & Anwendung");

        Spinner<Integer> port = new Spinner<>(1024, 65535, current.port());
        port.setEditable(true);
        Label portHint = new Label(runningPort == current.port()
                ? "Änderung wird nach einem Neustart wirksam."
                : "Aktuell läuft der Server auf Port " + runningPort + " – neuer Port nach Neustart.");
        portHint.getStyleClass().add("form-help");

        TextField token = new TextField(current.authToken());
        token.setPromptText("leer = kein Token erforderlich");
        HBox.setHgrow(token, Priority.ALWAYS);
        Button generate = new Button("Erzeugen");
        generate.setOnAction(e -> {
            byte[] b = new byte[24];
            new SecureRandom().nextBytes(b);
            token.setText(HexFormat.of().formatHex(b));
        });
        Label tokenHint = new Label("Clients senden es als Header „Authorization: Bearer <token>“. Wirkt sofort.");
        tokenHint.getStyleClass().add("form-help");

        CheckBox closeToTray = new CheckBox("Fenster schließen minimiert in den System-Tray");
        closeToTray.setSelected(current.closeToTray());
        CheckBox startMinimized = new CheckBox("Minimiert im Tray starten");
        startMinimized.setSelected(current.startMinimized());

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(8);
        grid.setPadding(new Insets(16));
        grid.addRow(0, new Label("Port"), port);
        grid.add(portHint, 1, 1);
        grid.addRow(2, new Label("Zugriffstoken"), new HBox(6, token, generate));
        grid.add(tokenHint, 1, 3);
        grid.add(closeToTray, 1, 4);
        grid.add(startMinimized, 1, 5);
        getDialogPane().setContent(grid);
        getDialogPane().setPrefWidth(620);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        setResultConverter(bt -> bt == ButtonType.OK
                ? new ServerSettings(port.getValue(), token.getText(), closeToTray.isSelected(), startMinimized.isSelected())
                : null);
    }
}
