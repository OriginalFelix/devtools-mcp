package systems.grebe.devtools.mcp.modules.maven;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.ToolHints;

/** Maven-Tools: neueste Versionen, POM-Metadaten und Breaking Changes zwischen zwei Versionen. */
@ToolHints(readOnly = true, idempotent = true)
public class MavenTools {

    private static final String COORDS_PARAM = "Maven-Koordinaten groupId:artifactId, z.B. com.fasterxml.jackson.core:jackson-databind";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
    private static final int MAX_CHANGES = 150;

    private final Supplier<MavenRepositoryClient> repo;
    private final Supplier<GitHubReleaseNotes> github;

    MavenTools(Supplier<MavenRepositoryClient> repo, Supplier<GitHubReleaseNotes> github) {
        this.repo = repo;
        this.github = github;
    }

    @Tool(name = "latest_version", description = "Neueste Version eines Maven-Artefakts (Release und ggf. neuere Vorabversion) "
            + "mit Veröffentlichungsdatum und den letzten Versionen. Mit currentVersion: ob ein Update vorliegt und ob es "
            + "laut SemVer Breaking Changes erwarten lässt. Statt `curl` gegen maven-metadata.xml, search.maven.org oder "
            + "`mvn versions:display-dependency-updates` verwenden." + ShellHints.MAVEN)
    public String latestVersion(
            @ToolParam(description = COORDS_PARAM) String coordinates,
            @ToolParam(required = false, description = "Aktuell verwendete Version (optional) – für Update-Einschätzung") String currentVersion,
            @ToolParam(required = false, description = "Auch Vorabversionen (alpha, beta, RC, M, SNAPSHOT) auflisten; Standard false") Boolean includePrereleases,
            @ToolParam(required = false, description = "Anzahl der aufgelisteten Versionen (Standard 15)") Integer limit) {
        Coordinates c = Coordinates.parse(coordinates);
        MavenRepositoryClient client = repo.get();
        MavenRepositoryClient.Metadata meta = client.metadata(c);
        boolean pre = Boolean.TRUE.equals(includePrereleases);
        String release = MavenVersions.latest(meta.versions(), false);
        String newest = MavenVersions.latest(meta.versions(), true);
        if (newest == null) {
            return c.ga() + ": keine Versionen in " + client.baseUrl();
        }

        StringBuilder sb = new StringBuilder(c.ga()).append('\n');
        if (release != null) {
            sb.append("Neueste Release-Version: ").append(release).append(date(client, c, release)).append('\n');
        } else {
            sb.append("Keine Release-Version, nur Vorabversionen.\n");
        }
        if (!newest.equals(release)) {
            sb.append("Neueste Vorabversion: ").append(newest).append(date(client, c, newest)).append('\n');
        }
        if (meta.lastUpdated() != null) {
            sb.append("Metadaten aktualisiert: ").append(DATE.format(meta.lastUpdated())).append('\n');
        }

        if (currentVersion != null && !currentVersion.isBlank()) {
            String cur = currentVersion.trim();
            String target = release != null ? release : newest;
            sb.append('\n');
            if (!meta.versions().contains(cur)) {
                sb.append("Hinweis: Version ").append(cur).append(" ist im Repository nicht gelistet.\n");
            }
            if (MavenVersions.compare(cur, target) >= 0) {
                sb.append("Aktuell: ").append(cur).append(" ist die neueste Version.\n");
            } else {
                long behind = meta.versions().stream().filter(v -> !MavenVersions.isPrerelease(v))
                        .filter(v -> MavenVersions.compare(v, cur) > 0 && MavenVersions.compare(v, target) <= 0).count();
                sb.append("Update verfügbar: ").append(cur).append(" → ").append(target)
                        .append(" (").append(behind).append(" neuere Release-Versionen)\n")
                        .append(MavenVersions.semverAssessment(cur, target)).append('\n');
                if (MavenVersions.potentiallyBreaking(cur, target)) {
                    sb.append("Details: maven_breaking_changes mit fromVersion=").append(cur).append('\n');
                }
            }
        }

        int n = limit == null || limit < 1 ? 15 : Math.min(limit, 200);
        List<String> shown = meta.versions().stream().filter(v -> pre || !MavenVersions.isPrerelease(v))
                .sorted(MavenVersions.ORDER.reversed()).limit(n).toList();
        sb.append("\nLetzte ").append(shown.size()).append(" von ").append(meta.versions().size())
                .append(" Versionen (neueste zuerst):\n");
        for (String v : shown) {
            sb.append("  ").append(v).append(MavenVersions.isPrerelease(v) ? "  (Vorabversion)" : "").append('\n');
        }
        return sb.toString().trim();
    }

