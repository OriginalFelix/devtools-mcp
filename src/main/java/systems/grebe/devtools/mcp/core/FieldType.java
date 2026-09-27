package systems.grebe.devtools.mcp.core;

/** Typen von Konfigurationsfeldern. Jeder Typ hat einen passenden Editor in der UI. */
public enum FieldType {
    /** Einzeiliger Text. */
    STRING,
    /** Geheimnis (Token, Passwort) – maskiert angezeigt und verschlüsselt gespeichert. */
    SECRET,
    /** Ganzzahl. */
    INT,
    /** Ja/Nein. */
    BOOLEAN,
    /** http(s)-URL. */
    URL,
    /** Einzelnes Verzeichnis (mit Auswahldialog). */
    DIRECTORY,
    /** Liste von Verzeichnissen (eine Zeile je Eintrag gespeichert). */
    DIRECTORY_LIST,
    /** Auswahl aus {@link ConfigField#options()}. */
    ENUM,
    /** Freie Liste von Texten (eine Zeile je Eintrag). */
    STRING_LIST
}
