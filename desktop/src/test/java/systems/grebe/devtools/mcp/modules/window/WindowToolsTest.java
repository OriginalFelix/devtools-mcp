package systems.grebe.devtools.mcp.modules.window;

import java.awt.Rectangle;
import java.awt.datatransfer.StringSelection;
import java.awt.event.InputEvent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import systems.grebe.devtools.mcp.core.ToolImages;
import systems.grebe.devtools.mcp.modules.window.platform.NativeWindow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** window_* gegen ein gefälschtes Fenstersystem; gebunden wird der Testprozess selbst. */
class WindowToolsTest {

    private static final long SELF = ProcessHandle.current().pid();
    private static final long MAIN = 0x1A2B;
    private static final long DIALOG = 0x3C4D;

    private FakeDesktop desktop;
    private WindowSession session;
    private WindowReadTools read;
    private WindowInputTools input;
    private WindowKeyboardTools keys;

    @BeforeEach
    void setUp() {
        desktop = new FakeDesktop();
        desktop.windows.add(new NativeWindow(MAIN, SELF, 42L, "Editor", new Rectangle(100, 50, 2560, 1440), false));
        desktop.windows.add(new NativeWindow(DIALOG, SELF, 42L, "Speichern", new Rectangle(500, 400, 400, 200), false));
        desktop.windows.add(new NativeWindow(0x99, 999_999_999L, 7L, "Fremd", new Rectangle(0, 0, 300, 300), false));
        session = new WindowSession();
        // self = -1: der Testprozess gilt nicht als "diese App"
        WindowSupport support = desktop.support(session, new ProcessFilter(null, null, -1));
        read = new WindowReadTools(support);
        input = new WindowInputTools(support, true);
        keys = new WindowKeyboardTools(support);
    }

    private void bind() {
        read.bind(SELF, null, false);
    }

    @Test
    void listShowsProcessWithWindowsAndThread() {
        assertThat(read.list(null)).contains("(PID " + SELF + ")", "0x1A2B  „Editor“  2560×1440 @ 100,50  Thread 42",
                "Gebunden: nichts").doesNotContain("Fremd");
    }

    @Test
    void withoutBindingToolsAskToBindFirst() {
        assertThatThrownBy(() -> read.windows()).hasMessageContaining("window_bind");
        assertThatThrownBy(() -> input.click(1, 1, null, null, null, null)).hasMessageContaining("window_bind");
    }

    @Test
    void bindByNameAndListOwnWindows() {
        String name = ProcessFilter.info(SELF).orElseThrow().name();
        assertThat(read.bind(null, java.util.regex.Pattern.quote(name), null)).contains("Gebunden: " + name,
                "0x1A2B", "0x3C4D", "mit Kindprozessen");
        assertThat(read.windows()).doesNotContain("Fremd");
    }

    @Test
    void foreignWindowIsRejected() {
        bind();
        assertThatThrownBy(() -> read.screenshot("0x99")).hasMessageContaining("gehört nicht zum gebundenen Prozess");
    }

    @Test
    void screenshotIsScaledAndClickMapsBack() {
        bind();
        var shot = ToolImages.capture(() -> read.screenshot("0x1A2B"));
        assertThat(shot.value()).contains("Bild 1280×720 px", "Faktor 0.500");
        assertThat(shot.images()).singleElement().satisfies(i -> assertThat(i.mimeType()).isEqualTo("image/png"));
        assertThat(desktop.events).contains("activate 1a2b", "capture 2560x1440");

        desktop.events.clear();
        input.click(100, 50, null, 2, null, "0x1A2B");
        int b1 = InputEvent.BUTTON1_DOWN_MASK;
        assertThat(desktop.events).containsExactly("move 300,150", "press " + b1, "release " + b1, "press " + b1,
                "release " + b1);
    }

    @Test
    void ownPointerScreenshotsInTheBackgroundEvenWithPhysicalPixels() {
        bind();
        desktop.independent = true;
        desktop.foreground = 0x99;
        // PrintWindow bei 200 %: doppelt so viele Pixel wie Bildschirmpunkte
        desktop.backgroundImage = new java.awt.image.BufferedImage(5120, 2880, java.awt.image.BufferedImage.TYPE_INT_RGB);

        var shot = ToolImages.capture(() -> read.screenshot("0x1A2B"));

        assertThat(shot.value()).contains("Bild 1280×720 px").doesNotContain("Hinweis");
        assertThat(desktop.events).containsExactly("background-capture 1a2b");
        assertThat(desktop.foreground).isEqualTo(0x99); // nichts nach vorn geholt
        desktop.events.clear();
        input.click(100, 50, null, null, null, "0x1A2B");
        assertThat(desktop.events).startsWith("move 300,150"); // dieselbe Stelle wie beim Bild vom Bildschirm
    }

