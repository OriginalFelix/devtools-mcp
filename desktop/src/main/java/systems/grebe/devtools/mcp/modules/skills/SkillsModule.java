package systems.grebe.devtools.mcp.modules.skills;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.backend.skills.Skill;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.core.ModuleConfig;
import systems.grebe.devtools.mcp.core.ToolModule;

/**
 * Skill-Speicher für das LLM, angelehnt an das Skill-Management von Hermes: Ein Skill ist die Registrierung eines
 * Aufgabentyps mit seinem erprobten Ablauf (z.B. {@code ticket-review}: wie ein Ticket geprüft wird). Das LLM sucht vor
 * einer Aufgabe passende Skills, lädt sie und legt nach einer schwierigen oder neu gelernten Aufgabe selbst einen an
 * bzw. korrigiert einen bestehenden. Was bei einem einzelnen Durchlauf passiert ist, gehört nicht in den Skill, sondern
 * in eine Memory ({@code memories_*}). Die Skills liegen im Backend ({@link SkillBackend}) – eingebettet in der App oder auf dem Team-Server –
 * und gehören der E-Mail des Benutzerkontos; dazu kommen schreibgeschützte globale Vorlagen.
 */
@Component
public class SkillsModule implements ToolModule {

    public static final String ID = "skills";

    /** Frühere Einstellungen der lokalen Skill-Datenbank – beim Start in Backend-Properties übernommen. */
    public static final String LEGACY_JDBC_URL = "jdbcUrl";
    public static final String LEGACY_USERNAME = "username";
    public static final String LEGACY_PASSWORD = "password";
    public static final String LEGACY_USER_EMAIL = "userEmail";

    static final String ALLOW_WRITE = "allowWrite";
    static final String ALLOW_DELETE = "allowDelete";
    static final String MAX_CONTENT = "maxContentChars";
    static final String REVIEW_INTERVAL = "reviewNudgeInterval";

    private final SkillBackend skills;
    private final SkillReview review;
    private final SkillReviewTracker tracker;

    public SkillsModule(SkillBackend skills, SkillReview review, SkillReviewTracker tracker) {
        this.skills = skills;
        this.review = review;
        this.tracker = tracker;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return "Skills";
    }

    @Override
    public String description() {
        return "Registrierte Abläufe (Skills) für Aufgabentypen wie „Ticket-Review“: suchen, laden und nach gelösten "
                + "Aufgaben selbst anlegen oder verbessern – mit Zusatzdateien und Änderungshistorie. Gespeichert im "
                + "Backend (eingebettet oder Team-Server) je Benutzerkonto, plus schreibgeschützte globale Vorlagen.";
    }

