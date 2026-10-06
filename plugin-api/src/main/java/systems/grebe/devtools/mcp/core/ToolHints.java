package systems.grebe.devtools.mcp.core;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * MCP-Tool-Annotations ({@code readOnlyHint}, {@code destructiveHint}, {@code idempotentHint}, {@code openWorldHint})
 * für {@code @Tool}-Methoden. An der Klasse gilt sie für alle Tools der Klasse, an der Methode für dieses Tool (hat
 * Vorrang). Clients nutzen die Hinweise z.B., um vor verändernden Tools nachzufragen und lesende ohne Rückfrage
 * auszuführen. Wirkt nur, wenn das Modul seine Tools mit {@link ToolBeans#callbacks} erzeugt – auch in Plugins.
 *
 * <p>Die Defaults entsprechen denen der MCP-Spezifikation: ohne Angabe gilt ein Tool als verändernd und
 * möglicherweise zerstörerisch.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface ToolHints {

    /** Verändert nichts in seiner Umgebung. */
    boolean readOnly() default false;

    /** Kann Bestehendes löschen oder überschreiben (nur bedeutsam, wenn nicht {@link #readOnly}). */
    boolean destructive() default true;

    /** Wiederholter Aufruf mit denselben Argumenten hat keine weitere Wirkung. */
    boolean idempotent() default false;

    /** Wirkt auf eine offene Welt (fremde Systeme, Netz) statt nur auf lokale, abgeschlossene Daten. */
    boolean openWorld() default true;
}
