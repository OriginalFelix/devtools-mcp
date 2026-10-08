package systems.grebe.devtools.mcp.core;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

/**
 * Typen von Konfigurationsfeldern. Jeder Typ hat einen passenden Editor in der UI.
 *
 * <p>Typen, die erst eine andere App-Version kennt (neuere Version, anderer Branch, Plugin), liest der Modulkatalog
 * des Backends als {@link #STRING} – mit {@code EnumFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE}.
 */
public enum FieldType {
    /** Einzeiliger Text; auch Ersatz für unbekannte Typen. */
    @JsonEnumDefaultValue
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
    STRING_LIST,
    /**
     * Regulärer Ausdruck auf Prozessname und Kommandozeile. Die Desktop-App bietet dazu eine grafische Fensterauswahl,
     * der Team-Server ein Textfeld.
     */
    PROCESS_PATTERN,
    /**
     * Liste gleichartiger Datensätze (z.B. Verbindungen), Felder aus {@link ConfigField#columns()}. Gespeichert als
     * JSON-Array von Objekten; enthält eine Spalte ein {@link #SECRET}, wird der ganze Wert verschlüsselt abgelegt.
     */
    RECORD_LIST
}
