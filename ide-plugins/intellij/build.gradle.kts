plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "systems.grebe"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // -PlocalIde=<Pfad> baut gegen eine installierte IDE, statt eine herunterzuladen
        val localIde = providers.gradleProperty("localIde")
        if (localIde.isPresent) local(localIde.get()) else intellijIdeaCommunity("2025.1")
    }
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

intellijPlatform {
    buildSearchableOptions = false
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "251"
            untilBuild = provider { null }
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
}
