package systems.grebe.devtools.mcp.backend.blobs;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import systems.grebe.devtools.mcp.backend.GraphQlErrors;
import systems.grebe.devtools.mcp.backend.SkillCaller;
import systems.grebe.devtools.mcp.backend.account.TokenService;
import systems.grebe.devtools.mcp.backend.account.UserAccount;
import systems.grebe.devtools.mcp.backend.api.ApiSchemas;
import systems.grebe.devtools.mcp.backend.shares.SharedBlobs;
import systems.grebe.devtools.mcp.backend.skills.SkillOwner;

/**
 * Inhalte der {@link BlobStore Dateiablage} über HTTP – GraphQL taugt nicht für große Binärdaten. Angemeldet wird wie
 * bei {@code /graphql} mit {@code Authorization: Bearer <Token>}; jeder Benutzer schreibt und liest in seinem eigenen
 * Verzeichnis, lesen darf er außerdem die globalen Vorlagen und die Anhänge der für ihn geteilten Skills und Memories.
 *
 * <ul>
 *   <li>{@code POST /blobs/uploads} beginnt einen Upload → {@code {"upload": "<id>"}}</li>
 *   <li>{@code PUT /blobs/uploads/<id>?offset=<n>} hängt einen Teil an (Rumpf = Bytes) → {@code {"size": n}}</li>
 *   <li>{@code POST /blobs/uploads/<id>/complete} legt den Inhalt ab → {@code {"blob": "<sha256>", "size": n}}</li>
 *   <li>{@code GET /blobs/<sha256>} liefert den Inhalt</li>
 * </ul>
 *
 * In Teilen, damit keine Grenze je Anfrage (Servlet-Container, Reverse-Proxy) die Dateigröße beschränkt. Angehängt
 * wird ein abgelegter Inhalt anschließend per GraphQL ({@code attachSkillFile}, {@code attachMemoryFile}).
 *
 * <p>Dieselben Pfade gibt es je {@link systems.grebe.devtools.mcp.api.ApiVersions API-Version} unter
 * {@code /api/v<n>/blobs}; {@code /blobs} ist Version 0. Ändert eine Version die Dateiablage, unterscheiden die
 * Methoden nach {@code apiVersion}.
 */
@RestController
@RequestMapping({BlobController.PATH, BlobController.VERSIONED_PATH})
public class BlobController {

    /** Pfad ohne Version (Version 0). */
    public static final String PATH = "/blobs";
    /** Pfad je API-Version. */
    public static final String VERSIONED_PATH = "/api/v{apiVersion:\\d+}/blobs";

    private final BlobStore store;
    private final TokenService tokens;
    private final SharedBlobs shared;

    public BlobController(BlobStore store, TokenService tokens, SharedBlobs shared) {
        this.store = store;
        this.tokens = tokens;
        this.shared = shared;
    }

    /** Nur angebotene Versionen; sonst wie ein unbekannter Pfad. */
    @ModelAttribute
    void requireOffered(@PathVariable(required = false) Integer apiVersion) {
        if (apiVersion != null && !ApiSchemas.offered(apiVersion)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "API-Version " + apiVersion
                    + " wird nicht angeboten.");
        }
    }

    @PostMapping("/uploads")
    public Map<String, Object> start(@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String auth)
            throws IOException {
        return Map.of("upload", store.startUpload(owner(auth)));
    }

    @PutMapping("/uploads/{id}")
    public Map<String, Object> append(@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String auth,
                                      @PathVariable String id, @RequestParam long offset, InputStream body)
            throws IOException {
        return Map.of("size", store.append(owner(auth), id, offset, body));
    }

    @PostMapping("/uploads/{id}/complete")
    public Map<String, Object> complete(@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String auth,
                                        @PathVariable String id) throws IOException {
        BlobStore.Blob blob = store.complete(owner(auth), id);
        return Map.of("blob", blob.sha(), "size", blob.size());
    }

    @GetMapping("/{sha}")
    public ResponseEntity<Resource> download(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String auth, @PathVariable String sha) {
        UserAccount user = user(auth);
        String s = BlobStore.requireSha(sha);
        Path file = store.find(email(user), s).or(() -> store.find(SkillOwner.GLOBAL, s))
                .or(() -> SkillCaller.as(user, () -> shared.owner(s)).flatMap(o -> store.find(o, s)))
                .orElseThrow(() -> new IllegalArgumentException("Datei " + s + " gibt es nicht."));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(new FileSystemResource(file));
    }

    /** E-Mail des angemeldeten Benutzers (Verzeichnis seiner Dateien), wie bei den Skills. */
    private String owner(String auth) {
        return email(user(auth));
    }

    private UserAccount user(String auth) {
        if (auth == null || !auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new GraphQlErrors.Unauthorized();
        }
        UserAccount user = tokens.verify(auth.substring(7).strip()).map(TokenService.TokenUser::user)
                .orElseThrow(GraphQlErrors.Unauthorized::new);
        if (user.passwordChangeRequired()) {
            throw new GraphQlErrors.Forbidden("Bitte zuerst das Passwort ändern.");
        }
        if (user.email() == null || user.email().isBlank()) {
            throw new GraphQlErrors.Forbidden("Im Konto von '" + user.username() + "' ist keine E-Mail hinterlegt.");
        }
        return user;
    }

    private static String email(UserAccount user) {
        return user.email().strip().toLowerCase(Locale.ROOT);
    }

    @ExceptionHandler(GraphQlErrors.Unauthorized.class)
    ResponseEntity<Map<String, Object>> unauthorized(GraphQlErrors.Unauthorized e) {
        return error(HttpStatus.UNAUTHORIZED, "Anmeldung fehlt oder ist abgelaufen.");
    }

    @ExceptionHandler(GraphQlErrors.Forbidden.class)
    ResponseEntity<Map<String, Object>> forbidden(GraphQlErrors.Forbidden e) {
        return error(HttpStatus.FORBIDDEN, e.getMessage());
    }

    /** Mit Rumpf statt über die Fehlerseite – die läge beim Team-Server hinter der Anmeldung der Web-UI. */
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, Object>> status(ResponseStatusException e) {
        return error(HttpStatus.valueOf(e.getStatusCode().value()), e.getReason());
    }

    @ExceptionHandler({IllegalArgumentException.class, NoSuchFileException.class})
    ResponseEntity<Map<String, Object>> notFound(Exception e) {
        return error(e instanceof NoSuchFileException ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST,
                e.getMessage());
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", message == null ? status.getReasonPhrase() : message));
    }
}
