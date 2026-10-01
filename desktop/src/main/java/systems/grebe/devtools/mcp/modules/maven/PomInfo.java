package systems.grebe.devtools.mcp.modules.maven;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.w3c.dom.Element;

import static systems.grebe.devtools.mcp.modules.maven.MavenRepositoryClient.child;
import static systems.grebe.devtools.mcp.modules.maven.MavenRepositoryClient.children;
import static systems.grebe.devtools.mcp.modules.maven.MavenRepositoryClient.text;

/**
 * Die für Nutzer interessanten Angaben eines POMs, inklusive der von Parent-POMs geerbten (Lizenzen, URL, SCM,
 * Properties, dependencyManagement). BOM-Imports ({@code scope=import}) werden nicht aufgelöst.
 */
record PomInfo(String groupId, String artifactId, String version, String packaging, String name, String description,
               String url, String inceptionYear, String organization, List<String> licenses, String scmUrl,
               String issueUrl, List<String> developers, String parent, String relocation, String javaRelease,
               List<Dependency> dependencies) {

    /** Direkte Abhängigkeit; {@code version} ist {@code null}, wenn sie aus einem nicht aufgelösten BOM kommt. */
    record Dependency(String groupId, String artifactId, String version, String scope, boolean optional) {
    }

    private static final int MAX_PARENTS = 8;
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");
    private static final List<String> JAVA_PROPS = List.of("maven.compiler.release", "maven.compiler.target",
            "java.version", "maven.compiler.source", "jdk.version", "java.release");

    /**
     * Liest ein POM samt Parent-Kette.
     *
     * @param loadPom lädt ein weiteres POM ({@code groupId:artifactId}, Version); leer, wenn nicht vorhanden
     */
    static PomInfo read(byte[] pom, BiFunction<Coordinates, String, Optional<byte[]>> loadPom) {
        List<Element> chain = new ArrayList<>();
        Element current = MavenRepositoryClient.parseXml(pom).getDocumentElement();
        chain.add(current);
        for (int i = 0; i < MAX_PARENTS; i++) {
            Element parent = child(current, "parent");
            if (parent == null || text(parent, "groupId") == null || text(parent, "artifactId") == null
                    || text(parent, "version") == null) {
                break;
            }
            Optional<byte[]> parentPom;
            try {
                parentPom = loadPom.apply(new Coordinates(text(parent, "groupId"), text(parent, "artifactId"), null),
                        text(parent, "version"));
            } catch (RuntimeException e) {
                break; // geerbte Angaben fehlen dann – kein Grund, die ganze Auskunft scheitern zu lassen
            }
            if (parentPom.isEmpty()) {
                break;
            }
            current = MavenRepositoryClient.parseXml(parentPom.get()).getDocumentElement();
            chain.add(current);
        }
        return fromChain(chain);
    }

    private static PomInfo fromChain(List<Element> chain) {
        Element self = chain.getFirst();
        Element parentRef = child(self, "parent");
        String groupId = firstNonNull(text(self, "groupId"), text(parentRef, "groupId"));
        String artifactId = text(self, "artifactId");
        String version = firstNonNull(text(self, "version"), text(parentRef, "version"));

        // Properties: Kind überschreibt Parent
        Map<String, String> props = new HashMap<>();
        for (int i = chain.size() - 1; i >= 0; i--) {
            Element p = child(chain.get(i), "properties");
            if (p != null) {
                for (var n = p.getFirstChild(); n != null; n = n.getNextSibling()) {
                    if (n instanceof Element e) {
                        props.put(e.getTagName(), e.getTextContent().trim());
                    }
                }
            }
        }
        props.put("project.groupId", groupId);
        props.put("project.artifactId", artifactId);
        props.put("project.version", version);
        props.put("pom.version", version);
        if (parentRef != null) {
            props.put("project.parent.version", text(parentRef, "version"));
            props.put("parent.version", text(parentRef, "version"));
            props.put("project.parent.groupId", text(parentRef, "groupId"));
        }

        // dependencyManagement: Kind überschreibt Parent
        Map<String, String> managed = new HashMap<>();
        for (int i = chain.size() - 1; i >= 0; i--) {
            for (Element d : children(child(child(chain.get(i), "dependencyManagement"), "dependencies"), "dependency")) {
                String v = text(d, "version");
                if (v != null && !"import".equals(text(d, "scope"))) {
                    managed.put(interpolate(text(d, "groupId"), props) + ":" + interpolate(text(d, "artifactId"), props),
                            interpolate(v, props));
                }
            }
        }

        List<String> licenses = new ArrayList<>();
        List<String> developers = new ArrayList<>();
        String url = null;
        String scm = null;
        String issues = null;
        String org = null;
        String inception = null;
        for (Element e : chain) {
            if (licenses.isEmpty()) {
                for (Element l : children(child(e, "licenses"), "license")) {
                    String n = text(l, "name");
                    String u = text(l, "url");
                    licenses.add(n == null ? u : u == null ? n : n + " (" + u + ")");
                }
            }
            if (developers.isEmpty()) {
                for (Element d : children(child(e, "developers"), "developer")) {
                    String n = firstNonNull(text(d, "name"), text(d, "id"));
                    if (n != null) {
                        developers.add(n);
                    }
                }
            }
            // URLs von Parents gehören zum Parent-Projekt; nur übernehmen, was das Kind nicht selbst angibt
            url = firstNonNull(url, text(e, "url"));
            scm = firstNonNull(scm, text(child(e, "scm"), "url"), text(child(e, "scm"), "connection"));
            issues = firstNonNull(issues, text(child(e, "issueManagement"), "url"));
            org = firstNonNull(org, text(child(e, "organization"), "name"));
            inception = firstNonNull(inception, text(e, "inceptionYear"));
        }

        String java = JAVA_PROPS.stream().map(props::get).filter(v -> v != null && !v.isBlank())
                .map(v -> interpolate(v, props)).findFirst().orElse(null);

        Element relocationEl = child(child(self, "distributionManagement"), "relocation");
        String relocation = null;
        if (relocationEl != null) {
            relocation = firstNonNull(text(relocationEl, "groupId"), groupId) + ":"
                    + firstNonNull(text(relocationEl, "artifactId"), artifactId) + ":"
                    + firstNonNull(text(relocationEl, "version"), version)
                    + (text(relocationEl, "message") == null ? "" : " – " + text(relocationEl, "message"));
        }

        List<Dependency> deps = new ArrayList<>();
        for (Element d : children(child(self, "dependencies"), "dependency")) {
            String g = interpolate(text(d, "groupId"), props);
            String a = interpolate(text(d, "artifactId"), props);
            String v = text(d, "version");
            v = v == null ? managed.get(g + ":" + a) : interpolate(v, props);
            deps.add(new Dependency(g, a, v, firstNonNull(text(d, "scope"), "compile"),
                    "true".equals(text(d, "optional"))));
        }

        String parent = parentRef == null ? null
                : text(parentRef, "groupId") + ":" + text(parentRef, "artifactId") + ":" + text(parentRef, "version");
        return new PomInfo(groupId, artifactId, version, firstNonNull(text(self, "packaging"), "jar"),
                interpolate(text(self, "name"), props), interpolate(text(self, "description"), props),
                interpolate(url, props), inception, interpolate(org, props), licenses,
                interpolate(scm, props), interpolate(issues, props), developers, parent, relocation, java, deps);
    }

    /** GitHub-Repository {@code owner/repo} aus SCM- oder Projekt-URL, falls erkennbar. */
    Optional<String> githubRepository() {
        Pattern p = Pattern.compile("github\\.com[/:]([\\w.-]+)/([\\w.-]+?)(?:\\.git)?(?:[/#?].*)?$");
        for (String candidate : new String[] {scmUrl, url, issueUrl}) {
            if (candidate != null) {
                Matcher m = p.matcher(candidate.trim());
                if (m.find()) {
                    return Optional.of(m.group(1) + "/" + m.group(2));
                }
            }
        }
        return Optional.empty();
    }

    static String interpolate(String value, Map<String, String> props) {
        if (value == null) {
            return null;
        }
        String result = value;
        for (int round = 0; round < 5 && result.contains("${"); round++) {
            Matcher m = PLACEHOLDER.matcher(result);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String v = props.get(m.group(1));
                m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? m.group() : v));
            }
            m.appendTail(sb);
            if (sb.toString().equals(result)) {
                break;
            }
            result = sb.toString();
        }
        return result.replaceAll("\\s+", " ").trim();
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T v : values) {
            if (v != null) {
                return v;
            }
        }
        return null;
    }
}
