plugins {
    `java-library`
}

// Gemeinsam für Desktop, Backend und Server: Einstellungs-Modell (Felder, Modul-Einstellungen, Überschreibungen),
// Verschlüsselung der Geheimnisse und die Datenklassen der GraphQL-API.
dependencies {
    api("tools.jackson.core:jackson-databind")
}
