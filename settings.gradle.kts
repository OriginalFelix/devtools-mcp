rootProject.name = "devtools-mcp"

// plugin-api: Schnittstellen für Plugins (Modul-Vertrag, Einstellungs-Modell, Plugin-Basisklasse) – als Maven-Artefakt veröffentlichbar
// shared: Einstellungs-Modell und API zwischen Desktop und Server
// backend: Benutzer, Profile, Einstellungen, Projekte, Skills mit GraphQL-API – im Server und eingebettet im Desktop
// server: Team-Server (Backend + Web-UI) – kein MCP
// natives: Zugriffe auf das Betriebssystem (Fenstersysteme, zweiter KI-Zeiger, Programmstart) – von der Desktop-App genutzt
// desktop: Desktop-App mit MCP-Server und Modulen
include("plugin-api", "shared", "backend", "server", "natives", "desktop")
