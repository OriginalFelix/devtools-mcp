package systems.grebe.devtools.mcp.ui.code;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javafx.application.Platform;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.IndexRange;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Font;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.model.StyleSpansBuilder;
import systems.grebe.devtools.mcp.modules.scripts.ScriptViews;
import systems.grebe.devtools.mcp.modules.scripts.assist.Completion;
import systems.grebe.devtools.mcp.modules.scripts.assist.CompletionResult;
import systems.grebe.devtools.mcp.modules.scripts.assist.ScriptAssist;
import systems.grebe.devtools.mcp.modules.scripts.assist.SyntaxHighlighter;

/**
 * Quelltext-Editor für Skripte, angelehnt an IntelliJ:
 *
 * <ul>
 *   <li>Syntaxhervorhebung (Farben „IntelliJ Light“), Zeilennummern, aktuelle Zeile, passende Klammer;</li>
 *   <li>Autovervollständigung: automatisch beim Tippen (erster Buchstabe, nach {@code .}, in Gherkin nach dem
 *       Schlüsselwort …) oder mit Strg+Leertaste; Pfeiltasten wählen, Enter fügt ein, Tab ersetzt das Wort bis zum
 *       Ende, Esc schließt. Klassen werden beim Übernehmen importiert;</li>
 *   <li>Enter rückt passend ein ({@code {|}} wird aufgeklappt), Klammern und Anführungszeichen werden paarweise
 *       eingefügt und überschrieben, Tab/Umschalt+Tab rückt ein und aus, Strg+/ (auch Strg+# und Strg+Umschalt+7)
 *       kommentiert, Strg+D verdoppelt die Zeile;</li>
 *   <li>Fehler mit „Zeile N“ aus Prüfen/Speichern werden in der Zeile markiert, bis der Text sich ändert.</li>
 * </ul>
 */
public class CodeEditor extends StackPane {

