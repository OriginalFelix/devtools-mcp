package systems.grebe.devtools.mcp.ui;

import java.util.List;

import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.PrimerLight;
import javafx.application.Application;
import javafx.application.ColorScheme;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.stage.Window;

/**
 * Erscheinungsbild: AtlantaFX Primer hell oder dunkel nach der Systemeinstellung, darüber {@code app.css} mit den
 * App-Stilen auf den Farb-Tokens des Themes und im Dunkelmodus {@code app-dark.css} (Farben des Skript-Editors).
 */
public final class Theme {

    private static final String CSS = Theme.class.getResource("/ui/app.css").toExternalForm();
    private static final String DARK_CSS = Theme.class.getResource("/ui/app-dark.css").toExternalForm();

    private Theme() {
    }

    /** Basistheme setzen und Wechsel des Systemschemas verfolgen; einmal beim Start auf dem FX-Thread. */
    public static void install() {
        applyBase();
        Platform.getPreferences().colorSchemeProperty().addListener((o, was, now) -> {
            applyBase();
            Window.getWindows().stream().filter(w -> w.getScene() != null)
                    .forEach(w -> restyle(w.getScene().getStylesheets()));
        });
    }

    /** Stylesheets der App für eine Szene (nach {@link #install()}). */
    public static List<String> stylesheets() {
        return dark() ? List.of(CSS, DARK_CSS) : List.of(CSS);
    }

    /** {@code -Ddevtools.theme=dark|light} übersteuert die Systemeinstellung. */
    private static boolean dark() {
        String forced = System.getProperty("devtools.theme", "");
        return forced.isEmpty() ? Platform.getPreferences().getColorScheme() == ColorScheme.DARK : forced.equals("dark");
    }

    private static void applyBase() {
        Application.setUserAgentStylesheet((dark() ? new PrimerDark() : new PrimerLight()).getUserAgentStylesheet());
    }

    private static void restyle(ObservableList<String> sheets) {
        if (sheets.contains(CSS)) {
            sheets.removeAll(CSS, DARK_CSS);
            sheets.addAll(stylesheets());
        }
    }
}