    @Tool(name = "artifact_info", description = "Metadaten eines Maven-Artefakts aus dem POM (inkl. Parent-POMs): Name, "
            + "Beschreibung, Projekt-URL, Lizenzen, SCM, Issue-Tracker, Organisation, Java-Version, Relocation und direkte "
            + "Abhängigkeiten. Ohne Version wird die neueste Release-Version verwendet. Statt POMs per `curl` oder "
            + "`mvn help:effective-pom`/`mvn dependency:tree` zu lesen." + ShellHints.MAVEN)
    public String artifactInfo(
            @ToolParam(description = "Maven-Koordinaten groupId:artifactId[:version], z.B. org.slf4j:slf4j-api oder org.slf4j:slf4j-api:2.0.16") String coordinates,
            @ToolParam(required = false, description = "Version (optional, sonst aus den Koordinaten bzw. neueste Release-Version)") String version,
            @ToolParam(required = false, description = "Abhängigkeiten auflisten (Standard true)") Boolean includeDependencies) {
        Coordinates c = Coordinates.parse(coordinates);
        MavenRepositoryClient client = repo.get();
        MavenRepositoryClient.Metadata meta = client.metadata(c);
        String v = resolveVersion(c, version, meta);
        PomInfo pom = readPom(client, c, v);
        String latest = MavenVersions.latest(meta.versions(), false);

        StringBuilder sb = new StringBuilder();
        sb.append(pom.groupId()).append(':').append(pom.artifactId()).append(':').append(v)
                .append("  [").append(pom.packaging()).append("]\n");
        line(sb, "Name", pom.name());
        line(sb, "Beschreibung", pom.description());
        sb.append("Veröffentlicht: ").append(client.published(c, v).map(DATE::format).orElse("unbekannt")).append('\n');
        if (latest != null) {
            sb.append("Neueste Release-Version: ").append(latest)
                    .append(latest.equals(v) ? " (diese)" : MavenVersions.compare(v, latest) < 0 ? " – " + MavenVersions.semverAssessment(v, latest) : "")
                    .append('\n');
        }
        line(sb, "Projekt-URL", pom.url());
        line(sb, "SCM", pom.scmUrl());
        line(sb, "Issues", pom.issueUrl());
        line(sb, "Organisation", pom.organization());
        line(sb, "Seit", pom.inceptionYear());
        line(sb, "Lizenzen", pom.licenses().isEmpty() ? null : String.join("; ", pom.licenses()));
        line(sb, "Entwickler", pom.developers().isEmpty() ? null
                : String.join(", ", pom.developers().subList(0, Math.min(8, pom.developers().size())))
                + (pom.developers().size() > 8 ? " … (+" + (pom.developers().size() - 8) + ")" : ""));
        line(sb, "Java (Compiler-Ziel)", pom.javaRelease());
        line(sb, "Parent", pom.parent());
        if (pom.relocation() != null) {
            sb.append("ACHTUNG – verschoben nach: ").append(pom.relocation()).append('\n');
        }

        if (!Boolean.FALSE.equals(includeDependencies)) {
            List<PomInfo.Dependency> deps = pom.dependencies();
            sb.append("\nDirekte Abhängigkeiten (").append(deps.size()).append("):\n");
            if (deps.isEmpty()) {
                sb.append("  (keine)\n");
            }
            for (PomInfo.Dependency d : deps) {
                sb.append("  ").append(d.groupId()).append(':').append(d.artifactId()).append(':')
                        .append(d.version() == null ? "(Version aus BOM)" : d.version());
                if (!"compile".equals(d.scope())) {
                    sb.append("  [").append(d.scope()).append(']');
                }
                if (d.optional()) {
                    sb.append("  (optional)");
                }
                sb.append('\n');
            }
        }
        return sb.toString().trim();
    }