    @Override
    public String instructions() {
        return """
                Skills sind dein prozedurales Gedächtnis: Jeder Skill registriert einen wiederkehrenden Aufgabentyp \
                mit seinem erprobten Ablauf – Schritte, Befehle, Kriterien, Fallstricke und Vorlieben des Nutzers \
                (z.B. `ticket-review`: wie ein Ticket geprüft wird). Was bei einem einzelnen Durchlauf konkret \
                passiert ist (welches Ticket, welches Ergebnis), gehört nicht in den Skill, sondern – wenn \
                `memories_save` angeboten wird – in eine Memory mit Verweis auf den Skill.

                Vor einer Aufgabe:
                - `skills_list` (mit Suchtext) aufrufen, sobald die Aufgabe über eine einfache Frage hinausgeht. Passt \
                ein Skill auch nur teilweise, ihn mit `skills_view` laden und seine Anweisungen befolgen.

                Nach einer Aufgabe – Skill anlegen oder verbessern, wenn:
                - die Aufgabe schwierig oder mehrstufig war (etwa 5+ Tool-Aufrufe) oder Fehlversuche nötig waren,
                - der Nutzer dich korrigiert oder eine Vorliebe für diese Art Arbeit genannt hat,
                - du einen nicht offensichtlichen Ablauf, Befehl oder Workaround gefunden hast, der wiederkommen wird.
                Dann: gibt es einen passenden Skill → `skills_patch` (gezielt ergänzen/korrigieren); sonst → \
                `skills_create`. War ein geladener Skill falsch oder lückenhaft, ihn sofort mit `skills_patch` \
                korrigieren. Nach größeren Aufgaben kurz anbieten, den Ablauf als Skill zu speichern.

                Globale Vorlagen: In `skills_list` mit „(global)“ markierte Skills sind schreibgeschützte Vorlagen \
                für alle Benutzer. Genauso laden und befolgen wie eigene. Eine Änderung (`skills_patch`, \
                `skills_update`, `skills_write_file`, `skills_remove_file`) legt automatisch eine persönliche Kopie an, \
                die ab dann statt der Vorlage gilt – dafür nichts Besonderes tun und keinen neuen Skill anlegen.

                Selbstverbesserung: Nach einer abgeschlossenen mehrstufigen Aufgabe `skills_review` aufrufen und die \
                Checkliste abarbeiten. Hängt der Server an ein Tool-Ergebnis einen Hinweis „[DevTools-Skills] …“ \
                (Stand der Bibliothek bzw. Aufrufe seit der letzten Skill-Pflege), gilt das ebenso – sobald die \
                laufende Aufgabe fertig ist, nicht mittendrin. Hat der Client einen eigenen Skill-Speicher, gehört \
                Wissen über die hier angebotenen Tools und Projekte in diese Bibliothek.

                Inhalt: Lehren statt Protokoll – Regel plus Begründung, konkrete Befehle/Tool-Aufrufe, Prüfschritte. \
                Keine Einmal-Details (Datumsangaben, Ticketnummern, PIDs) und niemals Passwörter, Tokens oder andere \
                Geheimnisse. `description` ist ein Satz, wann der Skill greift. Lange Referenzen mit \
                `skills_write_file` nach references/ auslagern. `skills_delete` nur auf ausdrücklichen Wunsch.""";
    }

    @Override
    public boolean enabledByDefault() {
        return true;
    }

    @Override
    public int order() {
        return 50;
    }

    @Override
    public List<ConfigField> configSchema() {
        return List.of(
                ConfigField.of(ALLOW_WRITE, "Anlegen und Bearbeiten erlauben", FieldType.BOOLEAN).withDefault("true")
                        .withHelp("create, patch, update, write_file, remove_file."),
                ConfigField.of(ALLOW_DELETE, "Löschen erlauben", FieldType.BOOLEAN).withDefault("false"),
                ConfigField.of(MAX_CONTENT, "Max. Zeichen je Inhalt", FieldType.INT).withDefault("100000")
                        .withHelp("Obergrenze für Skill-Inhalt und Zusatzdateien."),
                ConfigField.of(REVIEW_INTERVAL, "Review-Erinnerung nach N Tool-Aufrufen", FieldType.INT)
                        .withDefault(String.valueOf(SkillReviewTracker.DEFAULT_INTERVAL))
                        .withHelp("Wie Hermes' creation_nudge_interval: nach so vielen Aufrufen ohne Skill-Pflege "
                                + "(je Client-Session) erinnert ein Hinweis im Tool-Ergebnis an skills_review. Der "
                                + "erste Aufruf einer Session (und der erste nach 30 min Pause) weist außerdem auf die "
                                + "Skill-Bibliothek hin. 0 = beides aus."));
    }

    @Override
    public List<ToolCallback> createTools(ModuleConfig config) {
        List<ToolCallback> tools = new ArrayList<>(List.of(ToolCallbacks.from(new SkillReadTools(skills))));
        if (config.getBoolean(ALLOW_WRITE)) {
            tools.addAll(List.of(ToolCallbacks.from(new SkillWriteTools(skills, maxContent(config)))));
            // Review nur, wenn das LLM das Gelernte auch speichern darf
            tools.addAll(List.of(ToolCallbacks.from(new SkillReviewTools(skills, review, tracker))));
        }
        if (config.getBoolean(ALLOW_DELETE)) {
            tools.addAll(List.of(ToolCallbacks.from(new SkillDeleteTools(skills))));
        }
        return tools;
    }

    private static int maxContent(ModuleConfig config) {
        return Math.max(1_000, Math.min(Skill.CONTENT_COLUMN, config.getInt(MAX_CONTENT, 100_000)));
    }
}