    /** Ein Hintergrund-Thread für alle Editoren: Vorschläge rechnen (javac kann einige hundert ms brauchen). */
    private static final ExecutorService ASSIST = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "script-assist");
        t.setDaemon(true);
        return t;
    });
    private static final Pattern ERROR_LINE = Pattern.compile("Zeile (\\d+)");
    private static String fontFamily;

    private final CodeArea area = new CodeArea();
    private final ScriptAssist assist;
    private final CompletionPopup popup;
    private final IntFunction<Node> lineNumbers;
    private final AtomicLong requests = new AtomicLong();
    private final Set<Integer> errorLines = new HashSet<>();
    private ScriptViews.Language language = ScriptViews.Language.GROOVY;
    private List<SyntaxHighlighter.Span> spans = List.of();
    private int[] brackets = {};
    /** Angezeigte Vorschläge und die Eingabe, für die sie berechnet wurden. */
    private CompletionResult current;
    private String currentTyped = "";
    private double popupX;
    private double popupY;
    private boolean warmedUp;

    /** @param assist Vervollständigung oder {@code null} (z.B. für die Anzeige alter Revisionen) */
    public CodeEditor(ScriptAssist assist) {
        this.assist = assist;
        this.popup = new CompletionPopup(c -> accept(c, false));
        popup.setOnHidden(() -> current = null);
        area.getStyleClass().add("code-editor");
        area.setStyle("-fx-font-family: '" + font() + "';");
        area.setWrapText(false);
        lineNumbers = LineNumberFactory.get(area);
        area.setParagraphGraphicFactory(this::gutter);
        getChildren().add(new VirtualizedScrollPane<>(area));
        area.multiPlainChanges().subscribe(changes -> textChanged());
        area.caretPositionProperty().addListener((o, a, b) -> caretMoved());
        area.addEventFilter(KeyEvent.KEY_PRESSED, this::keyPressed);
        area.addEventFilter(KeyEvent.KEY_TYPED, this::keyTyped);
        area.focusedProperty().addListener((o, was, focused) -> {
            if (focused) {
                warmUp();
            }
        });
    }

    /** Erste vorhandene Programmierschrift – JavaFX-CSS kennt keine Ausweichliste. */
    private static String font() {
        if (fontFamily == null) {
            List<String> families = Font.getFamilies();
            fontFamily = Stream.of("JetBrains Mono", "Cascadia Mono", "Consolas", "Menlo", "DejaVu Sans Mono",
                    "Liberation Mono").filter(families::contains).findFirst().orElse("Monospaced");
        }
        return fontFamily;
    }

    // ------------------------------------------------------------------ Schnittstelle

    /**
     * Bereitet die Vervollständigung im Hintergrund vor (Klassenindex, javac – zusammen gut eine Sekunde beim ersten
     * Mal), sobald der Editor in Sicht kommt; spätestens beim ersten Fokus.
     */
    public Thread warmUp() {
        if (assist == null || warmedUp) {
            return null;
        }
        warmedUp = true;
        popup.prepare(area);
        return assist.warmUp();
    }

    public void setLanguage(ScriptViews.Language language) {
        this.language = language == null ? ScriptViews.Language.GROOVY : language;
        hidePopup();
        rehighlight();
    }

    public ScriptViews.Language getLanguage() {
        return language;
    }

    public String getText() {
        return area.getText();
    }

    public ObservableValue<String> textProperty() {
        return area.textProperty();
    }

    /** Ersetzt den Text – rückgängig machbar (z.B. „Revision übernehmen“). */
    public void setText(String text) {
        area.replaceText(text == null ? "" : text);
    }

    /** Lädt neuen Inhalt: ohne Undo-Historie, Schreibmarke am Anfang. */
    public void load(String text) {
        hidePopup();
        area.replaceText(text == null ? "" : text);
        area.getUndoManager().forgetHistory();
        area.moveTo(0);
        area.showParagraphAtTop(0);
    }

    public void setEditable(boolean editable) {
        area.setEditable(editable);
    }

    /** Markiert die in einer Fehlermeldung genannten Zeilen („Zeile 3, Spalte 5: …“) bis zur nächsten Änderung. */
    public void markErrors(String message) {
        errorLines.clear();
        if (message != null) {
            Matcher m = ERROR_LINE.matcher(message);
            while (m.find()) {
                int line = Integer.parseInt(m.group(1)) - 1;
                if (line >= 0 && line < area.getParagraphs().size()) {
                    errorLines.add(line);
                }
            }
        }
        rehighlight();
        area.setParagraphGraphicFactory(this::gutter);
    }

    /** CSS-Klassen des Zeichens an {@code pos} (für Sichttests). */
    Collection<String> styleAt(int pos) {
        return area.getStyleOfChar(pos);
    }

    /** Die Liste der Vervollständigung (für Sichttests). */
    CompletionPopup popup() {
        return popup;
    }

    /** Für Sichttests: Schreibmarke setzen und Vorschläge wie mit Strg+Leertaste anfordern. */
    public void completeAt(int position) {
        area.requestFocus();
        area.moveTo(position);
        request(true);
    }

    // ------------------------------------------------------------------ Hervorhebung

    private void textChanged() {
        if (!errorLines.isEmpty()) {
            errorLines.clear();
            area.setParagraphGraphicFactory(this::gutter);
        }
        rehighlight();
        updatePopup();
    }

    private void caretMoved() {
        highlightBrackets();
        updatePopup();
    }

    private void rehighlight() {
        String text = area.getText();
        spans = SyntaxHighlighter.spans(language, text);
        brackets = new int[0];
        if (text.isEmpty()) {
            return;
        }
        StyleSpansBuilder<Collection<String>> b = new StyleSpansBuilder<>();
        int pos = 0;
        boolean any = false;
        for (SyntaxHighlighter.Span s : spans) {
            if (s.start() < pos || s.end() > text.length() || s.end() <= s.start()) {
                continue;
            }
            if (s.start() > pos) {
                b.add(List.of(), s.start() - pos);
            }
            b.add(List.of(s.style()), s.end() - s.start());
            pos = s.end();
            any = true;
        }
        if (pos < text.length() || !any) {
            b.add(List.of(), text.length() - pos);
        }
        area.setStyleSpans(0, b.create());
        for (int p : errorLines) {
            if (p < area.getParagraphs().size() && area.getParagraph(p).length() > 0) {
                int start = area.getAbsolutePosition(p, 0);
                int end = start + area.getParagraph(p).length();
                area.setStyleSpans(start, area.getStyleSpans(start, end).mapStyles(st -> with(st, "error")));
            }
        }
        highlightBrackets();
    }

    /** Klammer vor oder hinter der Schreibmarke und ihr Gegenstück hinterlegen. */
    private void highlightBrackets() {
        for (int p : brackets) {
            if (p < area.getLength()) {
                area.setStyle(p, p + 1, baseStyle(p));
            }
        }
        brackets = new int[0];
        int caret = area.getCaretPosition();
        String text = area.getText();
        for (int p : new int[] {caret - 1, caret}) {
            int match = SyntaxHighlighter.matchingBracket(language, text, p);
            if (match >= 0) {
                brackets = new int[] {p, match};
                for (int q : brackets) {
                    area.setStyle(q, q + 1, with(baseStyle(q), "bracket"));
                }
                return;
            }
        }
    }

    private Collection<String> baseStyle(int pos) {
        List<String> out = new ArrayList<>();
        for (SyntaxHighlighter.Span s : spans) {
            if (s.start() <= pos && pos < s.end()) {
                out.add(s.style());
                break;
            }
        }
        if (errorLines.contains(area.offsetToPosition(pos, org.fxmisc.richtext.model.TwoDimensional.Bias.Forward)
                .getMajor())) {
            out.add("error");
        }
        return out;
    }

    private static Collection<String> with(Collection<String> styles, String extra) {
        List<String> out = new ArrayList<>(styles);
        out.add(extra);
        return out;
    }

    private Node gutter(int paragraph) {
        Node n = lineNumbers.apply(paragraph);
        if (errorLines.contains(paragraph)) {
            n.getStyleClass().add("lineno-error");
        }
        return n;
    }

    // ------------------------------------------------------------------ Vervollständigung

    private void request(boolean explicit) {
        if (assist == null || !area.isEditable()) {
            return;
        }
        long id = requests.incrementAndGet();
        String text = area.getText();
        int caret = area.getCaretPosition();
        ScriptViews.Language lang = language;
        ASSIST.execute(() -> {
            if (requests.get() != id) {
                return; // überholt – nur die neueste Anfrage rechnen
            }
            CompletionResult r = assist.complete(lang, text, caret);
            String typed = r.from() <= caret ? text.substring(r.from(), caret) : "";
            Platform.runLater(() -> showResult(id, r, text, typed, explicit));
        });
    }

    private void showResult(long id, CompletionResult r, String requestText, String typed, boolean explicit) {
        if (requests.get() != id) {
            return;
        }
        String text = area.getText();
        int caret = area.getCaretPosition();
        if (r.isEmpty() || r.from() > caret || r.from() > text.length()
                || !text.regionMatches(0, requestText, 0, r.from())) {
            if (!r.incomplete()) {
                hidePopup();
            }
            return;
        }
        if (explicit) {
            List<CompletionResult.Ranked> ranked = r.rank(text.substring(r.from(), caret));
            if (ranked.size() == 1 && !r.incomplete()) { // wie IntelliJ: einziger Treffer sofort
                current = r;
                accept(ranked.getFirst().item(), false);
                return;
            }
        }
        boolean showing = popup.isShowing();
        current = r;
        currentTyped = typed;
        if (!showing) {
            anchor(r.from());
        }
        updatePopup();
    }

    /** Liste an die aktuelle Eingabe anpassen – oder schließen, wenn sie nicht mehr passt. */
    private void updatePopup() {
        CompletionResult r = current;
        if (r == null) {
            return;
        }
        String text = area.getText();
        int caret = area.getCaretPosition();
        if (caret < r.from() || r.from() > text.length()) {
            hidePopup();
            return;
        }
        String typed = text.substring(r.from(), caret);
        if (!continues(typed, r.multiWord())) {
            hidePopup();
            return;
        }
        List<CompletionResult.Ranked> ranked = r.rank(typed);
        if (r.incomplete() && !typed.equals(currentTyped)) {
            currentTyped = typed;
            request(false); // gekürzte Liste: mit der längeren Eingabe neu fragen
        }
        if (ranked.isEmpty()) {
            if (!r.incomplete()) {
                hidePopup();
            }
            return;
        }
        popup.show(area, ranked, popupX, popupY);
    }

    private static boolean continues(String typed, boolean multiWord) {
        for (int i = 0; i < typed.length(); i++) {
            char c = typed.charAt(i);
            if (c == '\n' || !multiWord && !Character.isJavaIdentifierPart(c)) {
                return false;
            }
        }
        return true;
    }

    /** Liste unter dem Wortanfang, Text bündig mit dem Getippten (wie IntelliJ). */
    private void anchor(int from) {
        Optional<Bounds> b = from < area.getLength() && area.getText().charAt(from) != '\n'
                ? area.getCharacterBoundsOnScreen(from, from + 1) : Optional.empty();
        if (b.isEmpty()) {
            b = area.getCaretBounds();
        }
        b.ifPresent(x -> {
            popupX = x.getMinX() - 30;
            popupY = x.getMaxY() + 2;
        });
    }

    private void hidePopup() {
        current = null;
        popup.hide();
    }

    /** Übernimmt einen Vorschlag ({@link Edits#accept}) und ergänzt den Import ({@link Edits#addImport}). */
    private void accept(Completion c, boolean replaceWord) {
        CompletionResult r = current;
        hidePopup();
        if (r == null || c == null) {
            return;
        }
        int caret = area.getCaretPosition();
        Edits.Edit edit = Edits.accept(area.getText(), Math.min(r.from(), caret), caret, replaceWord, c);
        area.replaceText(edit.from(), edit.to(), edit.text());
        int anchor = edit.anchor();
        int end = edit.caret();
        if (c.importName() != null) {
            Edits.Edit imp = Edits.addImport(language, area.getText(), c.importName());
            if (imp != null) {
                area.replaceText(imp.from(), imp.to(), imp.text());
                if (imp.from() <= anchor) {
                    anchor += imp.text().length();
                    end += imp.text().length();
                }
            }
        }
        area.selectRange(anchor, end);
        area.requestFollowCaret();
        if (c.retrigger()) {
            Platform.runLater(() -> request(false));
        }
    }

    // ------------------------------------------------------------------ Tasten

    private void keyPressed(KeyEvent e) {
        if (popup.isShowing() && popupKey(e.getCode())) {
            e.consume();
            return;
        }
        KeyCode code = e.getCode();
        if (e.isShortcutDown() && code == KeyCode.SPACE) {
            request(true);
            e.consume();
            return;
        }
        if (!area.isEditable()) {
            return;
        }
        IndexRange sel = area.getSelection();
        String text = area.getText();
        Edits.Edit edit = null;
        if (e.isShortcutDown() && (code == KeyCode.SLASH || code == KeyCode.DIVIDE || code == KeyCode.NUMBER_SIGN
                || code == KeyCode.DIGIT7 && e.isShiftDown())) {
            edit = Edits.toggleComment(language, text, sel.getStart(), sel.getEnd());
        } else if (e.isShortcutDown() && !e.isShiftDown() && code == KeyCode.D) {
            edit = Edits.duplicate(text, sel.getStart(), sel.getEnd());
        } else if (code == KeyCode.ENTER && !e.isShortcutDown() && !e.isAltDown()) {
            edit = Edits.newline(language, text, sel.getStart(), sel.getEnd());
        } else if (code == KeyCode.TAB && !e.isShortcutDown()) {
            edit = e.isShiftDown() ? Edits.shift(language, text, sel.getStart(), sel.getEnd(), false)
                    : Edits.tab(language, text, sel.getStart(), sel.getEnd());
        } else if (code == KeyCode.BACK_SPACE && !e.isShortcutDown() && sel.getLength() == 0) {
            edit = Edits.deletePair(language, text, sel.getStart());
        }
        if (edit != null) {
            apply(edit);
            e.consume();
        }
    }

    /** Tasten der offenen Liste; {@code false}, wenn die Taste weiter an den Editor geht. */
    private boolean popupKey(KeyCode code) {
        switch (code) {
            case UP -> popup.move(-1);
            case DOWN -> popup.move(1);
            case PAGE_UP -> popup.move(-CompletionPopup.page());
            case PAGE_DOWN -> popup.move(CompletionPopup.page());
            case ENTER -> accept(popup.selected(), false);
            case TAB -> accept(popup.selected(), true);
            case ESCAPE -> hidePopup();
            case HOME, END -> {
                hidePopup();
                return false;
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private void keyTyped(KeyEvent e) {
        String s = e.getCharacter();
        if (!area.isEditable() || s.isEmpty() || s.charAt(0) < ' ' || s.charAt(0) == 127
                || e.isControlDown() && !e.isAltDown() || e.isMetaDown()) {
            return; // Strg+Alt ist AltGr: { [ ] } auf deutschen Tastaturen
        }
        char c = s.charAt(0);
        if (area.getSelection().getLength() == 0) {
            Edits.Edit edit = Edits.typed(language, area.getText(), area.getCaretPosition(), c);
            if (edit != null) {
                apply(edit);
                e.consume();
            }
        }
        // nach dem Einfügen des Zeichens entscheiden, ob Vorschläge aufgehen
        Platform.runLater(() -> {
            if (assist != null && !popup.isShowing()
                    && ScriptAssist.autoTrigger(language, area.getText(), area.getCaretPosition(), c)) {
                request(false);
            }
        });
    }

    private void apply(Edits.Edit edit) {
        area.replaceText(edit.from(), edit.to(), edit.text());
        area.selectRange(edit.anchor(), edit.caret());
        area.requestFollowCaret();
    }
}
