package systems.grebe.devtools.mcp.modules.window.cursor.windows;

import java.awt.AWTEvent;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GraphicsConfiguration;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.event.WindowEvent;

import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * Eigener Prozess für {@link AwtInputTest}: ein Swing-Fenster wie ein typischer Java-Client (Suchfeld, Reiter), das
 * ohne Fokus erscheint und meldet, was bei ihm ankommt – {@code TEXT}, {@code ENTER}, {@code TAB} und den Java-Fokus
 * des Fensters ({@code FOCUSED}/{@code UNFOCUSED}).
 */
public final class AwtFixture {

    static final String TITLE = "DevTools-AWT-Eingabetest";

    private AwtFixture() {
    }

    private static void out(String line) {
        System.out.println(line);
        System.out.flush();
    }

    public static void main(String[] args) throws Exception {
        JTextField field = new JTextField(20);
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Eins", new JLabel("eins"));
        tabs.addTab("Zwei", new JLabel("zwei"));
        tabs.addTab("Teilnehmer", new JLabel("drei"));
        Toolkit.getDefaultToolkit().addAWTEventListener(e -> out(e.getID() == WindowEvent.WINDOW_GAINED_FOCUS
                ? "FOCUSED" : "UNFOCUSED"), AWTEvent.WINDOW_FOCUS_EVENT_MASK);
        // zur Diagnose: was sonst ankommt
        Toolkit.getDefaultToolkit().addAWTEventListener(e -> out("event " + e.getSource().getClass().getSimpleName()
                + " " + e.paramString()), AWTEvent.KEY_EVENT_MASK | AWTEvent.FOCUS_EVENT_MASK);
        SwingUtilities.invokeAndWait(() -> {
            JFrame f = new JFrame(TITLE);
            JPanel top = new JPanel();
            top.add(new JLabel("Suche:"));
            top.add(field);
            f.add(top, BorderLayout.NORTH);
            f.add(tabs, BorderLayout.CENTER);
            f.setBounds(160, 120, 520, 300);
            f.setAutoRequestFocus(false); // wie ein Fenster im Hintergrund: nicht aktiv, dem Nutzer nichts wegnehmen
            f.setVisible(true);
            field.getDocument().addDocumentListener(new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent e) {
                    out("TEXT " + field.getText());
                }

                @Override
                public void removeUpdate(DocumentEvent e) {
                    out("TEXT " + field.getText());
                }

                @Override
                public void changedUpdate(DocumentEvent e) {
                }
            });
            field.addActionListener(e -> out("ENTER " + field.getText()));
            tabs.addChangeListener(e -> out("TAB " + tabs.getSelectedIndex()));
        });
        Thread.sleep(500);
        SwingUtilities.invokeAndWait(() -> {
            Point fieldAt = physical(field, field.getWidth() / 2, field.getHeight() / 2);
            Rectangle second = tabs.getBoundsAt(1);
            Point tabAt = physical(tabs, second.x + second.width / 2, second.y + second.height / 2);
            out("READY " + fieldAt.x + " " + fieldAt.y + " " + tabAt.x + " " + tabAt.y);
        });
        Thread.sleep(60_000);
        System.exit(0);
    }

    /** Punkt in {@code c} als physische Bildschirmpixel (Java rechnet je Bildschirm skaliert). */
    private static Point physical(Component c, int x, int y) {
        Point p = c.getLocationOnScreen();
        GraphicsConfiguration gc = c.getGraphicsConfiguration();
        Rectangle b = gc.getBounds();
        double s = gc.getDefaultTransform().getScaleX();
        return new Point((int) Math.round(b.x + (p.x + x - b.x) * s), (int) Math.round(b.y + (p.y + y - b.y) * s));
    }
}
