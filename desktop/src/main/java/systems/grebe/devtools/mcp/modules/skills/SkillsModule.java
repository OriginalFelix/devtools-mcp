package systems.grebe.devtools.mcp.modules.skills;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.backend.skills.Skill;
import systems.grebe.devtools.mcp.config.DataHome;
import systems.grebe.devtools.mcp.core.ConfigField;
import systems.grebe.devtools.mcp.core.FieldType;
import systems.grebe.devtools.mcp.core.LocalFiles;
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
    static final String FILE_DIRS = "fileDirectories";

    private final SkillBackend skills;
    private final SkillReview review;
    private final SkillReviewTracker tracker;
    private final DataHome home;

    public SkillsModule(SkillBackend skills, SkillReview review, SkillReviewTracker tracker, DataHome home) {
        this.skills = skills;
        this.review = review;
        this.tracker = tracker;
        this.home = home;
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
                + "Aufgaben selbst anlegen oder verbessern – mit Zusatzdateien (Text oder beliebige Dateien ohne "
                + "Größengrenze) und Änderungshistorie. Gespeichert im "
                + "Backend (eingebettet oder Team-Server) je Benutzerkonto, plus schreibgeschützte globale Vorlagen.";
    }

    @Override
    public String instructions() {
        return """
                Skills = registrierte Abläufe je Aufgabentyp (z.B. `ticket-review`): Schritte, Tool-Aufrufe, \
                Kriterien, Fallstricke, Vorlieben des Nutzers.
                - Vor jeder Aufgabe, die über eine einfache Frage hinausgeht, zuerst `skills_list` mit 1–3 \
                Stichworten – nicht ohne Suche loslegen. Genau ein Treffer kommt direkt mit Inhalt; sonst den \
                passenden mit `skills_view` laden und befolgen.
                - Nennt der Server „[DevTools] Registrierter Skill …“ oder „Passende Skills …“, diesen Skill laden, \
                bevor du weitermachst.
                - Danach: Korrektur des Nutzers, Fehlversuch oder neuer Workaround → `skills_patch` am passenden \
                Skill, sonst `skills_create` mit `triggers` (Tools, bei denen er greifen soll). Checkliste: \
                `skills_review`; Hinweise „[DevTools-Skills] …“ erst nach der laufenden Aufgabe abarbeiten.
                - `*` in `skills_list` = globale Vorlage; Änderungen legen automatisch eine persönliche Kopie an.
                - Inhalt: Regel + Begründung, konkrete Tool-Aufrufe; keine Einzelfall-Details (→ Memory), keine \
                Geheimnisse. `skills_delete` nur auf Wunsch.
                - Vorlagen, Bilder, PDFs u.ä. mit `skills_write_file` anhängen (`source_path` für lokale Dateien, \
                beliebig groß); `skills_view` mit `file_path` liefert Text bzw. speichert Binäres lokal.""";
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
                        .withHelp("create, patch, update, write_file (auch Dateien anhängen), remove_file."),
                ConfigField.of(ALLOW_DELETE, "Löschen erlauben", FieldType.BOOLEAN).withDefault("false"),
                ConfigField.of(MAX_CONTENT, "Max. Zeichen je Inhalt", FieldType.INT).withDefault("100000")
                        .withHelp("Obergrenze für den Skill-Inhalt und Zusatzdateien als Text (mit skills_patch "
                                + "änderbar). Längere und binäre Dateien werden ohne Grenze als Anhang gespeichert."),
                ConfigField.of(FILE_DIRS, "Dateien anhängen aus und speichern in", FieldType.DIRECTORY_LIST)
                        .withHelp("Aus diesen Verzeichnissen (inkl. Unterverzeichnissen) darf das LLM Dateien an "
                                + "Skills hängen (source_path) und Zusatzdateien hineinspeichern (target_path); dazu "
                                + "die globalen „Freigaben“. Ohne target_path landen binäre Zusatzdateien in "
                                + "„attachments“ im Datenordner der App."),
                ConfigField.of(REVIEW_INTERVAL, "Review-Erinnerung nach N Tool-Aufrufen", FieldType.INT)
                        .withDefault(String.valueOf(SkillReviewTracker.DEFAULT_INTERVAL))
                        .withHelp("Wie Hermes' creation_nudge_interval: nach so vielen Aufrufen ohne Skill-Pflege "
                                + "(je Client-Session) erinnert ein Hinweis im Tool-Ergebnis an skills_review. Der "
                                + "erste Aufruf einer Session (und der erste nach 30 min Pause) weist außerdem auf die "
                                + "Skill-Bibliothek hin. 0 = beides aus."));
    }

    @Override
    public Set<String> sharedDirectoryFields() {
        return Set.of(FILE_DIRS);
    }

    @Override
    public List<ToolCallback> createTools(ModuleConfig config) {
        LocalFiles files = new LocalFiles(config.getList(FILE_DIRS),
                "Module → Skills → „Dateien anhängen aus und speichern in“ oder global unter „Freigaben“");
        List<ToolCallback> tools = new ArrayList<>(List.of(ToolCallbacks.from(
                new SkillReadTools(skills, files, attachments(home)))));
        if (config.getBoolean(ALLOW_WRITE)) {
            tools.addAll(List.of(ToolCallbacks.from(new SkillWriteTools(skills, maxContent(config), files))));
            // Review nur, wenn das LLM das Gelernte auch speichern darf
            tools.addAll(List.of(ToolCallbacks.from(new SkillReviewTools(skills, review, tracker))));
        }
        if (config.getBoolean(ALLOW_DELETE)) {
            tools.addAll(List.of(ToolCallbacks.from(new SkillDeleteTools(skills))));
        }
        return tools;
    }

    /** Ablage für gelesene Anhänge (Skills und Memories) im Datenordner der App. */
    public static Path attachments(DataHome home) {
        return home.dir().resolve("attachments");
    }

    private static int maxContent(ModuleConfig config) {
        return Math.max(1_000, Math.min(Skill.CONTENT_COLUMN, config.getInt(MAX_CONTENT, 100_000)));
    }
}