    @Test
    void ownPointerWithoutBackgroundCaptureWarnsInsteadOfActivating() {
        bind();
        desktop.independent = true;
        desktop.foreground = 0x99;

        var shot = ToolImages.capture(() -> read.screenshot("0x1A2B"));

        assertThat(shot.value()).contains("Hinweis: Bild vom Bildschirm");
        assertThat(desktop.events).containsExactly("capture 2560x1440");
    }

    @Test
    void clickOutsideTheWindowIsRefused() {
        bind();
        assertThatThrownBy(() -> input.click(5000, 10, null, null, null, "0x3C4D")).hasMessageContaining("außerhalb");
        assertThat(desktop.events).noneMatch(e -> e.startsWith("press"));
    }

    @Test
    void withoutIdTheForegroundWindowOfTheProcessIsUsed() {
        bind();
        desktop.foreground = DIALOG;
        input.click(10, 10, "right", null, null, null);
        assertThat(desktop.events).contains("move 510,410", "press " + InputEvent.BUTTON3_DOWN_MASK);
    }

    @Test
    void typesKeysAndPastesSpecialCharactersRestoringTheClipboard() {
        bind();
        StringSelection previous = new StringSelection("vorher");
        desktop.clipboard = previous;
        keys.type("Ab ü!", null, "0x1A2B");
        assertThat(desktop.events).containsSubsequence("down Shift", "down A", "up A", "up Shift", "down B", "up B",
                "down Space", "up Space", "clipboard ü!", "down Ctrl", "down V", "up V", "up Ctrl", "clipboard vorher");
    }

    @Test
    void focusLossStopsTypingAndReportsProgress() {
        bind();
        desktop.foreground = MAIN;
        desktop.loseForegroundAfter(2);
        assertThatThrownBy(() -> keys.type("a".repeat(100), "keys", "0x1A2B"))
                .hasMessageContaining("nicht mehr im Vordergrund").hasMessageContaining("Bis dahin getippt: 20 von 100");
    }

    @Test
    void windowThatCannotBeActivatedGetsNoInput() {
        bind();
        desktop.activationWorks = false;
        assertThatThrownBy(() -> keys.key("enter", null, "0x1A2B")).hasMessageContaining("Vordergrund");
        assertThat(desktop.events).noneMatch(e -> e.startsWith("down"));
    }

    @Test
    void ownPointerIsNotStoppedByOtherPointers() {
        bind();
        desktop.independent = true;
        input.click(10, 10, null, null, null, "0x1A2B");
        desktop.pointer = new java.awt.Point(900, 900); // z.B. der Zeiger einer anderen KI oder die Maus des Nutzers

        assertThat(input.click(20, 20, null, null, null, "0x1A2B")).contains("Klick");
    }

