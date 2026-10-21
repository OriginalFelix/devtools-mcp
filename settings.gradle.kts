rootProject.name = "devtools-mcp"

// Container4J: OCI-Container aus Java steuern (Docker, Podman) – eigenständiges Projekt (Git-Submodule
// github.com/OriginalFelix/Container4J), als Composite Build eingebunden; Abhängigkeiten auf
// systems.grebe:container4j werden durch den Quellcode ersetzt. Fehlt das Submodule (Klon ohne
// --recurse-submodules), holt Gradle es selbst.
if (!file("container4j/settings.gradle.kts").exists()) {
    val init = providers.exec {
        commandLine("git", "submodule", "update", "--init", "container4j")
        workingDir = settingsDir
        isIgnoreExitValue = true
    }
    if (init.result.get().exitValue != 0) {
        throw GradleException("Container4J fehlt und 'git submodule update --init container4j' ist fehlgeschlagen:\n"
                + init.standardError.asText.get().trim())
    }
}
// Explizite Ersetzung, weil performance-mcp Container4J ebenfalls als (verschachtelten) Composite Build einbindet:
// ohne sie ist systems.grebe:container4j im Composite nicht eindeutig. Das Submodule performance-mcp/container4j
// wird dadurch nicht gebraucht; Gradle holt es aber beim Konfigurieren von performance-mcp selbst.
includeBuild("container4j") {
    dependencySubstitution {
        substitute(module("systems.grebe:container4j")).using(project(":"))
    }
}

// performance-mcp: SDK für JVM-Diagnose, JFR, async-profiler, VisualVM und Debugger (Git-Submodule
// github.com/OriginalFelix/performance-mcp), als Composite Build eingebunden; systems.grebe:performance-mcp-core
// wird durch den Quellcode ersetzt. Fehlt das Submodule, holt Gradle es selbst.
if (!file("performance-mcp/settings.gradle.kts").exists()) {
    val init = providers.exec {
        commandLine("git", "submodule", "update", "--init", "performance-mcp")
        workingDir = settingsDir
        isIgnoreExitValue = true
    }
    if (init.result.get().exitValue != 0) {
        throw GradleException("performance-mcp fehlt und 'git submodule update --init performance-mcp' ist fehlgeschlagen:\n"
                + init.standardError.asText.get().trim())
    }
}
includeBuild("performance-mcp")

// plugin-api: Schnittstellen für Plugins (Modul-Vertrag, Einstellungs-Modell, Plugin-Basisklasse) – als Maven-Artefakt veröffentlichbar
// shared: Einstellungs-Modell und API zwischen Desktop und Server
// backend: Benutzer, Profile, Einstellungen, Projekte, Skills mit GraphQL-API – im Server und eingebettet im Desktop
// server: Team-Server (Backend + Web-UI) – kein MCP
// desktop: Desktop-App mit MCP-Server und Modulen
include("plugin-api", "shared", "backend", "server", "desktop")
