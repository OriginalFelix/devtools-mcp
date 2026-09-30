package systems.grebe.devtools.mcp.project;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/** Projekte und Freigaben in der Core-Datenbank (Tabellen {@code project}, {@code project_share}). */
@Repository
public class ProjectRepository {

    private static final String SELECT = """
            SELECT p.id, p.owner_id, u.username AS owner_name, p.name, p.description, p.sonar_key, p.ticket_project, p.created_at
            FROM project p JOIN app_user u ON u.id = p.owner_id""";

    private final JdbcClient jdbc;

    public ProjectRepository(@Qualifier("coreJdbc") JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Project> all() {
        return jdbc.sql(SELECT + " ORDER BY u.username, p.name").query(ProjectRepository::project).list();
    }

    public Optional<Project> project(long id) {
        return jdbc.sql(SELECT + " WHERE p.id = ?").param(id).query(ProjectRepository::project).optional();
    }

    public List<Project> owned(long ownerId) {
        return jdbc.sql(SELECT + " WHERE p.owner_id = ? ORDER BY p.name").param(ownerId)
                .query(ProjectRepository::project).list();
    }

    /** Fremde Projekte, die dem Benutzer freigegeben sind, mit dem Zugriff der Freigabe. */
    public List<Project.Visible> sharedWith(long userId) {
        return jdbc.sql("""
                        SELECT p.id, p.owner_id, u.username AS owner_name, p.name, p.description, p.sonar_key, p.ticket_project, p.created_at, s.access
                        FROM project p JOIN app_user u ON u.id = p.owner_id
                        JOIN project_share s ON s.project_id = p.id
                        WHERE s.user_id = ? ORDER BY p.name, u.username""")
                .param(userId)
                .query((rs, row) -> new Project.Visible(project(rs, row), Project.Access.valueOf(rs.getString("access"))))
                .list();
    }

    public long insert(long ownerId, String name, String description, String sonarKey, String ticketProject,
                       Instant createdAt) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("""
                        INSERT INTO project (owner_id, name, description, sonar_key, ticket_project, created_at)
                        VALUES (?, ?, ?, ?, ?, ?)""")
                .params(ownerId, name, description, sonarKey, ticketProject, Timestamp.from(createdAt))
                .update(keys, "id");
        return keys.getKeyAs(Number.class).longValue();
    }

    public void update(long id, String name, String description, String sonarKey, String ticketProject) {
        jdbc.sql("""
                        UPDATE project SET name = ?, description = ?, sonar_key = ?, ticket_project = ?
                        WHERE id = ?""")
                .params(name, description, sonarKey, ticketProject, id).update();
    }

    public void delete(long id) {
        jdbc.sql("DELETE FROM project WHERE id = ?").param(id).update(); // Freigaben per ON DELETE CASCADE
    }

    public List<Project.Share> shares(long projectId) {
        return jdbc.sql("""
                        SELECT s.project_id, s.user_id, u.username, s.access FROM project_share s
                        JOIN app_user u ON u.id = s.user_id WHERE s.project_id = ? ORDER BY u.username""")
                .param(projectId)
                .query((rs, row) -> new Project.Share(rs.getLong(1), rs.getLong(2), rs.getString(3),
                        Project.Access.valueOf(rs.getString(4))))
                .list();
    }

    public void upsertShare(long projectId, long userId, Project.Access access) {
        if (jdbc.sql("UPDATE project_share SET access = ? WHERE project_id = ? AND user_id = ?")
                .params(access.name(), projectId, userId).update() == 0) {
            jdbc.sql("INSERT INTO project_share (project_id, user_id, access) VALUES (?, ?, ?)")
                    .params(projectId, userId, access.name()).update();
        }
    }

    public void deleteShare(long projectId, long userId) {
        jdbc.sql("DELETE FROM project_share WHERE project_id = ? AND user_id = ?").params(projectId, userId).update();
    }

    private static Project project(ResultSet rs, int row) throws SQLException {
        return new Project(rs.getLong("id"), rs.getLong("owner_id"), rs.getString("owner_name"), rs.getString("name"),
                rs.getString("description"), rs.getString("sonar_key"),
                rs.getString("ticket_project"), rs.getTimestamp("created_at").toInstant());
    }
}
