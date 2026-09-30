rootProject.name = "devtools-mcp"

// shared: Einstellungs-Modell und API zwischen Desktop und Server
// server: Team-Server (Web-UI, Benutzer, Profile, Projekte, Skills) – kein MCP
// desktop: Desktop-App mit MCP-Server und Modulen
include("shared", "server", "desktop")
