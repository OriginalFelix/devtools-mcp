package systems.grebe.devtools.mcp.modules.skills;

import java.util.List;
import java.util.Optional;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.remote.TeamServer;

/**
 * Der Skill-Speicher, mit dem Tools und Oberfläche arbeiten: zentral auf dem Team-Server, solange die App mit einem
 * verbunden ist, sonst lokal ({@link SkillService} auf der Datenbank aus den Modul-Einstellungen).
 */
@Primary
@Component
public class SkillStore implements SkillBackend {

    private final TeamServer team;
    private final SkillService local;
    private final RemoteSkillBackend remote;

    public SkillStore(TeamServer team, SkillService local, RemoteSkillBackend remote) {
        this.team = team;
        this.local = local;
        this.remote = remote;
    }

    /** Ob die Skills gerade auf dem Team-Server liegen. */
    public boolean remote() {
        return team.active();
    }

    private SkillBackend target() {
        return remote() ? remote : local;
    }

    @Override
    public void addChangeListener(Runnable listener) {
        local.addChangeListener(listener);
        remote.addChangeListener(listener);
        team.addListener(listener); // Verbinden/Trennen wechselt den Speicher
    }

    @Override
    public List<SkillViews.Summary> overview() {
        return target().overview();
    }

    @Override
    public Optional<SkillViews.Details> details(String name) {
        return target().details(name);
    }

    @Override
    public String publish(String name) {
        return target().publish(name);
    }

    @Override
    public String unpublish(String name) {
        return target().unpublish(name);
    }

    @Override
    public String list(String query, String category) {
        return target().list(query, category);
    }

    @Override
    public String view(String name, String filePath) {
        return target().view(name, filePath);
    }

    @Override
    public String history(String name, Integer revision) {
        return target().history(name, revision);
    }

    @Override
    public int visibleCount() {
        return target().visibleCount();
    }

    @Override
    public String create(String name, String description, String content, String category, List<String> tags,
                         int maxContentChars) {
        return target().create(name, description, content, category, tags, maxContentChars);
    }

    @Override
    public String update(String name, String description, String content, String category, List<String> tags,
                         String note, Integer expectedRevision, int maxContentChars) {
        return target().update(name, description, content, category, tags, note, expectedRevision, maxContentChars);
    }

    @Override
    public String patch(String name, String oldString, String newString, Boolean replaceAll, String filePath,
                        String note, Integer expectedRevision, int maxContentChars) {
        return target().patch(name, oldString, newString, replaceAll, filePath, note, expectedRevision,
                maxContentChars);
    }

    @Override
    public String writeFile(String name, String filePath, String content, String note, int maxContentChars) {
        return target().writeFile(name, filePath, content, note, maxContentChars);
    }

    @Override
    public String removeFile(String name, String filePath, String note) {
        return target().removeFile(name, filePath, note);
    }

    @Override
    public String delete(String name) {
        return target().delete(name);
    }
}
