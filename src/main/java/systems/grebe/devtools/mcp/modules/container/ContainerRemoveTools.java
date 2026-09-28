package systems.grebe.devtools.mcp.modules.container;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import tools.jackson.databind.JsonNode;
import systems.grebe.devtools.mcp.modules.container.spi.ContainerRuntime;

/** Container und Images löschen. */
public class ContainerRemoveTools {

    private final ContainerEnvironment env;

    ContainerRemoveTools(ContainerEnvironment env) {
        this.env = env;
    }

    @Tool(name = "rm", description = "Löscht einen Container. Je nach Einstellung nur Container, die über container_run "
            + "angelegt wurden (Label '" + ContainerModule.OWN_LABEL + "').")
    public String rm(
            @ToolParam(description = ContainerReadTools.CONTAINER) String container,
            @ToolParam(required = false, description = "true = auch laufenden Container löschen") Boolean force,
            @ToolParam(required = false, description = "true = anonyme Volumes mitlöschen") Boolean volumes,
            @ToolParam(required = false, description = ContainerReadTools.RUNTIME) String runtime) {
        env.checkContainer(container);
        ContainerRuntime rt = env.runtime(runtime);
        if (env.onlyOwn()) {
            JsonNode labels = env.sanitizeInspect(rt.inspect(container)).path("Config").path("Labels");
            if (!labels.has(ContainerModule.OWN_LABEL)) {
                throw new IllegalStateException("Container '" + container + "' wurde nicht über container_run angelegt; "
                        + "Löschen ist nur für eigene Container erlaubt (Einstellung im Container-Modul).");
            }
        }
        rt.remove(container, Boolean.TRUE.equals(force), Boolean.TRUE.equals(volumes));
        return "Container " + container + " gelöscht.";
    }

    @Tool(name = "rmi", description = "Löscht ein lokales Image (muss freigegeben sein).")
    public String rmi(
            @ToolParam(description = "Image, z.B. redis:7-alpine") String image,
            @ToolParam(required = false, description = "true = auch wenn Container es verwenden") Boolean force,
            @ToolParam(required = false, description = ContainerReadTools.RUNTIME) String runtime) {
        env.checkImage(image);
        env.runtime(runtime).removeImage(image, Boolean.TRUE.equals(force));
        return "Image " + image + " gelöscht.";
    }
}
