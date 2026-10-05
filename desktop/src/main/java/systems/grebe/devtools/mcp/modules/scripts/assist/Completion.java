package systems.grebe.devtools.mcp.modules.scripts.assist;

/**
 * Ein Vorschlag der Autovervollständigung – aufgebaut wie die Lookup-Liste von IntelliJ: Name (nach ihm wird
 * gefiltert), grauer Zusatz dahinter (Parameter), rechts der Typ, daneben die Erklärung.
 *
 * @param kind       Art (Methode, Feld, Klasse, Schlüsselwort …) – bestimmt das Symbol in der Liste
 * @param label      angezeigter Name, nach ihm wird gefiltert, z.B. {@code getString}
 * @param tail       grauer Zusatz direkt hinter dem Namen, z.B. {@code (String key, String fallback)}
 * @param detail     rechtsbündig, meist der Typ
 * @param doc        Erklärung neben der Liste oder {@code null}
 * @param insert     eingefügter Text; Folgezeilen bekommen die Einrückung der aktuellen Zeile
 * @param caret      Schreibmarke in {@code insert} nach dem Einfügen, -1 = am Ende
 * @param select     so viele Zeichen ab der Schreibmarke markieren (Vorgabewert zum Überschreiben), sonst 0
 * @param importName voll qualifizierte Klasse, die beim Übernehmen importiert wird, oder {@code null}
 * @param priority   Vorrang bei gleich guter Übereinstimmung (höher = weiter oben)
 * @param retrigger  nach dem Übernehmen gleich weiter vervollständigen (z.B. Tool-Name nach dem Schritt)
 */
public record Completion(Kind kind, String label, String tail, String detail, String doc, String insert, int caret,
                         int select, String importName, int priority, boolean retrigger) {

    /** Art des Vorschlags – bestimmt das Symbol in der Liste. */
    public enum Kind {
        KEYWORD, SNIPPET, METHOD, PROPERTY, FIELD, CONSTANT, VARIABLE, PARAMETER, CLASS, INTERFACE, ENUM,
        ANNOTATION, PACKAGE, STEP, TOOL, TAG
    }

    /** Vorschlag, der genau seinen Namen einfügt. */
    public static Completion of(Kind kind, String label) {
        return new Completion(kind, label, null, null, null, label, -1, 0, null, 0, false);
    }

    public Completion withTail(String value) {
        return new Completion(kind, label, value, detail, doc, insert, caret, select, importName, priority, retrigger);
    }

    public Completion withDetail(String value) {
        return new Completion(kind, label, tail, value, doc, insert, caret, select, importName, priority, retrigger);
    }

    public Completion withDoc(String value) {
        return new Completion(kind, label, tail, detail, value, insert, caret, select, importName, priority, retrigger);
    }

    /** Eingefügter Text, Schreibmarke danach am Ende. */
    public Completion withInsert(String text) {
        return withInsert(text, -1, 0);
    }

    /** Eingefügter Text mit Schreibmarke an {@code caretAt} und {@code selectLength} markierten Zeichen ab dort. */
    public Completion withInsert(String text, int caretAt, int selectLength) {
        return new Completion(kind, label, tail, detail, doc, text, caretAt, selectLength, importName, priority,
                retrigger);
    }

    /** Text mit genau einem {@code |} als Schreibmarke, z.B. {@code "description '|'"}. */
    public Completion withTemplate(String template) {
        int at = template.indexOf('|');
        return at < 0 ? withInsert(template) : withInsert(template.substring(0, at) + template.substring(at + 1), at, 0);
    }

    public Completion withImport(String qualifiedName) {
        return new Completion(kind, label, tail, detail, doc, insert, caret, select, qualifiedName, priority,
                retrigger);
    }

    public Completion withPriority(int value) {
        return new Completion(kind, label, tail, detail, doc, insert, caret, select, importName, value, retrigger);
    }

    public Completion retriggering() {
        return new Completion(kind, label, tail, detail, doc, insert, caret, select, importName, priority, true);
    }

    /** Position der Schreibmarke im eingefügten Text. */
    public int caretOffset() {
        return caret < 0 ? insert.length() : caret;
    }
}
