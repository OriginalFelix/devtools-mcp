package systems.grebe.devtools.mcp.modules.skills;

import java.util.List;

import org.springframework.stereotype.Component;

/**
 * Baut die Review-Anleitung für die Selbstverbesserung – als Tool-Ergebnis ({@code skills_review}) und als MCP-Prompt
 * ({@code skills_review}). Inhaltlich angelehnt an den Skill-Review von Hermes ({@code _SKILL_REVIEW_PROMPT}): aktiv
 * sein, Signale erkennen, lieber einen bestehenden Skill verbessern als einen neuen anlegen, Lehren statt Protokoll.
 */
@Component
public class SkillReview {

    static final String CHECKLIST = """
            # Skill-Review

            Geh die bisherige Unterhaltung durch und pflege die Skill-Bibliothek. Sei aktiv: die meisten Aufgaben \
            liefern mindestens eine kleine Verbesserung. Ein Review ohne Ergebnis ist eine verpasste Gelegenheit, \
            kein neutraler Ausgang.

            ## Signale – jedes einzelne rechtfertigt eine Änderung
            - Der Nutzer hat Stil, Format, Umfang oder Ton korrigiert („zu ausführlich“, „nicht so formatieren“, \
            „merk dir das“). Die Vorliebe gehört in den Skill, der diese Art Aufgabe regelt.
            - Der Nutzer hat Vorgehen oder Reihenfolge korrigiert. Als Fallstrick oder expliziten Schritt festhalten.
            - Ein nicht offensichtlicher Ablauf, Fix, Workaround, Debug-Weg oder eine Tool-Kombination hat \
            funktioniert, die beim nächsten Mal wieder gebraucht wird.
            - Ein geladener Skill war falsch, veraltet oder lückenhaft. Jetzt korrigieren.
            - Es waren Fehlversuche nötig, bevor es klappte. Den funktionierenden Weg und den Grund festhalten.

            ## Reihenfolge – nimm die erste passende Option
            1. **Geladenen Skill patchen.** Deckt ein in dieser Session geladener Skill das Thema ab, ihn mit \
            skills_patch ergänzen (vorher mit skills_view frisch laden – Zitate aus der Unterhaltung zählen nicht). \
            Das gilt auch für globale Vorlagen: Die Änderung landet automatisch in einer persönlichen Kopie.
            2. **Übergreifenden Skill erweitern.** Mit skills_list suchen; passt ein bestehender Skill für diese \
            Klasse von Aufgaben, einen Abschnitt, Fallstrick oder Auslöser ergänzen.
            3. **Zusatzdatei anlegen.** Längere Details mit skills_write_file ablegen: references/<thema>.md für \
            Nachschlagewissen, templates/ für Vorlagen, scripts/ für wiederholbare Prüfungen. Im Hauptinhalt mit \
            einer Zeile darauf verweisen. Nach Thema benennen, nicht nach Sitzung.
            4. **Neuen Skill anlegen** (skills_create) nur, wenn keiner die Klasse abdeckt. Der Name beschreibt die \
            Klasse (z.B. „wildfly-heap-leak“), nie ein Ticket, einen Fehlertext oder „fix-x-heute“.

            ## Inhalt
            - Lehren statt Protokoll: Regel plus Begründung, konkrete Befehle/Tool-Aufrufe, Prüfschritte.
            - description = ein Satz, wann der Skill greift.
            - Bei Änderungen expected_revision aus skills_view mitgeben und eine kurze note („warum“).

            ## Nicht festhalten
            - Einmal-Details: Datumsangaben, PIDs, Ticket-/PR-Nummern, Commit-Hashes, temporäre Pfade. Was bei \
            diesem Durchlauf konkret passiert ist, gehört – wenn memories_save angeboten wird – in eine Memory mit \
            Verweis auf den Skill, nicht in den Skill.
            - Passwörter, Tokens, Schlüssel oder andere Geheimnisse – auch nicht maskiert.
            - Was nur für genau diese Sitzung gilt oder jederzeit leicht nachzulesen ist.

            Die erledigte Aktion selbst (Ticket, Ergebnis, Entscheidung) außerdem mit memories_save festhalten, \
            falls angeboten.

            „Nichts zu speichern.“ ist erlaubt, wenn die Aufgabe glatt lief, keine Korrektur kam und nichts Neues \
            gelernt wurde – aber nicht als Standard. Sonst handeln und dem Nutzer am Ende in einem Satz sagen, welcher \
            Skill angelegt oder geändert wurde.""";

    /** Anleitung plus Kontext der Session: geladene/geänderte Skills und die vorhandene Bibliothek. */
    public String render(SkillReviewTracker.SessionState session, String library, String focus) {
        StringBuilder sb = new StringBuilder(CHECKLIST);
        if (focus != null && !focus.isBlank()) {
            sb.append("\n\n## Schwerpunkt laut Aufruf\n").append(focus.strip());
        }
        sb.append("\n\n## Diese Session\n")
                .append("- Tool-Aufrufe über DevTools-MCP: ").append(session.totalCalls()).append('\n')
                .append("- Geladen (skills_view): ").append(join(session.viewed())).append('\n')
                .append("- Bereits geändert: ").append(join(session.changed()));
        sb.append("\n\n## Vorhandene Skills\n").append(library);
        return sb.toString();
    }

    private static String join(List<String> names) {
        return names.isEmpty() ? "keine" : String.join(", ", names);
    }
}
