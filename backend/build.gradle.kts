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
    // Servlet-API für die Routen der API-Versionen; den Container (Jetty) bringen Server und Desktop-App mit
    compileOnly("jakarta.servlet:jakarta.servlet-api")
    api("org.springframework.boot:spring-boot-starter-jdbc")
    api("org.springframework.boot:spring-boot-starter-flyway")
    api("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.security:spring-security-crypto")
    implementation("com.nimbusds:nimbus-jose-jwt:10.3.1")
    runtimeOnly("com.h2database:h2")
    // Groovy-Skripte: nur Syntaxprüfung (Parsen ohne Ausführung), ausgeführt wird in der Desktop-App
    implementation("org.apache.groovy:groovy")
    // Gherkin-Skripte: ebenfalls nur parsen
    implementation("io.cucumber:gherkin:42.0.1")
    // MQTT-Broker für die Kooperation der Desktop-Apps (HiveMQ CE, eingebettet; devtools.broker.*)
    api("com.hivemq:hivemq-community-edition-embedded:2026.5")
    implementation("com.arcadedb:arcadedb-engine:26.10.1") {
        exclude(group = "org.graalvm.polyglot", module = "js")
        exclude(group = "org.graalvm.js")
        exclude(group = "org.graalvm.truffle")
        exclude(group = "org.graalvm.regex")
    }
    implementation("com.arcadedb:arcadedb-network:26.10.1") {
        exclude(group = "org.graalvm.polyglot", module = "js")
        exclude(group = "org.graalvm.js")
        exclude(group = "org.graalvm.truffle")
        exclude(group = "org.graalvm.regex")
    }

    testImplementation("org.springframework.graphql:spring-graphql-test")
    // Externe Graph-Storage im Test: ArcadeDB-Server in derselben JVM
    testImplementation("com.arcadedb:arcadedb-server:26.10.1") {
        exclude(group = "org.graalvm.polyglot", module = "js")
        exclude(group = "org.graalvm.js")
        exclude(group = "org.graalvm.truffle")
        exclude(group = "org.graalvm.regex")
    }
    // MQTT-Client für die Tests des Brokers
    testImplementation("com.hivemq:hivemq-mqtt-client:1.4.0")
    // SkillTestSupport: schlanker Skill-Kontext, auch für die UI-Tests der Desktop-App
    testFixturesImplementation("org.springframework.boot:spring-boot-starter-data-jpa")
}
