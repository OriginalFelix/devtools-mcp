package systems.grebe.devtools.mcp.plugin.store;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.resolution.VersionRangeRequest;
import org.eclipse.aether.resolution.VersionRangeResolutionException;
import org.eclipse.aether.resolution.VersionRangeResult;
import org.eclipse.aether.supplier.RepositorySystemSupplier;
import org.eclipse.aether.supplier.SessionBuilderSupplier;
import org.eclipse.aether.util.artifact.JavaScopes;
import org.eclipse.aether.util.filter.DependencyFilterUtils;
import org.eclipse.aether.util.repository.AuthenticationBuilder;
import org.eclipse.aether.util.repository.JreProxySelector;
import org.eclipse.aether.version.Version;
import systems.grebe.devtools.mcp.api.Errors;

/**
 * Zugriff auf Maven-Repositories über Maven Resolver (derselbe Code wie in Maven selbst): Versionen aus
 * {@code maven-metadata.xml}, Artefakte samt transitiver Abhängigkeiten, Prüfsummen (Abbruch bei Abweichung),
 * Zugangsdaten und {@code file:}-Repositories. Heruntergeladenes landet in einem eigenen lokalen Repository
 * ({@code ~/.devtools-mcp/plugins/.repository}), nicht in {@code ~/.m2}.
 *
 * <p>Thread-sicher; jede Operation öffnet eine eigene Resolver-Session.
 */
public class MavenPluginResolver implements AutoCloseable {

    private final RepositorySystem system;
    private final Path localRepository;
    private final Supplier<List<PluginRepository>> repositories;

    public MavenPluginResolver(Path localRepository, Supplier<List<PluginRepository>> repositories) {
        this.system = new RepositorySystemSupplier().get();
        this.localRepository = localRepository;
        this.repositories = repositories;
    }

    /** Alle Versionen von {@code groupId:artifactId} über alle aktiven Repositories, neueste zuerst. */
    public List<String> versions(String groupId, String artifactId) {
        return versions(groupId, artifactId, remotes(repositories.get()));
    }

    /** Neueste Version oder {@code null}, wenn das Artefakt in keinem Repository liegt. */
    public String latestVersion(String groupId, String artifactId) {
        List<String> v = versions(groupId, artifactId);
        return v.isEmpty() ? null : v.getFirst();
    }

    /** Lädt ein einzelnes Artefakt ({@code g:a[:ext[:classifier]]:v}) ohne Abhängigkeiten. */
    public Path resolve(String coordinates) {
        try (RepositorySystemSession.CloseableSession session = session()) {
            ArtifactRequest request = new ArtifactRequest(new DefaultArtifact(coordinates), remotes(session), null);
            return system.resolveArtifact(session, request).getArtifact().getPath();
        } catch (ArtifactResolutionException e) {
            throw new ResolutionException("Artefakt " + coordinates + " nicht gefunden: " + Errors.rootMessage(e), e);
        }
    }

    /**
     * Lädt Bibliotheken samt transitiver Laufzeit-Abhängigkeiten (Scopes compile und runtime, wie Maven sie für die
     * Ausführung auflöst; Versionskonflikte nach „nearest wins“).
     */
    public List<Path> resolveWithDependencies(List<String> coordinates) {
        if (coordinates.isEmpty()) {
            return List.of();
        }
        try (RepositorySystemSession.CloseableSession session = session()) {
            CollectRequest collect = new CollectRequest();
            coordinates.forEach(c -> collect.addDependency(new Dependency(new DefaultArtifact(c), JavaScopes.RUNTIME)));
            collect.setRepositories(remotes(session));
            DependencyRequest request = new DependencyRequest(collect,
                    DependencyFilterUtils.classpathFilter(JavaScopes.RUNTIME));
            List<Path> paths = new ArrayList<>();
            for (ArtifactResult r : system.resolveDependencies(session, request).getArtifactResults()) {
                paths.add(r.getArtifact().getPath());
            }
            return paths;
        } catch (DependencyResolutionException e) {
            throw new ResolutionException("Bibliotheken " + coordinates + " nicht auflösbar: " + Errors.rootMessage(e), e);
        }
    }

    /** Neueste Version des Katalogs eines Repositories ({@code .yml}), nur aus diesem Repository. */
    public Path resolveCatalog(PluginRepository repo) {
        String[] ga = repo.catalog().split(":");
        List<RemoteRepository> only = remotes(List.of(repo.withEnabled(true)));
        List<String> versions = versions(ga[0], ga[1], only);
        if (versions.isEmpty()) {
            throw new ResolutionException("Katalog " + repo.catalog() + " liegt nicht in " + repo.url());
        }
        try (RepositorySystemSession.CloseableSession session = session()) {
            Artifact a = new DefaultArtifact(ga[0], ga[1], "yml", versions.getFirst());
            return system.resolveArtifact(session, new ArtifactRequest(a, only, null)).getArtifact().getPath();
        } catch (ArtifactResolutionException e) {
            throw new ResolutionException("Katalog " + repo.catalog() + " nicht ladbar: " + Errors.rootMessage(e), e);
        }
    }

