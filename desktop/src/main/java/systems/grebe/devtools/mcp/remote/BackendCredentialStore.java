package systems.grebe.devtools.mcp.remote;

import java.util.List;

import org.springframework.stereotype.Component;
import systems.grebe.devtools.mcp.api.CredentialInfo;
import systems.grebe.devtools.mcp.api.SettingsSnapshot;
import systems.grebe.devtools.mcp.core.CredentialStore;

/** Zugangsdaten des angemeldeten Benutzers im Backend (eingebettet oder Team-Server). */
@Component
public class BackendCredentialStore implements CredentialStore {

    private final BackendConnection backend;

    public BackendCredentialStore(BackendConnection backend) {
        this.backend = backend;
    }

    @Override
    public List<CredentialInfo> credentials() {
        return backend.signedIn() ? backend.settings().map(SettingsSnapshot::credentials).orElse(List.of()) : List.of();
    }

    @Override
    public void save(List<CredentialInfo> entries) {
        if (!backend.signedIn()) {
            throw new IllegalStateException("Nicht angemeldet.");
        }
        backend.saveCredentials(entries);
    }

    @Override
    public void refresh(long id) {
        backend.refreshCredential(id);
    }
}
