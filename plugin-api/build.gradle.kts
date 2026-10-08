plugins {
    `java-library`
    `maven-publish`
}

// Plugin-API: alles, wogegen ein Plugin kompiliert – Basisklasse und Kontext (DevToolsPlugin, PluginContext,
// PluginDescriptor), der Modul-Vertrag (ToolModule, ModuleAction, ToolScope) und das Einstellungs-Modell (ConfigField,
// ModuleConfig …). Keine Implementierung der App; die stellt zur Laufzeit genau diese Klassen bereit. Die Pakete sind
// dieselben wie vorher in der App – bestehende Plugins laufen ohne neuen Build weiter.
//
// Plugins binden sie mit compileOnly ein: ./gradlew :plugin-api:publishToMavenLocal, dann
// compileOnly("systems.grebe:devtools-mcp-plugin-api:<version>").
dependencies {
    // Laufzeit-Abhängigkeiten der API-Klassen selbst (ModuleConfig liest JSON, ToolScope loggt)
    api("tools.jackson.core:jackson-databind")
    api("org.slf4j:slf4j-api")

    // Bringt die App mit: Tools (ToolCallback, @Tool, ToolCallbacks) und der Spring-Kontext je Plugin (@Component,
    // @Bean, @PostConstruct …). Nur zum Kompilieren weitergereicht – Team-Server und Backend bekommen über shared
    // kein Spring AI.
    compileOnlyApi("org.springframework.ai:spring-ai-model")
    compileOnlyApi("org.springframework:spring-context")
    compileOnlyApi("jakarta.annotation:jakarta.annotation-api")
}

// Versionen stehen über versionMapping fest im POM – die BOM-Importe der App (Boot, Spring AI, Vaadin) gehören nicht hinein
configure<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension> {
    generatedPomCustomization { enabled(false) }
}

java {
    withSourcesJar()
    withJavadocJar()
}

tasks.javadoc {
    (options as StandardJavadocDocletOptions).apply {
        encoding = "UTF-8"
        docEncoding = "UTF-8"
        charSet = "UTF-8"
        addStringOption("Xdoclint:all,-missing", "-quiet")
    }
}

publishing {
    publications {
        create<MavenPublication>("pluginApi") {
            artifactId = "devtools-mcp-plugin-api"
            from(components["java"])
            // Feste Versionen statt der Spring-BOM im POM – Maven-Builds von Plugins brauchen sie dann nicht
            versionMapping {
                usage("java-api") { fromResolutionResult() }
                usage("java-runtime") { fromResolutionResult() }
            }
            pom {
                name = "DevTools MCP Plugin API"
                description = "Schnittstellen für Plugins der DevTools-MCP-App"
            }
        }
    }
    // Optional ein eigenes Maven-Repository (z.B. das des Plugin-Stores):
    // -PpluginApiRepository=https://nexus.acme.de/repository/maven-releases -PpluginApiRepositoryUser=… -PpluginApiRepositoryPassword=…
    providers.gradleProperty("pluginApiRepository").orNull?.let { url ->
        repositories {
            maven {
                name = "pluginApi"
                setUrl(url)
                providers.gradleProperty("pluginApiRepositoryUser").orNull?.let { user ->
                    credentials {
                        username = user
                        password = providers.gradleProperty("pluginApiRepositoryPassword").orNull
                    }
                }
            }
        }
    }
}
