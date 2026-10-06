package systems.grebe.devtools.mcp.modules.maven;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.aether.util.version.GenericVersionScheme;
import org.eclipse.aether.version.InvalidVersionSpecificationException;
import org.eclipse.aether.version.Version;

/** Versionsvergleich nach Maven-Regeln (wie {@code maven-metadata.xml} sortiert) und grobe SemVer-Einordnung. */
public final class MavenVersions {

    private static final GenericVersionScheme SCHEME = new GenericVersionScheme();
    private static final Pattern PRERELEASE = Pattern.compile(
            "(?i)(snapshot|alpha|beta|preview|milestone|incubating|\\bea\\b|[-.]?rc[-.]?\\d*$|[-.]rc\\d*[-.]|[-.]cr\\d*|[-.]m\\d+)");
    private static final Pattern NUMBERS = Pattern.compile("^(\\d{1,18})(?:\\.(\\d{1,18}))?(?:\\.(\\d{1,18}))?");

    /** Aufsteigend nach Maven-Versionslogik. */
    static final Comparator<String> ORDER = Comparator.comparing(MavenVersions::parse);

    private MavenVersions() {
    }

    static Version parse(String version) {
        try {
            return SCHEME.parseVersion(version);
        } catch (InvalidVersionSpecificationException e) {
            throw new IllegalArgumentException("Ungültige Version: " + version, e);
        }
    }

    static int compare(String a, String b) {
        return ORDER.compare(a, b);
    }

    /** Snapshots, Alphas, Betas, Milestones, Release Candidates … */
    public static boolean isPrerelease(String version) {
        return PRERELEASE.matcher(version.toLowerCase(Locale.ROOT)).find();
    }

    /** Neueste Version der Liste (optional ohne Vorabversionen) oder {@code null}. */
    public static String latest(List<String> versions, boolean includePrereleases) {
        return versions.stream().filter(v -> includePrereleases || !isPrerelease(v)).max(ORDER).orElse(null);
    }

    /** Einordnung des Sprungs {@code from → to} nach Semantic Versioning. */
    static String semverAssessment(String from, String to) {
        int cmp = compare(from, to);
        if (cmp == 0) {
            return "Gleiche Version – keine Änderungen.";
        }
        if (cmp > 0) {
            return "Downgrade (" + from + " → " + to + ").";
        }
        long[] a = numbers(from);
        long[] b = numbers(to);
        if (a == null || b == null) {
            return "Versionsschema nicht SemVer-artig – Einordnung nicht möglich, API-Vergleich und Release Notes prüfen.";
        }
        if (b[0] > a[0]) {
            return "MAJOR-Sprung " + a[0] + " → " + b[0] + ": Breaking Changes laut SemVer zu erwarten.";
        }
        if (a[0] == 0 && b[1] > a[1]) {
            return "Minor-Sprung in 0.x (" + from + " → " + to + "): vor 1.0 sind Breaking Changes auch bei Minor-Versionen üblich.";
        }
        if (b[1] > a[1]) {
            return "Minor-Sprung: laut SemVer abwärtskompatibel (neue Funktionen, ggf. Deprecations).";
        }
        return "Patch-Sprung: laut SemVer nur Fehlerbehebungen.";
    }

    /** {@code true}, wenn der Sprung laut SemVer inkompatibel sein darf (Major bzw. Minor unter 1.0). */
    static boolean potentiallyBreaking(String from, String to) {
        long[] a = numbers(from);
        long[] b = numbers(to);
        if (a == null || b == null || compare(from, to) >= 0) {
            return false;
        }
        return b[0] > a[0] || a[0] == 0 && b[1] > a[1];
    }

    private static long[] numbers(String version) {
        Matcher m = NUMBERS.matcher(version);
        if (!m.find()) {
            return null;
        }
        return new long[] {Long.parseLong(m.group(1)), m.group(2) == null ? 0 : Long.parseLong(m.group(2)),
                m.group(3) == null ? 0 : Long.parseLong(m.group(3))};
    }
}
