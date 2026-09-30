import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension
import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    id("org.springframework.boot") version "4.1.1" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("com.vaadin") version "25.2.8" apply false
}

val springAiVersion = "2.0.1"
val vaadinVersion = "25.2.8"

allprojects {
    group = "systems.grebe"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")
    apply(plugin = "io.spring.dependency-management")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
    }

    extensions.configure<DependencyManagementExtension> {
        imports {
            mavenBom(SpringBootPlugin.BOM_COORDINATES)
            mavenBom("org.springframework.ai:spring-ai-bom:$springAiVersion")
            mavenBom("com.vaadin:vaadin-bom:$vaadinVersion")
        }
    }

    // Webserver: Jetty statt des Standard-Tomcats (kommt transitiv über die WebMVC-Starter von Spring AI und Vaadin)
    configurations.all {
        exclude(group = "org.springframework.boot", module = "spring-boot-starter-tomcat")
        exclude(group = "org.springframework.boot", module = "spring-boot-tomcat")
    }

    dependencies {
        "testImplementation"("org.springframework.boot:spring-boot-starter-test")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-parameters", "-Xlint:deprecation"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        systemProperty("java.awt.headless", "true")
    }
}
