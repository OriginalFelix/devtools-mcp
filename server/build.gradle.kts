plugins {
    war
    id("org.springframework.boot")
    id("com.vaadin")
}

// Team-Server: Backend (GraphQL-API, Benutzer, Profile, Einstellungen, Projekte, Skills) plus Web-UI (Vaadin Flow)
// mit Formular-Anmeldung (Spring Security). Kein MCP – Tools laufen nur in der Desktop-App.
dependencies {
    implementation(project(":backend"))

    // Eingebetteter Jetty nur im bootJar; das WAR für WildFly lässt ihn weg (siehe warRuntimeClasspath)
    implementation("org.springframework.boot:spring-boot-starter-jetty")
    // Servlet-Kontext von Spring Boot; kommt sonst nur über spring-boot-jetty, das im WAR fehlt
    implementation("org.springframework.boot:spring-boot-web-server")
    // EL für Hibernate Validator (sonst nur über den Jetty-Starter, fehlte dann im WAR)
    runtimeOnly("org.apache.tomcat.embed:tomcat-embed-el")

    implementation("com.vaadin:vaadin-spring-boot-starter")
    developmentOnly("com.vaadin:vaadin-dev")
    implementation("org.springframework.boot:spring-boot-starter-security")

    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.graphql:spring-graphql-test")
    testImplementation("org.springframework:spring-webflux") // WebSocketGraphQlClient (Subscriptions)
    testImplementation("io.projectreactor:reactor-test")
}

tasks.test {
    // vaadin-dev ist nur developmentOnly – ohne diese Angabe suchte Vaadin in Tests den Dev-Server
    systemProperty("vaadin.productionMode", "true")
}

// Vaadin im Produktionsmodus bauen (auch für Tests, bootJar und WAR): ohne eigenes Frontend nimmt das Plugin das
// vorkompilierte Bundle, Node wird nicht gebraucht. Entwicklung mit Hot-Reload: -Pvaadin.productionMode=false bootRun
vaadin {
    productionMode = (findProperty("vaadin.productionMode") as String?)?.toBoolean() ?: true
}

springBoot {
    mainClass = "systems.grebe.devtools.mcp.DevToolsServerApplication"
}

// Zwei Artefakte: bootJar (ausführbar, eingebetteter Jetty) und ein WAR für einen externen WildFly
// (build/libs/server-<version>-wildfly.war).
tasks.named<Jar>("bootJar") {
    archiveBaseName = "devtools-server"
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootWar>("bootWar") {
    enabled = false
}

// Laufzeit-Classpath des WAR: wie runtimeClasspath ohne developmentOnly, aber ohne Jetty-Jars (Servlet-Stack kommt vom
// WildFly). spring-boot-web-server (Servlet-Kontext für SpringBootServletInitializer) bleibt über die eigene
// Abhängigkeit oben. Bewusst keine providedRuntime-Abhängigkeit: deren Datei-Differenz würde auch alles entfernen,
// was der Jetty-Starter transitiv mitbringt (spring-core, spring-boot, Logback …).
val warRuntimeClasspath by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    extendsFrom(configurations.implementation.get(), configurations.runtimeOnly.get())
    exclude(group = "org.springframework.boot", module = "spring-boot-starter-jetty-runtime")
    exclude(group = "org.springframework.boot", module = "spring-boot-jetty")
    exclude(group = "org.eclipse.jetty")
    exclude(group = "jakarta.servlet")
    shouldResolveConsistentlyWith(configurations.runtimeClasspath.get())
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(TargetJvmEnvironment.STANDARD_JVM))
    }
}

tasks.named<War>("war") {
    archiveBaseName = "devtools-server"
    archiveClassifier = "wildfly"
    setClasspath(sourceSets.main.get().output + warRuntimeClasspath)
}