    /** Prüft ein Repository: Metadaten eines bekannten Artefakts bzw. des Katalogs abrufen. */
    public String check(PluginRepository repo) {
        List<RemoteRepository> only = remotes(List.of(repo.withEnabled(true)));
        String ga = repo.hasCatalog() ? repo.catalog() : "org.apache.maven:maven-core";
        String[] parts = ga.split(":");
        List<String> v = versions(parts[0], parts[1], only);
        if (repo.hasCatalog()) {
            return v.isEmpty() ? "Erreichbar, aber Katalog " + ga + " nicht gefunden."
                    : "Erreichbar – Katalog " + ga + " in Version " + v.getFirst() + ".";
        }
        return v.isEmpty() ? "Keine Antwort mit Inhalt (Repository leer, Zugangsdaten falsch oder kein Maven-Layout?) "
                + "– ohne Katalog lassen sich Plugins trotzdem über ihre Koordinaten installieren."
                : "Erreichbar (Testabfrage " + ga + ": " + v.size() + " Versionen).";
    }

    @Override
    public void close() {
        system.shutdown();
    }

    // ------------------------------------------------------------------ intern

    private List<String> versions(String groupId, String artifactId, List<RemoteRepository> remotes) {
        try (RepositorySystemSession.CloseableSession session = session()) {
            VersionRangeRequest request = new VersionRangeRequest(
                    new DefaultArtifact(groupId, artifactId, "jar", "[0,)"), remotes, null);
            VersionRangeResult result = system.resolveVersionRange(session, request);
            List<String> out = new ArrayList<>(result.getVersions().stream().map(Version::toString).toList());
            Collections.reverse(out);
            return out;
        } catch (VersionRangeResolutionException e) {
            throw new ResolutionException("Versionen von " + groupId + ":" + artifactId + " nicht ermittelbar: "
                    + Errors.rootMessage(e), e);
        }
    }

    private RepositorySystemSession.CloseableSession session() {
        return new SessionBuilderSupplier(system).get()
                .withLocalRepositoryBaseDirectories(localRepository)
                .setProxySelector(new JreProxySelector())
                .setChecksumPolicy(RepositoryPolicy.CHECKSUM_POLICY_FAIL)
                // Metadaten (Versionslisten) immer frisch, Artefakte ändern sich bei Releases nicht
                .setMetadataUpdatePolicy(RepositoryPolicy.UPDATE_POLICY_ALWAYS)
                .setArtifactUpdatePolicy(RepositoryPolicy.UPDATE_POLICY_DAILY)
                .build();
    }

    private List<RemoteRepository> remotes(RepositorySystemSession session) {
        return system.newResolutionRepositories(session, remotes(repositories.get()));
    }

    static List<RemoteRepository> remotes(List<PluginRepository> repos) {
        List<RemoteRepository> out = new ArrayList<>();
        for (PluginRepository r : repos) {
            if (!r.enabled()) {
                continue;
            }
            RemoteRepository.Builder b = new RemoteRepository.Builder(r.id(), "default", r.url())
                    .setReleasePolicy(new RepositoryPolicy(true, RepositoryPolicy.UPDATE_POLICY_DAILY,
                            RepositoryPolicy.UPDATE_POLICY_ALWAYS, RepositoryPolicy.CHECKSUM_POLICY_FAIL))
                    .setSnapshotPolicy(new RepositoryPolicy(r.snapshots(), RepositoryPolicy.UPDATE_POLICY_ALWAYS,
                            RepositoryPolicy.UPDATE_POLICY_ALWAYS, RepositoryPolicy.CHECKSUM_POLICY_WARN));
            if (r.hasCredentials()) {
                b.setAuthentication(new AuthenticationBuilder().addUsername(r.username()).addPassword(r.password())
                        .build());
            }
            out.add(b.build());
        }
        return out;
    }

    /** Auflösung fehlgeschlagen; die Meldung nennt Koordinaten und Ursache und ist für die UI gedacht. */
    public static class ResolutionException extends IllegalStateException {
        public ResolutionException(String message) {
            super(message);
        }

        public ResolutionException(String message, Throwable cause) {
            super(message, cause);
        }
    }

}
