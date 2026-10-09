rootProject.name = "devtools-mcp"

// Container4J: OCI-Container aus Java steuern (Docker, Podman) – eigenständiges Projekt (Git-Submodule
// github.com/OriginalFelix/Container4J), als Composite Build eingebunden; Abhängigkeiten auf
// systems.grebe:container4j werden durch den Quellcode ersetzt
includeBuild("container4j")

// plugin-api: Schnittstellen für Plugins (Modul-Vertrag, Einstellungs-Modell, Plugin-Basisklasse) – als Maven-Artefakt veröffentlichbar
// shared: Einstellungs-Modell und API zwischen Desktop und Server
// backend: Benutzer, Profile, Einstellungen, Projekte, Skills mit GraphQL-API – im Server und eingebettet im Desktop
// server: Team-Server (Backend + Web-UI) – kein MCP
// desktop: Desktop-App mit MCP-Server und Modulen
include("plugin-api", "shared", "backend", "server", "desktop")
