plugins {
    id("org.springframework.boot")
}

val jgitVersion = "7.6.0.202603022253-r"
val javafxVersion = "25"

// JavaFX wird plattformabhängig ausgeliefert (natives Rendering)
val osName = System.getProperty("os.name").lowercase()
val osArch = System.getProperty("os.arch").lowercase()
val javafxPlatform = when {
    osName.contains("win") -> "win"
    osName.contains("mac") -> if (osArch.contains("aarch64")) "mac-aarch64" else "mac"
    else -> if (osArch.contains("aarch64")) "linux-aarch64" else "linux"
}

dependencies {
    // Backend (GraphQL, Benutzer, Profile, Projekte, Skills): eingebettet ohne eingetragenen Team-Server
    implementation(project(":backend"))
    // GraphQL-Client für Subscriptions (WebSocketGraphQlClient über den Jakarta-WebSocket-Client von Jetty)
    implementation("org.springframework:spring-webflux")

    implementation("org.springframework.ai:spring-ai-starter-mcp-server-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-jetty")

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

    // Code-Graph-Ablage: Spring Data Neo4j (Entities für Projekt/Branch, Bulk-Cypher über Neo4jClient für Knoten/Kanten)
    // Bewusst ohne Boot-Starter: die Verbindung kommt aus den Modul-Einstellungen und wird zur Laufzeit gebaut
    // (Änderungen gelten sofort), eine Neo4j-Auto-Konfiguration neben JPA wäre nur im Weg.
    implementation("org.springframework.data:spring-data-neo4j")
    implementation("org.neo4j.driver:neo4j-java-driver")

    // Plugins: plugin.yml (SnakeYAML, Version aus der Boot-BOM) und Plugin-Store über Maven-Repositories
    // (Maven Resolver: Auflösung, Versionen aus maven-metadata.xml, Prüfsummen, Zugangsdaten, file://-Repositories).
    implementation("org.yaml:snakeyaml")
    implementation("org.apache.maven.resolver:maven-resolver-supplier-mvn3:2.0.23")

    // Decompiler-Modul: Vineflower (gepflegter Fernflower-Fork, versteht aktuelle Java-Features, ohne Abhängigkeiten)
    implementation("org.vineflower:vineflower:1.12.0")

    // SSH-Modul: Befehle und SFTP über JSch (gepflegter Fork mit aktuellen Algorithmen, ohne weitere Abhängigkeiten)
    implementation("com.github.mwiede:jsch:2.27.2")

    listOf("base", "graphics", "controls").forEach {
        implementation("org.openjfx:javafx-$it:$javafxVersion:$javafxPlatform")
    }

    testImplementation("io.projectreactor:reactor-test")
    testImplementation(testFixtures(project(":backend")))
    // Eingebetteter SSH-/SFTP-Server für die Tests des SSH-Moduls
    testImplementation("org.apache.sshd:sshd-core:2.15.0")
    testImplementation("org.apache.sshd:sshd-sftp:2.15.0")
}

springBoot {
    mainClass = "systems.grebe.devtools.mcp.DevToolsMcpApplication"
}

tasks.named<Jar>("bootJar") {
    archiveBaseName = "devtools-mcp"
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