    @Tool(name = "breaking_changes", description = "Breaking Changes zwischen zwei Versionen eines Maven-Artefakts: "
            + "SemVer-Einordnung, Vergleich der öffentlichen API beider JARs (entfernte/geänderte Klassen, Methoden, Felder, "
            + "neue abstrakte Methoden …) und Breaking-Hinweise aus den GitHub-Release-Notes dazwischen. Ohne toVersion wird "
            + "die neueste Release-Version verwendet. Vor einem Versions-Upgrade aufrufen, statt Changelogs im Browser zu suchen."
            + ShellHints.MAVEN)
    public String breakingChanges(
            @ToolParam(description = COORDS_PARAM) String coordinates,
            @ToolParam(description = "Ausgangsversion (die aktuell verwendete)") String fromVersion,
            @ToolParam(required = false, description = "Zielversion (optional, Standard: neueste Release-Version)") String toVersion,
            @ToolParam(required = false, description = "Änderungen in internen Paketen (internal, impl, shaded) mit auflisten; Standard false") Boolean includeInternal,
            @ToolParam(required = false, description = "GitHub-Release-Notes auswerten (Standard true)") Boolean releaseNotes) {
        Coordinates c = Coordinates.parse(coordinates);
        if (fromVersion == null || fromVersion.isBlank()) {
            throw new IllegalArgumentException("fromVersion fehlt – die aktuell verwendete Version angeben.");
        }
        String from = fromVersion.trim();
        MavenRepositoryClient client = repo.get();
        MavenRepositoryClient.Metadata meta = client.metadata(c);
        String to = resolveVersion(c, toVersion, meta);

        StringBuilder sb = new StringBuilder("Breaking Changes ").append(c.ga()).append(": ").append(from)
                .append(" → ").append(to).append('\n');
        sb.append("SemVer: ").append(MavenVersions.semverAssessment(from, to)).append('\n');
        if (MavenVersions.compare(from, to) >= 0) {
            return sb.toString().trim();
        }
        long between = meta.versions().stream().filter(v -> MavenVersions.compare(v, from) > 0
                && MavenVersions.compare(v, to) <= 0).count();
        sb.append("Versionen dazwischen (inkl. Ziel): ").append(between).append('\n');

        // POM-Änderungen, die Nutzer direkt treffen
        PomInfo toPom = null;
        try {
            PomInfo fromPom = readPom(client, c, from);
            toPom = readPom(client, c, to);
            pomChanges(sb, fromPom, toPom);
        } catch (IllegalArgumentException e) {
            sb.append("\nPOM-Vergleich nicht möglich: ").append(e.getMessage()).append('\n');
        }

        apiChanges(sb, client, c, from, to, Boolean.TRUE.equals(includeInternal));

        if (!Boolean.FALSE.equals(releaseNotes)) {
            releaseNotes(sb, toPom, c, from, to);
        }
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------ Teile von breaking_changes

    private static void pomChanges(StringBuilder sb, PomInfo from, PomInfo to) {
        List<String> lines = new ArrayList<>();
        if (to.relocation() != null) {
            lines.add("Artefakt verschoben nach " + to.relocation());
        }
        if (from.javaRelease() != null && to.javaRelease() != null && !from.javaRelease().equals(to.javaRelease())) {
            lines.add("Java-Ziel geändert: " + from.javaRelease() + " → " + to.javaRelease()
                    + " (höheres Ziel erfordert neuere Laufzeit)");
        }
        if (!from.licenses().equals(to.licenses()) && !to.licenses().isEmpty()) {
            lines.add("Lizenz geändert: " + String.join("; ", from.licenses()) + " → " + String.join("; ", to.licenses()));
        }
        for (PomInfo.Dependency d : from.dependencies()) {
            boolean kept = to.dependencies().stream().anyMatch(x -> x.groupId().equals(d.groupId())
                    && x.artifactId().equals(d.artifactId()));
            if (!kept && ("compile".equals(d.scope()) || "runtime".equals(d.scope())) && !d.optional()) {
                lines.add("Transitive Abhängigkeit entfällt: " + d.groupId() + ":" + d.artifactId()
                        + " (wer sie indirekt nutzt, muss sie selbst deklarieren)");
            }
        }
        for (PomInfo.Dependency d : to.dependencies()) {
            from.dependencies().stream().filter(x -> x.groupId().equals(d.groupId()) && x.artifactId().equals(d.artifactId()))
                    .findFirst().filter(x -> x.version() != null && d.version() != null
                            && !x.version().equals(d.version()) && MavenVersions.potentiallyBreaking(x.version(), d.version()))
                    .ifPresent(x -> lines.add("Abhängigkeit mit Major-Sprung: " + d.groupId() + ":" + d.artifactId() + " "
                            + x.version() + " → " + d.version()));
        }
        if (!lines.isEmpty()) {
            sb.append("\nPOM-Änderungen:\n");
            lines.forEach(l -> sb.append("  ").append(l).append('\n'));
        }
    }

    private static void apiChanges(StringBuilder sb, MavenRepositoryClient client, Coordinates c, String from, String to,
                                   boolean includeInternal) {
        Optional<byte[]> oldJar;
        Optional<byte[]> newJar;
        try {
            oldJar = client.jar(c, from);
            newJar = client.jar(c, to);
        } catch (IllegalStateException e) {
            sb.append("\nAPI-Vergleich nicht möglich: ").append(e.getMessage()).append('\n');
            return;
        }
        if (oldJar.isEmpty() || newJar.isEmpty()) {
            sb.append("\nAPI-Vergleich nicht möglich: kein JAR für ").append(oldJar.isEmpty() ? from : to)
                    .append(" (z.B. packaging=pom/BOM).\n");
            return;
        }
        ApiDiff.Result r = ApiDiff.compare(oldJar.get(), newJar.get());
        List<ApiDiff.Change> breaking = r.breaking().stream().filter(ch -> includeInternal || !ch.internal()).toList();
        List<ApiDiff.Change> potential = r.potentiallyBreaking().stream().filter(ch -> includeInternal || !ch.internal()).toList();
        long hiddenInternal = r.breaking().size() + r.potentiallyBreaking().size() - breaking.size() - potential.size();

        sb.append("\nAPI-Vergleich (Bytecode, ").append(r.oldClasses()).append(" → ").append(r.newClasses())
                .append(" öffentliche Klassen; neu: ").append(r.addedClasses()).append(" Klassen, ")
                .append(r.addedMembers()).append(" Methoden/Felder):\n");
        if (breaking.isEmpty() && potential.isEmpty()) {
            sb.append("  Keine inkompatiblen Änderungen der öffentlichen API gefunden.\n");
        }
        appendChanges(sb, "Inkompatibel", breaking);
        appendChanges(sb, "Potenziell inkompatibel (nur für eigene Implementierungen/Unterklassen)", potential);
        if (hiddenInternal > 0) {
            sb.append("  (").append(hiddenInternal)
                    .append(" weitere Änderungen in internen Paketen ausgeblendet – includeInternal=true zeigt sie)\n");
        }
    }

    private static void appendChanges(StringBuilder sb, String title, List<ApiDiff.Change> changes) {
        if (changes.isEmpty()) {
            return;
        }
        sb.append("  ").append(title).append(" (").append(changes.size()).append("):\n");
        changes.stream().limit(MAX_CHANGES).forEach(ch -> sb.append("    ").append(ch.type()).append(": ")
                .append(ch.element()).append(ch.detail().isEmpty() ? "" : " – " + ch.detail()).append('\n'));
        if (changes.size() > MAX_CHANGES) {
            sb.append("    … und ").append(changes.size() - MAX_CHANGES).append(" weitere\n");
        }
    }

    private void releaseNotes(StringBuilder sb, PomInfo pom, Coordinates c, String from, String to) {
        Optional<String> gh = pom == null ? Optional.empty() : pom.githubRepository();
        if (gh.isEmpty()) {
            sb.append("\nRelease Notes: kein GitHub-Repository im POM angegeben.\n");
            return;
        }
        List<GitHubReleaseNotes.Release> releases;
        try {
            releases = github.get().between(gh.get(), c.artifactId(), from, to);
        } catch (IllegalStateException e) {
            sb.append("\nRelease Notes (").append(gh.get()).append(") nicht abrufbar: ").append(e.getMessage()).append('\n');
            return;
        }
        sb.append("\nRelease Notes (github.com/").append(gh.get()).append("): ");
        if (releases.isEmpty()) {
            sb.append("keine GitHub-Releases im Bereich – ggf. CHANGELOG im Repository prüfen.\n");
            return;
        }
        long withHints = releases.stream().filter(r -> !r.breaking().isEmpty()).count();
        sb.append(releases.size()).append(" Releases, ").append(withHints).append(" mit Breaking-Hinweisen\n");
        for (GitHubReleaseNotes.Release r : releases) {
            if (r.breaking().isEmpty()) {
                continue;
            }
            sb.append("  ").append(r.version()).append(" (").append(r.date()).append(") ").append(r.url()).append('\n');
            r.breaking().forEach(l -> sb.append("    ").append(l).append('\n'));
        }
        if (withHints < releases.size()) {
            sb.append("  Ohne Hinweise: ").append(String.join(", ", releases.stream().filter(r -> r.breaking().isEmpty())
                    .map(GitHubReleaseNotes.Release::version).toList())).append('\n');
        }
    }

    // ------------------------------------------------------------------ Hilfen

    private static String resolveVersion(Coordinates c, String version, MavenRepositoryClient.Metadata meta) {
        String v = version != null && !version.isBlank() ? version.trim() : c.version();
        if (v == null || v.equalsIgnoreCase("latest") || v.equalsIgnoreCase("release")) {
            v = MavenVersions.latest(meta.versions(), false);
            if (v == null) {
                v = MavenVersions.latest(meta.versions(), true);
            }
            if (v == null) {
                throw new IllegalArgumentException(c.ga() + " hat keine Versionen.");
            }
        }
        return v;
    }

    private static PomInfo readPom(MavenRepositoryClient client, Coordinates c, String version) {
        byte[] pom = client.pom(c, version).orElseThrow(() -> new IllegalArgumentException(
                "Version " + version + " von " + c.ga() + " nicht gefunden – maven_latest_version listet die vorhandenen."));
        return PomInfo.read(pom, client::pom);
    }

    private static String date(MavenRepositoryClient client, Coordinates c, String version) {
        return client.published(c, version).map(i -> " (" + DATE.format(i) + ")").orElse("");
    }

    private static void line(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            sb.append(label).append(": ").append(value).append('\n');
        }
    }
}
