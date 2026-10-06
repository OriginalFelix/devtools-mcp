plugins {
    `java-library`
}

// Gemeinsam für Desktop, Backend und Server: Einstellungs-Modell (Felder und Modul-Einstellungen kommen aus der
// Plugin-API, hier Überschreibungen und Ablage), Verschlüsselung der Geheimnisse und die Datenklassen der GraphQL-API.
dependencies {
    api(project(":plugin-api"))
    api("tools.jackson.core:jackson-databind")
}
