package systems.grebe.devtools.mcp.server;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;
import systems.grebe.devtools.mcp.account.UserAccount;
import systems.grebe.devtools.mcp.api.Catalog;
import systems.grebe.devtools.mcp.api.Me;
import systems.grebe.devtools.mcp.api.ProjectInfo;
import systems.grebe.devtools.mcp.api.SettingsSnapshot;
import systems.grebe.devtools.mcp.catalog.ModuleCatalog;
import systems.grebe.devtools.mcp.profile.ProfileService;
import systems.grebe.devtools.mcp.project.Project;
import systems.grebe.devtools.mcp.project.ProjectService;
import tools.jackson.databind.json.JsonMapper;

/**
 * REST-API für die Desktop-Apps (angemeldet per Desktop-Token, siehe {@link ApiTokenFilter}). Die Desktop-App meldet
 * ihre Module und holt sich Profil, Einstellungs-Vorgaben und Projekte des Benutzers; {@code /settings} und
 * {@code /projects} liefern ein {@code ETag}, damit der regelmäßige Abgleich meist nur {@code 304} bekommt.
 */
@RestController
@RequestMapping(SecurityConfig.API)
public class DesktopApi {

    private final ProfileService profiles;
    private final ProjectService projects;
    private final ModuleCatalog catalog;
    private final JsonMapper json = JsonMapper.builder().build();

    public DesktopApi(ProfileService profiles, ProjectService projects, ModuleCatalog catalog) {
        this.profiles = profiles;
        this.projects = projects;
        this.catalog = catalog;
    }

    @GetMapping("/me")
    public Me me(@RequestAttribute(ApiTokenFilter.USER) UserAccount user) {
        long active = profiles.activeProfile(user.id()).id();
        List<Me.ProfileInfo> list = profiles.profiles(user.id()).stream()
                .map(p -> new Me.ProfileInfo(p.id(), p.name(), p.description())).toList();
        return new Me(user.id(), user.username(), user.displayName(), user.email(), user.admin(), list, active);
    }

    @PutMapping("/catalog")
    public ResponseEntity<Void> catalog(@RequestAttribute(ApiTokenFilter.USER) UserAccount user,
                                        @RequestBody Catalog body) {
        catalog.report(body);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/settings")
    public ResponseEntity<SettingsSnapshot> settings(@RequestAttribute(ApiTokenFilter.USER) UserAccount user,
                                                     WebRequest request) {
        return withEtag(profiles.snapshot(user.id()), request);
    }

    /** Wechselt das aktive Profil ({@code {"profileId": 3}}). */
    @PutMapping("/profile/active")
    public ResponseEntity<Void> activate(@RequestAttribute(ApiTokenFilter.USER) UserAccount user,
                                         @RequestBody Map<String, Long> body) {
        Long id = body.get("profileId");
        if (id == null) {
            throw new IllegalArgumentException("profileId fehlt");
        }
        profiles.activate(user.id(), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/projects")
    public ResponseEntity<List<ProjectInfo>> projects(@RequestAttribute(ApiTokenFilter.USER) UserAccount user,
                                                      WebRequest request) {
        List<ProjectInfo> list = projects.visible(user.id()).stream().map(DesktopApi::info).toList();
        return withEtag(list, request);
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> badRequest(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", String.valueOf(e.getMessage())));
    }

    static ProjectInfo info(Project.Visible v) {
        Project p = v.project();
        return new ProjectInfo(p.id(), p.name(), p.ownerName(), v.toolName(), v.access().canWrite(),
                v.access().name(), p.description(), p.sonarKey(), p.ticketProject());
    }

    private <T> ResponseEntity<T> withEtag(T body, WebRequest request) {
        String etag = "\"" + sha256(json.writeValueAsString(body)) + "\"";
        if (request.checkNotModified(etag)) {
            return null; // 304, Antwort bereits gesetzt
        }
        return ResponseEntity.ok().eTag(etag).body(body);
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8)), 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
