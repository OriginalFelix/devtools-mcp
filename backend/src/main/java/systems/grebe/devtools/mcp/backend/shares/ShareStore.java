package systems.grebe.devtools.mcp.backend.shares;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.backend.skills.SkillOwner;
import systems.grebe.devtools.mcp.modules.shares.ShareViews;

/**
 * Freigaben von Skills und Memories für {@code SkillService} und {@code MemoryService}: anlegen und zurücknehmen
 * (nur der Eigentümer), auflisten und – für die Sichtbarkeit – die für den aktuellen Benutzer freigegebenen IDs.
 * Läuft in der Transaktion des aufrufenden Services.
 *
 * <p>Einzelne Benutzer und Rollen darf jeder als Ziel angeben; für alle nur mit dem Recht „Mit allen teilen“
 * ({@link SkillOwner#shareWithAll()}).
 */
@Component
public class ShareStore {

    /** Platzhalter für {@code in :roles}, wenn der Benutzer keine Rolle hat (leere Listen mögen nicht alle Datenbanken). */
    private static final String NO_ROLE = "\u0000";

    private final ItemShareRepository shares;
    private final SkillOwner users;
    private final ShareResolver resolver;

    public ShareStore(ItemShareRepository shares, SkillOwner users, ShareResolver resolver) {
        this.shares = shares;
        this.users = users;
        this.resolver = resolver;
    }

    /** Für den aktuellen Benutzer freigegebene Skills bzw. Memories anderer Eigentümer. */
    public List<Long> visible(ItemShare.Kind kind) {
        List<String> roles = users.roles().isEmpty() ? List.of(NO_ROLE) : users.roles();
        return shares.visibleItems(kind, users.email(), roles, ShareViews.Target.ALL, ShareViews.Target.USER,
                ShareViews.Target.ROLE);
    }

    public List<ShareViews.Share> of(ItemShare.Kind kind, long itemId) {
        return shares.findByKindAndItemIdOrderByTargetAscNameAsc(kind, itemId).stream().map(ItemShare::view).toList();
    }

    /** Entfernt alle Freigaben (Skill bzw. Memory gelöscht). */
    public void removeAll(ItemShare.Kind kind, long itemId) {
        shares.deleteItem(kind, itemId);
    }

    /**
     * Gibt frei bzw. nimmt zurück und beschreibt das Ergebnis samt aktuellem Stand.
     *
     * @param what z.B. „Skill 'heap-leak'“
     */
    public String apply(ItemShare.Kind kind, long itemId, String what, ShareViews.Request request, boolean revoke) {
        if (request == null || request.empty()) {
            return describe(what, of(kind, itemId));
        }
        String owner = users.email();
        Map<String, ShareViews.Share> targets = new LinkedHashMap<>();
        for (String u : request.users()) {
            String email = revoke ? resolver.userEmail(u).orElse(ShareViews.email(u)) : resolver.userEmail(u)
                    .orElseThrow(() -> new IllegalArgumentException("Benutzer '" + u + "' gibt es nicht (oder er "
                            + "ist gesperrt bzw. ohne E-Mail). Anmeldename oder E-Mail eines Benutzers auf dem "
                            + "Team-Server angeben."));
            if (email.equals(owner) && !revoke) {
                throw new IllegalArgumentException("Eigene Skills und Memories sieht man ohnehin – '" + u
                        + "' ist der eigene Benutzer.");
            }
            targets.put("u:" + email, new ShareViews.Share(ShareViews.Target.USER, email, null));
        }
        for (String r : request.roles()) {
            String role = revoke ? resolver.role(r).orElse(r) : resolver.role(r).orElseThrow(() ->
                    new IllegalArgumentException("Rolle '" + r + "' gibt es nicht."));
            targets.put("r:" + role, new ShareViews.Share(ShareViews.Target.ROLE, role, null));
        }
        if (request.everyone()) {
            if (!revoke && !users.shareWithAll()) {
                throw new IllegalStateException("Mit allen teilen: dafür fehlt das Recht „Mit allen teilen“ – "
                        + "stattdessen einzelne Benutzer oder Rollen angeben.");
            }
            targets.put("*", new ShareViews.Share(ShareViews.Target.ALL, ShareViews.EVERYONE, null));
        }
        Instant now = Instant.now();
        List<String> changed = new ArrayList<>();
        for (ShareViews.Share t : targets.values()) {
            Optional<ItemShare> existing = shares.findByKindAndItemIdAndTargetAndName(kind, itemId, t.target(),
                    t.name());
            if (revoke && existing.isPresent()) {
                shares.delete(existing.get());
                changed.add(t.label());
            } else if (!revoke && existing.isEmpty()) {
                shares.save(new ItemShare(kind, itemId, owner, t.target(), t.name(), now));
                changed.add(t.label());
            }
        }
        shares.flush();
        String head = changed.isEmpty()
                ? (revoke ? "Keine dieser Freigaben bestand." : "Schon freigegeben, nichts geändert.")
                : what + (revoke ? " nicht mehr freigegeben für " : " freigegeben für ") + String.join(", ", changed)
                + ".";
        return head + " " + describe(what, of(kind, itemId));
    }

    static String describe(String what, List<ShareViews.Share> current) {
        return current.isEmpty() ? what + " ist nicht geteilt."
                : what + " ist geteilt mit: " + current.stream().map(ShareViews.Share::label)
                .collect(Collectors.joining(", ")) + ".";
    }

    /** Zur Auswahl in der Oberfläche; „alle“ nur mit dem Recht dafür. */
    public List<ShareViews.Candidate> candidates() {
        List<ShareViews.Candidate> list = new ArrayList<>(resolver.candidates(users.email()));
        if (users.shareWithAll()) {
            list.add(new ShareViews.Candidate(ShareViews.Target.ALL, ShareViews.EVERYONE, "Alle Benutzer"));
        }
        return list;
    }
}
