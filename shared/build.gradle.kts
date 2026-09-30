plugins {
    `java-library`
}

// Gemeinsam für Desktop und Server: Einstellungs-Modell (Felder, Modul-Einstellungen, Überschreibungen),
// Verschlüsselung der Geheimnisse, die DTOs der REST-API zwischen beiden und die Skill-Ablage (Spring Data JPA) –
// lokal in der Desktop-App, zentral auf dem Team-Server.
dependencies {
    api("tools.jackson.core:jackson-databind")
    api("org.springframework.boot:spring-boot-starter-data-jpa")
}
