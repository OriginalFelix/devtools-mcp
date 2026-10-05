plugins {
    `java-library`
    `java-test-fixtures`
}

// Backend: Benutzer und Tokens, Profile und Einstellungs-Ebenen, Modul-Katalog, Projekte und Skills mit GraphQL-API
// (HTTP und WebSocket für Subscriptions). Läuft im Team-Server und – ohne eingetragene Server-URL – eingebettet in der
// Desktop-App. Core-Datenbank über JdbcClient + Flyway, Skills über Spring Data JPA; kein Spring Security (Anmeldung
// per Token im GraphQL-Interceptor, nur der Passwort-Encoder aus spring-security-crypto).
dependencies {
    api(project(":shared"))

    api("org.springframework.boot:spring-boot-starter-graphql")
    api("org.springframework.boot:spring-boot-starter-websocket")
    api("org.springframework.boot:spring-boot-starter-webmvc")
    api("org.springframework.boot:spring-boot-starter-jdbc")
    api("org.springframework.boot:spring-boot-starter-flyway")
    api("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.security:spring-security-crypto")
    implementation("com.nimbusds:nimbus-jose-jwt:10.3.1")
    runtimeOnly("com.h2database:h2")
    // Groovy-Skripte: nur Syntaxprüfung (Parsen ohne Ausführung), ausgeführt wird in der Desktop-App
    implementation("org.apache.groovy:groovy")

    testImplementation("org.springframework.graphql:spring-graphql-test")
    // SkillTestSupport: schlanker Skill-Kontext, auch für die UI-Tests der Desktop-App
    testFixturesImplementation("org.springframework.boot:spring-boot-starter-data-jpa")
}
