package systems.grebe.devtools.mcp.modules.window.platform;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;

/** Eigener Prozess für {@link Win32WindowSystemTest}: zeigt ein Fenster an fester Position und meldet die Skalierung. */
public final class WindowFixture {

    static final String TITLE = "DevTools-Fenstertest";

    private WindowFixture() {
    }

    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JFrame f = new JFrame(TITLE);
            f.setBounds(200, 150, 400, 300);
            f.setFocusableWindowState(false); // dem Nutzer nicht den Fokus stehlen
            f.setVisible(true);
            System.out.println("SCALE " + f.getGraphicsConfiguration().getDefaultTransform().getScaleX());
            System.out.flush();
        });
        Thread.sleep(60_000);
        System.exit(0);
    }
}
