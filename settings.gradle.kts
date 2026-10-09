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
includeBuild("container4j")

// plugin-api: Schnittstellen für Plugins (Modul-Vertrag, Einstellungs-Modell, Plugin-Basisklasse) – als Maven-Artefakt veröffentlichbar
// shared: Einstellungs-Modell und API zwischen Desktop und Server
// backend: Benutzer, Profile, Einstellungen, Projekte, Skills mit GraphQL-API – im Server und eingebettet im Desktop
// server: Team-Server (Backend + Web-UI) – kein MCP
// natives: Zugriffe auf das Betriebssystem (Fenstersysteme, zweiter KI-Zeiger, Programmstart) – von der Desktop-App genutzt
// desktop: Desktop-App mit MCP-Server und Modulen
include("plugin-api", "shared", "backend", "server", "natives", "desktop")
