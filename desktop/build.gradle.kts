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

// Compile-Classpath eines Plugins, das nur die Plugin-API kennt (für PluginApiOnlyTest)
val pluginApiClasspath by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_API))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
    }
}

dependencies {
    // Plugin-API: Modul-Vertrag und Plugin-Basisklasse; die App stellt sie Plugins zur Laufzeit bereit
    implementation(project(":plugin-api"))
    pluginApiClasspath(project(":plugin-api"))

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

    // Syntaxbäume (Code-Graph u.a.): tree-sitter samt Grammatiken als WebAssembly (src/main/resources/tree-sitter,
    // gebaut mit natives/build-tree-sitter-wasm.sh), ausgeführt von Chicory – reiner Java-Code, der Compiler übersetzt
    // die Module zur Laufzeit in JVM-Bytecode. Keine nativen Bibliotheken, die Windows blockieren könnte.
    val chicoryVersion = "1.7.5"
    implementation("com.dylibso.chicory:runtime:$chicoryVersion")
    implementation("com.dylibso.chicory:compiler:$chicoryVersion")

    // Plugins: plugin.yml (SnakeYAML, Version aus der Boot-BOM) und Plugin-Store über Maven-Repositories
    // (Maven Resolver: Auflösung, Versionen aus maven-metadata.xml, Prüfsummen, Zugangsdaten, file://-Repositories).
    implementation("org.yaml:snakeyaml")
    // Plugin-Signaturen: plugin.jwt (JWS, ES256/384/512 oder RS256) – dieselbe Bibliothek wie im Backend
    implementation("com.nimbusds:nimbus-jose-jwt:10.3.1")
    implementation("org.apache.maven.resolver:maven-resolver-supplier-mvn3:2.0.23")

    // Decompiler-Modul: Vineflower (gepflegter Fernflower-Fork, versteht aktuelle Java-Features, ohne Abhängigkeiten)
    implementation("org.vineflower:vineflower:1.12.0")

    // SSH-Modul: Befehle und SFTP über JSch (gepflegter Fork mit aktuellen Algorithmen, ohne weitere Abhängigkeiten)
    implementation("com.github.mwiede:jsch:2.27.2")

    // Mail-Modul: IMAP inkl. IDLE (Angus Mail, Implementierung von Jakarta Mail; Version aus der Boot-BOM)
    implementation("org.eclipse.angus:angus-mail")

    // Kooperation: Austausch zwischen Instanzen über einen MQTT-5-Broker (HiveMQ, Mosquitto …); WebSockets über
    // netty-codec-http (beim Client optional)
    implementation("com.hivemq:hivemq-mqtt-client:1.4.0")
    implementation("io.netty:netty-codec-http")

    // Matrix-Modul: Markdown der Nachrichten als HTML (formatted_body), mit Tabellen und Durchstreichen wie GFM
    val commonmarkVersion = "0.30.0"
    implementation("org.commonmark:commonmark:$commonmarkVersion")
    implementation("org.commonmark:commonmark-ext-gfm-tables:$commonmarkVersion")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:$commonmarkVersion")

    // Skripte: eigene Module mit Tools als Groovy-Skripte (Version aus der Boot-BOM), zur Laufzeit übersetzt
    implementation("org.apache.groovy:groovy")
    implementation("org.apache.groovy:groovy-json") // JsonSlurper/JsonOutput in Skripten
    // Skripte in Gherkin: Parser (Funktionalität → Szenarien) und Cucumber Expressions für die Schritte – ohne die
    // Cucumber-Runtime, ausgeführt wird von der App selbst (GherkinScriptCompiler)
    implementation("io.cucumber:gherkin:42.0.1")
    implementation("io.cucumber:cucumber-expressions:20.1.0")

    // Ticket-Klassifizierung: Einschätzung der Komplexität über die Claude API (offizielles Java-SDK)
    implementation("com.anthropic:anthropic-java:2.68.0")

    // Fenstersteuerung: Fenstersysteme, zweiter KI-Zeiger und Programmstart im eigenen Projekt (siehe natives)
    implementation(project(":natives"))

    listOf("base", "graphics", "controls").forEach {
        implementation("org.openjfx:javafx-$it:$javafxVersion:$javafxPlatform")
    }
    // Skript-Editor: Code-Bereich mit Stil je Zeichen (Syntaxhervorhebung), Zeilennummern und Undo
    implementation("org.fxmisc.richtext:richtextfx:0.11.7")

    testImplementation("io.projectreactor:reactor-test")
    testImplementation(testFixtures(project(":backend")))
    // Eingebetteter SSH-/SFTP-Server für die Tests des SSH-Moduls
    testImplementation("org.apache.sshd:sshd-core:2.15.0")
    testImplementation("org.apache.sshd:sshd-sftp:2.15.0")
    // Eingebetteter IMAP-/SMTP-Server für die Tests des Mail-Moduls
    testImplementation("com.icegreen:greenmail-junit5:2.1.3")
    // Tests des Dolt-Moduls gegen Dolt-/Doltgres-Container: Treiber, die die App sonst per Maven lädt (Boot-BOM)
    testRuntimeOnly("com.mysql:mysql-connector-j")
    testRuntimeOnly("org.postgresql:postgresql")
}

springBoot {
    mainClass = "systems.grebe.devtools.mcp.DevToolsMcpApplication"
}

tasks.named<Jar>("bootJar") {
    archiveBaseName = "devtools-mcp"
    // Native Zugriffe (JavaFX) ohne Warnung beim Start mit java -jar
    manifest.attributes("Enable-Native-Access" to "ALL-UNNAMED")
}

tasks.test {
    inputs.files(pluginApiClasspath).withPropertyName("pluginApiClasspath").withNormalizer(ClasspathNormalizer::class)
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Ddevtools.test.pluginApiClasspath=${pluginApiClasspath.asPath}")
    })
    // Doltlite-Tests: Programm per -Pdoltlite=<pfad zu doltlite(.exe)>, sonst aus dem PATH; ohne beides übersprungen
    providers.gradleProperty("doltlite").orNull?.let { systemProperty("devtools.test.doltlite", it) }
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
