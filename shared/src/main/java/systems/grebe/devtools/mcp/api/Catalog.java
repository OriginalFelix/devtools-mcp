package systems.grebe.devtools.mcp.api;

import java.util.List;

/** Alle Module einer Desktop-App ({@code PUT /api/catalog}). */
public record Catalog(List<ModuleDescriptor> modules) {

    public Catalog {
        modules = modules == null ? List.of() : List.copyOf(modules);
    }
}
