plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.vaadin") version "25.2.8"
}

group = "systems.grebe"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

val springAiVersion = "2.0.1"
val vaadinVersion = "25.2.8"
val javafxVersion = "25"
val jgitVersion = "7.6.0.202603022253-r"

// JavaFX wird plattformabhängig ausgeliefert (natives Rendering)
val osName = System.getProperty("os.name").lowercase()
val osArch = System.getProperty("os.arch").lowercase()
val javafxPlatform = when {
    osName.contains("win") -> "win"
    osName.contains("mac") -> if (osArch.contains("aarch64")) "mac-aarch64" else "mac"
    else -> if (osArch.contains("aarch64")) "linux-aarch64" else "linux"
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:$springAiVersion")
        mavenBom("com.vaadin:vaadin-bom:$vaadinVersion")
    }
}

dependencies {
    implementation("org.springframework.ai:spring-ai-starter-mcp-server-webmvc")

    // Team-Server: Web-UI (Vaadin Flow), Anmeldung (Spring Security), MCP-Zugriff per JWT (Nimbus, Version wie in
    // vaadin-dev). Benutzer/Tokens liegen in einer eigenen Core-Datenbank (JdbcClient + Flyway), getrennt von den Skills.
    implementation("com.vaadin:vaadin-spring-boot-starter")
    developmentOnly("com.vaadin:vaadin-dev")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("com.nimbusds:nimbus-jose-jwt:10.3.1")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")

    implementation("org.eclipse.jgit:org.eclipse.jgit:$jgitVersion")

    // Performance/Diagnose: Flame Graphs aus JFR (async-profiler-Konverter) und VisualVM-Engines (Heap, Sampler)
    implementation("tools.profiler:jfr-converter:4.5")
    implementation("org.graalvm.visualvm.modules:org-graalvm-visualvm-lib-jfluid-heap:2.2")
    implementation("org.graalvm.visualvm.modules:org-graalvm-visualvm-lib-jfluid:2.2")

    // Code-Graph: tree-sitter über die offiziellen FFM-Bindings (jtreesitter). Die bonede-Artefakte liefern nur die
    // vorkompilierten nativen Bibliotheken (macOS/Linux/Windows) als Ressourcen – ihre JNI-Klassen werden nicht
    // verwendet, weil sie bei vollem Heap die JVM mit SIGSEGV beenden (siehe TreeSitterNatives).
    implementation("io.github.tree-sitter:jtreesitter:0.26.1")
    runtimeOnly("io.github.bonede:tree-sitter:0.26.6")
    runtimeOnly("io.github.bonede:tree-sitter-java:0.23.5")

    // Skills: Spring Data JPA (Hibernate ORM + HikariCP), Standard-Datenbank H2 als lokale Datei.
    // Die DataSource baut SkillsPersistenceConfig aus den Modul-Einstellungen. Versionen aus der Boot-BOM.
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    runtimeOnly("com.h2database:h2")

    // Code-Graph-Ablage: Spring Data Neo4j (Entities für Projekt/Branch, Bulk-Cypher über Neo4jClient für Knoten/Kanten)
    // Bewusst ohne Boot-Starter: die Verbindung kommt aus den Modul-Einstellungen und wird zur Laufzeit gebaut
    // (Änderungen gelten sofort), eine Neo4j-Auto-Konfiguration neben JPA wäre nur im Weg.
    implementation("org.springframework.data:spring-data-neo4j")
    implementation("org.neo4j.driver:neo4j-java-driver")

    // Plugins: plugin.yml (SnakeYAML, Version aus der Boot-BOM) und Plugin-Store über Maven-Repositories
    // (Maven Resolver: Auflösung, Versionen aus maven-metadata.xml, Prüfsummen, Zugangsdaten, file://-Repositories).
    implementation("org.yaml:snakeyaml")
    implementation("org.apache.maven.resolver:maven-resolver-supplier-mvn3:2.0.23")

    // SSH-Modul: Befehle und SFTP über JSch (gepflegter Fork mit aktuellen Algorithmen, ohne weitere Abhängigkeiten)
    implementation("com.github.mwiede:jsch:2.27.2")

    listOf("base", "graphics", "controls").forEach {
        implementation("org.openjfx:javafx-$it:$javafxVersion:$javafxPlatform")
    }

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    // Eingebetteter SSH-/SFTP-Server für die Tests des SSH-Moduls
    testImplementation("org.apache.sshd:sshd-core:2.15.0")
    testImplementation("org.apache.sshd:sshd-sftp:2.15.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:deprecation"))
}

tasks.test {
    useJUnitPlatform()
    systemProperty("java.awt.headless", "true")
    // vaadin-dev ist nur developmentOnly – ohne diese Angabe suchte Vaadin in Tests den Dev-Server
    systemProperty("vaadin.productionMode", "true")
}

// Vaadin im Produktionsmodus bauen (auch für Tests und bootJar): ohne eigenes Frontend nimmt das Plugin das
// vorkompilierte Bundle, Node wird nicht gebraucht. Entwicklung mit Hot-Reload: -Pvaadin.productionMode=false bootRun
vaadin {
    productionMode = (findProperty("vaadin.productionMode") as String?)?.toBoolean() ?: true
}

springBoot {
    mainClass = "systems.grebe.devtools.mcp.DevToolsMcpApplication"
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