    @Test
    void pastingRestoresAnEmptyClipboard() {
        bind();
        desktop.clipboard = null;

        keys.type("ü", "paste", "0x1A2B");

        assertThat(desktop.clipboard).isNotNull();
        assertThat(desktop.clipboard.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.stringFlavor)).isTrue();
        assertThat(clipboardText()).isEmpty(); // der eingefügte Text bleibt nicht darin stehen
    }

    private String clipboardText() {
        try {
            return (String) desktop.clipboard.getTransferData(java.awt.datatransfer.DataFlavor.stringFlavor);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void userMovingTheMouseAbortsAndLocksInput() {
        bind();
        input.click(10, 10, null, null, null, "0x1A2B");
        desktop.pointer = new java.awt.Point(900, 900); // Nutzer greift ein
        assertThatThrownBy(() -> keys.key("enter", null, "0x1A2B")).hasMessageContaining("Nutzer hat die Maus bewegt");
        assertThatThrownBy(() -> keys.key("enter", null, "0x1A2B")).hasMessageContaining("gesperrt");

        desktop.clock.addAndGet(11_000); // nach der Abkühlzeit geht es weiter
        assertThat(keys.key("enter", null, "0x1A2B")).contains("Gedrückt: enter");
    }

    @Test
    void ownPointerClicksWithoutActivatingButKeysStillDo() {
        bind();
        desktop.independent = true;
        desktop.foreground = 0x99; // der Nutzer arbeitet in einem anderen Fenster
        desktop.events.clear();

        input.click(10, 10, null, 2, null, "0x3C4D");
        input.drag(10, 10, 50, 10, null, "0x3C4D");
        input.scroll(10, 10, 2, "0x3C4D");

        assertThat(desktop.events).noneMatch(e -> e.startsWith("activate"));
        assertThat(desktop.foreground).isEqualTo(0x99);
        assertThat(desktop.control).allMatch(e -> e.equals("target 3c4d"));
        // gehaltene Strg-Taste und Tippen brauchen die echte Tastatur – also den Vordergrund
        input.click(10, 10, null, null, "ctrl", "0x3C4D");
        assertThat(desktop.events).contains("activate 3c4d");
    }

    @Test
    void ownKeyboardTypesWithoutActivatingAndWithoutClipboard() {
        bind();
        desktop.independent = true;
        desktop.independentKeys = true;
        desktop.foreground = 0x99; // der Nutzer arbeitet in einem anderen Fenster
        desktop.events.clear();

        assertThat(keys.type("Aü!", null, "0x3C4D")).contains("getippt", "Wirkung nicht geprüft");
        assertThat(keys.key("ctrl+s", null, "0x3C4D")).contains("Wirkung nicht geprüft");
        input.click(10, 10, null, null, "ctrl", "0x3C4D");

        assertThat(desktop.events).noneMatch(e -> e.startsWith("activate"));
        assertThat(desktop.foreground).isEqualTo(0x99);
        assertThat(desktop.events).containsSubsequence("char A", "char ü", "char !", "down Ctrl", "down S", "up S",
                "up Ctrl");
        assertThat(desktop.clipboard).isNull(); // nichts über die Zwischenablage
    }

    @Test
    void confirmedTextIsReportedAsArrived() {
        bind();
        desktop.independent = true;
        desktop.independentKeys = true;
        desktop.confirmsTyping = true; // wie die Bedienungshilfen unter macOS

        String text = "x".repeat(WindowKeyboardTools.CHUNK * 2 + 1);
        assertThat(keys.type(text, null, "0x3C4D")).contains("eingefügt (über die Bedienungshilfen bestätigt)")
                .doesNotContain("nicht geprüft");
        assertThat(desktop.events).filteredOn(e -> e.startsWith("char ")).hasSize(text.length());
    }

    @Test
    void inputIntoTheForegroundWindowNeedsNoCheckHint() {
        bind();
        assertThat(keys.type("abc", null, "0x3C4D")).doesNotContain("nicht geprüft");
    }

    @Test
    void clickWithHeldKeysNeedsKeyboardPermission() {
        bind();
        WindowInputTools mouseOnly = new WindowInputTools(desktop.support(session, new ProcessFilter(null, null, -1)),
                false);

        assertThatThrownBy(() -> mouseOnly.click(10, 10, null, null, "ctrl", "0x1A2B"))
                .hasMessageContaining("Tastatur erlauben");
        assertThat(desktop.events).noneMatch(e -> e.startsWith("down") || e.startsWith("press"));
        assertThat(mouseOnly.click(10, 10, null, null, null, "0x1A2B")).contains("Klick");
    }

    @Test
    void inputShowsControlOfTheWindowAndUnbindEndsIt() {
        bind();
        input.click(10, 10, null, null, null, "0x3C4D");
        keys.key("enter", null, "0x3C4D");

        assertThat(desktop.control).containsExactly("target 3c4d", "target 3c4d");
        read.unbind();
        assertThat(desktop.control).endsWith("release");
    }

    @Test
    void userInterventionEndsControlImmediately() {
        bind();
        input.click(10, 10, null, null, null, "0x1A2B");
        desktop.pointer = new java.awt.Point(900, 900); // Nutzer greift ein

        assertThatThrownBy(() -> keys.key("enter", null, "0x1A2B")).hasMessageContaining("Nutzer hat die Maus bewegt");
        assertThat(desktop.control).containsExactly("target 1a2b", "release");
    }

    @Test
    void keyCombosAndSystemKeyBlock() {
        bind();
        keys.key("ctrl+s", null, "0x1A2B");
        assertThat(desktop.events).containsSubsequence("down Ctrl", "down S", "up S", "up Ctrl");
        assertThatThrownBy(() -> keys.key("win+r", null, "0x1A2B")).hasMessageContaining("gesperrt");
    }

    @Test
    void endedProcessDropsTheBinding() throws Exception {
        String java = ProcessHandle.current().info().command().orElseThrow();
        Process p = new ProcessBuilder(java, "-version").redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        p.waitFor();
        session.bind(new WindowSession.Binding(p.toHandle(), "java", false), false);
        assertThatThrownBy(() -> read.windows()).hasMessageContaining("ist beendet");
        assertThat(session.current()).isNull();
    }
}
